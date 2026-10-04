package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.FormationSlots;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.MarchActivityType;
import dev.frostguard.api.domain.MarchMovementPhase;
import dev.frostguard.api.domain.MarchSlotAvailability;
import dev.frostguard.api.domain.MarchSlotState;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;
import dev.frostguard.engine.error.ADBConnectionException;
import dev.frostguard.engine.error.BearSessionExecutionException;
import dev.frostguard.engine.error.StopExecutionException;
import dev.frostguard.engine.helper.BearTrapHelper;
import dev.frostguard.engine.helper.DeploymentPostTapRead;
import dev.frostguard.engine.helper.DeploymentHelper;
import dev.frostguard.engine.helper.DeploymentPreflightRead;
import dev.frostguard.engine.helper.FormationSelectionVerifier;
import dev.frostguard.engine.helper.FormationOccupancyVerifier;
import dev.frostguard.engine.helper.MarchHelper;
import dev.frostguard.engine.helper.TemplateSearchHelper;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.BearSessionCheckpoint;
import dev.frostguard.engine.schedule.BearTerminalCleanup;
import dev.frostguard.engine.schedule.BearTrapParticipationSchedule;
import dev.frostguard.engine.schedule.BearFlagConfiguration;
import dev.frostguard.engine.schedule.BearObserveOnlyFallback;
import dev.frostguard.engine.schedule.BearTrapSessionLease;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.RallyFlagCoordinates;
import dev.frostguard.engine.nav.SidebarSection;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.vision.convert.ImageConverter;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.*;
import static dev.frostguard.api.configs.TemplatesEnum.*;

public class BearTrapRoutine extends DelayedTask implements BearTerminalCleanup {

private List<Integer> joinFlags = new ArrayList<>();

private static final int TRAP_DURATION_MINUTES_VALUE = 30;

private static final int TERMINAL_CLEANUP_TRANSITION_LIMIT = 4;
private static final Duration TERMINAL_CLEANUP_SETTLE_DEADLINE = Duration.ofSeconds(1);

private static final int TRAP_ACTIVATION_OFFSET_MINUTES_VALUE = 30;

private static final int RALLY_DURATION_BASE_MINUTES_VALUE = 5;

private static final int MAX_GATHER_RECALL_ATTEMPTS_LIMIT = 120;

private static final int TEMPLATE_SEARCH_RETRIES_VALUE = 3;

private static final int TEMPLATE_SEARCH_RETRIES_EXTENDED_VALUE = 5;

private static final PointData ALLIANCE_BUTTON_TL_VALUE = new PointData(493, 1187);

private static final PointData ALLIANCE_BUTTON_BR_VALUE = new PointData(561, 1240);

private static final PointData SPECIAL_BUILDINGS_BUTTON_TL_VALUE = new PointData(460, 110);

private static final PointData SPECIAL_BUILDINGS_BUTTON_BR_VALUE = new PointData(560, 130);

private static final PointData BEAR_TRAP_1_GO_BUTTON_TL_VALUE = new PointData(570, 350);

private static final PointData BEAR_TRAP_1_GO_BUTTON_BR_VALUE = new PointData(620, 370);

private static final PointData BEAR_TRAP_2_GO_BUTTON_TL_VALUE = new PointData(570, 530);

private static final PointData BEAR_TRAP_2_GO_BUTTON_BR_VALUE = new PointData(620, 550);

private static final PointData BEAR_CENTER_POINT_VALUE = new PointData(370, 507);

private static final PointData AUTOJOIN_BUTTON_TL_VALUE = new PointData(260, 1200);

private static final PointData AUTOJOIN_BUTTON_BR_VALUE = new PointData(450, 1240);

private static final PointData AUTOJOIN_STOP_BUTTON_TL_VALUE = new PointData(120, 1070);

private static final PointData AUTOJOIN_STOP_BUTTON_BR_VALUE = new PointData(240, 1110);

private static final PointData RECALL_CONFIRM_BUTTON_TL_VALUE = new PointData(446, 780);

private static final PointData RECALL_CONFIRM_BUTTON_BR_VALUE = new PointData(578, 800);

private static final int DEFAULT_TRAP_NUMBER_VALUE = 1;

private static final int DEFAULT_PREPARATION_TIME_MINUTES_MS = 10;

private static final int DEFAULT_OWN_RALLY_FLAG_VALUE = 1;


private static final long FRESH_TRANSITION_TIMEOUT_MS = 1500;

private static final long NAVIGATION_TRANSITION_TIMEOUT_MS = 4_000;

private static final long POST_DEPLOY_CONFIRMATION_TIMEOUT_MS = 5_000;

private static final long POST_DEPLOY_CONFIRMATION_POLL_MS = 100;

private static final int TERRITORY_TRANSITION_ATTEMPTS = 2;

private static final PointData RALLY_LIST_SCROLL_FROM = new PointData(540, 1010);

private static final PointData RALLY_LIST_SCROLL_TO = new PointData(540, 430);

private static final boolean DEFAULT_CALL_OWN_RALLY_VALUE = false;

private static final boolean DEFAULT_JOIN_RALLY_VALUE = false;

private static final boolean DEFAULT_USE_PETS_VALUE = false;

private static final boolean DEFAULT_RECALL_TROOPS_VALUE = false;

private boolean callOwnRally;

private boolean joinRally;

private boolean usePets;

private boolean recallTroops;

// Changed by pernerch | Date: 2026-07-02 | Why: detect shared-emulator profiles to avoid rally contention across accounts.
private boolean sharedEmulator;

private String flagRefusal;

private int trapNumber;

private int ownRallyFlag;

private int trapPreparationTime;

private LocalDateTime referenceTrapTime;

private BearSessionCoordinator.ExitReason activeSessionExit;

public BearTrapRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

@Override
    protected boolean acceptsInjections() {
        return false;
    }

@Override
    protected void execute() {
        activeSessionExit = null;
        hydrateConfiguration();

        Optional<BearEventWindow> window = BearEventWindow.resolve(
                BearTrapSessionLease.active(profile.getId()),
                () -> confirmExecutionWindow()
                        ? Optional.of(computeTrapTiming().window())
                        : Optional.empty());
        if (window.isEmpty()) {
            deferToNextWindow();
            return;
        }
        if (window.get().leaseOwned()) {
            logInfo(routineLogBearTrapLine("Event window taken from the active Bear lease; "
                    + "schedule edits cannot move it. end=" + window.get().end()));
        }
        if (flagRefusal != null && !observeOnly()) {
            logError(routineLogBearTrapLine("Bear configuration refused: " + flagRefusal));
            throw protectedFailure(
                    BearSessionExecutionException.FailureKind.FATAL_CONFIGURATION,
                    BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION,
                    "bear-formation-configuration",
                    new IllegalStateException(flagRefusal));
        }

        TrapTimingShape timing = null;
        LiveBearSessionDriver sessionDriver = null;
        BearSessionExecutionException sessionFailure = null;
        try {
            timing = TrapTimingShape.from(window.get());
            logTrapTimingFlow(timing);

            LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
            Instant eventEndInstant = timing.endTime.atZone(ZoneId.of("UTC")).toInstant();
            if (observeOnly()) {
                sessionDriver = new LiveBearSessionDriver(eventEndInstant, openCaptureRecorder(eventEndInstant));
                activeSessionExit = sessionDriver.observeOnlyUntil(eventEndInstant);
            } else {
                sessionDriver = new LiveBearSessionDriver(eventEndInstant,
                        Boolean.getBoolean("frostguard.bear.record") ? openCaptureRecorder(eventEndInstant) : null);

                if (now.isBefore(timing.activationTime)) {
                    sessionDriver.prepareUntil(
                            timing.activationTime.atZone(ZoneId.of("UTC")).toInstant());
                } else {
                    logInfo(routineLogBearTrapLine("Trap is already ACTIVE (preparation time passed)"));
                    sessionDriver.beginActivePhase();
                }

                now = LocalDateTime.now(ZoneId.of("UTC"));

                if (now.isBefore(timing.endTime)) {
                    activeSessionExit = performTrapActivePhase(timing.endTime, sessionDriver);
                } else {
                    logInfo(routineLogBearTrapLine("Trap already ended for this window"));
                    activeSessionExit = BearSessionCoordinator.ExitReason.EVENT_ENDED;
                }
            }
        } catch (BearSessionExecutionException e) {
            activeSessionExit = BearSessionCoordinator.ExitReason.UNRECOVERABLE_FAILURE;
            sessionFailure = e;
            logError(routineLogBearTrapLine("Bear session entered protected recovery: "
                    + e.failureKind() + "/" + e.recoveryDirective() + ": " + e.getMessage()), e);
        } catch (StopExecutionException e) {
            if (e.isCancellation() || Thread.currentThread().isInterrupted()) {
                activeSessionExit = BearSessionCoordinator.ExitReason.CANCELLED;
                logInfo(routineLogBearTrapLine("Bear session cancelled by the operator"));
            } else {
                activeSessionExit = BearSessionCoordinator.ExitReason.UNRECOVERABLE_FAILURE;
                sessionFailure = protectedFailure(
                        BearSessionExecutionException.FailureKind.VISUAL_UNKNOWN,
                        BearSessionExecutionException.RecoveryDirective.DEGRADED_WAIT,
                        "preemption-check",
                        e);
                logError(routineLogBearTrapLine("Bear session stopped: " + e.getMessage()), e);
            }
        } catch (ADBConnectionException e) {
            activeSessionExit = BearSessionCoordinator.ExitReason.UNRECOVERABLE_FAILURE;
            sessionFailure = protectedFailure(
                    BearSessionExecutionException.FailureKind.DEVICE_OFFLINE,
                    BearSessionExecutionException.RecoveryDirective.REBIND_DEVICE,
                    "adb-frame-or-input",
                    e);
            logError(routineLogBearTrapLine("Bear session lost its ADB device: " + e.getMessage()), e);
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted() || e.getCause() instanceof InterruptedException) {
                activeSessionExit = BearSessionCoordinator.ExitReason.CANCELLED;
                logInfo(routineLogBearTrapLine("Bear session interrupted by the operator"));
            } else {
                activeSessionExit = BearSessionCoordinator.ExitReason.UNRECOVERABLE_FAILURE;
                sessionFailure = protectedFailure(
                        BearSessionExecutionException.FailureKind.VISUAL_UNKNOWN,
                        BearSessionExecutionException.RecoveryDirective.DEGRADED_WAIT,
                        "active-session",
                        e);
                logError(routineLogBearTrapLine("Issue while Bear Trap execution: " + e.getMessage()), e);
            }
        } finally {
            boolean resumeNormalTasks = BearSessionCoordinator.shouldResumeNormalTasks(activeSessionExit);
            try {
                if (sessionDriver != null) {
                    sessionDriver.cleanup(activeSessionExit, resumeNormalTasks);
                } else {
                    cleanupFlow(resumeNormalTasks);
                }
            } catch (RuntimeException cleanupFailure) {
                BearSessionExecutionException typedCleanup = cleanupFailure
                        instanceof BearSessionExecutionException bearFailure
                        ? bearFailure
                        : protectedFailure(
                                BearSessionExecutionException.FailureKind.CAPTURE_TRANSIENT,
                                BearSessionExecutionException.RecoveryDirective.DEGRADED_WAIT,
                                "terminal-cleanup",
                                cleanupFailure);
                sessionFailure = preferTerminalFailure(sessionFailure, typedCleanup);
                activeSessionExit = BearSessionCoordinator.ExitReason.UNRECOVERABLE_FAILURE;
                resumeNormalTasks = false;
            }
            if (resumeNormalTasks) {
                deferToNextWindow();
            } else if (timing != null
                    && LocalDateTime.now(ZoneId.of("UTC")).isBefore(timing.endTime)
                    && BearSessionCoordinator.shouldRetryDuringActiveWindow(activeSessionExit)) {
                logWarning(routineLogBearTrapLine(
                        "Bear window remains active after a recoverable execution failure; "
                                + "the scheduler owns the protected retry deadline"));
            }
        }
        if (sessionFailure != null) {
            throw sessionFailure;
        }
    }

private BearSessionExecutionException protectedFailure(
        BearSessionExecutionException.FailureKind kind,
        BearSessionExecutionException.RecoveryDirective directive,
        String operation,
        Throwable cause) {
        return new BearSessionExecutionException(
                kind,
                directive,
                EMULATOR_NUMBER,
                operation,
                cause == null ? "Bear session recovery requested" : cause.getMessage(),
                cause);
    }

@Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

private boolean observeOnly() {
        return Boolean.TRUE.equals(profile.getConfig(BEAR_TRAP_OBSERVE_ONLY_BOOL, Boolean.class))
                || BearObserveOnlyFallback.active(profile, Instant.now());
    }

    static BearSessionExecutionException preferTerminalFailure(
            BearSessionExecutionException primary, BearSessionExecutionException cleanup) {
        if (primary != null && primary != cleanup) cleanup.addSuppressed(primary);
        return cleanup;
    }

    @Override
    public boolean recoverTerminalUi(Instant eventEnd, Runnable authorizeInput) {
        checkPreemption();
        if (eventEnd == null || Instant.now().isBefore(eventEnd) || observeOnly()) return false;
        trapNumber = resolveConfigInt(BEAR_TRAP_NUMBER_INT, DEFAULT_TRAP_NUMBER_VALUE);
        var driver = new LiveBearSessionDriver(eventEnd,
                Boolean.getBoolean("frostguard.bear.record") ? openCaptureRecorder(eventEnd) : null);
        driver.cleanupAuthorization = java.util.Objects.requireNonNull(authorizeInput);
        return driver.recoverTerminalUiOnly();
    }

/** The device transport for a session; replaced in tests by scripted frames. */
BearRealtimeFrameSource createRealtimeFrameSource(BearCaptureRecorder capture) {
        return new BearRealtimeFrameSource(
                emuManager.getAdbPath(),
                emuManager.getDeviceSerial(EMULATOR_NUMBER),
                () -> Thread.currentThread().isInterrupted(),
                capture == null ? () -> null : capture::nextSegment,
                () -> emuManager.captureScreen(EMULATOR_NUMBER));
    }

/** Template matching for the live classifier; replaced in tests by scripted screens. */
BearFrameClassifier.TemplateMatcher templateMatcher() {
        return new BearTemplateMatcher(emuManager, EMULATOR_NUMBER);
    }

/** Session tests replace transport, not authorization or transition semantics. */
void executeObservedInput(Runnable authorize, Runnable input) {
        emuManager.withSingleAttemptInput(EMULATOR_NUMBER, authorize, input);
    }

/** Terminal UI verification uses input, so an observe-only session never performs it. */
    boolean verifiesTerminalUi(boolean resumeNormalTasks) {
        return resumeNormalTasks && !observeOnly();
    }

private BearCaptureRecorder openCaptureRecorder(Instant eventEnd) {
        try {
            BearCaptureRecorder recorder = BearCaptureRecorder.open(WorkspacePaths.current().logs(), eventEnd);
            logInfo(routineLogBearTrapLine("Bear evidence recording: " + recorder.directory()));
            return recorder;
        } catch (IOException failure) {
            if (Boolean.getBoolean("frostguard.bear.record")) {
                throw protectedFailure(BearSessionExecutionException.FailureKind.PERSISTENCE,
                        BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION,
                        "required-bear-recording-could-not-open", failure);
            }
            logWarning(routineLogBearTrapLine("Bear capture could not be opened; observing without recording: "
                    + failure.getMessage()));
            return null;
        }
    }

private void requireInputAllowed(String input) {
        if (observeOnly()) {
            throw new IllegalStateException("Bear observe-only session refused device input: " + input);
        }
    }

@Override
    public boolean tapInside(ImageSearchResultData result) {
        requireInputAllowed("tap");
        return super.tapInside(result);
    }

@Override
    public void tapInside(AreaData area) {
        requireInputAllowed("tap");
        super.tapInside(area);
    }

@Override
    public void tapInside(PointData corner1, PointData corner2) {
        requireInputAllowed("tap");
        super.tapInside(corner1, corner2);
    }

@Override
    public void swipe(PointData start, PointData end) {
        requireInputAllowed("swipe");
        super.swipe(start, end);
    }

@Override
    public boolean tapInside(ImageSearchResultData result, int count, int delayMs) {
        requireInputAllowed("tap");
        return super.tapInside(result, count, delayMs);
    }

@Override
    public void tapInside(AreaData area, int count, int delayMs) {
        requireInputAllowed("tap");
        super.tapInside(area, count, delayMs);
    }

@Override
    public void tapInside(PointData corner1, PointData corner2, int count, int delayMs) {
        requireInputAllowed("tap");
        super.tapInside(corner1, corner2, count, delayMs);
    }

@Override
    public void tapNear(PointData point) {
        requireInputAllowed("tap");
        super.tapNear(point);
    }

@Override
    public void tapNear(PointData point, int radius) {
        requireInputAllowed("tap");
        super.tapNear(point, radius);
    }

@Override
    public void tapNear(PointData point, int radius, int count, int delayMs) {
        requireInputAllowed("tap");
        super.tapNear(point, radius, count, delayMs);
    }

@Override
    public void swipe(PointData start, PointData end, int durationMs) {
        requireInputAllowed("swipe");
        super.swipe(start, end, durationMs);
    }

@Override
    public void pressBack() {
        requireInputAllowed("back");
        super.pressBack();
    }

@Override
    protected void enterStartScreen(boolean profileSwitchedOnEmulator) {
        // Every Bear screen transition goes through BearUiStateMachine. The shared blind
        // navigation and its Initialize hand-off would bypass protected recovery.
        if (!gameProcessRunning()) {
            throw protectedFailure(
                    BearSessionExecutionException.FailureKind.APP_NOT_FOREGROUND,
                    BearSessionExecutionException.RecoveryDirective.RESTART_APP,
                    "game-process-missing",
                    null);
        }
    }

@Override
    protected void leaveScreen() {
    }

@Override
    public boolean consumesStamina() {
        return false;
    }

@Override
    public boolean provideDailyMissionProgress() {
        return false;
    }

private static class TrapTimingShape {
        final LocalDateTime windowStart;
        final LocalDateTime activationTime;
        final LocalDateTime endTime;

        static TrapTimingShape from(BearEventWindow window) {
            LocalDateTime activation = LocalDateTime.ofInstant(window.activation(), ZoneId.of("UTC"));
            return new TrapTimingShape(activation, activation,
                    LocalDateTime.ofInstant(window.end(), ZoneId.of("UTC")));
        }

        BearEventWindow window() {
            return new BearEventWindow(
                    activationTime.atZone(ZoneId.of("UTC")).toInstant(),
                    endTime.atZone(ZoneId.of("UTC")).toInstant(),
                    false);
        }

        TrapTimingShape(LocalDateTime windowStart, LocalDateTime activationTime, LocalDateTime endTime) {
            this.windowStart = windowStart;
            this.activationTime = activationTime;
            this.endTime = endTime;
        }
    }

private void refreshNextWindowDateTime() {
        BearTrapHelper.WindowResult result = resolveWindowState();

        LocalDateTime nextWindowStart = LocalDateTime.ofInstant(
                result.getNextWindowStart(),
                ZoneId.of("UTC"));

        LocalDateTime nextTrapActivation = nextWindowStart.plusMinutes(trapPreparationTime);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
        String formattedDateTime = nextTrapActivation.format(formatter);

        logInfo(routineLogBearTrapLine("Updating next trap activation time to: " + formattedDateTime + " UTC"));

        ConfigService.obtain().writeAccountSetting(
                profile,
                selectedTrapScheduleKey(),
                formattedDateTime);
    }

private void requeueDisabledTasksFlow() {
        logInfo(routineLogBearTrapLine("Re-queueing tasks after Bear Trap event..."));

        TaskQueue queue = dev.frostguard.engine.service.ScheduleService.obtain().getCoordinator().getQueue(profile.getId());

        if (queue == null) {
            logError(routineLogBearTrapLine("Could not access task queue for profile " + profile.getName()));
            return;
        }

        requeueGatherTaskFlow(queue);
        requeueAutojoinTaskFlow(queue);

    }

private void requeueAutojoinTaskFlow(TaskQueue queue) {
        logInfo(routineLogBearTrapLine("Inspecting autojoin task..."));

        Boolean autojoinEnabled = profile.getConfig(
                ConfigurationKeyEnum.ALLIANCE_AUTOJOIN_BOOL,
                Boolean.class);

        if (Boolean.TRUE.equals(autojoinEnabled)) {
            queue.runNow(TpDailyTaskEnum.ALLIANCE_AUTOJOIN, true);
            logInfo(routineLogBearTrapLine("Re-queued Alliance Autojoin task"));
        }
    }

private String routineLogBearTrapLine(String note) {
        return "BearTrapRoutine | " + note;
    }

private BearSessionCoordinator.ExitReason performTrapActivePhase(
        LocalDateTime trapEndTime,
        LiveBearSessionDriver driver) {
        logInfo(routineLogBearTrapLine("=== TRAP IS NOW ACTIVE - Starting strategy execution ==="));
        BearSessionCoordinator coordinator = new BearSessionCoordinator(
                driver,
                trapEndTime.atZone(ZoneId.of("UTC")).toInstant(),
                callOwnRally,
                ownRallyFlag,
                joinRally && !sharedEmulator,
                joinFlags);
        BearSessionCoordinator.ExitReason exit = coordinator.run();
        logInfo(routineLogBearTrapLine("=== TRAP SESSION FINISHED: " + exit + " ==="));
        return exit;
    }

private boolean confirmExecutionWindow() {
        if (!hasInsideWindow()) {
            logWarning(routineLogBearTrapLine(
                    "Execution was requested outside the configured Bear window; "
                            + "manual Run Now does not invent or extend an event lease."));
            return false;
        }

        logInfo(routineLogBearTrapLine("Confirmed: We are INSIDE a valid execution window"));
        return true;
    }

private LocalDateTime resolveConfigDateTime(ConfigurationKeyEnum key) {
        LocalDateTime value = profile.getConfig(key, LocalDateTime.class);
        if (value == null) {
            logWarning(routineLogBearTrapLine("Reference trap time not configured, using default: now + 1 hour"));
            return LocalDateTime.now(ZoneId.of("UTC")).plusHours(1);
        }
        return value;
    }

private void requeueGatherTaskFlow(TaskQueue queue) {
        logInfo(routineLogBearTrapLine("Inspecting Gather Resources task..."));

        Boolean gatherEnabled = profile.getConfig(
                ConfigurationKeyEnum.GATHER_TASK_BOOL,
                Boolean.class);

        if (Boolean.TRUE.equals(gatherEnabled)) {
            queue.runNow(TpDailyTaskEnum.GATHER_RESOURCES, true);
            logInfo(routineLogBearTrapLine("Re-queued Gather Resources task"));
        }
    }

private boolean resolveConfigBoolean(ConfigurationKeyEnum key, boolean defaultValue) {
        Boolean value = profile.getConfig(key, Boolean.class);
        return (value != null) ? value : defaultValue;
    }

private void hydrateConfiguration() {
        this.trapNumber = resolveConfigInt(BEAR_TRAP_NUMBER_INT, DEFAULT_TRAP_NUMBER_VALUE);
        this.referenceTrapTime = resolveConfigDateTime(selectedTrapScheduleKey());
        this.trapPreparationTime = resolveConfigInt(BEAR_TRAP_PREPARATION_TIME_INT, DEFAULT_PREPARATION_TIME_MINUTES_MS);
        this.callOwnRally = resolveConfigBoolean(BEAR_TRAP_CALL_RALLY_BOOL, DEFAULT_CALL_OWN_RALLY_VALUE);
        this.joinRally = resolveConfigBoolean(BEAR_TRAP_JOIN_RALLY_BOOL, DEFAULT_JOIN_RALLY_VALUE);
        this.usePets = resolveConfigBoolean(BEAR_TRAP_ACTIVE_PETS_BOOL, DEFAULT_USE_PETS_VALUE);
        this.recallTroops = resolveConfigBoolean(BEAR_TRAP_RECALL_TROOPS_BOOL, DEFAULT_RECALL_TROOPS_VALUE);
        this.ownRallyFlag = resolveConfigInt(BEAR_TRAP_RALLY_FLAG_INT, DEFAULT_OWN_RALLY_FLAG_VALUE);


        BearFlagConfiguration.Validation flags = BearFlagConfiguration.validate(
                callOwnRally, ownRallyFlag, joinRally, decodeJoinFlags());
        this.joinFlags = new ArrayList<>(flags.joinFlags());
        this.flagRefusal = flags.refusal().orElse(null);
        this.sharedEmulator = isSharedEmulatorProfile();
        if (joinRally && sharedEmulator) {
            logWarning(routineLogBearTrapLine("Rally joining is disabled: another enabled profile "
                    + "shares this emulator"));
        }


        logDebug(routineLogBearTrapLine(String.format(
                "Configuration loaded - Trap: %d, PrepTime: %dmin, OwnRally: %s (flag:%d), JoinRally: %s (flags:%s), Pets: %s, Recall: %s, SharedEmulator: %s",
                trapNumber, trapPreparationTime, callOwnRally, ownRallyFlag, joinRally, joinFlags, usePets,
                recallTroops, sharedEmulator)));
    }

private ConfigurationKeyEnum selectedTrapScheduleKey() {
        return BearTrapParticipationSchedule.scheduleKey(trapNumber);
    }

private BearTrapHelper.WindowResult resolveWindowState() {
        Instant referenceUTC = referenceTrapTime.atZone(ZoneId.of("UTC")).toInstant();
        return BearTrapHelper.calculateWindow(referenceUTC, trapPreparationTime);
    }

private void logTrapTimingFlow(TrapTimingShape timing) {
        logInfo(routineLogBearTrapLine("Preparation window: " + timing.windowStart.format(DATETIME_FORMATTER) + " to " +
                timing.activationTime.format(DATETIME_FORMATTER)));
        logInfo(routineLogBearTrapLine("Trap will auto-activate at: " + timing.activationTime.format(DATETIME_FORMATTER)));
        logInfo(routineLogBearTrapLine("Trap will end at: " + timing.endTime.format(DATETIME_FORMATTER)));
    }

private void deferToNextWindow() {
        BearTrapHelper.WindowResult result = resolveWindowState();

        LocalDateTime nextWindowStart = LocalDateTime.ofInstant(
                result.getNextWindowStart(),
                ZoneId.systemDefault());

        LocalDateTime nextWindowStartUtc = LocalDateTime.ofInstant(
                result.getNextWindowStart(),
                ZoneId.of("UTC"));

        logInfo(routineLogBearTrapLine("Planning next run Bear Trap for (UTC): " + nextWindowStartUtc.format(DATETIME_FORMATTER)));
        logInfo(routineLogBearTrapLine("Planning next run Bear Trap for (Local): " + nextWindowStart.format(DATETIME_FORMATTER)));

        reschedule(nextWindowStart);
        refreshNextWindowDateTime();
    }

private TrapTimingShape computeTrapTiming() {
        BearTrapHelper.WindowResult window = resolveWindowState();

        LocalDateTime windowStart = LocalDateTime.ofInstant(
                window.getCurrentWindowStart(),
                ZoneId.of("UTC"));
        LocalDateTime windowEnd = LocalDateTime.ofInstant(
                window.getCurrentWindowEnd(),
                ZoneId.of("UTC"));

        LocalDateTime activationTime = windowEnd.minusMinutes(TRAP_ACTIVATION_OFFSET_MINUTES_VALUE);
        LocalDateTime endTime = activationTime.plusMinutes(TRAP_DURATION_MINUTES_VALUE);

        return new TrapTimingShape(windowStart, activationTime, endTime);
    }

private int resolveConfigInt(ConfigurationKeyEnum key, int defaultValue) {
        Integer value = profile.getConfig(key, Integer.class);
        return (value != null) ? value : defaultValue;
    }

private boolean isSharedEmulatorProfile() {
        if (profile == null || profile.getEmulatorNumber() == null || profile.getEmulatorNumber().isBlank()) {
            return false;
        }
        return ProfileService.obtain().fetchAllAccounts().stream()
                .filter(other -> other != null && other.getId() != null && !other.getId().equals(profile.getId()))
                .filter(other -> profile.getEmulatorNumber().equals(other.getEmulatorNumber()))
                .anyMatch(other -> Boolean.TRUE.equals(other.getEnabled()));
    }

private boolean hasInsideWindow() {
        Instant referenceUTC = referenceTrapTime.atZone(ZoneId.of("UTC")).toInstant();
        BearTrapHelper.WindowResult result = BearTrapHelper.calculateWindow(referenceUTC, trapPreparationTime);
        return result.getState() == BearTrapHelper.WindowState.INSIDE;
    }

private List<Integer> decodeJoinFlags() {
        String flagConfig = profile.getConfig(BEAR_TRAP_JOIN_FLAG_INT, String.class);
        return decodeJoinFlags(flagConfig);
    }

static List<Integer> decodeJoinFlags(String flagConfig) {
        LinkedHashSet<Integer> flags = new LinkedHashSet<>();

        if (flagConfig != null && !flagConfig.trim().isEmpty()) {
            String[] parts = flagConfig.split(",");
            for (String part : parts) {
                try {
                    int flag = Integer.parseInt(part.trim());
                    if (FormationSlots.supports(flag)) {
                        flags.add(flag);
                    }
                } catch (NumberFormatException e) {
                    // Ignore corrupt persisted values; the effective configuration is logged by the caller.
                }
            }
        }


        return new ArrayList<>(flags);
    }

final class LiveBearSessionDriver implements BearSessionCoordinator.Driver {
        private Runnable cleanupAuthorization = () -> { };

        private final Instant eventEnd;
        private List<MarchSlotState> lastMarches = List.of();
        private BearSessionCoordinator.State lastState;
        private Instant ownRallyBusyUntil;
        private final BearFrameClassifier classifier = new BearFrameClassifier(templateMatcher(), trapNumber);
        private Instant nextMarchRefreshAt = Instant.MIN;
        private boolean cachedSpecialRallyPreparing;
        private final BearFrameStream<RawImageData> frames;
        private final BearRealtimeFrameSource realtimeFrames;
        private final BearUiStateMachine<RawImageData> ui;
        private final TemplateSearchHelper sessionSearch;
        private final BearRallyFrameScan rallyFrameScan;
        private final MarchHelper sessionMarchHelper;
        private final DeploymentHelper sessionDeploymentHelper;
        private BearFrameStream.Snapshot<RawImageData> lastObservedFrame;
        private final BearRecoveryStrikes recoveryStrikes = new BearRecoveryStrikes(3);
        private boolean alliedRallyIndicatorAbsent;
        private final BearRallyListTraversal rallyTraversal = new BearRallyListTraversal();
        private boolean rallyListBottomProven;
        private String restoredJoinSubstate = "NONE";
        private BearRallyListTraversal.Row restoredJoinRow;
        private boolean preserveRestoredListFrontier;
        private final BearFormationAvailabilityGate formationGate =
                new BearFormationAvailabilityGate();
        private final BearCaptureRecorder capture;
        private int failedRecordingSegment;
        private final Map<String, Long> classificationTemplateNanos = new HashMap<>();
        private long classificationNanos;
        private Instant classificationStartedAt = Instant.EPOCH;

        LiveBearSessionDriver(Instant eventEnd, BearCaptureRecorder capture) {
            this.eventEnd = eventEnd;
            this.capture = capture;
            BearSessionCheckpoint.Checkpoint checkpoint = BearSessionCheckpoint.load(profile)
                    .filter(saved -> saved.eventEnd().equals(eventEnd))
                    .orElse(null);
            this.ownRallyBusyUntil = checkpoint == null
                    || Instant.EPOCH.equals(checkpoint.ownRallyReturnDeadline())
                    ? null : checkpoint.ownRallyReturnDeadline();
            if (checkpoint != null) {
                restoredJoinSubstate = checkpoint.joinSubstate();
                if (checkpoint.listRowFingerprint() != 0L
                        && !"NONE".equals(checkpoint.listLeader())) {
                    restoredJoinRow = new BearRallyListTraversal.Row(
                            new BearRallyScanner.RallyRow(
                                    null,
                                    checkpoint.listRowY(),
                                    checkpoint.listRowFingerprint(),
                                    checkpoint.listLeader()));
                    if (BearJoinCheckpointSemantics.carriesSpatialFrontier(
                            restoredJoinSubstate)) {
                        rallyTraversal.restoreCompleted(restoredJoinRow);
                        preserveRestoredListFrontier = true;
                    }
                }
            }
            this.realtimeFrames = createRealtimeFrameSource(capture);
            this.frames = BearFrameStream.fromTimestampedSource(
                    realtimeFrames::next,
                    this::classifyBearScreen,
                    () -> Thread.currentThread().isInterrupted(),
                    this::recoverCapture,
                    3,
                    Clock.systemUTC());
            this.frames.observeWith(this::recordObservedFrame);
            this.frames.measureWith(System::nanoTime,
                    timing -> recordTransitionDiagnostic(timing.diagnostic()));
            this.ui = new BearUiStateMachine<>(
                    frames, Duration.ofSeconds(4), this::recordTransitionDiagnostic,
                    this::dispatchObservedInput);
            this.sessionSearch = new TemplateSearchHelper(
                    emuManager,
                    EMULATOR_NUMBER,
                    profile,
                    () -> frames.nextUnclassified().frame());
            this.sessionSearch.setPreemptionCheck(BearTrapRoutine.this::checkPreemption);
            this.rallyFrameScan = new BearRallyFrameScan(raw -> new BearRallyScanner(sessionSearch, raw).scanRows());
            this.sessionMarchHelper = new MarchHelper(
                    emuManager,
                    EMULATOR_NUMBER,
                    stringHelper,
                    profile,
                    () -> frames.nextUnclassified().frame());
            this.sessionDeploymentHelper = new DeploymentHelper(
                    emuManager,
                    EMULATOR_NUMBER,
                    sessionSearch,
                    integerHelper,
                    durationHelper,
                    profile,
                    () -> frames.nextUnclassified().frame());
        }

        private void recordTransitionDiagnostic(String diagnostic) {
            logDebug(routineLogBearTrapLine(diagnostic));
            if (capture != null) capture.transition(frames.latestSequence(), diagnostic);
            if (!diagnostic.startsWith("transition")
                    && !diagnostic.startsWith("phase")
                    && !diagnostic.startsWith("terminal")) {
                return;
            }
            BearFrameStream.Snapshot<RawImageData> observed = ui == null ? null : ui.current();
            if (!BearSessionCheckpoint.recordObservation(profile, new BearSessionCheckpoint.Checkpoint(
                    eventEnd,
                    ui == null ? "CONSTRUCTING" : ui.phase().name(),
                    observed == null ? "UNOBSERVED" : observed.screen().name(),
                    diagnostic,
                    observed == null ? frames.latestSequence() : observed.sequence(),
                    observed == null ? Instant.EPOCH : observed.capturedAt(),
                    0,
                    "ui-transition",
                    Instant.now()))) {
                logWarning(routineLogBearTrapLine(
                        "Could not persist Bear transition provenance; scheduler recovery budget was not changed"));
            }
        }

        private void beginActivePhase() {
            ui.phase(BearUiStateMachine.Phase.ACTIVE);
            BearFrameStream.Snapshot<RawImageData> observed = ui.observe();
            if (observed.screen() == BearNavigationPolicy.Screen.RECONNECT) {
                throw protectedFailure(
                        BearSessionExecutionException.FailureKind.RECONNECT_SCREEN,
                        BearSessionExecutionException.RecoveryDirective.RESTART_APP,
                        "active-entry",
                        null);
            }
        }

        private void prepareUntil(Instant activation) {
            ui.phase(BearUiStateMachine.Phase.PREPARING);
            try {
                prepareFrameDriven();
            } catch (BearSessionExecutionException preparationUnavailable) {
                if (!BearPreparationPolicy.mayWaitForActivation(preparationUnavailable)) {
                    throw preparationUnavailable;
                }
                // Missing visual evidence forbids the preparation taps, but it must not throw
                // away the protected event session. Retain the lease, record the omission, and
                // enter the active state machine as soon as the game exposes the event UI.
                logWarning(routineLogBearTrapLine(
                        "Preparation step unavailable; waiting safely for Bear activation: "
                                + preparationUnavailable.operation()));
            }
            waitForActivation(activation);
            beginActivePhase();
        }

        /** Samples frames until activation, leaving early only for a reconnect screen. */
        void waitForActivation(Instant activation) {
            ui.phase(BearUiStateMachine.Phase.WAITING_FOR_ACTIVATION);
            while (now().isBefore(activation)) {
                if (cancellationRequested()) {
                    throw new StopExecutionException("Bear cancelled while waiting for activation",
                            StopExecutionException.Reason.USER_CANCELLED);
                }
                Duration remaining = Duration.between(now(), activation);
                Duration sampleWindow = remaining.compareTo(Duration.ofSeconds(1)) > 0
                        ? Duration.ofSeconds(1) : remaining;
                ui.await(sampleWindow, frame -> frame.screen() == BearNavigationPolicy.Screen.RECONNECT,
                        "activation-deadline").ifPresent(frame -> {
                            throw protectedFailure(
                                    BearSessionExecutionException.FailureKind.RECONNECT_SCREEN,
                                    BearSessionExecutionException.RecoveryDirective.RESTART_APP,
                                    "wait-for-activation",
                                    null);
                        });
            }
        }

        void prepareFrameDriven() {
            BearFrameStream.Snapshot<RawImageData> observed = ui.observe();
            if (observed.screen() == BearNavigationPolicy.Screen.RECONNECT) {
                throw protectedFailure(
                        BearSessionExecutionException.FailureKind.RECONNECT_SCREEN,
                        BearSessionExecutionException.RecoveryDirective.RESTART_APP,
                        "prepare-entry",
                        null);
            }
            BearPreparationSequence.run(java.util.List.of(
                    this::disableAutoJoinForPreparation,
                    this::recallTroopsForPreparation,
                    this::activatePetsForPreparation,
                    this::navigateForPreparation), operation -> {
                recordTransitionDiagnostic("preparation-unavailable operation=" + operation
                        + " reason=missing-positive-frame-evidence inputSent=false");
                logWarning(routineLogBearTrapLine("Preparation step unavailable: " + operation
                        + "; continuing independently verified preparation steps"));
            });
        }

        private void disableAutoJoinForPreparation() {
            if (Boolean.TRUE.equals(profile.getConfig(
                    ConfigurationKeyEnum.ALLIANCE_AUTOJOIN_BOOL, Boolean.class))) {
                requireLiveEvidence("autojoin panel and stopped-state frames", "disable-autojoin");
            }
        }

        private void recallTroopsForPreparation() {
            if (recallTroops) {
                requireLiveEvidence("march-sidebar and recall-confirmation frames", "recall-troops");
            }
        }

        private void activatePetsForPreparation() {
            if (usePets) {
                boolean petUseArmed = BearSessionCheckpoint.load(profile)
                        .filter(value -> value.eventEnd().equals(eventEnd))
                        .map(value -> !"NONE".equals(value.petUseState())).orElse(false);
                if (!BearPetPreparation.activate(ui, petUseArmed, (action, authorization) -> {
                    TemplatesEnum target = switch (action) {
                        case OPEN_PETS -> GAME_HOME_PETS;
                        case SELECT_BATTLE_SKILL -> BEAR_PET_BATTLE_ICON;
                        case OPEN_PET_QUICK_USE -> BEAR_PET_QUICK_USE;
                        case CONFIRM_PET_USE -> BEAR_PET_CONFIRM_USE;
                        case CLOSE_PET_SKILLS -> BEAR_PET_CLOSE;
                        default -> throw new IllegalArgumentException("Not a pet preparation edge: " + action);
                    };
                    if (action == BearUiAction.CONFIRM_PET_USE && !BearSessionCheckpoint.armPetUse(
                            profile, eventEnd, authorization.sequence(), authorization.capturedAt())) {
                        throw protectedFailure(BearSessionExecutionException.FailureKind.PERSISTENCE,
                                BearSessionExecutionException.RecoveryDirective.DEGRADED_WAIT,
                                "pet-use-intent-not-persisted", null);
                    }
                    tapTemplateFrom(authorization, target, 95, action.name());
                })) {
                    throw protectedFailure(
                            BearSessionExecutionException.FailureKind.VISUAL_UNKNOWN,
                            BearSessionExecutionException.RecoveryDirective.DEGRADED_WAIT,
                            "pet-battle-skills-postcondition-not-verified", null);
                }
            }
        }

        private void navigateForPreparation() {
            if (!BearTrapPreparation.navigate(ui, frame -> classifier.configuredBearIdentity(frame.frame()),
                    (action, authorization) -> {
                        if (action == BearUiAction.GO_TO_CONFIGURED_TRAP) {
                            if (classifier.trapStatus(authorization.frame(), trapNumber)
                                    == BearFrameClassifier.TrapStatus.UNKNOWN) {
                                throw new BearInputRefusedException("configured-trap-row-not-verified");
                            }
                            AreaData row = CommonGameAreas.bearTrapGoArea(trapNumber);
                            ImageSearchResultData hit = emuManager.locatePattern(EMULATOR_NUMBER,
                                    authorization.frame(), BEAR_TRAP_GO, row.topLeft(), row.bottomRight(), 95);
                            if (!hit.isFound()) throw new BearInputRefusedException("configured-trap-go-missing");
                            requireFreshAuthorization(authorization, "configured-trap-go");
                            tapInside(hit);
                        } else {
                            TemplatesEnum target = switch (action) {
                                case OPEN_ALLIANCE -> BEAR_WORLD_ALLIANCE;
                                case OPEN_TERRITORY -> ALLIANCE_TERRITORY_BUTTON;
                                case OPEN_SPECIAL_BUILDINGS -> BEAR_SPECIAL_TAB_UNSELECTED;
                                default -> throw new IllegalArgumentException("Not a trap navigation edge: " + action);
                            };
                            tapTemplateFrom(authorization, target, 95, action.name());
                        }
                    })) {
                throw protectedFailure(BearSessionExecutionException.FailureKind.VISUAL_UNKNOWN,
                        BearSessionExecutionException.RecoveryDirective.DEGRADED_WAIT,
                        "configured-trap-arrival-not-verified", null);
            }
        }

        private void requireLiveEvidence(String evidence, String operation) {
            throw protectedFailure(
                    BearSessionExecutionException.FailureKind.FATAL_CONFIGURATION,
                    BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION,
                    operation,
                    new IllegalStateException("Missing positive live evidence: " + evidence));
        }

        void cleanup(
                BearSessionCoordinator.ExitReason exit,
                boolean resumeNormalTasks) {
            try {
                try {
                    if (verifiesTerminalUi(resumeNormalTasks)
                            && !BearSessionCheckpoint.armCleanup(profile, eventEnd)) {
                        throw protectedFailure(BearSessionExecutionException.FailureKind.PERSISTENCE,
                                BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION,
                                "terminal-cleanup-intent-not-persisted", null);
                    }
                    ui.phase(BearUiStateMachine.Phase.CLEANING_UP);
                    if (verifiesTerminalUi(resumeNormalTasks) && !verifyTerminalUiCleanup()) {
                        throw protectedFailure(
                                BearSessionExecutionException.FailureKind.VISUAL_UNKNOWN,
                                BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION,
                                "terminal-cleanup-not-verified",
                                null);
                    }
                    if (verifiesTerminalUi(resumeNormalTasks)
                            && !BearSessionCheckpoint.verifyInitialCleanup(profile, eventEnd)) {
                        throw protectedFailure(BearSessionExecutionException.FailureKind.PERSISTENCE,
                                BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION,
                                "terminal-cleanup-verification-not-persisted", null);
                    }
                    cleanupFlow(resumeNormalTasks);
                    BearUiStateMachine.TerminalReason terminal = switch (exit == null
                            ? BearSessionCoordinator.ExitReason.UNRECOVERABLE_FAILURE : exit) {
                        case EVENT_ENDED -> BearUiStateMachine.TerminalReason.EVENT_ENDED;
                        case CANCELLED -> BearUiStateMachine.TerminalReason.CANCELLED;
                        case UNRECOVERABLE_FAILURE -> BearUiStateMachine.TerminalReason.RECOVERY_EXHAUSTED;
                    };
                    ui.terminate(terminal);
                } catch (BearSessionExecutionException protectedFailure) {
                    throw protectedFailure.operation().startsWith("terminal-cleanup")
                            ? protectedFailure
                            : new BearSessionExecutionException(protectedFailure.failureKind(),
                                    protectedFailure.recoveryDirective(), protectedFailure.device(),
                                    "terminal-cleanup-" + protectedFailure.operation(),
                                    "Terminal Bear cleanup failed: " + protectedFailure.operation(), protectedFailure);
                } catch (RuntimeException rawFailure) {
                    throw protectedFailure(
                            BearSessionExecutionException.FailureKind.CAPTURE_TRANSIENT,
                            BearSessionExecutionException.RecoveryDirective.DEGRADED_WAIT,
                            "terminal-cleanup-frame-failure",
                            rawFailure);
                }
            } finally {
                realtimeFrames.close();
                if (capture != null) {
                    capture.close();
                }
            }
        }

        private boolean verifyTerminalUiCleanup() {
            for (int attempt = 0; attempt < TERMINAL_CLEANUP_TRANSITION_LIMIT; attempt++) {
                BearNavigationPolicy.Screen screen = observeBearScreen();
                if (terminalWorldVerified(ui.current())) {
                    return true;
                }
                if (screen == BearNavigationPolicy.Screen.RECONNECT
                        || screen == BearNavigationPolicy.Screen.APP_LOADING
                        || screen == BearNavigationPolicy.Screen.UNKNOWN) {
                    // A transient/unclassified frame is not permission for Back. Recovery
                    // samples newer frames under a deadline, then re-enters classification.
                    ui.await(TERMINAL_CLEANUP_SETTLE_DEADLINE, frame -> frame.screen() != screen,
                            "terminal-cleanup-settle-" + (attempt + 1));
                    continue;
                }
                if (!BearUiAction.BACK_TO_PARENT.legalFrom(screen)
                        && !BearUiAction.DISMISS_DEPLOY_DIALOG.legalFrom(screen)) {
                    return false;
                }
                if (!backToVerifiedParent(screen)) {
                    // An unconfirmed input may have succeeded late. Observe for completion,
                    // but never repeat that input merely because its acknowledgment was lost.
                    return ui.await(TERMINAL_CLEANUP_SETTLE_DEADLINE,
                            this::terminalWorldVerified,
                            "terminal-cleanup-late-postcondition").isPresent();
                }
            }
            // The final allowed transition has its own newer-frame postcondition; do not
            // reject a successful return just because there is no next loop iteration.
            observeBearScreen();
            return terminalWorldVerified(ui.current());
        }

        boolean recoverTerminalUiOnly() {
            try {
                cleanupAuthorization.run();
                ui.phase(BearUiStateMachine.Phase.CLEANING_UP);
                boolean verified = verifyTerminalUiCleanup();
                if (verified) ui.terminate(BearUiStateMachine.TerminalReason.EVENT_ENDED);
                return verified;
            } finally {
                realtimeFrames.close();
                if (capture != null) capture.close();
            }
        }

        private boolean terminalWorldVerified(BearFrameStream.Snapshot<RawImageData> frame) {
            return frame != null && BearProductionScreens.WORLD.contains(frame.screen())
                    && frames.isCurrent(frame, BearVerifiedActionExecutor.MAXIMUM_AUTHORIZING_FRAME_AGE);
        }

        @Override
        public Instant now() {
            return Instant.now();
        }

        @Override
        public boolean cancellationRequested() {
            if (Thread.currentThread().isInterrupted()) {
                return true;
            }
            try {
                checkPreemption();
                return false;
            } catch (BearSessionExecutionException e) {
                throw e;
            } catch (StopExecutionException e) {
                if (e.isCancellation()) {
                    return true;
                }
                throw e;
            }
        }

        @Override
        public BearSessionCoordinator.EventStatus eventStatus() {
            return now().isBefore(eventEnd)
                    ? BearSessionCoordinator.EventStatus.ACTIVE
                    : BearSessionCoordinator.EventStatus.ENDED;
        }

        @Override
        public BearSessionCoordinator.MarchSnapshot readMarches(
                OptionalInt trackedOwnSlot, boolean mayAdoptExisting) {
            Instant observedAt = now();
            if (!lastMarches.isEmpty() && observedAt.isBefore(nextMarchRefreshAt)) {
                return describeMarches(lastMarches, cachedSpecialRallyPreparing,
                        trackedOwnSlot, mayAdoptExisting);
            }
            try {
                MarchHelper.MarchQueueSnapshot queue = readMarchSnapshot();
                List<MarchSlotState> slots = queue.slots();
                boolean reliable = !slots.isEmpty() && slots.stream()
                        .anyMatch(slot -> slot.availability() != MarchSlotAvailability.UNKNOWN);
                if (!reliable) {
                    return new BearSessionCoordinator.MarchSnapshot(
                            false, 0, BearSessionCoordinator.OwnRallyObservation.active(
                                    trackedOwnSlot.orElse(0), BearSessionCoordinator.OwnRallyPhase.UNKNOWN, null));
                }
                rememberMarchSnapshot(queue, observedAt);
                return describeMarches(slots, queue.specialRallyPreparing(),
                        trackedOwnSlot, mayAdoptExisting);
            } catch (BearSessionExecutionException e) {
                throw e;
            } catch (ADBConnectionException e) {
                throw e;
            } catch (StopExecutionException e) {
                throw e;
            } catch (Exception e) {
                logWarning(routineLogBearTrapLine("March queue could not be read: " + e.getMessage()));
                return new BearSessionCoordinator.MarchSnapshot(
                        false, 0, BearSessionCoordinator.OwnRallyObservation.active(
                                trackedOwnSlot.orElse(0), BearSessionCoordinator.OwnRallyPhase.UNKNOWN, null));
            }
        }

        @Override
        public BearSessionCoordinator.OwnRallyStartResult startOwnRally(int formation) {
            if (!openBearRallyFromAnchor()) {
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.NAVIGATION_FAILURE);
            }
            if (ui.transition(
                    BearUiAction.OPEN_RALLY_TIMER,
                    authorization -> tapTemplateFrom(
                            authorization, BEAR_RALLY_BUTTON, 80, "open-own-rally-timer"))
                    != BearVerifiedActionExecutor.Outcome.CONFIRMED) {
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.STALE_SCREEN);
            }
            if (!ensureRallyTimeSelected(RALLY_DURATION_BASE_MINUTES_VALUE)) {
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.RALLY_TIMER_NOT_CONFIRMED);
            }
            int rallySeconds = RALLY_DURATION_BASE_MINUTES_VALUE * 60;
            if (ui.transition(
                    BearUiAction.CONFIRM_RALLY_TIMER,
                    authorization -> tapTemplateFrom(
                            authorization, RALLY_HOLD_BUTTON, 90, "confirm-own-rally-timer"))
                    != BearVerifiedActionExecutor.Outcome.CONFIRMED) {
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.STALE_SCREEN);
            }
            if (!ensureFormationSelected(formation)) {
                if (!backToVerifiedParent(BearNavigationPolicy.Screen.FORMATION)) {
                    return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                            BearSessionCoordinator.OwnRallyStartOutcome.NAVIGATION_FAILURE);
                }
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.FORMATION_UNAVAILABLE);
            }
            BearFrameStream.Snapshot<RawImageData> preflightFrame = ui.observe();
            if (preflightFrame.screen() != BearNavigationPolicy.Screen.FORMATION) {
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.STALE_SCREEN);
            }
            DeploymentPreflightRead preflight = sessionDeploymentHelper.readPreflightScreen(
                    preflightFrame.frame(), DeploymentHelper.MAX_RALLY_STAMINA_COST);
            if (preflight.noDeployableTroops()) {
                if (!backToVerifiedParent(BearNavigationPolicy.Screen.FORMATION)) {
                    return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                            BearSessionCoordinator.OwnRallyStartOutcome.NAVIGATION_FAILURE);
                }
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.FORMATION_UNAVAILABLE);
            }

            long travelSeconds = preflight.deployment().travelTimeSeconds();
            if (travelSeconds <= 0) {
                if (!backToVerifiedParent(BearNavigationPolicy.Screen.FORMATION)) {
                    return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                            BearSessionCoordinator.OwnRallyStartOutcome.NAVIGATION_FAILURE);
                }
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.OCR_MISS);
            }
            if (now().plusSeconds(rallySeconds + travelSeconds * 2).isAfter(eventEnd)) {
                if (!backToVerifiedParent(BearNavigationPolicy.Screen.FORMATION)) {
                    return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                            BearSessionCoordinator.OwnRallyStartOutcome.NAVIGATION_FAILURE);
                }
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.TOO_LATE);
            }

            // Persist a conservative no-duplicate marker before requesting final authorization.
            // The callback itself remains side-effect-light so checkpoint I/O cannot age its frame.
            Instant expectedReturn = BearOwnRallyArm.expectedReturn(
                    now(), rallySeconds, travelSeconds, eventEnd);
            requireTacticalCheckpoint(
                    now(),
                    expectedReturn,
                    "OWN_RALLY_SEND_ARMED",
                    null,
                    "own-send-armed-before-authorization");
            ownRallyBusyUntil = expectedReturn;
            Instant[] sendTappedAt = new Instant[1];
            BearVerifiedActionExecutor.Outcome deployOutcome = ui.transition(
                        BearUiAction.DEPLOY_OWN_RALLY,
                        authorization -> {
                            Instant inputAt = now();
                            if (inputAt.plusSeconds(rallySeconds + travelSeconds * 2)
                                    .isAfter(eventEnd)) {
                                throw new BearInputRefusedException("own-rally-no-longer-fits");
                            }
                            ImageSearchResultData deploy = emuManager.locatePattern(
                                    EMULATOR_NUMBER,
                                    authorization.frame(),
                                    BEAR_DEPLOY_BUTTON,
                                    90);
                            if (!deploy.isFound()) {
                                throw new BearInputRefusedException(
                                        "own-deploy-missing-in-authorizing-frame");
                            }
                            requireFreshAuthorization(authorization, "deploy-own-rally");
                            Instant exactInputAt = now();
                            if (exactInputAt.plusSeconds(rallySeconds + travelSeconds * 2)
                                    .isAfter(eventEnd)) {
                                throw new BearInputRefusedException("own-rally-no-longer-fits");
                            }
                            sendTappedAt[0] = exactInputAt;
                            tapInside(deploy.getMatchedArea());
                        });
            BearOwnRallyArm.Decision sent = BearOwnRallyArm.afterDeploy(
                    deployOutcome, ui.lastInputSent(), ui.lastRefusal(), now());
            if (sent.kind() == BearOwnRallyArm.Kind.ABORTED) {
                ownRallyBusyUntil = null;
                requireTacticalCheckpoint(
                        Instant.EPOCH,
                        Instant.EPOCH,
                        "OWN_RALLY_SEND_ABORTED",
                        null,
                        "no-input-" + deployOutcome + ":" + ui.lastRefusal());
                if (!backToVerifiedParent(BearNavigationPolicy.Screen.FORMATION)) {
                    return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                            BearSessionCoordinator.OwnRallyStartOutcome.NAVIGATION_FAILURE);
                }
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(sent.tooLate()
                        ? BearSessionCoordinator.OwnRallyStartOutcome.TOO_LATE
                        : BearSessionCoordinator.OwnRallyStartOutcome.DEPLOY_NOT_CONFIRMED);
            }
            if (sent.kind() == BearOwnRallyArm.Kind.SENT_UNCONFIRMED) {
                // Send may have taken effect. Hold briefly so the next fresh march read either
                // shows the Special rally (adopted) or proves it absent (recreated).
                ownRallyBusyUntil = sent.busyUntil();
                requireTacticalCheckpoint(
                        sendTappedAt[0] == null ? now() : sendTappedAt[0],
                        ownRallyBusyUntil,
                        "OWN_RALLY_SENT_UNCONFIRMED",
                        null,
                        "own-send-postcondition-not-observed");
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.DEPLOY_NOT_CONFIRMED);
            }
            DeploymentPostTapRead postTap = sessionDeploymentHelper.readPostTapScreen(
                    ui.current().frame());
            if (postTap.marchQueueFull()) {
                clearOwnRallySendArm("own-send-rejected-march-queue-full");
                if (!backToVerifiedParent(BearNavigationPolicy.Screen.MARCH_QUEUE_FULL)) {
                    return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                            BearSessionCoordinator.OwnRallyStartOutcome.NAVIGATION_FAILURE);
                }
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.MARCH_QUEUE_FULL);
            }
            if (postTap.sameTargetDialog()) {
                if (!leaveVerifiedFormationScreen()) {
                    return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                            BearSessionCoordinator.OwnRallyStartOutcome.NAVIGATION_FAILURE);
                }
                recover(BearSessionCoordinator.State.OWN_RALLY_STARTING);
                MarchHelper.MarchQueueSnapshot queue = readMarchSnapshot();
                OptionalInt existing = queue.specialRallyPreparing()
                        ? OptionalInt.of(0)
                        : OptionalInt.empty();
                if (existing.isPresent()) {
                    // Special proves ownership but not the original Send instant, travel time, or
                    // remaining countdown. Keep the session conservatively busy until event end;
                    // subsequent live queue observations can clear it when Special disappears.
                    // Special proves ownership but not the Send instant: assume it was just sent,
                    // which is the latest possible return, and re-observe after it.
                    ownRallyBusyUntil = BearOwnRallyArm.expectedReturn(
                            now(), rallySeconds, travelSeconds, eventEnd);
                    requireTacticalCheckpoint(
                            Instant.EPOCH, ownRallyBusyUntil, "OWN_RALLY_ADOPTED", null,
                            "same-target-dialog-adopted-with-latest-possible-return");
                } else {
                    clearOwnRallySendArm("same-target-dialog-without-active-rally");
                }
                return existing.isPresent()
                        ? BearSessionCoordinator.OwnRallyStartResult.alreadyActive(existing.getAsInt())
                        : BearSessionCoordinator.OwnRallyStartResult.recoverable(
                                 BearSessionCoordinator.OwnRallyStartOutcome.DEPLOY_NOT_CONFIRMED);
            }
            if (postTap.confirmationDialog().isFound()) {
                clearOwnRallySendArm("own-send-rejected-confirmation-dialog");
                if (!dismissDeployConfirmationAndLeaveFormation()) {
                    return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                            BearSessionCoordinator.OwnRallyStartOutcome.NAVIGATION_FAILURE);
                }
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.DEPLOY_NOT_CONFIRMED);
            }
            if (postTap.deployButton().isFound()) {
                clearOwnRallySendArm("own-send-remained-on-formation");
                if (!backToVerifiedParent(BearNavigationPolicy.Screen.FORMATION)) {
                    return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                            BearSessionCoordinator.OwnRallyStartOutcome.NAVIGATION_FAILURE);
                }
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.DEPLOY_NOT_CONFIRMED);
            }

            OptionalInt newRally = awaitNewRallySlot();
            if (newRally.isPresent()) {
                Instant sentAt = sendTappedAt[0];
                if (sentAt == null) {
                    return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                            BearSessionCoordinator.OwnRallyStartOutcome.DEPLOY_NOT_CONFIRMED);
                }
                ownRallyBusyUntil = sentAt
                        .plusSeconds(rallySeconds + travelSeconds * 2)
                        ;
                requireTacticalCheckpoint(
                        sentAt,
                        ownRallyBusyUntil,
                        "OWN_RALLY_CONFIRMED",
                        null,
                        "exact-send-and-return-deadline");
                recoveryStrikes.recovered(BearSessionCoordinator.State.OWN_RALLY_REQUIRED);
                logInfo(routineLogBearTrapLine(newRally.getAsInt() == 0
                        ? "Own rally confirmed in the Bear Special row"
                        : "Own rally confirmed in march slot #" + newRally.getAsInt()));
                return BearSessionCoordinator.OwnRallyStartResult.confirmed(
                        newRally.getAsInt(), sentAt,
                        Duration.ofSeconds(rallySeconds), Duration.ofSeconds(travelSeconds));
            }
            return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                    BearSessionCoordinator.OwnRallyStartOutcome.DEPLOY_NOT_CONFIRMED);
        }

        private void clearOwnRallySendArm(String reason) {
            ownRallyBusyUntil = null;
            requireTacticalCheckpoint(
                    Instant.EPOCH,
                    Instant.EPOCH,
                    "OWN_RALLY_SEND_ABORTED",
                    null,
                    reason);
        }

        @Override
        public BearSessionCoordinator.JoinOutcome joinNext(
                List<Integer> formations,
                Instant ownReturnDeadline) {
            if (ownReturnDeadline != null && !now().isBefore(ownReturnDeadline)) {
                return BearSessionCoordinator.JoinOutcome.OWN_RALLY_DUE;
            }
            if (formationGate.blocked(now())) {
                return BearSessionCoordinator.JoinOutcome.NO_FORMATION_AVAILABLE;
            }
            formationGate.clearIfDue(now());
            try {
                if (!recoverPersistedJoinTransaction()) {
                    return BearSessionCoordinator.JoinOutcome.STALE_SCREEN;
                }
                alliedRallyIndicatorAbsent = false;
                if (!openWarList()) {
                    // No war indicator on World means no allied rally to join, not a failure
                    // that needs recovery.
                    return alliedRallyIndicatorAbsent
                            ? BearSessionCoordinator.JoinOutcome.NO_JOINABLE_RALLY
                            : BearSessionCoordinator.JoinOutcome.PAGE_NOT_READY;
                }

                BearFrameStream.Snapshot<RawImageData> scanFrame = ui.observe();
                if (scanFrame.screen() != BearNavigationPolicy.Screen.WAR_LIST) {
                    return BearSessionCoordinator.JoinOutcome.PAGE_NOT_READY;
                }
                List<BearRallyScanner.RallyRow> controls = rallyFrameScan.rows(scanFrame);
                BearRallyListTraversal.Decision decision = rallyTraversal.decide(
                        rallyObservation(scanFrame, controls, rallyListBottomProven));
                if (decision.kind() == BearRallyListTraversal.Kind.WAIT_FOR_VISIBLE_PLUS) {
                    ui.await(Duration.ofMillis(250),
                            frame -> frame.screen() != BearNavigationPolicy.Screen.WAR_LIST,
                            "rally-list-live-observation-window");
                    return BearSessionCoordinator.JoinOutcome.LIST_OBSERVING;
                }
                if (decision.kind() == BearRallyListTraversal.Kind.SCROLL) {
                    return scrollRallyList(controls)
                            ? BearSessionCoordinator.JoinOutcome.LIST_ADVANCED
                            : BearSessionCoordinator.JoinOutcome.STALE_SCREEN;
                }
                if (decision.kind() == BearRallyListTraversal.Kind.REOPEN_FROM_TOP) {
                    return reopenRallyListAtTop()
                            ? BearSessionCoordinator.JoinOutcome.LIST_ADVANCED
                            : BearSessionCoordinator.JoinOutcome.NAVIGATION_FAILURE;
                }
                if (decision.kind() == BearRallyListTraversal.Kind.OVERLAP_NOT_PROVEN) {
                    return BearSessionCoordinator.JoinOutcome.STALE_SCREEN;
                }
                BearRallyListTraversal.Row candidate = rallyTraversal.currentTopmostJoinable(
                        controls.stream().map(BearRallyListTraversal.Row::new).toList()).orElse(null);
                if (candidate == null) {
                    return BearSessionCoordinator.JoinOutcome.RALLY_DEPARTED;
                }
                if (!BearJoinCheckpointSemantics.hasReconstructableRowIdentity(candidate)) {
                    return BearSessionCoordinator.JoinOutcome.RALLY_DEPARTED;
                }
                if (ownReturnDeadline != null && !now().isBefore(ownReturnDeadline)) {
                    return BearSessionCoordinator.JoinOutcome.OWN_RALLY_DUE;
                }
                String priorSubstate = restoredJoinSubstate;
                BearRallyListTraversal.Row priorRow = restoredJoinRow;
                // Persist the transaction before authorization: the checkpoint write must not
                // age the authorizing frame. The callback only proves the same row is still there.
                requireTacticalCheckpoint(
                        null,
                        ownRallyBusyUntil,
                        "JOIN_ARMED",
                        candidate,
                        "join-armed-before-authorization");
                BearRallyListTraversal.Row[] committedRow = new BearRallyListTraversal.Row[1];
                BearVerifiedActionExecutor.Outcome plus = ui.transition(
                        BearUiAction.OPEN_JOIN_FORMATION,
                        authorization -> {
                            List<BearRallyListTraversal.Row> currentRows = rallyFrameScan.rows(authorization).stream()
                                    .map(BearRallyListTraversal.Row::new).toList();
                            BearRallyListTraversal.Row first = rallyTraversal.authorizeCandidate(candidate, currentRows)
                                    .orElseThrow(() -> new BearInputRefusedException(
                                            "join-identity-or-order-not-proven-in-authorizing-frame"));
                            BearRallyScanner.RallyRow current = first.control();
                            if (ownReturnDeadline != null && !now().isBefore(ownReturnDeadline)) {
                                throw new BearInputRefusedException("own-rally-due-before-plus");
                            }
                            requireFreshAuthorization(authorization, "open-join-formation");
                            committedRow[0] = first;
                            tapInside(current.joinArea());
                        });
                if (!ui.lastInputSent()) {
                    requireTacticalCheckpoint(null, ownRallyBusyUntil, priorSubstate, priorRow,
                            "join-not-sent:" + plus + ":" + ui.lastRefusal());
                    return ui.lastRefusal().contains("own-rally-due")
                            ? BearSessionCoordinator.JoinOutcome.OWN_RALLY_DUE
                            : BearSessionCoordinator.JoinOutcome.RALLY_DEPARTED;
                }
                if (plus != BearVerifiedActionExecutor.Outcome.CONFIRMED) {
                    return BearSessionCoordinator.JoinOutcome.RALLY_DEPARTED;
                }
                BearRallyListTraversal.Row selectedRow = committedRow[0];
                if (selectedRow == null) {
                    return BearSessionCoordinator.JoinOutcome.RALLY_DEPARTED;
                }
                requireTacticalCheckpoint(
                        null,
                        ownRallyBusyUntil,
                        "PLUS_COMMITTED",
                        selectedRow,
                        "join-plus-committed");
                int formation = selectFirstAvailableFormation(formations);
                if (formation == 0) {
                    if (!returnToWarListFromFormation()) {
                        return BearSessionCoordinator.JoinOutcome.STALE_SCREEN;
                    }
                    formationGate.block(
                            now(), eventEnd, ownReturnDeadline, lastMarches);
                    return BearSessionCoordinator.JoinOutcome.NO_FORMATION_AVAILABLE;
                }
                BearFrameStream.Snapshot<RawImageData> preflightFrame = ui.observe();
                if (preflightFrame.screen() != BearNavigationPolicy.Screen.FORMATION) {
                    return BearSessionCoordinator.JoinOutcome.STALE_SCREEN;
                }
                DeploymentPreflightRead joinPreflight = sessionDeploymentHelper.readPreflightScreen(
                        preflightFrame.frame(), DeploymentHelper.MAX_RALLY_STAMINA_COST);
                if (joinPreflight.noDeployableTroops()) {
                    return returnToWarListFromFormation()
                            ? BearSessionCoordinator.JoinOutcome.FORMATION_UNAVAILABLE
                            : BearSessionCoordinator.JoinOutcome.STALE_SCREEN;
                }
                if (!now().isBefore(eventEnd)) {
                    if (!returnToWarListFromFormation()) {
                        return BearSessionCoordinator.JoinOutcome.STALE_SCREEN;
                    }
                    requireTacticalCheckpoint(null, ownRallyBusyUntil, "ABORTED_EVENT_END",
                            selectedRow, "event-ended-before-deploy");
                    return BearSessionCoordinator.JoinOutcome.PAGE_NOT_READY;
                }
                BearVerifiedActionExecutor.Outcome joinDeploy = ui.transition(
                            BearUiAction.DEPLOY_JOIN,
                            authorization -> {
                                ImageSearchResultData deploy = emuManager.locatePattern(
                                        EMULATOR_NUMBER,
                                        authorization.frame(),
                                        BEAR_DEPLOY_BUTTON,
                                        90);
                                if (!deploy.isFound()) {
                                    throw new BearInputRefusedException(
                                            "join-deploy-missing-in-authorizing-frame");
                                }
                                requireFreshAuthorization(authorization, "deploy-joined-rally");
                                if (!now().isBefore(eventEnd)) {
                                    throw new BearInputRefusedException(
                                            "event-ended-at-final-deploy-authorization");
                                }
                                tapInside(deploy.getMatchedArea());
                            });
                if (!ui.lastInputSent()) {
                    if (!returnToWarListFromFormation()) {
                        return BearSessionCoordinator.JoinOutcome.STALE_SCREEN;
                    }
                    requireTacticalCheckpoint(null, ownRallyBusyUntil, "ABORTED_EVENT_END",
                            selectedRow, "join-deploy-not-sent:" + joinDeploy + ":" + ui.lastRefusal());
                    return BearSessionCoordinator.JoinOutcome.PAGE_NOT_READY;
                }
                if (joinDeploy != BearVerifiedActionExecutor.Outcome.CONFIRMED) {
                    return BearSessionCoordinator.JoinOutcome.RALLY_GONE;
                }
                DeploymentPostTapRead postTap = sessionDeploymentHelper.readPostTapScreen(
                        ui.current().frame());
                if (postTap.marchQueueFull()) {
                    if (!backToVerifiedParent(BearNavigationPolicy.Screen.MARCH_QUEUE_FULL)) {
                        return BearSessionCoordinator.JoinOutcome.STALE_SCREEN;
                    }
                    rallyTraversal.completed(selectedRow);
                    return BearSessionCoordinator.JoinOutcome.MARCH_QUEUE_FULL;
                }
                if (postTap.sameTargetDialog()) {
                    if (!leaveVerifiedFormationScreen()) {
                        return BearSessionCoordinator.JoinOutcome.STALE_SCREEN;
                    }
                    rallyTraversal.completed(selectedRow);
                    return BearSessionCoordinator.JoinOutcome.ALREADY_JOINED_OR_MARCHING;
                }
                if (postTap.confirmationDialog().isFound()) {
                    if (!dismissDeployConfirmationAndLeaveFormation()) {
                        return BearSessionCoordinator.JoinOutcome.STALE_SCREEN;
                    }
                    rallyTraversal.completed(selectedRow);
                    return BearSessionCoordinator.JoinOutcome.RALLY_FULL;
                }
                if (postTap.deployButton().isFound()) {
                    if (!returnToWarListFromFormation()) {
                        return BearSessionCoordinator.JoinOutcome.STALE_SCREEN;
                    }
                    rallyTraversal.completed(selectedRow);
                    return BearSessionCoordinator.JoinOutcome.RALLY_FULL;
                }

                classifier.rememberWarList(true);
                if (ui.current() != null
                        && ui.current().screen() == BearNavigationPolicy.Screen.WAR_LIST) {
                    rallyTraversal.completed(selectedRow);
                    requireTacticalCheckpoint(null, ownRallyBusyUntil, "ROW_COMPLETED", selectedRow,
                            "join-returned-to-list");
                    recoveryStrikes.recovered(BearSessionCoordinator.State.FILL_JOIN_SLOTS);
                    return BearSessionCoordinator.JoinOutcome.JOINED;
                }
                return BearSessionCoordinator.JoinOutcome.RALLY_GONE;
            } catch (BearSessionExecutionException e) {
                throw e;
            } catch (ADBConnectionException e) {
                throw e;
            } catch (StopExecutionException e) {
                throw e;
            } catch (Exception e) {
                logWarning(routineLogBearTrapLine("Join attempt lost its screen: " + e.getMessage()));
                return BearSessionCoordinator.JoinOutcome.STALE_SCREEN;
            }
        }

        private boolean recoverPersistedJoinTransaction() {
            if ("NONE".equals(restoredJoinSubstate)
                    || "ROW_COMPLETED".equals(restoredJoinSubstate)
                    || restoredJoinSubstate.startsWith("RECOVERED_")
                    || restoredJoinSubstate.startsWith("OWN_RALLY_")
                    || "ABORTED_EVENT_END".equals(restoredJoinSubstate)) {
                return true;
            }
            BearNavigationPolicy.Screen screen = observeBearScreen();
            boolean ambiguousPlus = "JOIN_ARMED".equals(restoredJoinSubstate);
            if (!BearJoinCheckpointSemantics.plusMayHaveBeenCommitted(restoredJoinSubstate)) {
                return false;
            }

            // A crash can occur after the tap but before PLUS_COMMITTED is durable. JOIN_ARMED is
            // therefore ambiguous and must use the same no-repeat recovery as a committed plus.
            // Resolve a visible transaction through verified Back edges, then retain the row
            // frontier even when recovery begins from World and the list must be reopened.
            // The durable transaction also proves list provenance. Seed it before Back so a
            // dialog that dismisses directly to an empty close-only list remains classifiable.
            if (BearJoinCheckpointSemantics.provesWarListProvenance(
                    restoredJoinSubstate, screen)) {
                classifier.rememberWarList(true);
            }
            if (screen == BearNavigationPolicy.Screen.DEPLOY_CONFIRMATION
                    || screen == BearNavigationPolicy.Screen.MARCH_QUEUE_FULL) {
                if (!backToVerifiedParent(screen)) return false;
                screen = observeBearScreen();
            }
            if (screen == BearNavigationPolicy.Screen.FORMATION) {
                if (!backToVerifiedParent(screen)) return false;
                screen = ui.current() == null
                        ? BearNavigationPolicy.Screen.UNKNOWN : ui.current().screen();
            }
            if (restoredJoinRow != null) {
                rallyTraversal.restoreCompleted(restoredJoinRow);
                preserveRestoredListFrontier = true;
            }
            if (screen != BearNavigationPolicy.Screen.WAR_LIST
                    && !BearProductionScreens.WORLD.contains(screen)) {
                return false;
            }
            requireTacticalCheckpoint(null, ownRallyBusyUntil, "RECOVERED_PLUS_ABORTED",
                    restoredJoinRow, ambiguousPlus
                            ? "restart-resolved-ambiguous-plus-without-repeating-input"
                            : "restart-resolved-committed-plus-without-repeating-input");
            restoredJoinSubstate = "RECOVERED_PLUS_ABORTED";
            return true;
        }


        @Override
        public boolean recover(BearSessionCoordinator.State resumeState) {
            boolean recovered = recoverOnce(resumeState);
            // Returning to World is navigation, not completion of the failed rally goal.
            // Count the failed goal even when Back succeeds; only real progress resets it.
            if (recoveryStrikes.failed(resumeState)) {
                throw protectedFailure(
                        BearSessionExecutionException.FailureKind.VISUAL_UNKNOWN,
                        BearSessionExecutionException.RecoveryDirective.DEGRADED_WAIT,
                        "recover-budget-" + resumeState,
                        null);
            }
            return recovered;
        }

        private boolean recoverOnce(BearSessionCoordinator.State resumeState) {
            try {
                ui.phase(BearUiStateMachine.Phase.RECOVERING);
                BearNavigationPolicy.Screen screen = observeBearScreen();
                if (screen != BearNavigationPolicy.Screen.UNKNOWN) {
                    recoveryStrikes.classifiedScreen();
                }
                if (screen == BearNavigationPolicy.Screen.FORMATION
                        || screen == BearNavigationPolicy.Screen.RALLY_TIMER_PANEL
                        || screen == BearNavigationPolicy.Screen.BEAR_RALLY_PANEL
                        || screen == BearNavigationPolicy.Screen.ALLIANCE_MENU
                        || screen == BearNavigationPolicy.Screen.ALLIANCE_TERRITORY
                        || screen == BearNavigationPolicy.Screen.SPECIAL_BUILDINGS
                        || screen == BearNavigationPolicy.Screen.PET_SKILL_PANEL
                        || screen == BearNavigationPolicy.Screen.PET_BATTLE_SELECTED
                        || screen == BearNavigationPolicy.Screen.PET_BATTLE_ACTIVE
                        || screen == BearNavigationPolicy.Screen.PET_CONFIRMATION
                        || screen == BearNavigationPolicy.Screen.WAR_LIST
                        || screen == BearNavigationPolicy.Screen.MARCH_SIDEBAR
                        || screen == BearNavigationPolicy.Screen.SIDEBAR_OTHER
                        || screen == BearNavigationPolicy.Screen.DEPLOY_CONFIRMATION
                        || screen == BearNavigationPolicy.Screen.MARCH_QUEUE_FULL) {
                    boolean recovered = backToVerifiedParent(screen);
                    if (recovered) {
                        ui.phase(BearUiStateMachine.Phase.ACTIVE);
                    }
                    return recovered;
                }
                if (screen != BearNavigationPolicy.Screen.UNKNOWN) {
                    if (screen == BearNavigationPolicy.Screen.RECONNECT) {
                        throw protectedFailure(
                                BearSessionExecutionException.FailureKind.RECONNECT_SCREEN,
                                BearSessionExecutionException.RecoveryDirective.RESTART_APP,
                                "recover-" + resumeState,
                                null);
                    }
                    if (screen == BearNavigationPolicy.Screen.APP_LOADING) {
                        boolean recovered = ui.await(
                                Duration.ofSeconds(2),
                                frame -> frame.screen() != BearNavigationPolicy.Screen.APP_LOADING
                                        && frame.screen() != BearNavigationPolicy.Screen.UNKNOWN,
                                "bounded-recovery-" + resumeState)
                                .filter(frame -> validRecoveryDestination(resumeState, frame.screen()))
                                .isPresent();
                        if (recovered) {
                            ui.phase(BearUiStateMachine.Phase.ACTIVE);
                        }
                        return recovered;
                    }
                    boolean recovered = validRecoveryDestination(resumeState, screen);
                    if (recovered) {
                        ui.phase(BearUiStateMachine.Phase.ACTIVE);
                    }
                    return recovered;
                }
                // An unclassified frame is often a transient overlay or animation. Give it a
                // bounded window, then one Back, then a game restart.
                boolean classified = ui.await(
                        Duration.ofSeconds(2),
                        frame -> frame.screen() != BearNavigationPolicy.Screen.UNKNOWN,
                        "unknown-screen-recovery-" + resumeState).isPresent();
                if (classified) {
                    recoveryStrikes.classifiedScreen();
                    return false;
                }
                if (recoveryStrikes.unknownScreen() == BearRecoveryStrikes.UnknownStep.PRESS_BACK_ONCE) {
                    // The operator permits one Back on UNKNOWN, not an unguarded transport call.
                    logWarning(routineLogBearTrapLine("Unclassified screen during " + resumeState
                            + " recovery; sending one bounded Back"));
                    BearVerifiedActionExecutor.Outcome outcome = ui.transition(
                            BearUiAction.RECOVER_UNKNOWN_BACK, authorization -> pressBack());
                    boolean recovered = outcome == BearVerifiedActionExecutor.Outcome.CONFIRMED
                            && validRecoveryDestination(resumeState, ui.current().screen());
                    if (recovered) ui.phase(BearUiStateMachine.Phase.ACTIVE);
                    return recovered;
                }
                throw protectedFailure(
                        BearSessionExecutionException.FailureKind.VISUAL_UNKNOWN,
                        BearSessionExecutionException.RecoveryDirective.RESTART_APP,
                        "recover-unknown-screen-" + resumeState,
                        null);
            } catch (BearSessionExecutionException e) {
                throw e;
            } catch (StopExecutionException e) {
                throw e;
            } catch (Exception e) {
                logWarning(routineLogBearTrapLine(
                        "Could not recover World for " + resumeState + ": " + e.getMessage()));
                return false;
            }
        }

        @Override
        public void pause(Duration duration) {
            if (duration.isZero() || duration.isNegative()) {
                return;
            }
            Instant deadline = now().plus(duration);
            while (now().isBefore(deadline) && now().isBefore(eventEnd)) {
                Duration remaining = Duration.between(now(), deadline);
                Duration sampleWindow = remaining.compareTo(Duration.ofSeconds(1)) > 0
                        ? Duration.ofSeconds(1) : remaining;
                Optional<BearFrameStream.Snapshot<RawImageData>> exceptional = ui.await(
                        sampleWindow,
                        frame -> frame.screen() == BearNavigationPolicy.Screen.RECONNECT
                                || frame.screen() == BearNavigationPolicy.Screen.APP_LOADING,
                        "coordinator-wait");
                if (exceptional.isPresent()
                        && exceptional.orElseThrow().screen() == BearNavigationPolicy.Screen.RECONNECT) {
                    throw protectedFailure(
                            BearSessionExecutionException.FailureKind.RECONNECT_SCREEN,
                            BearSessionExecutionException.RecoveryDirective.RESTART_APP,
                            "coordinator-wait",
                            null);
                }
                checkPreemption();
            }
        }

        @Override
        public void stateChanged(BearSessionCoordinator.State state) {
            if (lastState != state) {
                logInfo(routineLogBearTrapLine("Session state: " + state));
                lastState = state;
            }
        }

        private List<MarchSlotState> readMarchRows() {
            return readMarchSnapshot().slots();
        }

        private MarchHelper.MarchQueueSnapshot readMarchSnapshot() {
            BearNavigationPolicy.Screen screen = observeBearScreen();
            if (screen != BearNavigationPolicy.Screen.MARCH_SIDEBAR) {
                if (!BearProductionScreens.WORLD.contains(screen)) {
                    return new MarchHelper.MarchQueueSnapshot(List.of(), false);
                }
                if (ui.transition(
                        BearUiAction.OPEN_MARCH_SIDEBAR,
                        authorization -> tapInside(CommonGameAreas.LEFT_MENU_TRIGGER))
                        != BearVerifiedActionExecutor.Outcome.CONFIRMED) {
                    return new MarchHelper.MarchQueueSnapshot(List.of(), false);
                }
                screen = ui.current().screen();
            }
            if (screen == BearNavigationPolicy.Screen.SIDEBAR_OTHER) {
                if (ui.transition(
                        BearUiAction.SELECT_MARCH_SIDEBAR,
                        authorization -> tapInside(
                                CommonGameAreas.sidebarTab(SidebarSection.WILDERNESS)))
                        != BearVerifiedActionExecutor.Outcome.CONFIRMED) {
                    return new MarchHelper.MarchQueueSnapshot(List.of(), false);
                }
            }
            BearFrameStream.Snapshot<RawImageData> marchFrame = ui.current();
            MarchHelper.MarchQueueSnapshot snapshot = sessionMarchHelper
                    .readVisibleMarchQueueSnapshot(marchFrame.frame());
            BearVerifiedActionExecutor.Outcome closed = ui.transition(
                    BearUiAction.CLOSE_MARCH_SIDEBAR,
                    authorization -> tapInside(CommonGameAreas.LEFT_MENU_CLOSE));
            if (closed != BearVerifiedActionExecutor.Outcome.CONFIRMED) {
                return new MarchHelper.MarchQueueSnapshot(List.of(), false);
            }
            if (!snapshot.slots().isEmpty()) {
                rememberMarchSnapshot(snapshot, now());
            }
            return snapshot;
        }

        private BearSessionCoordinator.MarchSnapshot describeMarches(
                List<MarchSlotState> slots,
                boolean specialRallyPreparing,
                OptionalInt trackedOwnSlot,
                boolean mayAdoptExisting) {
            int freeSlots = (int) slots.stream().filter(MarchSlotState::isIdle).count();
            Duration earliestRelease = slots.stream()
                    .filter(MarchSlotState::hasExactReleaseCountdown)
                    .map(MarchSlotState::countdown)
                    .min(Duration::compareTo)
                    .orElse(null);
            BearSessionCoordinator.OwnRallyObservation own = observeOwnRally(
                    slots, specialRallyPreparing, trackedOwnSlot, mayAdoptExisting);
            return new BearSessionCoordinator.MarchSnapshot(true, freeSlots, own, earliestRelease);
        }

        private void rememberMarchSnapshot(MarchHelper.MarchQueueSnapshot snapshot, Instant observedAt) {
            lastMarches = List.copyOf(snapshot.slots());
            cachedSpecialRallyPreparing = snapshot.specialRallyPreparing();
            Duration refreshDelay = BearMarchRefreshPolicy.nextDelay(
                    lastMarches, cachedSpecialRallyPreparing, ownRallyBusyUntil, observedAt);
            nextMarchRefreshAt = observedAt.plus(refreshDelay);
        }

        private BearSessionCoordinator.OwnRallyObservation observeOwnRally(
                List<MarchSlotState> slots,
                boolean specialRallyPreparing,
                OptionalInt trackedOwnSlot,
                boolean mayAdoptExisting) {
            if (trackedOwnSlot.isEmpty()
                    && ownRallyBusyUntil != null
                    && ownRallyBusyUntil.isAfter(now())) {
                return BearSessionCoordinator.OwnRallyObservation.active(
                        0,
                        specialRallyPreparing
                                ? BearSessionCoordinator.OwnRallyPhase.PREPARING
                                : BearSessionCoordinator.OwnRallyPhase.RETURNING,
                        Duration.between(now(), ownRallyBusyUntil));
            }
            if (specialRallyPreparing) {
                return trackedOwnSlot.isPresent()
                        || ownRallyBusyUntil != null && ownRallyBusyUntil.isAfter(now())
                        ? BearSessionCoordinator.OwnRallyObservation.active(
                                0,
                                BearSessionCoordinator.OwnRallyPhase.PREPARING,
                                ownRallyBusyUntil != null && ownRallyBusyUntil.isAfter(now())
                                        ? Duration.between(now(), ownRallyBusyUntil)
                                        : null)
                        : BearSessionCoordinator.OwnRallyObservation.unclassifiedActive(0);
            }
            if (trackedOwnSlot.isPresent()) {
                if (trackedOwnSlot.getAsInt() == 0) {
                    if (ownRallyBusyUntil != null && now().isBefore(ownRallyBusyUntil)) {
                        return BearSessionCoordinator.OwnRallyObservation.active(
                                0,
                                BearSessionCoordinator.OwnRallyPhase.RETURNING,
                                Duration.between(now(), ownRallyBusyUntil));
                    }
                    ownRallyBusyUntil = null;
                    return BearSessionCoordinator.OwnRallyObservation.idle(0);
                }
                Optional<MarchSlotState> tracked = slots.stream()
                        .filter(slot -> slot.slot() == trackedOwnSlot.getAsInt())
                        .findFirst();
                if (tracked.isEmpty()) {
                    return BearSessionCoordinator.OwnRallyObservation.active(
                            trackedOwnSlot.getAsInt(), BearSessionCoordinator.OwnRallyPhase.UNKNOWN, null);
                }
                MarchSlotState slot = tracked.get();
                if (slot.isIdle()) {
                    return BearSessionCoordinator.OwnRallyObservation.idle(slot.slot());
                }
                if (slot.activityType() == MarchActivityType.RALLY) {
                    return BearSessionCoordinator.OwnRallyObservation.active(
                            slot.slot(), BearSessionCoordinator.OwnRallyPhase.PREPARING, slot.countdown());
                }
                if (slot.movementPhase() == MarchMovementPhase.RETURNING) {
                    return BearSessionCoordinator.OwnRallyObservation.active(
                            slot.slot(), BearSessionCoordinator.OwnRallyPhase.RETURNING,
                            slot.hasExactReleaseCountdown() ? slot.countdown() : null);
                }
                if (slot.availability() == MarchSlotAvailability.OCCUPIED) {
                    return BearSessionCoordinator.OwnRallyObservation.active(
                            slot.slot(), BearSessionCoordinator.OwnRallyPhase.OUTBOUND, null);
                }
                return BearSessionCoordinator.OwnRallyObservation.active(
                        slot.slot(), BearSessionCoordinator.OwnRallyPhase.UNKNOWN, null);
            }

            return BearSessionCoordinator.OwnRallyObservation.absent();
        }

        private OptionalInt awaitNewRallySlot() {
            long deadline = System.nanoTime()
                    + Duration.ofMillis(POST_DEPLOY_CONFIRMATION_TIMEOUT_MS).toNanos();
            BearNavigationPolicy.Screen postDeploy = observeBearScreen();
            if (!BearProductionScreens.WORLD.contains(postDeploy)) {
                logWarning(routineLogBearTrapLine(
                        "Own rally deployment left no stable World frame for confirmation"));
                return OptionalInt.empty();
            }
            do {
                checkPreemption();
                try {
                    MarchHelper.MarchQueueSnapshot after = readMarchSnapshot();
                    if (after.specialRallyPreparing()) {
                        return OptionalInt.of(0);
                    }
                    // The bot's own Bear rally is the independent Special row. An ordinary Rally
                    // march can be an allied join and must never confirm or be adopted as own.
                } catch (IllegalStateException e) {
                    logDebug(routineLogBearTrapLine(
                            "World was not ready for own-rally confirmation: " + e.getMessage()));
                }
                if (System.nanoTime() < deadline) {
                    ui.await(Duration.ofMillis(POST_DEPLOY_CONFIRMATION_POLL_MS),
                            frame -> false, "own-rally-march-confirmation-sample-cadence");
                }
            } while (System.nanoTime() < deadline);
            return OptionalInt.empty();
        }

        private boolean awaitNewOccupiedSlot(List<MarchSlotState> before) {
            long deadline = System.nanoTime()
                    + Duration.ofMillis(POST_DEPLOY_CONFIRMATION_TIMEOUT_MS).toNanos();
            if (!returnToWorldAfterJoin(deadline)) {
                logWarning(routineLogBearTrapLine(
                        "Joined rally left no stable World frame for march confirmation"));
                return false;
            }
            do {
                checkPreemption();
                try {
                    List<MarchSlotState> after = readMarchRows();
                    boolean confirmed = after.stream()
                            .filter(slot -> !slot.isIdle())
                            .anyMatch(slot -> before.stream()
                                    .filter(previous -> previous.slot() == slot.slot())
                                    .anyMatch(MarchSlotState::isIdle));
                    if (confirmed) {
                        return true;
                    }
                } catch (IllegalStateException e) {
                    logDebug(routineLogBearTrapLine(
                            "World was not ready for joined-rally confirmation: " + e.getMessage()));
                }
                if (System.nanoTime() < deadline) {
                    ui.await(Duration.ofMillis(POST_DEPLOY_CONFIRMATION_POLL_MS),
                            frame -> false, "joined-rally-march-confirmation-sample-cadence");
                }
            } while (System.nanoTime() < deadline);
            return false;
        }

        private boolean returnToWorldAfterJoin(long deadline) {
            do {
                BearNavigationPolicy.Screen screen = observeBearScreen();
                BearNavigationPolicy.Action action = BearNavigationPolicy.next(
                        screen, BearNavigationPolicy.Goal.WORLD_READY);
                switch (action) {
                    case READY -> {
                        classifier.rememberWarList(false);
                        return true;
                    }
                    case BACK_ONCE -> {
                        if (backToVerifiedParent(screen)) {
                            return true;
                        }
                    }
                    case WAIT_FOR_FRAME -> ui.await(
                            Duration.ofMillis(POST_DEPLOY_CONFIRMATION_POLL_MS),
                            frame -> frame.screen() != screen,
                            "return-to-world");
                    case FAIL_CLOSED -> {
                        return false;
                    }
                    default -> throw new IllegalStateException(
                            "Unexpected World-return action after Bear join: " + action);
                }
            } while (System.nanoTime() < deadline);
            return false;
        }

        private boolean openBearRallyFromAnchor() {
            for (int transition = 0; transition < 4; transition++) {
                BearNavigationPolicy.Screen screen = observeBearScreen();
                if (screen == BearNavigationPolicy.Screen.SPECIAL_BUILDINGS
                        && classifier.trapStatus(ui.current().frame(), trapNumber)
                                == BearFrameClassifier.TrapStatus.COOLDOWN) {
                    throw protectedFailure(
                            BearSessionExecutionException.FailureKind.FATAL_CONFIGURATION,
                            BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION,
                            "configured-trap-" + trapNumber + "-on-cooldown",
                            new IllegalStateException("Configured trap is on cooldown; a Go button is not activation"));
                }
                BearNavigationPolicy.Action action = BearNavigationPolicy.next(
                        screen, BearNavigationPolicy.Goal.OWN_RALLY,
                        BearNavigationPolicy.Phase.ACTIVE);
                switch (action) {
                    case READY -> {
                        return true;
                    }
                    case TAP_ACTIVE_BEAR_ICON -> {
                        BearVerifiedActionExecutor.Outcome opened = ui.transition(
                                BearUiAction.OPEN_ACTIVE_BEAR,
                                authorization -> tapTemplateFrom(
                                        authorization, BEAR_HUNT_IS_RUNNING, 90, "active-bear-icon"));
                        if (opened == BearVerifiedActionExecutor.Outcome.CONFIRMED) {
                            continue;
                        }
                        return false;
                    }
                    case ROUTE_TO_BEAR -> {
                        return false;
                    }
                    case TAP_CENTERED_BEAR -> {
                        if (ui.transition(BearUiAction.OPEN_CENTERED_BEAR, authorization -> {
                            if (!classifier.configuredBearCentered(authorization.frame())) {
                                throw new BearInputRefusedException("configured-bear-not-centered-in-authorizing-frame");
                            }
                            requireFreshAuthorization(authorization, "open-centered-bear");
                            tapInside(CommonGameAreas.BEAR_CENTER_BODY);
                        }) != BearVerifiedActionExecutor.Outcome.CONFIRMED) return false;
                    }
                    case BACK_ONCE -> {
                        if (!backToVerifiedParent(screen)) {
                            return false;
                        }
                    }
                    case FAIL_CLOSED -> {
                        return false;
                    }
                    case WAIT_FOR_FRAME -> {
                        continue;
                    }
                    case TAP_WAR, OPEN_ALLIANCE, OPEN_TERRITORY, OPEN_SPECIAL_BUILDINGS,
                            TAP_CONFIGURED_GO ->
                            throw new IllegalStateException("Unexpected Bear navigation action");
                }
            }
            return false;
        }

        private boolean openWarList() {
            for (int transition = 0; transition < 4; transition++) {
                BearNavigationPolicy.Screen screen = observeBearScreen();
                BearNavigationPolicy.Action action = BearNavigationPolicy.next(
                        screen, BearNavigationPolicy.Goal.WAR_LIST);
                switch (action) {
                    case READY -> {
                        return true;
                    }
                    case TAP_WAR -> {
                        classifier.rememberWarList(true);
                        BearVerifiedActionExecutor.Outcome opened = ui.transition(
                                BearUiAction.OPEN_WAR_LIST,
                                authorization -> tapTemplateFrom(
                                        authorization, RALLY_INDICATOR, 80, "rally-indicator"));
                        if (opened != BearVerifiedActionExecutor.Outcome.CONFIRMED) {
                            classifier.rememberWarList(false);
                            alliedRallyIndicatorAbsent = !ui.lastInputSent()
                                    && ui.lastRefusal().startsWith("rally-indicator-missing");
                            return false;
                        }
                        classifier.rememberWarList(true);
                        if (preserveRestoredListFrontier) {
                            preserveRestoredListFrontier = false;
                        } else {
                            rallyTraversal.reopenedAtTop();
                        }
                        rallyListBottomProven = false;
                        return true;
                    }
                    case BACK_ONCE -> {
                        if (!backToVerifiedParent(screen)) {
                            return false;
                        }
                    }
                    case FAIL_CLOSED, ROUTE_TO_BEAR, OPEN_ALLIANCE, OPEN_TERRITORY,
                            OPEN_SPECIAL_BUILDINGS, TAP_CONFIGURED_GO, TAP_ACTIVE_BEAR_ICON,
                            WAIT_FOR_FRAME -> {
                        return false;
                    }
                }
            }
            return false;
        }

        private BearRallyListTraversal.Observation rallyObservation(
                BearFrameStream.Snapshot<RawImageData> frame,
                List<BearRallyScanner.RallyRow> controls,
                boolean bottom) {
            return new BearRallyListTraversal.Observation(
                    frame.capturedAt(),
                    controls.stream().map(BearRallyListTraversal.Row::new).toList(),
                    bottom);
        }

        private boolean scrollRallyList(List<BearRallyScanner.RallyRow> beforeControls) {
            if (beforeControls.isEmpty()) {
                return false;
            }
            @SuppressWarnings("unchecked")
            List<BearRallyScanner.RallyRow>[] authorizingRows = new List[1];
            BearRallyScanner.RallyRow bottomAnchor = beforeControls.getLast();
            BearVerifiedActionExecutor.Outcome outcome = ui.transition(
                    BearUiAction.SCROLL_RALLY_LIST,
                    authorization -> {
                        // Read captain identity again from the exact swipe-authorizing frame.
                        List<BearRallyScanner.RallyRow> currentRows = rallyFrameScan.rows(authorization);
                        var anchor = new BearRallyListTraversal.Row(bottomAnchor);
                        boolean anchorUnchanged = currentRows.stream()
                                .filter(row -> anchor.sameIdentity(new BearRallyListTraversal.Row(row))).count() == 1
                                && !currentRows.isEmpty()
                                && anchor.sameIdentity(new BearRallyListTraversal.Row(currentRows.getLast()));
                        if (!anchorUnchanged) {
                            throw new BearInputRefusedException("scroll-anchor-not-in-authorizing-frame");
                        }
                        authorizingRows[0] = currentRows;
                        rallyTraversal.scrollAuthorized(new BearRallyListTraversal.Row(currentRows.getLast()));
                        requireFreshAuthorization(authorization, "scroll-rally-list");
                        swipe(RALLY_LIST_SCROLL_FROM, RALLY_LIST_SCROLL_TO);
                    },
                    "newer Rally-list frame after a bounded downward scroll",
                    frame -> frame.screen() == BearNavigationPolicy.Screen.WAR_LIST,
                    frame -> frame.screen() == BearNavigationPolicy.Screen.WAR_LIST,
                    0);
            if (outcome != BearVerifiedActionExecutor.Outcome.CONFIRMED || ui.current() == null) {
                return false;
            }
            List<BearRallyScanner.RallyRow> exactBefore = authorizingRows[0];
            BearFrameStream.Snapshot<RawImageData> immediateFrame = ui.current();
            List<BearRallyScanner.RallyRow> afterControls = rallyFrameScan.rows(immediateFrame);
            Optional<BearFrameStream.Snapshot<RawImageData>> settledFrame = ui.await(
                    Duration.ofMillis(750),
                    frame -> frame.screen() == BearNavigationPolicy.Screen.WAR_LIST
                            && !frame.capturedAt().isBefore(
                                    immediateFrame.capturedAt().plusMillis(250)),
                    "rally-list-scroll-settled-proof");
            if (settledFrame.isEmpty()) {
                return false;
            }
            List<BearRallyScanner.RallyRow> settledControls = rallyFrameScan.rows(settledFrame.orElseThrow());
            BearRallyListTraversal.Row anchor = new BearRallyListTraversal.Row(
                    exactBefore.getLast());
            boolean overlapProven = settledControls.stream()
                    .map(BearRallyListTraversal.Row::new)
                    .anyMatch(anchor::sameIdentity);
            if (!overlapProven) {
                return false;
            }
            rallyListBottomProven = BearRallyListTraversal.stableAbsoluteBottom(
                    exactBefore, afterControls, settledControls);
            return true;
        }

        private boolean reopenRallyListAtTop() {
            BearNavigationPolicy.Screen screen = observeBearScreen();
            if (screen != BearNavigationPolicy.Screen.WAR_LIST || !backToVerifiedParent(screen)) {
                return false;
            }
            rallyTraversal.reopenedAtTop();
            preserveRestoredListFrontier = false;
            rallyListBottomProven = false;
            return openWarList();
        }

        private boolean persistTactical(
                Instant sentAt,
                Instant returnDeadline,
                String joinSubstate,
                BearRallyListTraversal.Row row,
                String reason) {
            BearSessionCheckpoint.Checkpoint existing = BearSessionCheckpoint.load(profile)
                    .filter(checkpoint -> checkpoint.eventEnd().equals(eventEnd))
                    .orElse(null);
            Instant effectiveSentAt = sentAt != null
                    ? sentAt
                    : existing == null ? Instant.EPOCH : existing.ownRallySentAt();
            Instant effectiveDeadline = returnDeadline != null
                    ? returnDeadline
                    : existing == null ? Instant.EPOCH : existing.ownRallyReturnDeadline();
            boolean persisted = BearSessionCheckpoint.recordTactical(
                    profile,
                    eventEnd,
                    effectiveSentAt,
                    effectiveDeadline,
                    joinSubstate,
                    row == null ? 0L : row.control().identityFingerprint(),
                    row == null ? "NONE" : row.control().leaderText(),
                    row == null ? 0 : row.y(),
                    reason);
            if (!persisted) {
                logWarning(routineLogBearTrapLine(
                        "Could not persist Bear tactical recovery point: " + reason));
            }
            return persisted;
        }

        private void requireTacticalCheckpoint(
                Instant sentAt,
                Instant returnDeadline,
                String joinSubstate,
                BearRallyListTraversal.Row row,
                String reason) {
            if (!persistTactical(sentAt, returnDeadline, joinSubstate, row, reason)) {
                throw protectedFailure(
                        BearSessionExecutionException.FailureKind.PERSISTENCE,
                        BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION,
                        "tactical-checkpoint-failed-before-input-" + reason,
                        null);
            }
            // Keep same-process recovery equivalent to reconstruction from the checkpoint.
            // An input failure after this method must not be allowed to repeat an ambiguous tap.
            restoredJoinSubstate = joinSubstate;
            restoredJoinRow = row;
            rallyTraversal.checkpointWritten(joinSubstate, row);
            if (row != null && ("ROW_COMPLETED".equals(joinSubstate)
                    || "RECOVERED_PLUS_ABORTED".equals(joinSubstate))) {
                preserveRestoredListFrontier = true;
            }
        }

        private void tapTemplateFrom(
                BearFrameStream.Snapshot<RawImageData> authorization,
                TemplatesEnum template,
                int threshold,
                String operation) {
            AreaData searchArea = CommonGameAreas.bearClassifierSearchArea(template);
            ImageSearchResultData hit = emuManager.locatePattern(
                    EMULATOR_NUMBER, authorization.frame(), template,
                    searchArea.topLeft(), searchArea.bottomRight(), threshold);
            if (!hit.isFound()) {
                throw new BearInputRefusedException(operation + "-missing-in-authorizing-frame");
            }
            requireFreshAuthorization(authorization, operation);
            tapInside(hit);
        }

        private void requireFreshAuthorization(
                BearFrameStream.Snapshot<RawImageData> authorization,
                String operation) {
            if (!frames.isCurrent(
                    authorization,
                    BearVerifiedActionExecutor.MAXIMUM_AUTHORIZING_FRAME_AGE)) {
                throw new BearInputRefusedException(operation + "-authorization-aged-before-input");
            }
        }

        private void dispatchObservedInput(BearFrameStream.Snapshot<RawImageData> authorization,
                Runnable input) {
            long started = System.nanoTime();
            long entryAge = frames.age(authorization).toMillis();
            boolean completed = false;
            try {
                executeObservedInput(() -> authorizePhysicalInput(authorization), input);
                completed = true;
            } finally {
                // Write after the boundary so logging cannot age a previously authorized tap.
                // Completion is transport completion, never proof of game-side deployment.
                recordTransitionDiagnostic("dispatch-timing frame=" + authorization.sequence()
                        + " entryAgeMs=" + entryAge
                        + " boundaryMs=" + (System.nanoTime() - started) / 1_000_000
                        + " exitAgeMs=" + frames.age(authorization).toMillis()
                        + " boundaryCompleted=" + completed);
            }
        }

        private void authorizePhysicalInput(BearFrameStream.Snapshot<RawImageData> authorization) {
            checkPreemption();
            cleanupAuthorization.run();
            requireInputAllowed("observed-transition");
            if (Boolean.getBoolean("frostguard.bear.record")) {
                String recordingFailure = capture == null ? "recorder-missing" : capture.failureReason();
                if (recordingFailure == null && realtimeFrames.recordingFailure() != null) {
                    recordingFailure = "segment-write-failed";
                }
                if (recordingFailure != null) {
                    throw protectedFailure(BearSessionExecutionException.FailureKind.PERSISTENCE,
                            BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION,
                            "required-bear-recording-" + recordingFailure, null);
                }
            }
            requireFreshAuthorization(authorization, "physical-dispatch");
            if (ui.phase() != BearUiStateMachine.Phase.CLEANING_UP && !now().isBefore(eventEnd)) {
                throw new BearInputRefusedException("event-ended-before-dispatch");
            }
        }

        private boolean ensureRallyTimeSelected(int minutes) {
            BearFrameStream.Snapshot<RawImageData> current = ui.current();
            if (current == null || current.screen() != BearNavigationPolicy.Screen.RALLY_TIMER_PANEL) {
                current = ui.observe();
            }
            if (current.screen() != BearNavigationPolicy.Screen.RALLY_TIMER_PANEL) {
                return false;
            }
            if (DeploymentHelper.selectedBearRallySetTimeMinutes(
                    ImageConverter.toBufferedImage(current.frame())) == minutes) {
                return true;
            }
            int option = -1;
            for (int i = 0; i < CommonGameAreas.BEAR_RALLY_SET_TIME_MINUTES.length; i++) {
                if (CommonGameAreas.BEAR_RALLY_SET_TIME_MINUTES[i] == minutes) {
                    option = i;
                    break;
                }
            }
            if (option < 0) {
                return false;
            }
            AreaData checkbox = CommonGameAreas.BEAR_RALLY_SET_TIME_CHECKBOXES[option];
            return ui.transition(
                    BearUiAction.SELECT_RALLY_TIME,
                    authorization -> tapInside(checkbox),
                    "newer rally-timer frame with " + minutes + " minute green tick",
                    frame -> frame.screen() == BearNavigationPolicy.Screen.RALLY_TIMER_PANEL
                            && DeploymentHelper.selectedBearRallySetTimeMinutes(
                                    ImageConverter.toBufferedImage(frame.frame())) == minutes,
                    frame -> frame.screen() == BearNavigationPolicy.Screen.RALLY_TIMER_PANEL,
                    0) == BearVerifiedActionExecutor.Outcome.CONFIRMED;
        }

        private boolean ensureFormationSelected(int formation) {
            if (!FormationSlots.supports(formation) || formation > 8) {
                // High-slot selection requires a swipe transition and real right-end frame evidence.
                return false;
            }
            BearFrameStream.Snapshot<RawImageData> current = ui.current();
            if (current == null || current.screen() != BearNavigationPolicy.Screen.FORMATION) {
                current = ui.observe();
            }
            if (current.screen() != BearNavigationPolicy.Screen.FORMATION) {
                return false;
            }
            AreaData slot = RallyFlagCoordinates.selectionAreaForFlag(formation);
            if (FormationSelectionVerifier.isSelected(
                    ImageConverter.toBufferedImage(current.frame()), slot)) {
                return true;
            }
            PointData target = RallyFlagCoordinates.pointForFlag(formation);
            return ui.transition(
                    BearUiAction.SELECT_FORMATION,
                    authorization -> tapInside(target, target),
                    "newer formation frame with selected yellow outline for #" + formation,
                    frame -> frame.screen() == BearNavigationPolicy.Screen.FORMATION
                            && FormationSelectionVerifier.isSelected(
                                    ImageConverter.toBufferedImage(frame.frame()), slot),
                    frame -> frame.screen() == BearNavigationPolicy.Screen.FORMATION,
                    0) == BearVerifiedActionExecutor.Outcome.CONFIRMED;
        }

        /**
         * Selects the first configured normal formation that the current formation frame shows as
         * idle. The list is deliberately reconsidered from its beginning for every rally: an
         * earlier slot may have returned since the preceding join. Orange occupancy is read from a
         * fresh frame immediately before the selection tap; the game remains authoritative and may
         * still reject the deployment if the slot changes concurrently.
         */
        private int selectFirstAvailableFormation(List<Integer> formations) {
            for (int formation : formations) {
                if (!FormationSlots.supports(formation) || formation > 8) {
                    continue;
                }
                BearFrameStream.Snapshot<RawImageData> observed = ui.observe();
                if (observed.screen() != BearNavigationPolicy.Screen.FORMATION) {
                    return 0;
                }
                if (FormationOccupancyVerifier.isOccupied(
                        ImageConverter.toBufferedImage(observed.frame()), formation)) {
                    continue;
                }
                if (ensureFormationSelected(formation)) {
                    return formation;
                }
                BearFrameStream.Snapshot<RawImageData> afterFailure = ui.current();
                if (afterFailure == null
                        || afterFailure.screen() != BearNavigationPolicy.Screen.FORMATION) {
                    return 0;
                }
            }
            return 0;
        }

        private boolean backToVerifiedParent(BearNavigationPolicy.Screen source) {
            BearUiAction action = source == BearNavigationPolicy.Screen.DEPLOY_CONFIRMATION
                    || source == BearNavigationPolicy.Screen.MARCH_QUEUE_FULL
                    ? BearUiAction.DISMISS_DEPLOY_DIALOG
                    : BearUiAction.BACK_TO_PARENT;
            BearVerifiedActionExecutor.Outcome outcome = ui.transition(
                    action,
                    authorization -> {
                        if (authorization.screen() != source) {
                            throw new BearInputRefusedException("back-source-changed-before-dispatch");
                        }
                        if (action == BearUiAction.DISMISS_DEPLOY_DIALOG) {
                            pressBack();
                        } else if (source == BearNavigationPolicy.Screen.WAR_LIST) {
                            AreaData close = classifier.warListBack(authorization.frame())
                                    .orElseThrow(() -> new BearInputRefusedException(
                                            "back-war-rally-list-missing-in-authorizing-frame"));
                            requireFreshAuthorization(authorization, "back-war-rally-list");
                            tapInside(close);
                        } else if (source == BearNavigationPolicy.Screen.PET_SKILL_PANEL
                                || source == BearNavigationPolicy.Screen.PET_BATTLE_SELECTED
                                || source == BearNavigationPolicy.Screen.PET_BATTLE_ACTIVE) {
                            tapTemplateFrom(authorization, BEAR_PET_CLOSE, 95, "close-pet-skills");
                        } else if (source == BearNavigationPolicy.Screen.PET_CONFIRMATION) {
                            tapTemplateFrom(authorization, BEAR_PET_CANCEL, 95, "cancel-pet-confirmation");
                        } else {
                            pressBack();
                        }
                    },
                    "newer frame at the verified parent of " + source,
                    frame -> BearUiAction.confirmsBackFrom(source, frame.screen()),
                    frame -> frame.screen() == source,
                    0);
            if (source == BearNavigationPolicy.Screen.WAR_LIST
                    && outcome == BearVerifiedActionExecutor.Outcome.CONFIRMED) {
                classifier.rememberWarList(false);
            }
            return outcome == BearVerifiedActionExecutor.Outcome.CONFIRMED;
        }

        private boolean leaveVerifiedFormationScreen() {
            BearNavigationPolicy.Screen screen = observeBearScreen();
            if (screen == BearNavigationPolicy.Screen.DEPLOY_CONFIRMATION
                    || screen == BearNavigationPolicy.Screen.MARCH_QUEUE_FULL) {
                if (!backToVerifiedParent(screen)) return false;
                screen = observeBearScreen();
            }
            if (screen == BearNavigationPolicy.Screen.FORMATION) {
                return backToVerifiedParent(screen);
            }
            return screen == BearNavigationPolicy.Screen.WAR_LIST;
        }

        private boolean returnToWarListFromFormation() {
            BearNavigationPolicy.Screen screen = observeBearScreen();
            boolean backOnList = screen == BearNavigationPolicy.Screen.FORMATION
                    && backToVerifiedParent(screen)
                    && ui.current() != null
                    && ui.current().screen() == BearNavigationPolicy.Screen.WAR_LIST;
            classifier.rememberWarList(backOnList);
            return backOnList;
        }

        private boolean dismissDeployConfirmationAndLeaveFormation() {
            // The live Bear capacity warning must never trigger Equalize or alter the configured
            // flag. Back dismisses the proven dialog; a second verified transition leaves formation.
            BearNavigationPolicy.Screen screen = observeBearScreen();
            if (screen == BearNavigationPolicy.Screen.DEPLOY_CONFIRMATION) {
                if (!backToVerifiedParent(screen)) return false;
            }
            if (observeBearScreen() == BearNavigationPolicy.Screen.FORMATION) {
                return returnToWarListFromFormation();
            }
            return ui.current() != null
                    && ui.current().screen() == BearNavigationPolicy.Screen.WAR_LIST;
        }

        private BearNavigationPolicy.Screen observeBearScreen() {
            checkPreemption();
            lastObservedFrame = ui.observe();
            return lastObservedFrame.screen();
        }

        private BearNavigationPolicy.Screen classifyBearScreen(RawImageData frame) {
            classificationStartedAt = Instant.now();
            BearFrameClassifier.Classification classification = classifier.classify(frame);
            classificationNanos = classification.totalNanos();
            classificationTemplateNanos.clear();
            classificationTemplateNanos.putAll(classification.templateNanos());
            if (classification.screen() == BearNavigationPolicy.Screen.SPECIAL_BUILDINGS) {
                logDebug(routineLogBearTrapLine("Observed configured trap=" + trapNumber
                        + " status=" + classification.configuredTrapStatus()
                        + "; Go availability does not establish activation"));
            }
            return classification.screen();
        }

        /**
         * Evidence-only session: owns the event window and records every ordered frame with its
         * classification and timing, but sends no input. It exists to collect the real frames and
         * latency data that templates, areas, and Bear centring still lack.
         */
        BearSessionCoordinator.ExitReason observeOnlyUntil(Instant end) {
            logWarning(routineLogBearTrapLine("Observe-only Bear session: no input will be sent; recording to "
                    + (capture == null ? "nowhere (capture unavailable)" : capture.directory())));
            DiagnosticSnapshotStore snapshots = DiagnosticSnapshotStore.forCurrentWorkspace();
            BearNavigationPolicy.Screen previous = null;
            while (now().isBefore(end)) {
                if (cancellationRequested()) {
                    return BearSessionCoordinator.ExitReason.CANCELLED;
                }
                ui.phase(now().isBefore(end.minus(BearEventWindow.EVENT_DURATION))
                        ? BearUiStateMachine.Phase.WAITING_FOR_ACTIVATION
                        : BearUiStateMachine.Phase.ACTIVE);
                BearFrameStream.Snapshot<RawImageData> frame = ui.observe();
                BearObservationProbe.inspect(frames, frame,
                        observed -> rallyFrameScan.rows(observed).size(), System::nanoTime,
                        this::recordTransitionDiagnostic);
                if (frame.screen() != previous) {
                    snapshots.write(frame.frame(), "bear-observe", frame.screen().name(), frame.capturedAt());
                    previous = frame.screen();
                }
            }
            return BearSessionCoordinator.ExitReason.EVENT_ENDED;
        }

        private void recordObservedFrame(BearFrameStream.Snapshot<RawImageData> frame, boolean classified) {
                if (capture != null) {
                    String recordingFailure = realtimeFrames.recordingFailure();
                    if (recordingFailure != null && realtimeFrames.segment() > failedRecordingSegment) {
                        failedRecordingSegment = realtimeFrames.segment();
                        capture.recordingFailed(failedRecordingSegment, recordingFailure);
                        logWarning(routineLogBearTrapLine("Bear capture segment " + failedRecordingSegment
                                + " stopped recording (" + recordingFailure + "); still observing, "
                                + "the next segment retries"));
                    }
                    BearRealtimeFrameSource.FrameOrigin origin = realtimeFrames.lastOrigin();
                    String image = origin != null
                            && origin.kind() == BearRealtimeFrameSource.FrameOrigin.Kind.SCREENCAP
                            ? capture.staticScreenshot(frame.frame()) : null;
                    Duration transportAge = Duration.between(frame.capturedAt(),
                            classified ? classificationStartedAt : Instant.now());
                    capture.observed(frame.sequence(), origin, image, frame.capturedAt(),
                            transportAge.isNegative() ? Duration.ZERO : transportAge,
                            frame.screen(), classified ? classificationNanos : 0,
                            classified ? Map.copyOf(classificationTemplateNanos) : Map.of());
                }
        }

        private boolean recoverCapture(RuntimeException failure, int failedAttempt) {
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            return !containsAdbFailure(failure) || failedAttempt < 3;
        }

        private boolean containsAdbFailure(Throwable failure) {
            Throwable current = failure;
            while (current != null) {
                if (current instanceof ADBConnectionException) {
                    return true;
                }
                current = current.getCause();
            }
            return false;
        }

        private boolean validRecoveryDestination(
                BearSessionCoordinator.State resumeState,
                BearNavigationPolicy.Screen screen) {
            return switch (resumeState) {
                case LOCATE_BEAR, OWN_RALLY_REQUIRED, OWN_RALLY_STARTING,
                        OWN_RALLY_ACTIVE, RECOVER_TO_KNOWN_SCREEN ->
                        screen == BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY
                                || screen == BearNavigationPolicy.Screen.WORLD_AT_CONFIGURED_BEAR
                                || screen == BearNavigationPolicy.Screen.BEAR_RALLY_PANEL;
                case FILL_JOIN_SLOTS, WAIT_FOR_NEXT_USEFUL_DEADLINE ->
                        screen == BearNavigationPolicy.Screen.WORLD
                                || screen == BearNavigationPolicy.Screen.WORLD_AT_CONFIGURED_BEAR
                                || screen == BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY
                                || screen == BearNavigationPolicy.Screen.WAR_LIST;
                case FINISHED, CANCELLED -> false;
            };
        }

    }

private void cleanupFlow(boolean requeueNormalTasks) {
        logInfo(routineLogBearTrapLine("Cleaning up Bear Trap state"));

        if (requeueNormalTasks) {
            requeueDisabledTasksFlow();
        } else {
            logInfo(routineLogBearTrapLine("Gather and Autojoin remain stopped while Bear protection is retained"));
        }
    }
}
