package dev.frostguard.engine.schedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.schedule.inject.InjectionRule;
import dev.frostguard.engine.schedule.preempt.PreemptionListener;
import dev.frostguard.engine.schedule.preempt.PreemptionRule;
import dev.frostguard.engine.service.LoggingService;

// Background sentinel that periodically screenshots each monitored profile
// and evaluates preemption and injection rules against the captured frame.
public class GlobalMonitorService {

    private static final Logger log = LoggerFactory.getLogger(GlobalMonitorService.class);
    private static GlobalMonitorService singleton;

    private final EmulatorController controller = EmulatorController.getInstance();
    private final List<PreemptionRule> preemptionRules = new ArrayList<>();
    private final List<InjectionRule> injectionRules = new ArrayList<>();
    private final List<PreemptionListener> preemptionObservers = new CopyOnWriteArrayList<>();

    private final Map<Long, MonitoredEntry> monitoredProfiles = new ConcurrentHashMap<>();
    private final Map<Long, Queue<InjectionRule>> pendingInjections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ticker;

    // Encapsulates a monitored profile and its task-check function.
    private record MonitoredEntry(AccountDescriptor profile,
                                  Function<TpDailyTaskEnum, Boolean> taskActiveCheck) {}

    private GlobalMonitorService() {
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "GlobalMonitor-Ticker");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleWithFixedDelay(this::runCycle, 5, 5, TimeUnit.SECONDS);
    }

    public static synchronized GlobalMonitorService getInstance() {
        if (singleton == null) singleton = new GlobalMonitorService();
        return singleton;
    }

    public void shutdown() {
        monitoredProfiles.clear();
        pendingInjections.clear();
        ticker.shutdownNow();
        synchronized (GlobalMonitorService.class) { singleton = null; }
        log.info("Global monitor shut down");
    }

    // ── registration ────────────────────────────────────────────────

    public void registerListener(PreemptionListener listener) {
        preemptionObservers.add(listener);
    }

    public void registerRule(PreemptionRule rule) {
        preemptionRules.add(rule);
        log.info("Preemption rule added: {}", rule.getRuleName());
    }

    public void registerInjectionRule(InjectionRule rule) {
        injectionRules.add(rule);
        log.info("Injection rule added: {}", rule.getRuleName());
    }

    public void startMonitoring(AccountDescriptor profile,
                                Function<TpDailyTaskEnum, Boolean> taskActiveCheck) {
        monitoredProfiles.put(profile.getId(),
                new MonitoredEntry(profile, taskActiveCheck));
        pendingInjections.computeIfAbsent(profile.getId(), k -> new ConcurrentLinkedQueue<>());
        log.info("Monitoring started for {}", profile.getName());
    }

    public void stopMonitoring(Long profileId) {
        monitoredProfiles.remove(profileId);
        pendingInjections.remove(profileId);
        log.info("Monitoring stopped for profile {}", profileId);
    }

    // Dequeues the next pending injection for the given profile.
    public InjectionRule pollPendingInjection(Long profileId) {
        Queue<InjectionRule> q = pendingInjections.get(profileId);
        return q != null ? q.poll() : null;
    }

    // ── monitoring loop ─────────────────────────────────────────────

    private void runCycle() {
        for (MonitoredEntry entry : monitoredProfiles.values()) {
            try {
                String devIdx = entry.profile.getEmulatorNumber();
                if (!controller.isRunning(devIdx)) continue;
                RawImageData frame = controller.captureScreen(devIdx);
                processPreemptionRules(entry, frame);
                processInjectionRules(entry, frame);
            } catch (Exception ex) {
                log.warn("Monitor tick error [{}]: {}", entry.profile.getId(), ex.getMessage());
            }
        }
    }

    private void processPreemptionRules(MonitoredEntry entry, RawImageData frame) {
        AccountDescriptor acct = entry.profile;
        try {
            for (PreemptionRule rule : preemptionRules) {
                if (entry.taskActiveCheck.apply(rule.getTaskToExecute())) continue;
                try {
                    if (!rule.shouldPreempt(controller, acct, frame)) continue;
                    log.info("Preemption triggered [{}]: {}", acct.getName(), rule.getRuleName());
                    LoggingService.obtain().emit(TpMessageSeverityEnum.WARNING,
                            "GlobalMonitor", acct.getName(),
                            "Preempted by: " + rule.getRuleName());
                    preemptionObservers.forEach(obs -> obs.onPreemption(acct, rule));
                } catch (Exception ex) {
                    log.error("Rule evaluation error [{}]: {} - {}",
                            acct.getName(), rule.getRuleName(), ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.error("Preemption loop error [{}]: {}", acct.getName(), ex.getMessage());
        }
    }

    private void processInjectionRules(MonitoredEntry entry, RawImageData frame) {
        AccountDescriptor acct = entry.profile;
        Queue<InjectionRule> pending = pendingInjections.get(acct.getId());
        if (pending == null) return;

        try {
            for (InjectionRule rule : injectionRules) {
                boolean alreadyQueued = pending.stream()
                        .anyMatch(queued -> queued.getClass().equals(rule.getClass()));
                if (alreadyQueued) continue;
                try {
                    if (!rule.shouldInject(controller, acct, frame)) continue;
                    log.debug("Injection queued [{}]: {}", acct.getName(), rule.getRuleName());
                    pending.offer(rule);
                } catch (Exception ex) {
                    log.error("Injection evaluation error [{}]: {} - {}",
                            acct.getName(), rule.getRuleName(), ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.error("Injection loop error [{}]: {}", acct.getName(), ex.getMessage());
        }
    }
}
