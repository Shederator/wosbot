package dev.frostguard.engine.schedule;

import dev.frostguard.api.runtime.WorkspacePaths;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.IdleBehaviorEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ActionRequiredIncidentReport;
import dev.frostguard.api.domain.TaskFailureReport;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.ProfileStatusData;
import dev.frostguard.api.domain.TaskQueueStatusData;
import dev.frostguard.api.domain.TaskStateData;
import dev.frostguard.engine.emulator.DeviceReleaseGuard;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.emulator.QueuePositionListener;
import dev.frostguard.engine.error.ADBConnectionException;
import dev.frostguard.engine.error.ActionRequiredContext;
import dev.frostguard.engine.error.BearSessionExecutionException;
import dev.frostguard.engine.error.HomeNotFoundException;
import dev.frostguard.engine.error.ProfileCooldownException;
import dev.frostguard.engine.error.ProfileInReconnectStateException;
import dev.frostguard.engine.error.StopExecutionException;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.schedule.inject.InjectionRule;
import dev.frostguard.engine.schedule.preempt.PreemptionRule;
import dev.frostguard.engine.schedule.priority.DefaultTaskPriorityProvider;
import dev.frostguard.engine.schedule.priority.TaskPriorityProvider;
import dev.frostguard.engine.service.AnalyticsService;
import dev.frostguard.engine.service.ActionRequiredIncidentService;
import dev.frostguard.engine.service.TaskFailureIncidentService;
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
    private static final String APP_LAUNCHER_PROPERTY = "frostguard.launcher";
    private static final long   TICK_INTERVAL_MS = 999L;
    /** Longest a Bear retry may wait while its event window is still active. */
    static final long BEAR_IN_WINDOW_RETRY_CAP_SECONDS = 30L;

    protected static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    private final TaskPriorityProvider rankingStrategy = new DefaultTaskPriorityProvider();
    private final PriorityBlockingQueue<DelayedTask> taskBacklog =
            new PriorityBlockingQueue<>(11, Comparator.comparing(
                    DelayedTask::getScheduled,
                    Comparator.nullsLast(Comparator.naturalOrder())));

    protected final EmulatorController deviceBridge = EmulatorController.getInstance();

    final TaskQueueStatusData statusModel = new TaskQueueStatusData();
    private final TaskQueueExecutor executor = new TaskQueueExecutor();
    private volatile AccountDescriptor profile;
    private volatile ExecutionContext   runningContext;
    private volatile LocalDateTime      sessionOrigin;
    private volatile boolean bearCleanupOwedByRunningTask;
    /** End of the event an operator revoked; every Bear run for that event is cleanup-only. */
    private volatile Instant bearRevokedUntil;
    private final BooleanSupplier deviceOwnership = () -> hasProtectedBearOwnership(Instant.now());
    private volatile String             profileCooldownStatus;
    // Changed by pernerch | Date: 2026-07-04 | Why: ensure first startup cycle runs Initialize regardless of idle heuristics.
    private volatile boolean    forceInitialInitialize = true;
    private volatile boolean    idleWakeInitializationPending = false;
    private volatile boolean    idleWakeInitializationForceNow = false;
    private volatile boolean    shuttingDown = false;
    private volatile boolean    stoppedCleanly = false;
    private Instant bearRecoveryLeaseEnd;
    private int bearDeviceRebindAttempts;
    private int bearAppRestartAttempts;
    private int bearDegradedFailures;
    private int bearTotalRecoveryAttempts;

    public enum StopStatus {
        TERMINATED,
        TIMED_OUT,
        CALLER_INTERRUPTED
    }

    public record StopResult(Long profileId, String profileName, String activeTask,
            StopStatus status, long elapsedMillis) {
        public boolean terminated() {
            return status == StopStatus.TERMINATED;
        }
    }

    public TaskQueue(AccountDescriptor profile) { this.profile = profile; }

    // ---- queue manipulation ------------------------------------------------

    public synchronized void enqueue(DelayedTask task) { offerToBacklog(task); }

    /**
     * Bear owns its profile through a single task: a revocation, a restore, and a returning session
     * may all requeue it, and the earliest schedule must win instead of running Bear twice.
     */
    private synchronized void offerToBacklog(DelayedTask task) {
        if (task.getTpTask() == TpDailyTaskEnum.BEAR_TRAP) {
            DelayedTask existing = taskBacklog.stream()
                    .filter(queued -> queued.getTpTask() == TpDailyTaskEnum.BEAR_TRAP)
                    .min(Comparator.comparing(DelayedTask::getScheduled))
                    .orElse(null);
            if (existing == task) return;
            if (existing != null && !existing.getScheduled().isAfter(task.getScheduled())) return;
            taskBacklog.removeIf(queued -> queued.getTpTask() == TpDailyTaskEnum.BEAR_TRAP);
        }
        taskBacklog.offer(task);
    }

    synchronized Optional<LocalDateTime> bearScheduledAt() {
        return taskBacklog.stream()
                .filter(task -> task.getTpTask() == TpDailyTaskEnum.BEAR_TRAP)
                .map(DelayedTask::getScheduled)
                .min(LocalDateTime::compareTo);
    }

    public synchronized boolean dequeue(TpDailyTaskEnum kind) {
        DelayedTask ref = createTask(kind);
        if (ref == null) { emitWarn("Cannot build prototype for removal: " + kind.getName()); return false; }
        boolean hit = taskBacklog.removeIf(t -> t.equals(ref));
        if (hit) emitInfoTask(ref, "Removed " + kind.getName() + " from queue");
        else     emitInfo("Task " + kind.getName() + " not present in queue");
        return hit;
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
    public boolean           isPaused()          { return statusModel.isPaused(); }
    public AccountDescriptor getProfile()        { return profile; }

    public synchronized void applyProfileUpdate(AccountDescriptor updatedProfile) {
        if (updatedProfile == null || updatedProfile.getId() == null
                || profile == null || !updatedProfile.getId().equals(profile.getId())) return;
        profile = updatedProfile;
        taskBacklog.forEach(task -> task.setProfile(updatedProfile));
    }

    public boolean isExecutingTask(TpDailyTaskEnum kind) {
        ExecutionContext snap = runningContext;
        return snap != null && snap.getTask().getTpTask() == kind;
    }

    public synchronized boolean isTaskQueued(TpDailyTaskEnum kind) {
        DelayedTask ref = createTask(kind);
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

    public synchronized List<TpDailyTaskEnum> getNextQueuedTaskTypes(int limit) {
        if (limit <= 0) {
            return List.of();
        }

        Comparator<DelayedTask> priorityOrder = Comparator
                .comparingInt(rankingStrategy::getPriority)
                .reversed();
        Comparator<DelayedTask> queueOrder = Comparator
                .comparing(DelayedTask::getScheduled, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(priorityOrder);
        return taskBacklog.stream()
                .sorted(queueOrder)
                .limit(limit)
                .map(DelayedTask::getTpTask)
                .toList();
    }

    public synchronized boolean scheduleOrRescheduleQueuedTask(
            TpDailyTaskEnum kind,
            AccountDescriptor updatedProfile,
            LocalDateTime nextRun) {
        if (kind == null || updatedProfile == null || nextRun == null) {
            return false;
        }
        if (kind == TpDailyTaskEnum.BEAR_TRAP && hasProtectedBearOwnership(Instant.now())) {
            Optional<LocalDateTime> queued = bearScheduledAt();
            if (queued.isEmpty() || nextRun.isAfter(queued.get())) {
                // The active event owns Bear's schedule; an edit applies after it ends.
                emitInfo("Bear schedule edit deferred: the active event keeps its in-window retry");
                return false;
            }
        }
        DelayedTask ref = DelayedTaskRegistry.create(kind, updatedProfile);
        if (ref == null) {
            return false;
        }
        DelayedTask existing = taskBacklog.stream().filter(ref::equals).findFirst().orElse(null);
        if (existing == null) {
            if (isExecutingTask(kind)) {
                return false;
            }
            ref.reschedule(nextRun);
            ref.setRecurring(true);
            offerToBacklog(ref);
            emitInfoTask(ref, "Enqueued with aligned schedule " + nextRun.format(TS_FMT));
            return true;
        }

        taskBacklog.remove(existing);
        existing.setProfile(updatedProfile);
        existing.reschedule(nextRun);
        offerToBacklog(existing);
        emitInfoTask(existing, "Schedule realigned to " + nextRun.format(TS_FMT));
        return true;
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
        if (executor.isAlive()) {
            throw new IllegalStateException("Cannot start queue while its previous worker is still alive: "
                    + profile.getName());
        }
        // Changed by pernerch | Date: 2026-07-04 | Why: reset startup Initialize gate on each queue start.
        forceInitialInitialize = true;
        shuttingDown = false;
        stoppedCleanly = false;
        profileCooldownStatus = null;
        idleWakeInitializationPending = false;
        idleWakeInitializationForceNow = false;
        statusModel.setRunning(true);
        registerDeviceProtection();
        restoreDurableBearOwnershipOnStart();
        executor.start(this::mainLoop, "TaskQueue-" + profile.getName());
    }

    public void requestStop() {
        shuttingDown = true;
        unregisterDeviceProtection();
        statusModel.setRunning(false);
        sessionOrigin = null;
        boolean durableBearHandoff = BearTrapSessionLease.active(profile.getId())
                .map(lease -> BearRecoveryFinalization.arm(profile, lease.eventEnd())
                        && BearSessionCheckpoint.recordScheduler(profile, new BearSessionCheckpoint.Checkpoint(
                    lease.eventEnd(),
                    "SCHEDULER",
                    "QUEUE_STOPPED",
                    "NONE",
                    0,
                    Instant.EPOCH,
                    0,
                    "explicit-queue-stop",
                    Instant.now()), true))
                .orElse(true);
        if (durableBearHandoff) {
            BearTrapSessionLease.releaseForQueueStop(profile.getId());
        } else {
            emitError("Bear queue stop could not persist its finalizer/checkpoint; "
                    + "the in-memory event lease is being retained");
        }
        ExecutionContext context = runningContext;
        if (context != null) {
            context.cancel();
        }
        executor.interrupt();
    }

    public StopResult awaitStop(Duration timeout) {
        String activeTask = activeTaskName();
        TaskQueueExecutor.AwaitResult result = executor.awaitTermination(timeout);
        StopResult stopResult = new StopResult(profile.getId(), profile.getName(), activeTask,
                StopStatus.valueOf(result.termination().name()), result.elapsedMillis());
        if (stopResult.terminated()) {
            completeStop();
        } else {
            String reason = result.termination() == TaskQueueExecutor.Termination.TIMED_OUT
                    ? "shutdown deadline exceeded"
                    : "shutdown waiter interrupted";
            broadcastStatus("STOPPING - " + reason);
            emitError("Queue still active after stop request: task=" + activeTask
                    + ", reason=" + reason + ", waitedMs=" + result.elapsedMillis());
        }
        return stopResult;
    }

    public StopResult stop() {
        requestStop();
        return awaitStop(Duration.ofSeconds(10));
    }

    public boolean hasLiveExecutor() {
        return executor.isAlive();
    }

    private synchronized void completeStop() {
        if (stoppedCleanly) {
            return;
        }
        stoppedCleanly = true;
        statusModel.reset();
        taskBacklog.clear();
        broadcastStatus("NOT RUNNING");
        emitInfo("TaskQueue stopped after worker termination");
    }

    private String activeTaskName() {
        ExecutionContext context = runningContext;
        return context == null ? "none" : context.getTask().getTaskName();
    }

    public void pause()  { statusModel.userPause(); broadcastStatus("PAUSE REQUESTED"); emitInfo("Queue paused"); }
    public void resume() {
        profileCooldownStatus = null;
        statusModel.setPaused(false);
        statusModel.setUserPaused(false);
        if (statusModel.isIdleTimeExceeded()) {
            idleWakeInitializationPending = true;
            idleWakeInitializationForceNow = true;
        }
        statusModel.setDelayUntil(LocalDateTime.now());
        broadcastStatus("RESUMING");
        emitInfo("Queue resumed");
    }

    // ---- run-now -----------------------------------------------------------

    public synchronized void runNow(TpDailyTaskEnum kind, boolean recurring) {
        if (kind == TpDailyTaskEnum.BEAR_TRAP
                && BearRecoveryFinalization.deadline(profile).isPresent()
                && !hasProtectedBearOwnership(Instant.now())) {
            emitError("Bear Run Now refused outside an active configured event window; the pending "
                    + "recovery finalizer was preserved");
            return;
        }
        DelayedTask ref = createTask(kind);
        if (ref == null) { emitWarn("Task not found: " + kind); return; }
        statusModel.setNeedsReconnect(true);

        DelayedTask present = taskBacklog.stream().filter(ref::equals).findFirst().orElse(null);
        if (present != null) {
            taskBacklog.remove(present);
            present.setProfile(profile);
            present.clearStaminaDeferral();
            present.reschedule(LocalDateTime.now());
            present.setRecurring(recurring);
            offerToBacklog(present);
            emitInfoTask(present, "Rescheduled " + kind + " to NOW");
        } else {
            ref.reschedule(LocalDateTime.now());
            ref.setRecurring(recurring);
            offerToBacklog(ref);
            emitInfoTask(ref, "Enqueued " + kind + " for immediate execution");
        }

        TaskStateData st = new TaskStateData();
        st.setProfileId(profile.getId()); st.setTaskId(kind.getId());
        st.setScheduled(true); st.setExecuting(false);
        st.setLastExecutionTime(LocalDateTime.now()); st.setNextExecutionTime(ref.getScheduled());
        TaskManagementService.shared().recordTaskState(profile.getId(), st);
    }

    protected DelayedTask createTask(TpDailyTaskEnum kind) {
        return DelayedTaskRegistry.create(kind, profile);
    }

    // ========================================================================
    //  Main loop
    // ========================================================================

    private void mainLoop() {
        acquireSlot();
        while (statusModel.isRunning() && !shuttingDown) {
            runSchedulerTickSafely();
        }
    }

    /** A failure in one tick (routing, finalization, persistence) must not end the worker. */
    void runSchedulerTickSafely() {
        try {
            runSchedulerTick();
        } catch (RuntimeException tickFailure) {
            emitError("Scheduler tick failed; the queue keeps running: " + tickFailure.getMessage());
            logger.error("Scheduler tick failed for profile {}", profile.getName(), tickFailure);
            sleepSchedulerTick(TICK_INTERVAL_MS);
        }
    }

    void runSchedulerTick() {
        statusModel.loopStarted();
        profile = ProfileService.obtain().fetchAllAccounts().stream()
                .filter(p -> p.getId().equals(profile.getId())).findFirst().orElse(profile);

        if (statusModel.isPaused()) {
            onPausedTick();
            return;
        }

        if (!prepareIdleQueueForExecution()) {
            if (!statusModel.getLoopState().isExecutedTask() && !statusModel.isPaused()) {
                finishIdleSchedulerTick();
            }
            return;
        }

        if (requiresSlotAcquisition(sessionOrigin)) {
            emitInfo("No active device lease - re-acquiring slot");
            acquireSlot();
            if (requiresSlotAcquisition(sessionOrigin)) {
                finishIdleSchedulerTick();
                return;
            }
        } else if (statusModel.isReadyToReconnect()
                && !deviceBridge.isRunning(profile.getEmulatorNumber())) {
            emitInfo("Device offline - re-acquiring slot");
            acquireSlot();
        }
        if (enforceSessionCap()) return;

        DelayedTask chosen = selectNextTask();

        if (chosen != null) {
            statusModel.getLoopState().setExecutedTask(executeTask(chosen));
            statusModel.setIdleTimeExceeded(false);
        } else if (!statusModel.isPaused()) {
            tryIdleInjection();
        }

        if (shouldHandleIdleTransitions(statusModel)) handleIdleTransitions();

        if (!statusModel.getLoopState().isExecutedTask() && !statusModel.isPaused()) {
            finishIdleSchedulerTick();
        }
    }

    private boolean prepareIdleQueueForExecution() {
        if (idleWakeInitializationPending) {
            return runIdleWakeInitialization();
        }
        if (!statusModel.isIdleTimeExceeded()) {
            return true;
        }

        LocalDateTime nextRun = Optional.ofNullable(taskBacklog.peek())
                .map(DelayedTask::getScheduled)
                .orElse(statusModel.getDelayUntil());
        boolean taskApproaching = nextRun != null
                && !LocalDateTime.now().plusMinutes(1).isBefore(nextRun);
        if (!taskApproaching) {
            return !requiresSlotAcquisition(sessionOrigin);
        }

        idleWakeInitializationPending = true;
        idleWakeInitializationForceNow = true;
        return runIdleWakeInitialization();
    }

    private boolean runIdleWakeInitialization() {
        if (!idleWakeInitializationForceNow && hasDeferredIdleWakeInitialize()) {
            return false;
        }

        if (requiresSlotAcquisition(sessionOrigin)) {
            emitInfo("Next task approaching - re-acquiring slot");
            acquireSlot();
            if (requiresSlotAcquisition(sessionOrigin)) {
                return false;
            }
        } else {
            emitInfo("Next task approaching - resuming active slot");
        }

        DelayedTask initialize = takeIdleWakeInitialize(idleWakeInitializationForceNow);
        if (initialize == null) {
            return false;
        }
        idleWakeInitializationForceNow = false;
        if (initialize.getDelay(TimeUnit.MILLISECONDS) > 0) {
            return false;
        }

        boolean initialized = executeTask(initialize);
        statusModel.getLoopState().setExecutedTask(initialized);
        if (initialized) {
            idleWakeInitializationPending = false;
            idleWakeInitializationForceNow = false;
            statusModel.setIdleTimeExceeded(false);
        } else if (!statusModel.isPaused()) {
            deferFailedIdleWakeInitialize(initialize);
            releaseSlotAfterFailedIdleWake();
        }
        return false;
    }

    /** Test seam between releasing the lease and arming the cleanup; production does nothing. */
    protected void afterBearOwnershipReleased() {
    }

    /** Test seam inside the atomic Bear claim; production does nothing here. */
    protected void afterBearLeaseAcquired() {
    }

    /** A failed idle-wake Initialize frees the slot, unless this profile owns an active Bear event. */
    void releaseSlotAfterFailedIdleWake() {
        if (requiresSlotAcquisition(sessionOrigin) || hasProtectedBearOwnership(Instant.now())) {
            return;
        }
        try {
            releaseActiveSlotLease();
        } catch (RuntimeException ex) {
            emitWarn("Could not release slot after failed idle wake Initialize: " + ex.getMessage());
        }
    }

    private synchronized boolean hasDeferredIdleWakeInitialize() {
        return taskBacklog.stream()
                .filter(task -> task.getTpTask() == TpDailyTaskEnum.INITIALIZE)
                .anyMatch(task -> task.getDelay(TimeUnit.MILLISECONDS) > 0);
    }

    private synchronized void deferFailedIdleWakeInitialize(DelayedTask attemptedInitialize) {
        boolean retryAlreadyQueued = taskBacklog.stream()
                .anyMatch(task -> task.getTpTask() == TpDailyTaskEnum.INITIALIZE);
        if (retryAlreadyQueued) {
            return;
        }

        attemptedInitialize.setRecurring(true);
        attemptedInitialize.reschedule(LocalDateTime.now()
                .plus(TaskFailureIncidentService.DEFAULT_UNHANDLED_RETRY_DELAY));
        taskBacklog.offer(attemptedInitialize);
        recordScheduleAdjustment(attemptedInitialize);
        emitWarnTask(attemptedInitialize,
                "Idle wake Initialize failed; retrying before normal work at "
                        + attemptedInitialize.getScheduled().format(TS_FMT));
    }

    private synchronized DelayedTask takeIdleWakeInitialize(boolean forceNow) {
        if (isExecutingTask(TpDailyTaskEnum.INITIALIZE)) {
            return null;
        }

        DelayedTask queuedInitialize = taskBacklog.stream()
                .filter(task -> task.getTpTask() == TpDailyTaskEnum.INITIALIZE)
                .findFirst()
                .orElse(null);
        if (queuedInitialize != null) {
            if (!forceNow && queuedInitialize.getDelay(TimeUnit.MILLISECONDS) > 0) {
                return null;
            }
            taskBacklog.remove(queuedInitialize);
            if (forceNow) {
                queuedInitialize.reschedule(LocalDateTime.now());
            }
            return queuedInitialize;
        }

        DelayedTask initialize = createIdleWakeInitializeTask();
        if (initialize == null) {
            emitError("Cannot resume idle queue because Initialize could not be created");
            return null;
        }
        initialize.reschedule(LocalDateTime.now());
        return initialize;
    }

    private void finishIdleSchedulerTick() {
        String nextLabel = taskBacklog.isEmpty() ? "None" : taskBacklog.peek().getTaskName();
        broadcastStatus("Idle " + formatCountdown(statusModel.getDelayUntil()) + "\nNext: " + nextLabel);
        statusModel.getLoopState().endLoop();
        long nap = Math.max(0, TICK_INTERVAL_MS - statusModel.getLoopState().getDuration());
        sleepSchedulerTick(nap);
    }

    DelayedTask createIdleWakeInitializeTask() {
        return DelayedTaskRegistry.create(TpDailyTaskEnum.INITIALIZE, profile);
    }

    protected void sleepSchedulerTick(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            if (!shuttingDown) {
                Thread.currentThread().interrupt();
            }
        }
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
        if (BearTrapProtectionPolicy.isFullPauseActive(profile)) return;

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

    boolean executeTask(DelayedTask task) {
        if (shuttingDown) {
            emitInfo("Skipping task execution during shutdown: " + task.getTaskName());
            return false;
        }
        if (finalizeBearRecoveryIfDue(task, Instant.now())) {
            return true;
        }
        if (deferForBearTrapProtection(task)) {
            return false;
        }
        BearTrapSessionLease.Lease bearLease = null;
        ExecutionContext ctx = new ExecutionContext(task);
        if (task.getTpTask() == TpDailyTaskEnum.BEAR_TRAP) {
            {
                if (bearRevoked(Instant.now()) && persistedBearOwnershipAllowed()) {
                    // Bear and its profile are on again in storage: the operator resumed the event.
                    bearRevokedUntil = null;
                    emitInfoTask(task, "Bear re-enabled during its revoked event; resuming the session");
                }
                if (bearRevoked(Instant.now())) {
                    return finalizeRevokedBear(task);
                }
                if (finalizeBearRecoveryIfDue(task, Instant.now())) {
                    return true;
                }
                Optional<BearTrapSessionLease.Lease> acquiredLease =
                        BearTrapSessionLease.acquireForBearExecution(profile);
                if (acquiredLease.isEmpty() && finalizeEndedBearSession(task)) {
                    return true;
                }
                if (acquiredLease.isEmpty()) {
                    task.setRecurring(true);
                    task.reschedule(LocalDateTime.now().plusSeconds(30));
                    emitErrorTask(task, "Bear execution refused: no configured active session deadline; "
                            + "retryAt=" + task.getScheduled().format(TS_FMT));
                    enqueue(task);
                    return false;
                }
                BearTrapSessionLease.Lease lease = acquiredLease.get();
                bearLease = lease;
                if (!BearSessionCheckpoint.open(profile, lease.eventEnd())) {
                    task.setRecurring(true);
                    task.reschedule(LocalDateTime.now().plusSeconds(30));
                    emitErrorTask(task, "Bear execution refused: durable session checkpoint could not be opened; "
                            + "retryAt=" + task.getScheduled().format(TS_FMT));
                    enqueue(task);
                    return false;
                }
                if (BearRecoveryFinalization.deadline(profile).isPresent()
                        && !BearRecoveryFinalization.clear(profile)) {
                    task.setRecurring(true);
                    task.reschedule(LocalDateTime.now().plusSeconds(30));
                    emitErrorTask(task, "Bear execution refused: manual recovery handoff could not clear its "
                            + "finalizer after the new durable checkpoint was opened");
                    enqueue(task);
                    return false;
                }
                afterBearLeaseAcquired();
                emitInfoTask(task, "Bear session lease acquired for Timer "
                        + lease.trapNumber() + " until "
                        + LocalDateTime.ofInstant(lease.eventEnd(), ZoneId.systemDefault())
                                .format(TS_FMT));
                runningContext = ctx;
                if (!bearRevoked(Instant.now()) && !persistedBearOwnershipAllowed()) {
                    // A disable persisted before this claim while nothing was owned yet, so its
                    // revocation had nothing to stop: every disable is stored before it revokes,
                    // so a claim that missed the revocation still finds the disable in storage.
                    bearRevokedUntil = lease.eventEnd();
                    emitWarnTask(task, "Bear claim found the profile or Bear participation disabled in "
                            + "storage; stopping before any input");
                }
                if (bearRevoked(Instant.now())) {
                    // A revocation that missed the published context must still stop this run and
                    // must not leave the lease this claim just took pinning the device.
                    bearCleanupOwedByRunningTask = true;
                    ctx.cancel();
                    BearTrapSessionLease.releaseForQueueStop(profile.getId());
                }
            }
        }
        if (task.getTpTask() == TpDailyTaskEnum.INITIALIZE
                && !idleWakeInitializationPending
                && !shouldRunInitialize()) {
            emitInfoTask(task, "Skipping Initialize - no imminent tasks"); return false;
        }
        LocalDateTime priorSchedule = task.getScheduled();
        TaskStateData st = recordPreExecution(task);
        long t0 = System.currentTimeMillis();
        boolean ok;
        synchronized (this) {
            runningContext = ctx;
            if (shuttingDown) {
                ctx.cancel();
            }
        }
        try {
            emitInfoTask(task, "Executing: " + task.getTaskName());
            broadcastStatus("Executing " + task.getTaskName());
            AnalyticsService.getInstance().trackTaskStarted(task.getTaskName());
            task.setLastExecutionTime(LocalDateTime.now());
            task.run();
            if (bearLease != null && Instant.now().isBefore(bearLease.eventEnd())) {
                // A normal return before the event end is not a completed event. Keep the
                // durable ownership and resume inside the window.
                scheduleBearRetry(task, bearInWindowRetrySeconds(bearLease));
                emitWarnTask(task, "Bear returned before its event end; durable ownership kept; "
                        + "retryAt=" + task.getScheduled().format(TS_FMT));
            } else if (bearLease != null
                    && (!BearRecoveryFinalization.clear(profile)
                            || !BearSessionCheckpoint.clear(profile)
                            || !BearObserveOnlyFallback.clear(profile))) {
                throw new IllegalStateException(
                        "completed Bear execution but could not clear its durable recovery state");
            }
            // Keep the initial force flag until Initialize reports success. A profile cooldown
            // persists and re-enqueues Initialize for the requested retry time instead.
            if (task.getTpTask() == TpDailyTaskEnum.INITIALIZE && !task.isRecurring()) {
                forceInitialInitialize = false;
            }
            long elapsed = (System.currentTimeMillis() - t0) / 1000;
            LocalDateTime scheduledAfterRun = task.getScheduled();
            emitInfoTask(task, "Completed: " + task.getTaskName() + " scheduled="
                    + (scheduledAfterRun != null ? scheduledAfterRun.format(TS_FMT) : "none"));
            AnalyticsService.getInstance().trackTaskCompleted(task.getTaskName(), "success", elapsed);
            ok = true;
            checkDailyMissionFollow(task);
            try {
                TaskFailureIncidentService.obtain().recordSuccess(
                        profile.getId(), incidentTaskKey(task));
            } catch (RuntimeException exception) {
                emitWarnTask(task, "Could not reset persistent task-failure state: "
                        + exception.getMessage());
            }
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
                if (task.getTpTask() == TpDailyTaskEnum.BEAR_TRAP && bearCleanupOwedByRunningTask) {
                    bearCleanupOwedByRunningTask = false;
                    queueBearCleanupNow();
                }
                recordPostExecution(task, st);
            }
        }
        return ok;
    }

    private boolean deferForBearTrapProtection(DelayedTask task) {
        BearTrapProtectionPolicy.Decision decision =
                BearTrapProtectionPolicy.evaluateTask(profile, task.getTpTask());
        if (!decision.blocked()) {
            return false;
        }

        LocalDateTime retryAt = LocalDateTime.ofInstant(
                decision.releaseAt(), ZoneId.systemDefault());
        task.reschedule(retryAt);
        enqueue(task);

        String reason = decision.reason() == BearTrapProtectionPolicy.BlockReason.ALL_TASKS
                ? "all scheduled tasks are paused"
                : "the task can start a rally";
        emitInfoTask(task, "Bear Trap " + decision.trapNumbers()
                + " protection window is active and " + reason
                + ". Deferred until " + retryAt.format(TS_FMT));
        try {
            recordDeferredState(task);
            ScheduleService.obtain().persistNextSchedule(
                    profile, task.getTpTask(), retryAt, distinctTaskLabel(task));
        } catch (Exception ex) {
            emitWarnTask(task, "Could not persist Bear Trap deferral: " + ex.getMessage());
        }
        return true;
    }

    private void recordDeferredState(DelayedTask task) {
        String customLabel = distinctTaskLabel(task);
        TaskStateData previous = TaskManagementService.shared().lookupTaskState(
                profile.getId(), task.getTpDailyTaskId(), customLabel);
        TaskStateData deferred = TaskStateData.of(
                profile.getId(),
                task.getTpDailyTaskId(),
                customLabel,
                true,
                false,
                previous != null ? previous.getLastExecutionTime() : task.getLastExecutionTime(),
                task.getScheduled());
        TaskManagementService.shared().recordTaskState(profile.getId(), deferred);
    }

    private String distinctTaskLabel(DelayedTask task) {
        Object key = task.getDistinctKey();
        return key != null ? key.toString() : null;
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
        return forceInitialInitialize || isInitializeWorthRunning();
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
        task.setProfile(profile);
        ConfigurationKeyEnum configKey = task.getTpTask().getConfigKey();
        if (configKey != null && !Boolean.TRUE.equals(profile.getConfig(configKey, Boolean.class))) {
            task.setRecurring(false);
        }
        if (Objects.equals(before, task.getScheduled()) && task.isRecurring()) task.reschedule(LocalDateTime.now());
        if (task.isRecurring()) { emitInfoTask(task, "Next run in: " + GameTimeUtils.formatCountdown(task.getScheduled())); enqueue(task); }
        else emitInfoTask(task, "Task removed from queue");
    }

    void routeError(DelayedTask task, Exception ex) {
        Optional<BearTrapSessionLease.Lease> activeBearLease =
                task.getTpTask() == TpDailyTaskEnum.BEAR_TRAP
                        ? BearTrapSessionLease.active(profile.getId())
                        : Optional.empty();
        if (ex instanceof BearSessionExecutionException
                && activeBearLease.isPresent()) {
            BearSessionExecutionException bearFailure = (BearSessionExecutionException) ex;
            BearTrapSessionLease.Lease lease = activeBearLease.orElseThrow();
            resetBearRecoveryBudgetFor(lease);
            int plannedAttempts = plannedAttemptsFor(bearFailure.recoveryDirective());
            if (!BearSessionCheckpoint.recordScheduler(profile, new BearSessionCheckpoint.Checkpoint(
                    lease.eventEnd(),
                    "RECOVERING",
                    bearFailure.failureKind().name(),
                    bearFailure.operation(),
                    0,
                    Instant.EPOCH,
                    plannedAttempts,
                    bearFailure.recoveryDirective().name(),
                    Instant.now()), false)) {
                throw new IllegalStateException(
                        "Bear recovery refused because its durable recovery point could not be persisted",
                        bearFailure);
            }
            String recoveryResult = applyBearRecoveryDirective(task, bearFailure, lease);
            emitErrorTask(task, "Bear session entered protected recovery: kind="
                    + bearFailure.failureKind()
                    + "; directive=" + bearFailure.recoveryDirective()
                    + "; device=" + bearFailure.device()
                    + "; operation=" + bearFailure.operation()
                    + "; detail=" + ex.getMessage()
                    + "; recovery=" + recoveryResult
                    + (task.isRecurring() && task.getScheduled() != null
                            ? "; retryAt=" + task.getScheduled().format(TS_FMT)
                            : "; retry=stopped"));
        } else if (ex instanceof ProfileCooldownException cooldown) {
            pauseForProfileCooldown(task, cooldown);
        } else if (ex instanceof HomeNotFoundException) {
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
            routeUnexpectedFailure(task, ex);
        }
    }

    private void resetBearRecoveryBudgetFor(BearTrapSessionLease.Lease lease) {
        if (!Objects.equals(bearRecoveryLeaseEnd, lease.eventEnd())) {
            bearRecoveryLeaseEnd = lease.eventEnd();
            int durableAttempts = BearSessionCheckpoint.load(profile)
                    .filter(checkpoint -> checkpoint.eventEnd().equals(lease.eventEnd()))
                    .map(BearSessionCheckpoint.Checkpoint::recoveryAttempts)
                    .orElse(0);
            bearDeviceRebindAttempts = 0;
            bearAppRestartAttempts = 0;
            bearDegradedFailures = 0;
            bearTotalRecoveryAttempts = durableAttempts;
        }
    }

    private int plannedAttemptsFor(BearSessionExecutionException.RecoveryDirective directive) {
        return directive == BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION
                || bearTotalRecoveryAttempts >= 4
                ? bearTotalRecoveryAttempts
                : bearTotalRecoveryAttempts + 1;
    }

    private String applyBearRecoveryDirective(
            DelayedTask task,
            BearSessionExecutionException failure,
            BearTrapSessionLease.Lease lease) {
        return switch (failure.recoveryDirective()) {
            case DEGRADED_WAIT -> {
                if (bearTotalRecoveryAttempts >= 4 || bearDegradedFailures >= 4) {
                    scheduleBearFinalization(task, lease);
                    yield "degraded retry budget exhausted; operator action required; retrying inside the window";
                }
                bearDegradedFailures++;
                bearTotalRecoveryAttempts++;
                long delaySeconds = Math.min(30L, 2L << Math.min(3, bearDegradedFailures - 1));
                scheduleBearRetry(task, delaySeconds);
                yield "bounded degraded wait #" + bearDegradedFailures
                        + "; totalRecovery=" + bearTotalRecoveryAttempts + "/4";
            }
            case REBIND_DEVICE -> {
                if (bearTotalRecoveryAttempts >= 4 || bearDeviceRebindAttempts >= 2) {
                    scheduleBearFinalization(task, lease);
                    yield "per-device rebind budget exhausted; operator action required; retrying inside the window";
                }
                bearDeviceRebindAttempts++;
                bearTotalRecoveryAttempts++;
                boolean healthy = probeBearDevice();
                scheduleBearRetry(task, healthy ? 2 : 8);
                yield "per-device probe #" + bearDeviceRebindAttempts + " healthy=" + healthy
                        + "; totalRecovery=" + bearTotalRecoveryAttempts + "/4";
            }
            case RESTART_APP -> {
                if (bearTotalRecoveryAttempts >= 4 || bearAppRestartAttempts >= 2) {
                    scheduleBearFinalization(task, lease);
                    yield "app restart budget exhausted; operator action required; retrying inside the window";
                }
                bearAppRestartAttempts++;
                bearTotalRecoveryAttempts++;
                boolean foreground = restartBearApp(task);
                scheduleBearRetry(task, foreground ? 3 : 8);
                yield "Whiteout restart #" + bearAppRestartAttempts + " foreground=" + foreground
                        + "; totalRecovery=" + bearTotalRecoveryAttempts + "/4";
            }
            case OPERATOR_ACTION -> {
                scheduleBearFinalization(task, lease);
                yield "operator action required";
            }
        };
    }

    private void scheduleBearFinalization(
            DelayedTask task, BearTrapSessionLease.Lease lease) {
        if (!BearRecoveryFinalization.arm(profile, lease.eventEnd())) {
            throw new IllegalStateException("Could not persist the Bear recovery finalizer");
        }
        if (Instant.now().isBefore(lease.eventEnd())) {
            // An exhausted budget limits the retry rate; it must not surrender the event. The
            // rest of the event is observed without input, and the armed finalizer keeps the
            // device pinned and restores normal work after the end.
            if (BearObserveOnlyFallback.arm(profile, lease.eventEnd())) {
                emitErrorTask(task, "Bear switched to observe-only for the rest of this event; "
                        + "operator attention required");
            } else {
                emitErrorTask(task, "Bear observe-only fallback could not be persisted");
            }
            scheduleBearRetry(task, bearInWindowRetrySeconds(lease));
            return;
        }
        task.setRecurring(true);
        task.reschedule(LocalDateTime.ofInstant(lease.eventEnd(), ZoneId.systemDefault()));
    }

    private static long bearInWindowRetrySeconds(BearTrapSessionLease.Lease lease) {
        long untilEnd = Duration.between(Instant.now(), lease.eventEnd()).getSeconds();
        return Math.max(1L, Math.min(BEAR_IN_WINDOW_RETRY_CAP_SECONDS, untilEnd));
    }

    /**
     * A session whose event already ended has no lease to acquire. Hand it to the finalizer
     * instead of refusing every retry until the next window.
     */
    private boolean finalizeEndedBearSession(DelayedTask task) {
        Instant now = Instant.now();
        Optional<Instant> endedAt = BearSessionCheckpoint.load(profile)
                .map(BearSessionCheckpoint.Checkpoint::eventEnd)
                .filter(end -> !now.isBefore(end));
        if (endedAt.isEmpty() || BearRecoveryFinalization.deadline(profile).isPresent()) {
            return false;
        }
        if (!BearRecoveryFinalization.arm(profile, endedAt.get())) {
            emitErrorTask(task, "Ended Bear session could not arm its finalizer");
            return false;
        }
        return finalizeBearRecoveryIfDue(task, now);
    }

    /** Whether storage has the profile and its Bear participation enabled; unreadable storage says no. */
    private boolean persistedBearOwnershipAllowed() {
        try {
            return ProfileService.obtain().fetchAllAccounts().stream()
                    .filter(candidate -> profile.getId().equals(candidate.getId()))
                    .findFirst()
                    .filter(persisted -> Boolean.TRUE.equals(persisted.getEnabled())
                            && Boolean.TRUE.equals(persisted.getConfig(
                                    ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, Boolean.class)))
                    .isPresent();
        } catch (RuntimeException unreadable) {
            emitError("Bear could not read the stored profile to confirm it is still enabled: "
                    + unreadable.getMessage());
            return false;
        }
    }

    /**
     * The operator re-enabled the profile during an event its disable revoked: queue Bear so its
     * next claim resumes the session. Re-enabling participation reaches the claim through the
     * configuration reconcile instead.
     *
     * @return whether a revoked Bear event was handed back to the scheduler
     */
    public boolean resumeRevokedBear() {
        if (!bearRevoked(Instant.now()) || !persistedBearOwnershipAllowed()) {
            return false;
        }
        runNow(TpDailyTaskEnum.BEAR_TRAP, true);
        emitInfo("Profile re-enabled during its revoked Bear event; Bear queued to resume");
        return true;
    }

    private boolean bearRevoked(Instant now) {
        Instant until = bearRevokedUntil;
        return until != null && now.isBefore(until);
    }

    /** A revoked Bear run performs only its cleanup finalization, even if the finalizer was lost. */
    private boolean finalizeRevokedBear(DelayedTask task) {
        Instant now = Instant.now();
        if (BearRecoveryFinalization.deadline(profile).filter(deadline -> !now.isBefore(deadline)).isEmpty()
                && !BearRecoveryFinalization.arm(profile, now)) {
            emitErrorTask(task, "Revoked Bear cleanup could not arm its finalizer; retrying");
            task.setRecurring(true);
            task.reschedule(LocalDateTime.now().plusSeconds(30));
            enqueue(task);
            return false;
        }
        return finalizeBearRecoveryIfDue(task, now);
    }

    boolean finalizeBearRecoveryIfDue(DelayedTask task, Instant now) {
        Optional<Instant> finalizationAt = BearRecoveryFinalization.deadline(profile);
        if (task.getTpTask() != TpDailyTaskEnum.BEAR_TRAP
                || finalizationAt.isEmpty()
                || now.isBefore(finalizationAt.orElseThrow())) {
            return false;
        }
        // Decide what to restore from persisted state: the queue's copy may predate an operator
        // disabling the profile or Bear participation.
        AccountDescriptor persisted = ProfileService.obtain().fetchAllAccounts().stream()
                .filter(candidate -> profile.getId().equals(candidate.getId()))
                .findFirst()
                .orElse(profile);
        // Either copy reporting the profile or participation disabled wins.
        boolean profileEnabled = Boolean.TRUE.equals(persisted.getEnabled())
                && Boolean.TRUE.equals(profile.getEnabled());
        if (profileEnabled
                && Boolean.TRUE.equals(persisted.getConfig(ConfigurationKeyEnum.GATHER_TASK_BOOL, Boolean.class))) {
            runNow(TpDailyTaskEnum.GATHER_RESOURCES, true);
        }
        if (profileEnabled
                && Boolean.TRUE.equals(persisted.getConfig(ConfigurationKeyEnum.ALLIANCE_AUTOJOIN_BOOL, Boolean.class))) {
            runNow(TpDailyTaskEnum.ALLIANCE_AUTOJOIN, true);
        }
        Optional<BearTrapParticipationSchedule.Plan> nextPlan =
                BearTrapParticipationSchedule.resolve(
                        persisted,
                        Clock.fixed(now, ZoneId.systemDefault()),
                        ZoneId.systemDefault());
        if (nextPlan.isPresent()) {
            task.reschedule(nextPlan.get().nextRun());
            ScheduleService.obtain().persistNextSchedule(
                    profile, TpDailyTaskEnum.BEAR_TRAP, task.getScheduled(), null);
            boolean participationEnabled = Boolean.TRUE.equals(persisted.getConfig(
                    ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, Boolean.class))
                    && Boolean.TRUE.equals(profile.getConfig(
                            ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, Boolean.class));
            task.setRecurring(participationEnabled && profileEnabled);
            if (participationEnabled && profileEnabled) {
                enqueue(task);
            }
            if (!BearRecoveryFinalization.clear(profile)) {
                throw new IllegalStateException(
                        "Bear recovery finalization completed but its durable marker could not be cleared");
            }
            if (!BearSessionCheckpoint.clear(profile) || !BearObserveOnlyFallback.clear(profile)) {
                throw new IllegalStateException(
                        "Bear recovery finalization completed but its durable checkpoint could not be cleared");
            }
            emitInfoTask(task, "Completed scheduler-owned Bear recovery finalization; next run="
                    + task.getScheduled().format(TS_FMT)
                    + (!profileEnabled ? "; profile remains disabled"
                            : participationEnabled ? "" : "; participation remains disabled"));
        } else {
            task.setRecurring(false);
            if (!BearRecoveryFinalization.clear(profile)) {
                throw new IllegalStateException(
                        "Bear recovery finalization completed but its durable marker could not be cleared");
            }
            if (!BearSessionCheckpoint.clear(profile) || !BearObserveOnlyFallback.clear(profile)) {
                throw new IllegalStateException(
                        "Bear recovery finalization completed but its durable checkpoint could not be cleared");
            }
            emitErrorTask(task, "Bear recovery finalization restored normal work, but the next "
                    + "Bear window could not be resolved from configuration");
        }
        return true;
    }

    private static void scheduleBearRetry(DelayedTask task, long delaySeconds) {
        task.setRecurring(true);
        task.reschedule(LocalDateTime.now().plusSeconds(delaySeconds));
    }

    protected boolean probeBearDevice() {
        deviceBridge.invalidateAllCaches(profile.getEmulatorNumber());
        return deviceBridge.probeDevice(profile.getEmulatorNumber());
    }

    protected boolean restartBearApp(DelayedTask task) {
        try {
            String gamePackage = EmulatorController.GAME.getPackageName();
            deviceBridge.forceStopApp(profile.getEmulatorNumber(), gamePackage);
            deviceBridge.launchApp(profile.getEmulatorNumber(), gamePackage);
            return deviceBridge.isPackageRunning(profile.getEmulatorNumber(), gamePackage);
        } catch (RuntimeException restartFailure) {
            emitWarnTask(task, "Bounded Whiteout restart failed: " + restartFailure.getMessage());
            return false;
        }
    }

    private void routeUnexpectedFailure(DelayedTask task, Exception failure) {
        LocalDateTime retryAt = LocalDateTime.now()
                .plus(TaskFailureIncidentService.DEFAULT_UNHANDLED_RETRY_DELAY);
        int consecutiveFailures = 0;
        boolean escalated = false;
        try {
            TaskFailureIncidentService.FailureDecision decision =
                    TaskFailureIncidentService.obtain().recordUnhandledFailure(
                            profile.getId(), profile.getName(), incidentTaskKey(task),
                            task.getTaskName(), failure, LocalDateTime.now());
            retryAt = decision.retryAt();
            consecutiveFailures = decision.consecutiveFailures();
            escalated = decision.escalated();
        } catch (RuntimeException persistenceFailure) {
            emitWarnTask(task, "Could not persist the task-failure streak: "
                    + persistenceFailure.getMessage());
        }

        task.setRecurring(true);
        if (task.getScheduled() == null || task.getScheduled().isBefore(retryAt)) {
            task.reschedule(retryAt);
        }
        emitErrorTask(task, "Unexpected " + failure.getClass().getSimpleName()
                + ": " + failure.getMessage()
                + "; consecutiveFailures=" + consecutiveFailures
                + "; retryAt=" + retryAt.format(TS_FMT)
                + (escalated ? "; action-required incident active" : ""));
    }

    private void pauseForProfileCooldown(DelayedTask task, ProfileCooldownException cooldown) {
        LocalDateTime retryAt = cooldown.getRetryAt();
        emitErrorTask(task, "Profile cooldown requested: " + cooldown.getMessage()
                + "; queue paused until " + retryAt.format(TS_FMT));
        applyProfileCooldown(task, statusModel, retryAt);
        boolean immediatelyActionRequired = cooldown.getActionRequiredContext().isPresent();
        profileCooldownStatus = (immediatelyActionRequired ? "ACTION REQUIRED - " : "COOLDOWN - ")
                + cooldown.getMessage() + " - retry " + retryAt.format(TS_FMT);

        boolean releaseAllowed = deviceReleaseAllowed();
        if (!releaseAllowed) {
            emitWarnTask(task, "Cooldown kept the game and emulator slot: Bear owns the device");
        }
        boolean gameStopped = releaseAllowed && stopBlockedGameProcess(task);
        boolean slotReleased = !hasProtectedBearOwnership(Instant.now())
                && releaseBlockedProfileSlot(task);
        emitInfoTask(task, "Cooldown resources settled: gameStopped=" + gameStopped
                + ", slotReleased=" + slotReleased
                + ", retryAt=" + retryAt.format(TS_FMT));
        if (immediatelyActionRequired) {
            recordActionRequiredIncident(task, cooldown, gameStopped, slotReleased);
        } else {
            recordCooldownFailureAttempt(task, cooldown, gameStopped, slotReleased);
        }
        broadcastStatus(profileCooldownStatus);
    }

    private void recordActionRequiredIncident(DelayedTask task, ProfileCooldownException cooldown,
            boolean gameStopped, boolean slotReleased) {
        ActionRequiredContext context = cooldown.getActionRequiredContext().orElseThrow();
        try {
            ActionRequiredIncidentService.obtain().report(new ActionRequiredIncidentReport(
                    profile.getId(),
                    profile.getName(),
                    incidentTaskKey(task),
                    task.getTaskName(),
                    context.signature(),
                    context.title(),
                    cooldown.getMessage(),
                    context.expectedState(),
                    context.observedState(),
                    context.lastAction(),
                    context.retryOrFallback(),
                    "gameStopped=" + gameStopped + "; slotReleased=" + slotReleased,
                    cooldown.getRetryAt(),
                    cooldown.getEvidencePath()));
        } catch (RuntimeException exception) {
            emitErrorTask(task, "Could not persist action-required incident: " + exception.getMessage());
        }
    }

    private void recordCooldownFailureAttempt(DelayedTask task, ProfileCooldownException cooldown,
            boolean gameStopped, boolean slotReleased) {
        try {
            TaskFailureIncidentService.obtain().recordFailure(new TaskFailureReport(
                    profile.getId(),
                    profile.getName(),
                    incidentTaskKey(task),
                    task.getTaskName(),
                    defaultIncidentSignature(task, cooldown),
                    "Task remains blocked after repeated recovery attempts",
                    cooldown.getMessage(),
                    "Task reaches its verified completion state",
                    "Bounded recovery ended in a profile cooldown",
                    "The task stopped its bounded recovery and yielded the profile",
                    "Retry at " + cooldown.getRetryAt(),
                    "gameStopped=" + gameStopped + "; slotReleased=" + slotReleased,
                    cooldown.getRetryAt(),
                    TaskFailureIncidentService.DEFAULT_ESCALATION_THRESHOLD,
                    cooldown.getEvidencePath()));
        } catch (RuntimeException exception) {
            emitErrorTask(task, "Could not persist the task-failure streak: " + exception.getMessage());
        }
    }

    private String incidentTaskKey(DelayedTask task) {
        String customLabel = distinctTaskLabel(task);
        return customLabel == null || customLabel.isBlank()
                ? task.getTpTask().name()
                : task.getTpTask().name() + ":" + customLabel;
    }

    private static String defaultIncidentSignature(DelayedTask task, ProfileCooldownException cooldown) {
        String normalized = Optional.ofNullable(cooldown.getMessage()).orElse("profile cooldown")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\d+", "#")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (normalized.length() > 150) {
            normalized = normalized.substring(0, 150);
        }
        return "profile-cooldown." + task.getTpTask().name().toLowerCase(java.util.Locale.ROOT)
                + "." + normalized;
    }

    protected boolean stopBlockedGameProcess(DelayedTask task) {
        try {
            deviceBridge.forceStopApp(profile.getEmulatorNumber(), EmulatorController.GAME.getPackageName());
            return true;
        } catch (RuntimeException ex) {
            emitWarnTask(task, "Could not stop blocked game process before cooldown: " + ex.getMessage());
            return false;
        }
    }

    protected boolean releaseBlockedProfileSlot(DelayedTask task) {
        try {
            releaseActiveSlotLease();
            return true;
        } catch (RuntimeException ex) {
            emitWarnTask(task, "Could not release emulator slot for cooldown: " + ex.getMessage());
            return false;
        }
    }

    protected void releaseEmulatorSlotLease() {
        deviceBridge.releaseEmulatorSlot(profile);
    }

    final void releaseActiveSlotLease() {
        releaseEmulatorSlotLease();
        sessionOrigin = null;
    }

    static void applyProfileCooldown(DelayedTask task, TaskQueueStatusData status, LocalDateTime retryAt) {
        task.setRecurring(true);
        task.reschedule(retryAt);
        status.setDelayUntil(retryAt);
        status.setPaused(true);
    }

    private void onReconnectNeeded(ProfileInReconnectStateException ex) {
        Long mins = profile.getReconnectionTime();
        if (mins != null && mins > 0) { emitInfo("Reconnect pause: " + mins + " min"); statusModel.setReconnectAt(mins); }
        else { emitError("No reconnect time configured"); attemptReconnect(); }
    }

    private void attemptReconnect() {
        try {
            ImageSearchResultData r = deviceBridge.locatePattern(profile.getEmulatorNumber(), TemplatesEnum.GAME_HOME_RECONNECT, 90);
            if (r.isFound()) TapInteractionService.forController(deviceBridge, profile.getEmulatorNumber()).tapInside(r);
            enqueue(DelayedTaskRegistry.create(TpDailyTaskEnum.INITIALIZE, profile));
        } catch (Exception ex) { emitError("Reconnect error: " + ex.getMessage()); }
    }

    private void checkDailyMissionFollow(DelayedTask task) {
        if (!profile.getConfig(ConfigurationKeyEnum.DAILY_MISSION_AUTO_SCHEDULE_BOOL, Boolean.class) || !task.provideDailyMissionProgress()) return;
        TaskStateData s = TaskManagementService.shared().lookupTaskState(profile.getId(), TpDailyTaskEnum.DAILY_MISSIONS.getId());
        LocalDateTime next = (s != null) ? s.getNextExecutionTime() : null;
        if (s == null || next == null || next.isAfter(LocalDateTime.now())) pushDailyMissionsToNow();
    }

    private synchronized void pushDailyMissionsToNow() {
        DelayedTask ref = DelayedTaskRegistry.create(TpDailyTaskEnum.DAILY_MISSIONS, profile);
        DelayedTask existing = taskBacklog.stream().filter(ref::equals).findFirst().orElse(null);
        if (existing != null) { taskBacklog.remove(existing); existing.reschedule(LocalDateTime.now()); existing.setRecurring(true); offerToBacklog(existing); }
        else { ref.reschedule(LocalDateTime.now()); ref.setRecurring(false); offerToBacklog(ref); }
    }

    protected void handleIdleTransitions() {
        if (Thread.currentThread().isInterrupted()) return;
        if (hasProtectedBearOwnership(Instant.now())) return;
        if (yieldSlotToProtectedOwner()) return;
        if (statusModel.getLoopState().isExecutedTask() || taskBacklog.isEmpty()) return;
        IdleBehaviorEnum idleBehavior = resolveIdleBehavior();
        if (!idleBehavior.requiresIdleTimeout()) {
            statusModel.setIdleTimeExceeded(false);
            return;
        }
        int idleCap = resolvePositiveIdleLimit();
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

            suspendDevice(statusModel.getDelayUntil(), hasEnabledSiblingOnSameEmulator());
                    // Changed by pernerch | Date: 2026-07-02 | Why: force immediate activation of the
                    // selected peer queue after slot handover to eliminate idle dead time.
            statusModel.setIdleTimeExceeded(true);
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
            releaseActiveSlotLease();
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
        if (hasProtectedBearOwnership(Instant.now())) {
            emitWarn("Refused device suspension while Bear owns the active event window");
            statusModel.setIdleTimeExceeded(false);
            return;
        }
        if (!deviceReleaseAllowed()) {
            // Another profile owns the event on this emulator: free the slot for it, but never
            // close or background the emulator underneath it.
            if (freeSlot && !requiresSlotAcquisition(sessionOrigin)) releaseActiveSlotLease();
            emitInfo("Idle without suspending the emulator: another profile owns its Bear event");
            broadcastStatus("Idle till " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(until));
            return;
        }
        IdleBehaviorEnum policy = resolveIdleBehavior();
        if (policy == IdleBehaviorEnum.SEND_TO_BACKGROUND) {
            deviceBridge.sendGameToBackground(profile.getEmulatorNumber());
            emitInfo("Device sent to background until " + until);
            if (freeSlot) { releaseActiveSlotLease(); emitInfo("Slot released"); }
        } else if (policy == IdleBehaviorEnum.PC_SLEEP) {
            triggerPcSleep(until);
        } else {
            deviceBridge.closeEmulator(profile.getEmulatorNumber());
            emitInfo("Device closed until " + until);
            releaseActiveSlotLease();
        }
        broadcastStatus("Idle till " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(until));
    }

    boolean hasProtectedBearOwnership(Instant now) {
        if (profile == null || profile.getId() == null || now == null) return false;
        if (BearTrapSessionLease.active(profile.getId()).isPresent()) return true;
        if (!Boolean.TRUE.equals(profile.getEnabled())
                || !Boolean.TRUE.equals(profile.getConfig(
                        ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, Boolean.class))) {
            return false;
        }
        // A crash skips requestStop and leaves only the checkpoint, so it also proves ownership.
        return BearRecoveryFinalization.deadline(profile)
                .or(() -> BearSessionCheckpoint.load(profile)
                        .map(BearSessionCheckpoint.Checkpoint::eventEnd))
                .filter(now::isBefore)
                .isPresent();
    }

    /**
     * Whether automatic paths may close, background, or force-stop this profile's emulator. Any
     * profile sharing the emulator vetoes it while it owns an active Bear event; a non-owner may
     * still free its slot.
     */
    boolean deviceReleaseAllowed() {
        return !hasProtectedBearOwnership(Instant.now())
                && !DeviceReleaseGuard.isProtected(profile.getEmulatorNumber());
    }

    /**
     * A profile that shares an emulator with an active Bear owner must not hold the slot the owner
     * needs. It frees the slot without touching the emulator and waits to reacquire it.
     */
    boolean yieldSlotToProtectedOwner() {
        if (runningContext != null || requiresSlotAcquisition(sessionOrigin)) return false;
        if (hasProtectedBearOwnership(Instant.now())) return false;
        if (!DeviceReleaseGuard.isProtected(profile.getEmulatorNumber())) return false;
        releaseActiveSlotLease();
        emitInfo("Yielded the emulator slot to the profile that owns the active Bear event");
        return true;
    }

    void registerDeviceProtection() {
        DeviceReleaseGuard.register(profile.getEmulatorNumber(), deviceOwnership);
    }

    void unregisterDeviceProtection() {
        DeviceReleaseGuard.unregister(profile.getEmulatorNumber(), deviceOwnership);
    }

    /**
     * Explicit operator revocation (Bear participation or the profile was disabled): stop a
     * running Bear session now, release the event lease and device pin, and queue a cleanup-only
     * finalization that restores normal work without any Bear strategy input.
     *
     * @return whether Bear owned the profile and was revoked
     */
    public boolean revokeBearOwnership(String reason) {
        boolean leased = BearTrapSessionLease.active(profile.getId()).isPresent();
        boolean durable = BearRecoveryFinalization.deadline(profile).isPresent()
                || BearSessionCheckpoint.load(profile).isPresent();
        if (!leased && !durable) {
            return false;
        }
        // Lock-free handshake with the worker's lease claim (no ConfigService write may happen
        // under the queue monitor; ConfigService calls back into the queue under its own lock):
        // revocation publishes this flag before reading the running context, and the worker
        // publishes its running context before reading the flag, so at least one side sees the
        // other and cancels the session before any input.
        bearRevokedUntil = BearTrapSessionLease.active(profile.getId())
                .map(BearTrapSessionLease.Lease::eventEnd)
                .or(() -> BearSessionCheckpoint.load(profile).map(BearSessionCheckpoint.Checkpoint::eventEnd))
                .filter(end -> end.isAfter(Instant.now()))
                .orElse(Instant.now().plusSeconds(BEAR_IN_WINDOW_RETRY_CAP_SECONDS));
        ExecutionContext running = runningContext;
        boolean bearRunning = running != null && running.getTask() != null
                && running.getTask().getTpTask() == TpDailyTaskEnum.BEAR_TRAP;
        if (bearRunning) {
            // The running task carries the cleanup when it returns.
            bearCleanupOwedByRunningTask = true;
            running.cancel();
        }
        BearTrapSessionLease.releaseForQueueStop(profile.getId());
        afterBearOwnershipReleased();
        if (!BearRecoveryFinalization.arm(profile, Instant.now())) {
            emitError("Bear ownership revoked (" + reason + ") but its cleanup-only finalizer "
                    + "could not be persisted");
            return true;
        }
        if (!bearRunning) {
            queueBearCleanupNow();
        }
        emitWarn("Bear ownership revoked by the operator (" + reason + "); session stopped, device "
                + "released, cleanup-only finalization queued");
        return true;
    }

    private synchronized void queueBearCleanupNow() {
        DelayedTask ref = createTask(TpDailyTaskEnum.BEAR_TRAP);
        if (ref == null) {
            emitError("Bear cleanup-only finalization could not be queued: task not registered");
            return;
        }
        DelayedTask queued = taskBacklog.stream().filter(ref::equals).findFirst().orElse(ref);
        taskBacklog.remove(queued);
        queued.setProfile(profile);
        queued.setRecurring(true);
        queued.reschedule(LocalDateTime.now());
        offerToBacklog(queued);
    }

    boolean restoreDurableBearOwnershipOnStart() {
        Instant now = Instant.now();
        if (BearTrapSessionLease.active(profile.getId()).isPresent()
                || !hasProtectedBearOwnership(now)) {
            return false;
        }
        runNow(TpDailyTaskEnum.BEAR_TRAP, true);
        boolean queued;
        synchronized (this) {
            queued = taskBacklog.stream().anyMatch(task -> task.getTpTask() == TpDailyTaskEnum.BEAR_TRAP
                    && task.isRecurring()
                    && task.getDelay(TimeUnit.MILLISECONDS) <= 0);
        }
        if (!queued) {
            emitError("Active Bear ownership was found after restart, but Bear could not be queued; "
                    + "the device stays pinned until the event ends");
            return false;
        }
        emitInfo("Restored active Bear ownership from durable recovery; device is pinned open");
        return true;
    }

    boolean enforceSessionCap() {
        if (runningContext != null || sessionOrigin == null) return false;
        if (hasProtectedBearOwnership(Instant.now())) return false;
        if (!resolveIdleBehavior().requiresIdleTimeout()) return false;
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

    private IdleBehaviorEnum resolveIdleBehavior() {
        return IdleBehaviorEnum.fromString(
                Optional.ofNullable(ConfigService.obtain().loadGlobalSettings())
                        .map(c -> c.getOrDefault(ConfigurationKeyEnum.IDLE_BEHAVIOR_STRING.name(),
                                ConfigurationKeyEnum.IDLE_BEHAVIOR_STRING.getDefaultValue()))
                        .orElse(ConfigurationKeyEnum.IDLE_BEHAVIOR_STRING.getDefaultValue()));
    }

    private int resolvePositiveIdleLimit() {
        int configured = Optional.ofNullable(ConfigService.obtain().loadGlobalSettings())
                .map(c -> c.get(ConfigurationKeyEnum.MAX_IDLE_TIME_INT.name()))
                .map(TaskQueue::parseInteger)
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

    protected void acquireSlot() {
        broadcastStatus("Waiting for device slot");
        try {
            QueuePositionListener cb = (t, pos) -> broadcastStatus("Queue position: " + pos);
            deviceBridge.adquireEmulatorSlot(profile, cb);
            markSlotAcquired();
        } catch (InterruptedException ie) { emitError("Interrupted waiting for slot"); Thread.currentThread().interrupt(); }
    }

    protected final void markSlotAcquired() {
        sessionOrigin = LocalDateTime.now();
    }

    private void onPausedTick() {
        if (!statusModel.isUserPaused() && statusModel.getDelayUntil().isBefore(LocalDateTime.now())) {
            boolean reconnect = statusModel.needsReconnect();
            if (reconnect) statusModel.setNeedsReconnect(false);
            broadcastStatus(reconnect ? "RESUMING AFTER PAUSE" : "RESUMING");
            profileCooldownStatus = null;
            statusModel.setPaused(false);
            if (requiresSlotAcquisition(sessionOrigin)) acquireSlot();
            if (reconnect) attemptReconnect();
            return;
        }
        String cooldownStatus = profileCooldownStatus;
        broadcastStatus(cooldownStatus != null ? cooldownStatus : "PAUSED");
        if (cooldownStatus == null && LocalDateTime.now().getSecond() % 10 == 0) emitInfo("Queue paused");
        sleepPausedTick();
    }

    protected void sleepPausedTick() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    static boolean requiresSlotAcquisition(LocalDateTime sessionOrigin) {
        return sessionOrigin == null;
    }

    static boolean shouldHandleIdleTransitions(TaskQueueStatusData status) {
        return !status.isPaused();
    }

    String getProfileCooldownStatus() {
        return profileCooldownStatus;
    }

    private void triggerPcSleep(LocalDateTime wakeAt) {
        try {
            deviceBridge.closeEmulator(profile.getEmulatorNumber());
            releaseActiveSlotLease();
            LocalDateTime wake = wakeAt.minusMinutes(1);
            if (wake.isBefore(LocalDateTime.now())) wake = LocalDateTime.now().plusMinutes(1);
            String tm = DateTimeFormatter.ofPattern("HH:mm").format(wake);
            String dt = DateTimeFormatter.ofPattern("MM/dd/yyyy").format(wake);
            WorkspacePaths workspace = WorkspacePaths.current();
            java.nio.file.Path runtimeCache = workspace.cache().resolve("runtime");
            java.nio.file.Files.createDirectories(runtimeCache);
            String taskCommand = resolveAutostartCommand(workspace, runtimeCache);
            String scheduledTaskName = "Frostguard_AutoStart_"
                    + Integer.toUnsignedString(workspace.root().toString().hashCode(), 36);
            new ProcessBuilder("schtasks","/create","/TN",scheduledTaskName,"/TR",
                    taskCommand,
                    "/SC","ONCE","/ST",tm,"/SD",dt,"/RL","HIGHEST","/F")
                    .redirectErrorStream(true).start().waitFor();
            java.nio.file.Path ws = runtimeCache.resolve("fg_wake.ps1");
            java.nio.file.Files.writeString(ws,
                    "$s=New-ScheduledTaskSettingsSet -WakeToRun -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -Priority 1\n"+
                    "Set-ScheduledTask -TaskName '" + scheduledTaskName + "' -Settings $s\n");
            new ProcessBuilder("powershell.exe","-NoProfile","-ExecutionPolicy","Bypass","-File",ws.toString())
                    .redirectErrorStream(true).start().waitFor();
            java.nio.file.Path ss = runtimeCache.resolve("fg_sleep.ps1");
            java.nio.file.Files.writeString(ss,
                    "Start-Sleep -Seconds 2\nAdd-Type -AssemblyName System.Windows.Forms\n"+
                    "[System.Windows.Forms.Application]::SetSuspendState('Suspend',$false,$false)\n");
            new ProcessBuilder("powershell.exe","-NoProfile","-ExecutionPolicy","Bypass","-File",ss.toString()).start();
            System.exit(0);
        } catch (Exception ex) { emitError("PC sleep scheduling error: " + ex.getMessage()); }
    }

    private String resolveDesktopJarForAutostart() throws java.io.IOException {
        return resolveDesktopJarForAutostart(java.nio.file.Path.of(System.getProperty("user.dir")));
    }

    private String resolveAutostartCommand(WorkspacePaths workspace, java.nio.file.Path runtimeCache)
            throws java.io.IOException {
        String nativeLauncher = packagedApplicationLauncher();
        if (!nativeLauncher.isBlank()) {
            java.nio.file.Path launcher = java.nio.file.Path.of(nativeLauncher).toAbsolutePath().normalize();
            if (java.nio.file.Files.isRegularFile(launcher)) {
                java.nio.file.Path wrapper = runtimeCache.resolve("frostguard-autostart.cmd");
                java.nio.file.Files.writeString(wrapper,
                        nativeAutostartLauncherContent(launcher, workspace));
                return "\"" + wrapper + "\"";
            }
        }
        String jar = resolveDesktopJarForAutostart();
        return "javaw.exe -D" + WorkspacePaths.WORKSPACE_PROPERTY + "=\"" + workspace.root()
                + "\" -D" + WorkspacePaths.CHANNEL_PROPERTY + "=" + workspace.channel().directoryName()
                + " -jar \"" + jar + "\" --autostart";
    }

    static String packagedApplicationLauncher() {
        String configured = System.getProperty(APP_LAUNCHER_PROPERTY, "").trim();
        return configured.isBlank() ? System.getProperty("jpackage.app-path", "").trim() : configured;
    }

    static String nativeAutostartLauncherContent(java.nio.file.Path launcher, WorkspacePaths workspace) {
        return "@echo off\r\n"
                + "setlocal DisableDelayedExpansion\r\n"
                + "set \"FROSTGUARD_WORKSPACE=" + escapeBatchValue(workspace.root().toString()) + "\"\r\n"
                + "set \"FROSTGUARD_CHANNEL=" + workspace.channel().directoryName() + "\"\r\n"
                + "start \"\" \"" + escapeBatchValue(launcher.toString()) + "\" --autostart\r\n";
    }

    private static String escapeBatchValue(String value) {
        return value.replace("^", "^^").replace("%", "%%");
    }

    static String resolveDesktopJarForAutostart(java.nio.file.Path workingDirectory)
            throws java.io.IOException {
        for (java.nio.file.Path directory : List.of(
                workingDirectory,
                workingDirectory.resolve("modules").resolve("desktop").resolve("target"))) {
            if (!java.nio.file.Files.isDirectory(directory)) {
                continue;
            }
            try (var files = java.nio.file.Files.list(directory)) {
                Optional<java.nio.file.Path> jar = files
                        .filter(java.nio.file.Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().startsWith("frostguard-desktop-"))
                        .filter(path -> path.getFileName().toString().endsWith(".jar"))
                        .max(Comparator.comparing(path -> path.getFileName().toString()));
                if (jar.isPresent()) {
                    return jar.get().toAbsolutePath().toString();
                }
            }
        }
        throw new java.io.IOException("Could not locate the Frostguard desktop JAR for autostart");
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
