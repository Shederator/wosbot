package dev.frostguard.engine.schedule;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.IdleBehaviorEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.ProfileStatusData;
import dev.frostguard.api.domain.TaskQueueStatusData;
import dev.frostguard.api.domain.TaskStateData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.emulator.QueuePositionListener;
import dev.frostguard.engine.error.ADBConnectionException;
import dev.frostguard.engine.error.HomeNotFoundException;
import dev.frostguard.engine.error.ProfileInReconnectStateException;
import dev.frostguard.engine.error.StopExecutionException;
import dev.frostguard.engine.schedule.inject.InjectionRule;
import dev.frostguard.engine.schedule.preempt.PreemptionRule;
import dev.frostguard.engine.schedule.priority.DefaultTaskPriorityProvider;
import dev.frostguard.engine.schedule.priority.TaskPriorityProvider;
import dev.frostguard.engine.service.AnalyticsService;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.LoggingService;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.engine.service.TaskManagementService;
import dev.frostguard.vision.convert.GameTimeUtils;

/**
 * Per-profile task execution engine.  Runs on a virtual thread and
 * continuously dequeues the highest-priority ready task, dispatching
 * it against the bound Android device.
 */
public class TaskQueue {

    private static final Logger logger = LoggerFactory.getLogger(TaskQueue.class);
    private static final long   TICK_INTERVAL_MS = 999L;

    /**
     * Minimum gap between progress-triggered Daily Missions pushes. matt's stated cadence:
     * daily missions can wait hours, there is no rush. This only bounds the follow-up push —
     * the routine's own schedule still runs it on its normal cycle.
     */
    private static final int DAILY_MISSION_PUSH_MIN_GAP_MINUTES = 180;

    /** When the follow-up push last fired, for the rate limit above. */
    private LocalDateTime lastDailyMissionPushAt;

    /** Guards the sleep-window log so it announces on entry/exit, not once per second. */
    private boolean sleepAnnounced;
    protected static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    private final TaskPriorityProvider rankingStrategy = new DefaultTaskPriorityProvider();
    private final PriorityBlockingQueue<DelayedTask> taskBacklog =
            new PriorityBlockingQueue<>(11, Comparator.comparing(
                    DelayedTask::getScheduled,
                    Comparator.nullsLast(Comparator.naturalOrder())));

    protected final EmulatorController deviceBridge = EmulatorController.getInstance();

    final TaskQueueStatusData statusModel = new TaskQueueStatusData();
    private Thread              executor;
    private AccountDescriptor   profile;
    private volatile ExecutionContext   runningContext;
    private volatile LocalDateTime      sessionOrigin;
    // Changed by pernerch | Date: 2026-07-04 | Why: ensure first startup cycle runs Initialize regardless of idle heuristics.
    private volatile boolean    forceInitialInitialize = true;
    private volatile boolean    shuttingDown = false;

    public TaskQueue(AccountDescriptor profile) { this.profile = profile; }

    // ---- queue manipulation ------------------------------------------------

    public synchronized void enqueue(DelayedTask task) { taskBacklog.offer(task); }

    public synchronized boolean dequeue(TpDailyTaskEnum kind) {
        DelayedTask ref = DelayedTaskRegistry.create(kind, profile);
        if (ref == null) { emitWarn("Cannot build prototype for removal: " + kind.getName()); return false; }
        boolean hit = taskBacklog.removeIf(t -> t.equals(ref));
        if (hit) emitInfoTask(ref, "Removed " + kind.getName() + " from queue");
        else     emitInfo("Task " + kind.getName() + " not present in queue");
        return hit;
    }

    /**
     * Pushes a task that is already waiting in the backlog out to a later time.
     *
     * <p>matt, 2026-08-09, holding the Chief Order shelf up against the app: <em>"productive day
     * is kicking off in three minutes. Rush job is kicking off in three minutes. Urgent
     * mobilization is kicking off in three minutes... it's like you're not even trying."</em> The
     * sweep had read all three cooldowns correctly and written them to the database — but a
     * database row is only consulted when a task is <em>enqueued</em>, at startup. The live
     * objects in the backlog kept the times they were built with, so the screen said ten hours and
     * the bot went back in three minutes. Reading a timer is worthless unless it moves the queued
     * task itself, not just its record.</p>
     *
     * <p>Only ever defers. A swept timer is authoritative about the earliest a visit could be
     * worth making, and must never drag a task forward.</p>
     *
     * @return {@code true} when a queued task was actually moved
     */
    public synchronized boolean deferQueued(TpDailyTaskEnum kind, LocalDateTime until) {
        if (kind == null || until == null) { return false; }
        DelayedTask ref = DelayedTaskRegistry.create(kind, profile);
        if (ref == null) { return false; }

        DelayedTask queued = taskBacklog.stream().filter(t -> t.equals(ref)).findFirst().orElse(null);
        if (queued == null) { return false; }

        LocalDateTime current = queued.getScheduled();
        if (current != null && !current.isBefore(until)) { return false; }

        // The backlog is a priority queue keyed on the scheduled time, so the entry has to come
        // out before that key changes — mutating it in place leaves the heap mis-ordered and the
        // task can still be handed back at its old position.
        taskBacklog.remove(queued);
        queued.rescheduleExact(until);
        taskBacklog.offer(queued);
        recordScheduleAdjustment(queued);

        emitInfoTask(queued, "Deferred to " + until.format(TS_FMT) + " from the swept on-screen timer.");
        return true;
    }

    /**
     * Moves a queued task to an on-screen time in <em>either</em> direction.
     *
     * <p>The blanket "only ever defer" rule is right for camp/research/order timers, but wrong for
     * a task whose work is waiting to be collected. matt, 2026-08-09, on Pet Adventure:
     * <em>"two are done and you're not doing anything about it... you should be pushing out pets on
     * new adventures if there are allotted times left."</em> Finished adventures sit unclaimed and
     * daily attempts expire at reset, so when the sweep reads that the soonest adventure is (or is
     * nearly) done, that task needs to be pulled forward to claim and redeploy — not held behind a
     * stale two-hour fallback.</p>
     *
     * @return {@code true} when the queued task was actually moved
     */
    public synchronized boolean requeueAt(TpDailyTaskEnum kind, LocalDateTime when) {
        if (kind == null || when == null) { return false; }
        DelayedTask ref = DelayedTaskRegistry.create(kind, profile);
        if (ref == null) { return false; }

        DelayedTask queued = taskBacklog.stream().filter(t -> t.equals(ref)).findFirst().orElse(null);
        if (queued == null) { return false; }

        LocalDateTime current = queued.getScheduled();
        if (current != null && Math.abs(java.time.Duration.between(current, when).toSeconds()) < 30) {
            return false; // already essentially there — don't churn the heap
        }

        taskBacklog.remove(queued);
        queued.rescheduleExact(when);
        taskBacklog.offer(queued);
        recordScheduleAdjustment(queued);

        emitInfoTask(queued, "Rescheduled to " + when.format(TS_FMT) + " from the swept on-screen timer.");
        return true;
    }

    public synchronized boolean dequeueByKey(String distinctKey) {
        boolean hit = taskBacklog.removeIf(t -> {
            Object k = t.getDistinctKey();
            return k != null && k.toString().equals(distinctKey);
        });
        emitInfo(hit ? "Removed custom task: " + distinctKey : "Custom task not found: " + distinctKey);
        return hit;
    }

    // ---- accessors ---------------------------------------------------------

    public LocalDateTime     getScheduledUntil() { return statusModel.getDelayUntil(); }
    public boolean           isActive()          { return statusModel.isRunning(); }
    public AccountDescriptor getProfile()        { return profile; }

    public boolean isExecutingTask(TpDailyTaskEnum kind) {
        ExecutionContext snap = runningContext;
        return snap != null && snap.getTask().getTpTask() == kind;
    }

    public synchronized boolean isTaskQueued(TpDailyTaskEnum kind) {
        DelayedTask ref = DelayedTaskRegistry.create(kind, profile);
        return ref != null && taskBacklog.stream().anyMatch(t -> t.equals(ref));
    }

    public synchronized boolean isTaskQueued(String key) {
        return taskBacklog.stream().anyMatch(t -> {
            Object k = t.getDistinctKey();
            return k != null && k.toString().equals(key);
        });
    }

    public synchronized boolean isTaskScheduledSoon(TpDailyTaskEnum kind, long withinSec) {
        DelayedTask ref = DelayedTaskRegistry.create(kind, profile);
        return ref != null && taskBacklog.stream()
                .filter(t -> t.equals(ref))
                .anyMatch(t -> t.getDelay(TimeUnit.SECONDS) <= withinSec);
    }

        // Changed by pernerch | Date: 2026-07-02 | Why: expose overdue runnable snapshot so
        // peer queues on the same emulator can be prioritized before idle behavior closes/suspends.
        public synchronized Optional<OverdueRunnableSnapshot> peekMostRelevantOverdueRunnableTask() {
        LocalDateTime now = LocalDateTime.now();

        return taskBacklog.stream()
            .filter(t -> t.getDelay(TimeUnit.MILLISECONDS) <= 0)
            .max(Comparator
                .comparingInt((DelayedTask t) -> rankingStrategy.getPriority(t))
                .thenComparingLong(t -> Duration.between(t.getScheduled(), now).getSeconds()))
            .map(t -> new OverdueRunnableSnapshot(
                t.getTaskName(),
                t.getTpTask(),
                rankingStrategy.getPriority(t),
                Math.max(0, Duration.between(t.getScheduled(), now).getSeconds()),
                t.getScheduled()));
        }

    public boolean hasRunnableTasksWithin(int maxIdleMin) {
        if (taskBacklog.isEmpty()) return false;
        long capSec = TimeUnit.MINUTES.toSeconds(maxIdleMin);
        return taskBacklog.stream()
                .filter(t -> t.getTpTask() != TpDailyTaskEnum.INITIALIZE)
                .anyMatch(t -> t.getDelay(TimeUnit.SECONDS) < capSec);
    }

    // ---- stamina re-evaluation ---------------------------------------------

    public synchronized int reconsiderStaminaDeferrals(int currentStamina) {
        int moved = 0;
        LocalDateTime now = LocalDateTime.now();

        for (DelayedTask task : new ArrayList<>(taskBacklog)) {
            StaminaDeferral deferral = task.getStaminaDeferral();
            if (deferral == null) {
                continue;
            }

            LocalDateTime revisedWakeAt = deferral.revisedWakeAt(currentStamina, now);
            if (!revisedWakeAt.isBefore(task.getScheduled())) {
                continue;
            }

            LocalDateTime previousWakeAt = task.getScheduled();
            taskBacklog.remove(task);
            task.reschedule(revisedWakeAt);
            taskBacklog.offer(task);
            recordScheduleAdjustment(task);
            emitInfoTask(task, String.format(
                    "External stamina gain: current=%d minimum=%d target=%d floor=%s; wake-up %s -> %s",
                    currentStamina,
                    deferral.minimumRequired(),
                    deferral.regenerationTarget(),
                    deferral.earliestRunnableAt().format(TS_FMT),
                    previousWakeAt.format(TS_FMT),
                    task.getScheduled().format(TS_FMT)));
            moved++;
        }
        return moved;
    }

    private void recordScheduleAdjustment(DelayedTask task) {
        Object distinctKey = task.getDistinctKey();
        String customName = distinctKey == null ? null : distinctKey.toString();
        TaskStateData state = TaskManagementService.shared().lookupTaskState(
                profile.getId(), task.getTpDailyTaskId(), customName);
        if (state == null) {
            state = new TaskStateData();
            state.setProfileId(profile.getId());
            state.setTaskId(task.getTpDailyTaskId());
            state.setCustomTaskName(customName);
            state.setScheduled(true);
            state.setExecuting(false);
        }
        state.setNextExecutionTime(task.getScheduled());
        TaskManagementService.shared().recordTaskState(profile.getId(), state);
        ScheduleService.obtain().persistScheduleAdjustment(
                profile, task.getTpTask(), task.getScheduled(), customName, task.getStaminaDeferral());
    }

    // ---- preemption --------------------------------------------------------

    public synchronized void preemptActiveTask(PreemptionRule rule) {
        DelayedTask replacement = DelayedTaskRegistry.create(rule.getTaskToExecute(), profile);
        if (replacement == null) { emitWarn("Preemption ignored - no mapping for " + rule.getTaskToExecute()); return; }

        boolean shouldSignal = false;
        ExecutionContext ctx = runningContext;
        if (ctx != null) {
            int runningRank  = rankingStrategy.getPriority(ctx.getTask());
            int incomingRank = rankingStrategy.getPriority(replacement);
            if (runningRank > incomingRank) { emitInfo("Preemption blocked - active task outranks"); }
            else { emitWarn("Interrupting " + ctx.getTask().getTaskName() + " for: " + rule.getRuleName()); shouldSignal = true; }
        }

        if (taskBacklog.remove(replacement)) emitInfo("Moved " + replacement.getTaskName() + " to NOW");
        else                                  emitInfo("Injecting " + replacement.getTaskName() + " NOW");
        enqueue(replacement);
        if (shouldSignal && ctx != null) ctx.preempt(rule);
    }

    // ---- lifecycle ---------------------------------------------------------

    public void start() {
        if (statusModel.isRunning()) return;
        // Changed by pernerch | Date: 2026-07-04 | Why: reset startup Initialize gate on each queue start.
        forceInitialInitialize = true;
        statusModel.setRunning(true);
        executor = Thread.ofVirtual().unstarted(this::mainLoop);
        executor.setName("TaskQueue-" + profile.getName());
        executor.start();
    }

    public void stop() {
        shuttingDown = true;
        statusModel.setRunning(false);
        sessionOrigin = null;
        if (executor != null) {
            executor.interrupt();
            try { 
                // Give the task 2 seconds to finish gracefully
                executor.join(2000); 
            } catch (InterruptedException ie) { 
                Thread.currentThread().interrupt(); 
            }
        }
        statusModel.reset();
        taskBacklog.clear();
        broadcastStatus("NOT RUNNING");
        emitInfo("TaskQueue stopped");
    }

    public void pause()  { statusModel.userPause(); broadcastStatus("PAUSE REQUESTED"); emitInfo("Queue paused"); }
    public void resume() {
        statusModel.setPaused(false);
        statusModel.setUserPaused(false);
        statusModel.setDelayUntil(LocalDateTime.now());
        broadcastStatus("RESUMING");
        emitInfo("Queue resumed");
    }

    // ---- run-now -----------------------------------------------------------

    public synchronized void runNow(TpDailyTaskEnum kind, boolean recurring) {
        DelayedTask ref = DelayedTaskRegistry.create(kind, profile);
        if (ref == null) { emitWarn("Task not found: " + kind); return; }
        statusModel.setNeedsReconnect(true);

        DelayedTask present = taskBacklog.stream().filter(ref::equals).findFirst().orElse(null);
        // Track the object actually placed back in the backlog: when an existing task was found we
        // enqueue THAT one, and the throwaway `ref` prototype is never scheduled. Persisting from
        // `ref` below recorded a null/stale nextExecutionTime for the real, enqueued task.
        DelayedTask enqueued;
        if (present != null) {
            taskBacklog.remove(present);
            present.setProfile(profile);
            present.clearStaminaDeferral();
            present.reschedule(LocalDateTime.now());
            present.setRecurring(recurring);
            taskBacklog.offer(present);
            enqueued = present;
            emitInfoTask(present, "Rescheduled " + kind + " to NOW");
        } else {
            ref.reschedule(LocalDateTime.now());
            ref.setRecurring(recurring);
            taskBacklog.offer(ref);
            enqueued = ref;
            emitInfoTask(ref, "Enqueued " + kind + " for immediate execution");
        }

        TaskStateData st = new TaskStateData();
        st.setProfileId(profile.getId()); st.setTaskId(kind.getId());
        st.setScheduled(true); st.setExecuting(false);
        st.setLastExecutionTime(LocalDateTime.now()); st.setNextExecutionTime(enqueued.getScheduled());
        TaskManagementService.shared().recordTaskState(profile.getId(), st);
    }

    // ========================================================================
    //  Main loop
    // ========================================================================

    private void mainLoop() {
        acquireSlot();
        while (statusModel.isRunning() && !shuttingDown) {
            statusModel.loopStarted();
            profile = ProfileService.obtain().fetchAllAccounts().stream()
                    .filter(p -> p.getId().equals(profile.getId())).findFirst().orElse(profile);

            if (statusModel.isPaused())                { onPausedTick(); continue; }
            if (statusModel.isReadyToReconnect() && !deviceBridge.isRunning(profile.getEmulatorNumber())) {
                emitInfo("Device offline - re-acquiring slot"); acquireSlot();
            }
            if (enforceSessionCap()) continue;

            DelayedTask chosen = selectNextTask();

            if (chosen != null) {
                statusModel.getLoopState().setExecutedTask(executeTask(chosen));
                statusModel.setIdleTimeExceeded(false);
            } else if (!statusModel.isPaused()) {
                tryIdleInjection();
            }

            handleIdleTransitions();

            if (!statusModel.getLoopState().isExecutedTask() && !statusModel.isPaused()) {
                DelayedTask head = taskBacklog.peek();
                String nextLabel = head == null ? "None" : head.getTaskName();
                broadcastStatus(describeIdleState(head == null ? null : head.getScheduled(), nextLabel));
                statusModel.getLoopState().endLoop();
                long nap = Math.max(0, TICK_INTERVAL_MS - statusModel.getLoopState().getDuration());
                try { Thread.sleep(nap); } catch (InterruptedException ie) { 
                    if (shuttingDown) break; // Exit immediately on shutdown
                    Thread.currentThread().interrupt(); 
                }
            }
        }
    }

    /**
     * Classifies the current idle gap as sleep when the next task is far enough out.
     *
     * <p>Purely observational — it never withholds a task. If something is due it has already
     * run by the time this is called, which is what makes matt's "why sleep if it is doing
     * nothing anyway" point the right design: the queue is idle regardless, and this only gives
     * that idleness a name and an end time the UI can show.</p>
     *
     * @param nextDueAt when the head of the backlog is scheduled, may be {@code null}
     * @return a status line describing the wait
     */
    private String describeIdleState(LocalDateTime nextDueAt, String nextLabel) {
        boolean asleep = SleepWindowPolicy.reportIdleUntil(profile.getId(), nextDueAt);

        if (asleep) {
            if (!sleepAnnounced) {
                sleepAnnounced = true;
                emitInfo("Nothing due for " + formatCountdown(nextDueAt)
                        + " (over the " + SleepWindowPolicy.thresholdMinutes()
                        + "-minute idle threshold) - sleeping until " + nextLabel + " is due.");
            }
            return "Sleeping " + formatCountdown(nextDueAt) + "\nNext: " + nextLabel;
        }

        if (sleepAnnounced) {
            sleepAnnounced = false;
            emitInfo("Waking - " + nextLabel + " is coming due.");
        }
        return "Idle " + formatCountdown(statusModel.getDelayUntil()) + "\nNext: " + nextLabel;
    }

    private synchronized DelayedTask selectNextTask() {
        DelayedTask head = taskBacklog.peek();
        if (head == null) { statusModel.setDelayUntil(LocalDateTime.now().plusSeconds(1)); return null; }
        if (head.getDelay(TimeUnit.MILLISECONDS) > 0) { statusModel.setDelayUntil(head.getScheduled()); return null; }

        List<DelayedTask> batch = new ArrayList<>();
        batch.add(taskBacklog.poll());
        while (taskBacklog.peek() != null && taskBacklog.peek().getDelay(TimeUnit.MILLISECONDS) <= 0)
            batch.add(taskBacklog.poll());

        DelayedTask winner = batch.stream()
                .max(Comparator.comparingInt(rankingStrategy::getPriority))
                .orElse(batch.get(0));
        batch.stream().filter(t -> t != winner).forEach(taskBacklog::offer);
        return winner;
    }

    private void tryIdleInjection() {
        InjectionRule pending = GlobalMonitorService.getInstance().pollPendingInjection(profile.getId());
        if (pending == null) return;
        broadcastStatus("Injection: " + pending.getRuleName());
        emitInfo("Running idle injection: " + pending.getRuleName());
        try {
            DelayedTask stub = DelayedTaskRegistry.create(TpDailyTaskEnum.INITIALIZE, profile);
            stub.setTaskName("Idle Injection");
            pending.executeInjection(EmulatorController.getInstance(), profile, stub);
        } catch (Exception ex) { emitError("Injection error: " + ex.getMessage()); }
        statusModel.getLoopState().setExecutedTask(true);
    }

    // ---- task dispatch -----------------------------------------------------

    private boolean executeTask(DelayedTask task) {
        if (shuttingDown) {
            emitInfo("Skipping task execution during shutdown: " + task.getTaskName());
            return false;
        }
        if (task.getTpTask() == TpDailyTaskEnum.INITIALIZE && !shouldRunInitialize()) {
            emitInfoTask(task, "Skipping Initialize - no imminent tasks"); return false;
        }
        LocalDateTime priorSchedule = task.getScheduled();
        TaskStateData st = recordPreExecution(task);
        long t0 = System.currentTimeMillis();
        boolean ok;
        ExecutionContext ctx = new ExecutionContext(task);
        synchronized (this) { runningContext = ctx; }
        try {
            emitInfoTask(task, "Executing: " + task.getTaskName());
            broadcastStatus("Executing " + task.getTaskName());
            AnalyticsService.getInstance().trackTaskStarted(task.getTaskName());
            task.setLastExecutionTime(LocalDateTime.now());
            task.run();
            // Changed by pernerch | Date: 2026-07-04 | Why: clear forced-Initialize mode once Initialize completed successfully.
            if (task.getTpTask() == TpDailyTaskEnum.INITIALIZE) {
                forceInitialInitialize = false;
            }
            long elapsed = (System.currentTimeMillis() - t0) / 1000;
            LocalDateTime scheduledAfterRun = task.getScheduled();
            emitInfoTask(task, "Completed: " + task.getTaskName() + " scheduled="
                    + (scheduledAfterRun != null ? scheduledAfterRun.format(TS_FMT) : "none"));
            AnalyticsService.getInstance().trackTaskCompleted(task.getTaskName(), "success", elapsed);
            ok = true;
            checkDailyMissionFollow(task);
        } catch (dev.frostguard.engine.error.TaskPreemptedException ex) {
            emitWarnTask(task, "PREEMPTED: " + ex.getReasoning());
            AnalyticsService.getInstance().trackTaskCompleted(task.getTaskName(), "preempted", (System.currentTimeMillis()-t0)/1000);
            task.reschedule(LocalDateTime.now()); ok = false;
        } catch (Exception ex) {
            if (shuttingDown) {
                emitInfo("Task interrupted during shutdown: " + task.getTaskName());
                ok = false;
            } else {
                routeError(task, ex);
                AnalyticsService.getInstance().trackTaskCompleted(task.getTaskName(), "failed", (System.currentTimeMillis()-t0)/1000);
                ok = false;
            }
        } finally {
            synchronized (this) { if (runningContext != null) runningContext.clear(); runningContext = null; }
            if (!shuttingDown) {
                handleReschedule(task, priorSchedule);
                recordPostExecution(task, st);
            }
        }
        return ok;
    }

    // ---- helpers -----------------------------------------------------------

    private boolean isInitializeWorthRunning() {
        if (profile.getConfig(ConfigurationKeyEnum.SKIP_TUTORIAL_ENABLED_BOOL, Boolean.class)) return false;
        int maxIdle = Optional.ofNullable(ConfigService.obtain().loadGlobalSettings())
                .map(c -> c.get(ConfigurationKeyEnum.MAX_IDLE_TIME_INT.name())).map(Integer::parseInt)
                .orElse(Integer.parseInt(ConfigurationKeyEnum.MAX_IDLE_TIME_INT.getDefaultValue()));
        return hasRunnableTasksWithin(maxIdle);
    }

    private boolean shouldRunInitialize() {
        // Changed by pernerch | Date: 2026-07-04 | Why: keep first Initialize mandatory, then fall back to previous worth-check behavior.
        // matt/2026-08-14: the "mandatory" force ran Initialize even for a profile with ZERO
        // enabled tasks (Testing, freshly cleared) -- there was nothing to initialize FOR, so
        // it just tapped the screen for no reason. Only force it when there's actually at
        // least one other task queued; an empty backlog means truly do nothing.
        return (forceInitialInitialize && hasAnyNonInitializeTaskQueued()) || isInitializeWorthRunning();
    }

    private boolean hasAnyNonInitializeTaskQueued() {
        return taskBacklog.stream().anyMatch(t -> t.getTpTask() != TpDailyTaskEnum.INITIALIZE);
    }

    private TaskStateData recordPreExecution(DelayedTask task) {
        TaskStateData s = new TaskStateData();
        s.setProfileId(profile.getId()); s.setTaskId(task.getTpDailyTaskId());
        Object k = task.getDistinctKey(); if (k != null) s.setCustomTaskName(k.toString());
        s.setScheduled(true); s.setExecuting(true);
        s.setLastExecutionTime(LocalDateTime.now()); s.setNextExecutionTime(task.getScheduled());
        TaskManagementService.shared().recordTaskState(profile.getId(), s);
        return s;
    }

    private void recordPostExecution(DelayedTask task, TaskStateData s) {
        if (shuttingDown) {
            emitInfo("Skipping state save during shutdown");
            return;
        }
        s.setExecuting(false); s.setScheduled(task.isRecurring());
        s.setLastExecutionTime(LocalDateTime.now()); s.setNextExecutionTime(task.getScheduled());
        Object k = task.getDistinctKey(); if (k != null) s.setCustomTaskName(k.toString());
        TaskManagementService.shared().recordTaskState(profile.getId(), s);
        if (task.getScheduled() != null) {
            ScheduleService.obtain().persistDailyCompletion(
                    profile, task.getTpTask(), task.getScheduled(), s.getCustomTaskName(), task.getStaminaDeferral());
        }
    }

    private void handleReschedule(DelayedTask task, LocalDateTime before) {
        if (Objects.equals(before, task.getScheduled()) && task.isRecurring()) task.reschedule(LocalDateTime.now());
        if (task.isRecurring()) { emitInfoTask(task, "Next run in: " + GameTimeUtils.formatCountdown(task.getScheduled())); enqueue(task); }
        else emitInfoTask(task, "Task removed from queue");
    }

    private void routeError(DelayedTask task, Exception ex) {
        if (ex instanceof HomeNotFoundException) {
            emitErrorTask(task, "Home not found: " + ex.getMessage());
            enqueue(DelayedTaskRegistry.create(TpDailyTaskEnum.INITIALIZE, profile));
        } else if (ex instanceof StopExecutionException) {
            emitErrorTask(task, "Execution stopped: " + ex.getMessage());
        } else if (ex instanceof ProfileInReconnectStateException) {
            onReconnectNeeded((ProfileInReconnectStateException) ex);
        } else if (ex instanceof ADBConnectionException) {
            emitErrorTask(task, "ADB error: " + ex.getMessage());
            enqueue(DelayedTaskRegistry.create(TpDailyTaskEnum.INITIALIZE, profile));
        } else {
            emitErrorTask(task, "Unexpected error: " + ex.getMessage());
        }
    }

    private void onReconnectNeeded(ProfileInReconnectStateException ex) {
        Long mins = profile.getReconnectionTime();
        if (mins != null && mins > 0) { emitInfo("Reconnect pause: " + mins + " min"); statusModel.setReconnectAt(mins); }
        else { emitError("No reconnect time configured"); attemptReconnect(); }
    }

    private void attemptReconnect() {
        try {
            ImageSearchResultData r = deviceBridge.locatePattern(profile.getEmulatorNumber(), TemplatesEnum.GAME_HOME_RECONNECT, 90);
            if (r.isFound()) deviceBridge.touchPoint(profile.getEmulatorNumber(), r.getPoint());
            enqueue(DelayedTaskRegistry.create(TpDailyTaskEnum.INITIALIZE, profile));
        } catch (Exception ex) { emitError("Reconnect error: " + ex.getMessage()); }
    }

    private void checkDailyMissionFollow(DelayedTask task) {
        if (!profile.getConfig(ConfigurationKeyEnum.DAILY_MISSION_AUTO_SCHEDULE_BOOL, Boolean.class) || !task.provideDailyMissionProgress()) return;

        // matt, 2026-08-08: rate-limit this. It fires after EVERY completed task reporting daily
        // mission progress, and a great many of them do, so Daily Missions was dragged back to
        // "now" about once a minute — measured at 4 runs in under 4 minutes, and 52 runs across
        // three hours, the busiest task on the board. Each run now also walks the Growth tab, so
        // the cost per push went up. matt's call on cadence: "that could be checked every, like,
        // three hours. There's no rush on that at all." The rewards do not expire; only the
        // daily reset matters, and the routine's own scheduling already covers that.
        LocalDateTime now = LocalDateTime.now();
        if (lastDailyMissionPushAt != null
                && lastDailyMissionPushAt.plusMinutes(DAILY_MISSION_PUSH_MIN_GAP_MINUTES).isAfter(now)) {
            return;
        }

        TaskStateData s = TaskManagementService.shared().lookupTaskState(profile.getId(), TpDailyTaskEnum.DAILY_MISSIONS.getId());
        LocalDateTime next = (s != null) ? s.getNextExecutionTime() : null;
        if (s == null || next == null || next.isAfter(now)) {
            lastDailyMissionPushAt = now;
            pushDailyMissionsToNow();
        }
    }

    private synchronized void pushDailyMissionsToNow() {
        DelayedTask ref = DelayedTaskRegistry.create(TpDailyTaskEnum.DAILY_MISSIONS, profile);
        DelayedTask existing = taskBacklog.stream().filter(ref::equals).findFirst().orElse(null);
        if (existing != null) { taskBacklog.remove(existing); existing.reschedule(LocalDateTime.now()); existing.setRecurring(true); taskBacklog.offer(existing); }
        else { ref.reschedule(LocalDateTime.now()); ref.setRecurring(false); taskBacklog.offer(ref); }
    }

    private void handleIdleTransitions() {
        if (Thread.currentThread().isInterrupted()) return;
        if (statusModel.getLoopState().isExecutedTask() || taskBacklog.isEmpty()) return;
        int idleCap = Optional.ofNullable(ConfigService.obtain().loadGlobalSettings())
                .map(c -> c.get(ConfigurationKeyEnum.MAX_IDLE_TIME_INT.name())).map(Integer::parseInt)
                .orElse(Integer.parseInt(ConfigurationKeyEnum.MAX_IDLE_TIME_INT.getDefaultValue()));
        statusModel.setIdleTimeLimit(idleCap);
        if (runningContext != null) return;
        if (!statusModel.isIdleTimeExceeded() && statusModel.checkIdleTimeExceeded()) {
            boolean keep = Boolean.TRUE.equals(profile.getConfig(ConfigurationKeyEnum.KEEP_EMULATOR_OPEN_BOOL, Boolean.class));
            if (keep) { emitInfo("Idle exceeded - keeping device open per config"); statusModel.setIdleTimeExceeded(true); return; }

            // Changed by pernerch | Date: 2026-07-02 | Why: keep single-profile-per-emulator
            // setups on the original idle path; only evaluate handover when siblings exist.
            if (hasEnabledSiblingOnSameEmulator()) {
                Optional<PeerSwitchCandidate> peerCandidate = findBestOverduePeerOnSameEmulator();
                if (peerCandidate.isPresent()) {
                    handoverSlotToPeer(peerCandidate.get());
                    statusModel.setIdleTimeExceeded(true);
                    return;
                }
            }

            suspendDevice(statusModel.getDelayUntil(), false);
                    // Changed by pernerch | Date: 2026-07-02 | Why: force immediate activation of the
                    // selected peer queue after slot handover to eliminate idle dead time.
            statusModel.setIdleTimeExceeded(true);
        } else if (statusModel.isIdleTimeExceeded() && LocalDateTime.now().plusMinutes(1).isAfter(statusModel.getDelayUntil())) {
            emitInfo("Next task approaching - re-acquiring slot"); acquireSlot();
            enqueue(DelayedTaskRegistry.create(TpDailyTaskEnum.INITIALIZE, profile));
            statusModel.setIdleTimeExceeded(false);
        }
    }

    private Optional<PeerSwitchCandidate> findBestOverduePeerOnSameEmulator() {
        if (profile == null || profile.getEmulatorNumber() == null || profile.getEmulatorNumber().isBlank()) {
            return Optional.empty();
        }

        TaskDispatcher coordinator = ScheduleService.obtain().getCoordinator();
        if (coordinator == null) {
            return Optional.empty();
        }

        return ProfileService.obtain().fetchAllAccounts().stream()
                .filter(other -> other != null && other.getId() != null && !other.getId().equals(profile.getId()))
                .filter(other -> Boolean.TRUE.equals(other.getEnabled()))
                .filter(other -> profile.getEmulatorNumber().equals(other.getEmulatorNumber()))
                .map(other -> {
                    TaskQueue q = coordinator.getQueue(other.getId());
                    if (q == null || !q.isActive()) {
                        return null;
                    }
                    Optional<OverdueRunnableSnapshot> snapshot = q.peekMostRelevantOverdueRunnableTask();
                    return snapshot.map(value -> new PeerSwitchCandidate(other, q, value)).orElse(null);
                })
                .filter(Objects::nonNull)
                .max(Comparator
                        .comparingInt((PeerSwitchCandidate c) -> c.overdue().taskPriority())
                        .thenComparingLong(c -> c.account().getPriority())
                        .thenComparingLong(c -> c.overdue().overdueSeconds()));
    }

    private boolean hasEnabledSiblingOnSameEmulator() {
        // Changed by pernerch | Date: 2026-07-02 | Why: explicit sibling detection guard for
        // no-impact behavior in single-profile-per-emulator environments.
        if (profile == null || profile.getEmulatorNumber() == null || profile.getEmulatorNumber().isBlank()) {
            return false;
        }

        return ProfileService.obtain().fetchAllAccounts().stream()
                .filter(other -> other != null && other.getId() != null && !other.getId().equals(profile.getId()))
                .filter(other -> Boolean.TRUE.equals(other.getEnabled()))
                .anyMatch(other -> profile.getEmulatorNumber().equals(other.getEmulatorNumber()));
    }

    private void handoverSlotToPeer(PeerSwitchCandidate candidate) {
        OverdueRunnableSnapshot overdue = candidate.overdue();
        emitInfo(String.format(
                "Idle exceeded - handing emulator slot to profile '%s' (task=%s, taskPriority=%d, profilePriority=%d, overdue=%ds)",
                candidate.account().getName(),
                overdue.taskType(),
                overdue.taskPriority(),
                candidate.account().getPriority(),
                overdue.overdueSeconds()));

        try {
            deviceBridge.releaseEmulatorSlot(profile);
            sessionOrigin = null;
        } catch (Exception ex) {
            emitWarn("Slot handover warning: " + ex.getMessage());
        }

        candidate.queue().runNow(TpDailyTaskEnum.INITIALIZE, false);
        candidate.queue().resume();
    }

    private record PeerSwitchCandidate(AccountDescriptor account,
                                       TaskQueue queue,
                                       OverdueRunnableSnapshot overdue) {
    }

    public record OverdueRunnableSnapshot(String taskName,
                                          TpDailyTaskEnum taskType,
                                          int taskPriority,
                                          long overdueSeconds,
                                          LocalDateTime scheduledAt) {
    }

    private void suspendDevice(LocalDateTime until, boolean freeSlot) {
        IdleBehaviorEnum policy = IdleBehaviorEnum.fromString(
                Optional.ofNullable(ConfigService.obtain().loadGlobalSettings())
                        .map(c -> c.getOrDefault(ConfigurationKeyEnum.IDLE_BEHAVIOR_STRING.name(), ConfigurationKeyEnum.IDLE_BEHAVIOR_STRING.getDefaultValue()))
                        .orElse(ConfigurationKeyEnum.IDLE_BEHAVIOR_STRING.getDefaultValue()));
        if (policy == IdleBehaviorEnum.SEND_TO_BACKGROUND) {
            deviceBridge.sendGameToBackground(profile.getEmulatorNumber());
            emitInfo("Device sent to background until " + until);
            if (freeSlot) { deviceBridge.releaseEmulatorSlot(profile); sessionOrigin = null; emitInfo("Slot released"); }
        } else if (policy == IdleBehaviorEnum.PC_SLEEP) {
            sessionOrigin = null; triggerPcSleep(until);
        } else {
            deviceBridge.closeEmulator(profile.getEmulatorNumber());
            emitInfo("Device closed until " + until);
            deviceBridge.releaseEmulatorSlot(profile); sessionOrigin = null;
        }
        broadcastStatus("Idle till " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(until));
    }

    private boolean enforceSessionCap() {
        if (runningContext != null || sessionOrigin == null) return false;
        Map<String,String> cfg = ConfigService.obtain().loadGlobalSettings();
        boolean on = Boolean.parseBoolean(Optional.ofNullable(cfg)
                .map(c -> c.get(ConfigurationKeyEnum.PROFILE_MAX_ACTIVE_TIME_ENABLED_BOOL.name()))
                .orElse(ConfigurationKeyEnum.PROFILE_MAX_ACTIVE_TIME_ENABLED_BOOL.getDefaultValue()));
        if (!on) return false;
        long active = ProfileService.obtain().fetchAllAccounts().stream().filter(p -> Boolean.TRUE.equals(p.getEnabled())).count();
        if (active <= 1) return false;
        int cap = Math.max(1, Optional.ofNullable(cfg)
                .map(c -> c.get(ConfigurationKeyEnum.PROFILE_MAX_ACTIVE_TIME_MINUTES_INT.name())).map(Integer::parseInt)
                .orElse(Integer.parseInt(ConfigurationKeyEnum.PROFILE_MAX_ACTIVE_TIME_MINUTES_INT.getDefaultValue())));
        if (LocalDateTime.now().isBefore(sessionOrigin.plusMinutes(cap))) return false;
        emitInfo("Max session time (" + cap + " min) reached - forcing idle");
        suspendDevice(statusModel.getDelayUntil(), true);
        statusModel.setIdleTimeExceeded(true);
        return true;
    }

    private void acquireSlot() {
        broadcastStatus("Waiting for device slot");
        try {
            QueuePositionListener cb = (t, pos) -> broadcastStatus("Queue position: " + pos);
            deviceBridge.adquireEmulatorSlot(profile, cb);
            sessionOrigin = LocalDateTime.now();
        } catch (InterruptedException ie) { emitError("Interrupted waiting for slot"); Thread.currentThread().interrupt(); }
    }

    private void onPausedTick() {
        if (!statusModel.isUserPaused() && statusModel.getDelayUntil().isBefore(LocalDateTime.now())) {
            boolean reconnect = statusModel.needsReconnect();
            if (reconnect) statusModel.setNeedsReconnect(false);
            broadcastStatus(reconnect ? "RESUMING AFTER PAUSE" : "RESUMING");
            statusModel.setPaused(false);
            if (!deviceBridge.isRunning(profile.getEmulatorNumber())) acquireSlot();
            if (reconnect) attemptReconnect();
            return;
        }
        broadcastStatus("PAUSED");
        if (LocalDateTime.now().getSecond() % 10 == 0) emitInfo("Queue paused");
        try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private void triggerPcSleep(LocalDateTime wakeAt) {
        try {
            deviceBridge.closeEmulator(profile.getEmulatorNumber());
            deviceBridge.releaseEmulatorSlot(profile);
            LocalDateTime wake = wakeAt.minusMinutes(1);
            if (wake.isBefore(LocalDateTime.now())) wake = LocalDateTime.now().plusMinutes(1);
            String tm = DateTimeFormatter.ofPattern("HH:mm").format(wake);
            String dt = DateTimeFormatter.ofPattern("MM/dd/yyyy").format(wake);
            String jar = System.getProperty("user.dir") + "\\fg-app\\target\\frostguard.jar";
            new ProcessBuilder("schtasks","/create","/TN","Frostguard_AutoStart","/TR",
                    "javaw.exe -jar \""+jar+"\" --autostart","/SC","ONCE","/ST",tm,"/SD",dt,"/RL","HIGHEST","/F")
                    .redirectErrorStream(true).start().waitFor();
            java.nio.file.Path ws = java.nio.file.Paths.get(System.getProperty("user.dir"),"fg_wake.ps1");
            java.nio.file.Files.writeString(ws,
                    "$s=New-ScheduledTaskSettingsSet -WakeToRun -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -Priority 1\n"+
                    "Set-ScheduledTask -TaskName 'Frostguard_AutoStart' -Settings $s\n");
            new ProcessBuilder("powershell.exe","-NoProfile","-ExecutionPolicy","Bypass","-File",ws.toString())
                    .redirectErrorStream(true).start().waitFor();
            java.nio.file.Path ss = java.nio.file.Paths.get(System.getProperty("user.dir"),"fg_sleep.ps1");
            java.nio.file.Files.writeString(ss,
                    "Start-Sleep -Seconds 2\nAdd-Type -AssemblyName System.Windows.Forms\n"+
                    "[System.Windows.Forms.Application]::SetSuspendState('Suspend',$false,$false)\n");
            new ProcessBuilder("powershell.exe","-NoProfile","-ExecutionPolicy","Bypass","-File",ss.toString()).start();
            System.exit(0);
        } catch (Exception ex) { emitError("PC sleep scheduling error: " + ex.getMessage()); }
    }

    private String formatCountdown(LocalDateTime target) {
        Duration d = Duration.between(LocalDateTime.now(), target);
        return String.format("%02d:%02d:%02d", d.toHours(), d.toMinutesPart(), d.toSecondsPart());
    }

    // ---- logging -----------------------------------------------------------
    private void emitInfo(String msg)                        { logger.info("{} - {}", profile.getName(), msg);  LoggingService.obtain().emit(TpMessageSeverityEnum.INFO,    "TaskQueue", profile.getName(), msg); }
    private void emitInfoTask(DelayedTask t, String msg)     { logger.info("{} - {}", profile.getName(), msg);  LoggingService.obtain().emit(TpMessageSeverityEnum.INFO,    t.getTaskName(), profile.getName(), msg); }
    private void emitWarn(String msg)                        { logger.warn("{} - {}", profile.getName(), msg);  LoggingService.obtain().emit(TpMessageSeverityEnum.WARNING, "TaskQueue", profile.getName(), msg); }
    @SuppressWarnings("unused")
    private void emitWarnTask(DelayedTask t, String msg)     { logger.warn("{} - {}", profile.getName(), msg);  LoggingService.obtain().emit(TpMessageSeverityEnum.WARNING, t.getTaskName(), profile.getName(), msg); }
    private void emitError(String msg)                       { logger.error("{} - {}", profile.getName(), msg); LoggingService.obtain().emit(TpMessageSeverityEnum.ERROR,   "TaskQueue", profile.getName(), msg); }
    private void emitErrorTask(DelayedTask t, String msg)    { logger.error("{} - {}", profile.getName(), msg); LoggingService.obtain().emit(TpMessageSeverityEnum.ERROR,   t.getTaskName(), profile.getName(), msg); }
    private void broadcastStatus(String s)                   { ProfileService.obtain().broadcastStatusChange(new ProfileStatusData(profile.getId(), s)); }
}
