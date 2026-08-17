package dev.frostguard.engine.schedule;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import dev.frostguard.api.configs.*;
import dev.frostguard.api.domain.*;
import dev.frostguard.engine.listener.StaminaChangeListener;
import dev.frostguard.engine.schedule.preempt.PreemptionListener;
import dev.frostguard.engine.schedule.preempt.PreemptionRule;
import dev.frostguard.engine.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Central orchestrator for all per-profile TaskQueue instances.
// Handles queue creation, ordered startup, global rule registration,
// and bulk lifecycle controls (pause/resume/stop).
public class TaskDispatcher implements PreemptionListener, StaminaChangeListener {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final Map<Long, TaskQueue> managedQueues = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> pauseFlags = new ConcurrentHashMap<>();

    // ── queue registration ──────────────────────────────────────────

    public void registerAccount(AccountDescriptor profile) {
        managedQueues.computeIfAbsent(profile.getId(), id -> {
            pauseFlags.put(id, Boolean.FALSE);
            return new TaskQueue(profile);
        });
    }

    public TaskQueue getQueue(Long accountId) {
        return managedQueues.get(accountId);
    }

    public boolean applyProfileUpdate(AccountDescriptor profile) {
        if (profile == null || profile.getId() == null) return false;
        TaskQueue queue = managedQueues.get(profile.getId());
        if (queue == null) return false;
        queue.applyProfileUpdate(profile);
        GlobalMonitorService.getInstance().updateProfile(profile);
        log.info("Committed profile state applied to queue profile={}", profile.getName());
        return true;
    }

    // ── preemption handling ─────────────────────────────────────────

    @Override
    public void onPreemption(AccountDescriptor profile, PreemptionRule rule) {
        TaskQueue q = managedQueues.get(profile.getId());
        if (q == null) return;
        try {
            q.preemptActiveTask(rule);
        } catch (Exception ex) {
            log.error("Preemption dispatch error [{}]: {}", profile.getName(), ex.getMessage());
        }
    }

    @Override
    public void onEnergyLevelChanged(Long accountId, int currentStamina) {
        // Scheduling reacts only to explicit additions, not OCR corrections or passive ticks.
    }

    @Override
    public void onStaminaSynchronized(Long accountId, int currentStamina) {
        reconsiderStaminaDeferrals(accountId, currentStamina, "Stamina synchronized");
    }

    @Override
    public void onStaminaAdded(Long accountId, int amount, int currentStamina) {
        reconsiderStaminaDeferrals(accountId, currentStamina, "Stamina +" + amount);
    }

    private void reconsiderStaminaDeferrals(Long accountId, int currentStamina, String source) {
        if (accountId == null) {
            return;
        }
        TaskQueue queue = managedQueues.get(accountId);
        if (queue == null) {
            return;
        }

        try {
            int moved = queue.reconsiderStaminaDeferrals(currentStamina);
            if (moved > 0) {
                log.info("{} for profile {} (now {}) advanced {} stamina-blocked task(s)",
                        source, accountId, currentStamina, moved);
            }
        } catch (Exception ex) {
            log.error("Stamina re-evaluation error [{}]", accountId, ex);
        }
    }

    // ── start all queues ────────────────────────────────────────────

    public void startAll() {
        List<String> liveProfiles = managedQueues.values().stream()
                .filter(TaskQueue::hasLiveExecutor)
                .map(queue -> queue.getProfile().getName())
                .toList();
        if (!liveProfiles.isEmpty()) {
            throw new IllegalStateException("Cannot start queues while previous workers are alive: "
                    + String.join(", ", liveProfiles));
        }
        GlobalMonitorService monitor = GlobalMonitorService.getInstance();
        monitor.registerListener(this);
        StaminaService.getServices().addStaminaChangeListener(this);

        // install global preemption and injection rules
        monitor.registerRule(new dev.frostguard.engine.schedule.preempt.BearTrapPreemptionRule());
        monitor.registerRule(new dev.frostguard.engine.schedule.preempt.GatherDeployPreemptionRule());
        monitor.registerRule(new dev.frostguard.engine.schedule.preempt.ManualRallyJoinPreemptionRule());
        monitor.registerInjectionRule(new dev.frostguard.engine.schedule.inject.HelpAllianceInjectionRule());
        monitor.registerInjectionRule(new dev.frostguard.engine.schedule.inject.FurnaceUpgradeInjectionRule());

        LoggingService.obtain().emit(TpMessageSeverityEnum.INFO, "TaskDispatcher", "-", "Launching all queues");
        log.info("Launching all queues");

        int idleCap = resolveIdleLimit();

        List<Map.Entry<Long, TaskQueue>> entries = new ArrayList<>(managedQueues.entrySet());
        entries.sort((a, b) -> {
            boolean aReady = a.getValue().hasRunnableTasksWithin(idleCap);
            boolean bReady = b.getValue().hasRunnableTasksWithin(idleCap);
            if (aReady != bReady) return aReady ? -1 : 1;
            return Long.compare(
                    b.getValue().getProfile().getPriority(),
                    a.getValue().getProfile().getPriority());
        });

        for (Map.Entry<Long, TaskQueue> entry : entries) {
            TaskQueue queue = entry.getValue();
            AccountDescriptor acct = queue.getProfile();
            boolean hasReady = queue.hasRunnableTasksWithin(idleCap);
            log.info("Starting queue for {} | priority={} | ready={}",
                    acct.getName(), acct.getPriority(), hasReady);

            queue.start();
            monitor.startMonitoring(acct,
                    taskEnum -> queue.isExecutingTask(taskEnum) || queue.isTaskScheduledSoon(taskEnum, 10));

            try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }

    // ── stop all queues ─────────────────────────────────────────────

    public StopAllResult stopAll() {
        LoggingService.obtain().emit(TpMessageSeverityEnum.INFO, "TaskDispatcher", "-", "Stopping all queues");
        log.info("Stopping all queues");

        List<Map.Entry<Long, TaskQueue>> queues = new ArrayList<>(managedQueues.entrySet());

        // mark all tasks as not scheduled
        for (Map.Entry<Long, TaskQueue> entry : managedQueues.entrySet()) {
            Long id = entry.getKey();
            for (TpDailyTaskEnum taskEnum : TpDailyTaskEnum.values()) {
                TaskStateData state = TaskManagementService.shared().lookupTaskState(id, taskEnum.getId());
                if (state == null) continue;
                state.setScheduled(false);
                TaskManagementService.shared().recordTaskState(id, state);
            }
        }

        StaminaService.getServices().removeStaminaChangeListener(this);
        GlobalMonitorService.getInstance().shutdown();

        queues.forEach(entry -> entry.getValue().requestStop());

        long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT.toNanos();
        List<TaskQueue.StopResult> results = new ArrayList<>();
        for (Map.Entry<Long, TaskQueue> entry : queues) {
            long remainingNanos = Math.max(0L, deadline - System.nanoTime());
            TaskQueue.StopResult result = entry.getValue().awaitStop(Duration.ofNanos(remainingNanos));
            results.add(result);
            if (result.terminated()) {
                managedQueues.remove(entry.getKey(), entry.getValue());
                pauseFlags.remove(entry.getKey());
            }
        }

        StopAllResult outcome = new StopAllResult(List.copyOf(results));
        if (outcome.complete()) {
            log.info("All {} queue worker(s) terminated", results.size());
        } else {
            log.error("Queue shutdown incomplete; retaining {} live queue(s): {}",
                    outcome.failures().size(), outcome.failureSummary());
        }
        return outcome;
    }

    public boolean hasManagedQueues() {
        return !managedQueues.isEmpty();
    }

    public record StopAllResult(List<TaskQueue.StopResult> queues) {
        public boolean complete() {
            return queues.stream().allMatch(TaskQueue.StopResult::terminated);
        }

        public List<TaskQueue.StopResult> failures() {
            return queues.stream().filter(result -> !result.terminated()).toList();
        }

        public String failureSummary() {
            return failures().stream()
                    .map(result -> result.profileName() + "/" + result.activeTask()
                            + "=" + result.status() + " after " + result.elapsedMillis() + "ms")
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("none");
        }
    }

    // ── bulk pause / resume ─────────────────────────────────────────

    public void pauseAll() {
        LoggingService.obtain().emit(TpMessageSeverityEnum.INFO, "TaskDispatcher", "-", "Pausing all queues");
        log.info("Pausing all queues");
        managedQueues.forEach((id, q) -> {
            q.pause();
            pauseFlags.put(id, Boolean.TRUE);
        });
    }

    public void resumeAll() {
        LoggingService.obtain().emit(TpMessageSeverityEnum.INFO, "TaskDispatcher", "-", "Resuming all queues");
        log.info("Resuming all queues");
        managedQueues.forEach((id, q) -> {
            q.resume();
            pauseFlags.put(id, Boolean.FALSE);
        });
    }

    // ── single-profile controls ─────────────────────────────────────

    public void pauseAccount(Long accountId) {
        TaskQueue q = managedQueues.get(accountId);
        if (q == null) return;
        LoggingService.obtain().emit(TpMessageSeverityEnum.INFO,
                "TaskDispatcher", String.valueOf(accountId), "Pausing queue");
        log.info("Pausing queue for profile {}", accountId);
        q.pause();
        pauseFlags.put(accountId, Boolean.TRUE);
    }

    public void resumeAccount(Long accountId) {
        TaskQueue q = managedQueues.get(accountId);
        if (q == null) return;
        LoggingService.obtain().emit(TpMessageSeverityEnum.INFO,
                "TaskDispatcher", String.valueOf(accountId), "Resuming queue");
        log.info("Resuming queue for profile {}", accountId);
        q.resume();
        pauseFlags.put(accountId, Boolean.FALSE);
    }

    // ── status snapshot ─────────────────────────────────────────────

    public List<QueueProfileStateData> getActiveQueueStates() {
        List<QueueProfileStateData> snapshot = new ArrayList<>();
        for (Map.Entry<Long, TaskQueue> entry : managedQueues.entrySet()) {
            Long id = entry.getKey();
            TaskQueue queue = entry.getValue();
            AccountDescriptor p = queue.getProfile();
            String label = (p != null) ? p.getName() : String.valueOf(id);
            boolean paused = pauseFlags.getOrDefault(id, Boolean.FALSE);
            snapshot.add(new QueueProfileStateData(id, label, paused));
        }
        snapshot.sort(Comparator.comparing(
                QueueProfileStateData::getProfileName, String.CASE_INSENSITIVE_ORDER));
        return snapshot;
    }

    // ── internal helpers ────────────────────────────────────────────

    private int resolveIdleLimit() {
        int configured = Optional.ofNullable(ConfigService.obtain().loadGlobalSettings())
                .map(cfg -> cfg.get(ConfigurationKeyEnum.MAX_IDLE_TIME_INT.name()))
                .map(TaskDispatcher::parseInteger)
                .orElse(Integer.parseInt(ConfigurationKeyEnum.MAX_IDLE_TIME_INT.getDefaultValue()));
        return configured > 0
                ? configured
                : Integer.parseInt(ConfigurationKeyEnum.MAX_IDLE_TIME_INT.getDefaultValue());
    }

    private static Integer parseInteger(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
