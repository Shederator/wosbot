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
import dev.frostguard.engine.error.ADBConnectionException;
import dev.frostguard.engine.error.BearSessionExecutionException;
import dev.frostguard.engine.error.StopExecutionException;
import dev.frostguard.engine.helper.BearTrapHelper;
import dev.frostguard.engine.helper.DeploymentPostTapRead;
import dev.frostguard.engine.helper.DeploymentHelper;
import dev.frostguard.engine.helper.DeploymentPreflightRead;
import dev.frostguard.engine.helper.FormationSelectionVerifier;
import dev.frostguard.engine.helper.MarchHelper;
import dev.frostguard.engine.helper.TemplateSearchHelper;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.BearSessionCheckpoint;
import dev.frostguard.engine.schedule.BearTrapParticipationSchedule;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.RallyFlagCoordinates;
import dev.frostguard.engine.nav.SidebarFrameClassifier;
import dev.frostguard.engine.nav.SidebarSection;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.vision.convert.ImageConverter;
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

public class BearTrapRoutine extends DelayedTask {

private List<Integer> joinFlags = new ArrayList<>();

private static final int TRAP_DURATION_MINUTES_VALUE = 30;

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

private static final PointData PET_RAZORBACK_TL_VALUE = new PointData(100, 410);

private static final PointData PET_RAZORBACK_BR_VALUE = new PointData(160, 460);

private static final PointData PET_QUICK_USE_BUTTON_TL_VALUE = new PointData(120, 1070);

private static final PointData PET_QUICK_USE_BUTTON_BR_VALUE = new PointData(280, 1100);

private static final PointData PET_USE_BUTTON_TL_VALUE = new PointData(460, 800);

private static final PointData PET_USE_BUTTON_BR_VALUE = new PointData(550, 830);

private static final PointData AUTOJOIN_BUTTON_TL_VALUE = new PointData(260, 1200);

private static final PointData AUTOJOIN_BUTTON_BR_VALUE = new PointData(450, 1240);

private static final PointData AUTOJOIN_STOP_BUTTON_TL_VALUE = new PointData(120, 1070);

private static final PointData AUTOJOIN_STOP_BUTTON_BR_VALUE = new PointData(240, 1110);

private static final PointData RECALL_CONFIRM_BUTTON_TL_VALUE = new PointData(446, 780);

private static final PointData RECALL_CONFIRM_BUTTON_BR_VALUE = new PointData(578, 800);

private static final int DEFAULT_TRAP_NUMBER_VALUE = 1;

private static final int DEFAULT_PREPARATION_TIME_MINUTES_MS = 10;

private static final int DEFAULT_OWN_RALLY_FLAG_VALUE = 1;

private static final int DEFAULT_JOIN_RALLY_FLAG_VALUE = 1;

private static final long FRESH_TRANSITION_TIMEOUT_MS = 1500;

private static final long NAVIGATION_TRANSITION_TIMEOUT_MS = 4_000;

private static final long POST_DEPLOY_CONFIRMATION_TIMEOUT_MS = 5_000;

private static final long POST_DEPLOY_CONFIRMATION_POLL_MS = 100;

private static final int TERRITORY_TRANSITION_ATTEMPTS = 2;

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


        if (!confirmExecutionWindow()) {
            deferToNextWindow();
            return;
        }


        TrapTimingShape timing = null;
        LiveBearSessionDriver sessionDriver = null;
        BearSessionExecutionException sessionFailure = null;
        try {
            timing = computeTrapTiming();
            logTrapTimingFlow(timing);

            LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
            sessionDriver = new LiveBearSessionDriver(
                    timing.endTime.atZone(ZoneId.of("UTC")).toInstant());

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
            if (sessionDriver != null) {
                sessionDriver.cleanup(activeSessionExit, resumeNormalTasks);
            } else {
                cleanupFlow(resumeNormalTasks);
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


        this.joinFlags = decodeJoinFlags();
        if (callOwnRally && joinFlags.removeIf(flag -> flag == ownRallyFlag)) {
            logWarning(routineLogBearTrapLine(
                    "Formation #" + ownRallyFlag
                            + " is reserved for the configured own rally and will not be used to join."));
        }
        if (joinRally && joinFlags.isEmpty()) {
            logWarning(routineLogBearTrapLine(
                    "Rally joining disabled for this run because no configured join formation remains available."));
            joinRally = false;
        }
        // Changed by pernerch | Date: 2026-07-02 | Why: resolve shared-emulator state at hydration for deterministic active-phase behavior.
        this.sharedEmulator = isSharedEmulatorProfile();


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


        if (flags.isEmpty()) {
            flags.add(DEFAULT_JOIN_RALLY_FLAG_VALUE);
        }


        return new ArrayList<>(flags);
    }

private final class LiveBearSessionDriver implements BearSessionCoordinator.Driver {

        private final Instant eventEnd;
        private List<MarchSlotState> lastMarches = List.of();
        private BearSessionCoordinator.State lastState;
        private Instant ownRallyBusyUntil;
        private final Map<Integer, Long> formationTroopCounts = new HashMap<>();
        private final Map<Integer, Set<String>> rejectedCandidatesByFormation = new HashMap<>();
        private boolean warListKnown;
        private Instant nextMarchRefreshAt = Instant.MIN;
        private boolean cachedSpecialRallyPreparing;
        private final BearFrameStream<RawImageData> frames;
        private final BearUiStateMachine<RawImageData> ui;
        private final TemplateSearchHelper sessionSearch;
        private final MarchHelper sessionMarchHelper;
        private final DeploymentHelper sessionDeploymentHelper;
        private BearFrameStream.Snapshot<RawImageData> lastObservedFrame;
        private int consecutiveRecoveryFailures;

        private LiveBearSessionDriver(Instant eventEnd) {
            this.eventEnd = eventEnd;
            this.frames = new BearFrameStream<>(
                    () -> emuManager.captureScreen(EMULATOR_NUMBER),
                    this::classifyBearScreen,
                    () -> Thread.currentThread().isInterrupted(),
                    this::recoverCapture,
                    3,
                    Clock.systemUTC());
            this.ui = new BearUiStateMachine<>(
                    frames, Duration.ofSeconds(4), this::recordTransitionDiagnostic);
            this.sessionSearch = new TemplateSearchHelper(
                    emuManager,
                    EMULATOR_NUMBER,
                    profile,
                    () -> frames.nextUnclassified().frame());
            this.sessionSearch.setPreemptionCheck(BearTrapRoutine.this::checkPreemption);
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
            prepareFrameDriven();
            ui.phase(BearUiStateMachine.Phase.WAITING_FOR_ACTIVATION);
            while (now().isBefore(activation)) {
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
            beginActivePhase();
        }

        private void prepareFrameDriven() {
            BearFrameStream.Snapshot<RawImageData> observed = ui.observe();
            if (observed.screen() == BearNavigationPolicy.Screen.RECONNECT) {
                throw protectedFailure(
                        BearSessionExecutionException.FailureKind.RECONNECT_SCREEN,
                        BearSessionExecutionException.RecoveryDirective.RESTART_APP,
                        "prepare-entry",
                        null);
            }
            if (Boolean.TRUE.equals(profile.getConfig(
                    ConfigurationKeyEnum.ALLIANCE_AUTOJOIN_BOOL, Boolean.class))) {
                requireLiveEvidence("autojoin panel and stopped-state frames", "disable-autojoin");
            }
            if (recallTroops) {
                requireLiveEvidence("march-sidebar and recall-confirmation frames", "recall-troops");
            }
            if (usePets) {
                requireLiveEvidence("pet overview, Razorback, quick-use and activated-benefit frames",
                        "activate-pet");
            }
            // Territory and Trap 2 have no positive live identity in the repository yet. Refuse the
            // old coordinate script; an active-event run can still use the verified red Bear icon.
            requireLiveEvidence("Territory, Special Buildings, configured Trap and at-Bear frames",
                    "navigate-to-configured-bear");
        }

        private void requireLiveEvidence(String evidence, String operation) {
            throw protectedFailure(
                    BearSessionExecutionException.FailureKind.FATAL_CONFIGURATION,
                    BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION,
                    operation,
                    new IllegalStateException("Missing positive live evidence: " + evidence));
        }

        private void cleanup(
                BearSessionCoordinator.ExitReason exit,
                boolean resumeNormalTasks) {
            ui.phase(BearUiStateMachine.Phase.CLEANING_UP);
            if (resumeNormalTasks && !verifyTerminalUiCleanup()) {
                throw protectedFailure(
                        BearSessionExecutionException.FailureKind.VISUAL_UNKNOWN,
                        BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION,
                        "terminal-cleanup-not-verified",
                        null);
            }
            cleanupFlow(resumeNormalTasks);
            BearUiStateMachine.TerminalReason terminal = switch (exit == null
                    ? BearSessionCoordinator.ExitReason.UNRECOVERABLE_FAILURE : exit) {
                case EVENT_ENDED -> BearUiStateMachine.TerminalReason.EVENT_ENDED;
                case CANCELLED -> BearUiStateMachine.TerminalReason.CANCELLED;
                case UNRECOVERABLE_FAILURE -> BearUiStateMachine.TerminalReason.RECOVERY_EXHAUSTED;
            };
            ui.terminate(terminal);
        }

        private boolean verifyTerminalUiCleanup() {
            for (int attempt = 0; attempt < 4; attempt++) {
                BearNavigationPolicy.Screen screen = observeBearScreen();
                if (screen == BearNavigationPolicy.Screen.WORLD
                        || screen == BearNavigationPolicy.Screen.WORLD_AT_BEAR
                        || screen == BearNavigationPolicy.Screen.WORLD_AT_VERIFIED_BEAR
                        || screen == BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY) {
                    return true;
                }
                if (screen == BearNavigationPolicy.Screen.RECONNECT
                        || screen == BearNavigationPolicy.Screen.APP_LOADING
                        || screen == BearNavigationPolicy.Screen.TRANSITIONING) {
                    ui.await(Duration.ofSeconds(1), frame -> frame.screen() != screen,
                            "terminal-cleanup-wait");
                    continue;
                }
                if (!BearUiAction.BACK_TO_PARENT.legalFrom(screen)
                        && !BearUiAction.DISMISS_DEPLOY_DIALOG.legalFrom(screen)) {
                    return false;
                }
                if (!backToVerifiedParent(screen)) {
                    return false;
                }
            }
            return false;
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
            List<MarchSlotState> before = lastMarches;
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
                backToVerifiedParent(BearNavigationPolicy.Screen.FORMATION);
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
                backToVerifiedParent(BearNavigationPolicy.Screen.FORMATION);
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.FORMATION_UNAVAILABLE);
            }

            long travelSeconds = preflight.deployment().travelTimeSeconds();
            if (travelSeconds <= 0) {
                backToVerifiedParent(BearNavigationPolicy.Screen.FORMATION);
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.OCR_MISS);
            }
            if (now().plusSeconds(rallySeconds + travelSeconds).isAfter(eventEnd)) {
                backToVerifiedParent(BearNavigationPolicy.Screen.FORMATION);
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.TOO_LATE);
            }

            BearVerifiedActionExecutor.Outcome deployOutcome = ui.transition(
                    BearUiAction.DEPLOY_OWN_RALLY,
                    authorization -> tapTemplateFrom(
                            authorization, BEAR_DEPLOY_BUTTON, 90, "deploy-own-rally"));
            if (deployOutcome != BearVerifiedActionExecutor.Outcome.CONFIRMED) {
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.DEPLOY_NOT_CONFIRMED);
            }
            DeploymentPostTapRead postTap = sessionDeploymentHelper.readPostTapScreen(
                    ui.current().frame());
            if (postTap.marchQueueFull()) {
                backToVerifiedParent(BearNavigationPolicy.Screen.MARCH_QUEUE_FULL);
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.MARCH_QUEUE_FULL);
            }
            if (postTap.sameTargetDialog()) {
                leaveVerifiedFormationScreen();
                recover(BearSessionCoordinator.State.OWN_RALLY_STARTING);
                MarchHelper.MarchQueueSnapshot queue = readMarchSnapshot();
                OptionalInt existing = queue.specialRallyPreparing()
                        ? OptionalInt.of(0)
                        : existingRallySlot(queue.slots());
                if (existing.isPresent()) {
                    ownRallyBusyUntil = now().plus(Duration.ofMinutes(5).plusSeconds(30));
                }
                return existing.isPresent()
                        ? BearSessionCoordinator.OwnRallyStartResult.alreadyActive(existing.getAsInt())
                        : BearSessionCoordinator.OwnRallyStartResult.recoverable(
                                 BearSessionCoordinator.OwnRallyStartOutcome.DEPLOY_NOT_CONFIRMED);
            }
            if (postTap.confirmationDialog().isFound()) {
                dismissDeployConfirmationAndLeaveFormation();
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.DEPLOY_NOT_CONFIRMED);
            }
            if (postTap.deployButton().isFound()) {
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.DEPLOY_NOT_CONFIRMED);
            }

            OptionalInt newRally = awaitNewRallySlot(before);
            if (newRally.isPresent()) {
                ownRallyBusyUntil = now()
                        .plusSeconds(rallySeconds + travelSeconds * 2)
                        .plusSeconds(2);
                logInfo(routineLogBearTrapLine(newRally.getAsInt() == 0
                        ? "Own rally confirmed in the Bear Special row"
                        : "Own rally confirmed in march slot #" + newRally.getAsInt()));
                return BearSessionCoordinator.OwnRallyStartResult.confirmed(
                        newRally.getAsInt(), Duration.ofSeconds(rallySeconds), Duration.ofSeconds(travelSeconds));
            }
            return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                    BearSessionCoordinator.OwnRallyStartOutcome.DEPLOY_NOT_CONFIRMED);
        }

        @Override
        public BearSessionCoordinator.JoinOutcome joinNext(int formation) {
            List<MarchSlotState> before = List.copyOf(lastMarches);
            try {
                if (!openWarList()) {
                    return BearSessionCoordinator.JoinOutcome.PAGE_NOT_READY;
                }

                long knownTroops = formationTroopCounts.getOrDefault(formation, 0L);
                Set<String> rejected = rejectedCandidatesByFormation.computeIfAbsent(
                        formation, ignored -> new HashSet<>());
                BearFrameStream.Snapshot<RawImageData> scanFrame = ui.observe();
                if (scanFrame.screen() != BearNavigationPolicy.Screen.WAR_LIST) {
                    return BearSessionCoordinator.JoinOutcome.PAGE_NOT_READY;
                }
                BearRallyScanner.ScanResult scan = new BearRallyScanner(
                        sessionSearch, scanFrame.frame()).scan(scanFrame.capturedAt());
                Optional<BearRallyCandidate> selected = BearRallyCandidateSelector.selectBest(
                        scan.candidates(), knownTroops, rejected);
                if (selected.isEmpty()) {
                    return scan.ocrFailure()
                            ? BearSessionCoordinator.JoinOutcome.OCR_MISS
                            : BearSessionCoordinator.JoinOutcome.NO_JOINABLE_RALLY;
                }
                BearRallyCandidate candidate = selected.get();
                BearFrameStream.Snapshot<RawImageData> authorizationFrame = ui.observe();
                if (authorizationFrame.screen() != BearNavigationPolicy.Screen.WAR_LIST) {
                    return BearSessionCoordinator.JoinOutcome.RALLY_DEPARTED;
                }
                BearRallyScanner.ScanResult authorizationScan = new BearRallyScanner(
                        sessionSearch, authorizationFrame.frame()).scan(authorizationFrame.capturedAt());
                Instant authorizationTime = authorizationFrame.capturedAt();
                Optional<BearRallyCandidate> authorizedCandidate =
                        BearRallyCandidateSelector.reauthorize(
                                candidate,
                                authorizationScan.candidates(),
                                knownTroops,
                                authorizationTime,
                                Duration.ofSeconds(2));
                if (authorizedCandidate.isEmpty()) {
                    rejected.add(candidate.stableKey());
                    return BearSessionCoordinator.JoinOutcome.RALLY_DEPARTED;
                }
                candidate = authorizedCandidate.get();
                warListKnown = false;
                AreaData authorizedJoinButton = candidate.joinButtonArea();
                if (ui.transition(
                        BearUiAction.OPEN_JOIN_FORMATION,
                        authorization -> tapInside(authorizedJoinButton))
                        != BearVerifiedActionExecutor.Outcome.CONFIRMED) {
                    rejected.add(candidate.stableKey());
                    return BearSessionCoordinator.JoinOutcome.RALLY_DEPARTED;
                }
                if (!ensureFormationSelected(formation)) {
                    returnToWarListFromFormation();
                    return BearSessionCoordinator.JoinOutcome.FORMATION_UNAVAILABLE;
                }
                BearFrameStream.Snapshot<RawImageData> preflightFrame = ui.observe();
                if (preflightFrame.screen() != BearNavigationPolicy.Screen.FORMATION) {
                    return BearSessionCoordinator.JoinOutcome.STALE_SCREEN;
                }
                DeploymentPreflightRead joinPreflight = sessionDeploymentHelper.readPreflightScreen(
                        preflightFrame.frame(), DeploymentHelper.MAX_RALLY_STAMINA_COST);
                if (joinPreflight.noDeployableTroops()) {
                    returnToWarListFromFormation();
                    return BearSessionCoordinator.JoinOutcome.FORMATION_UNAVAILABLE;
                }
                long selectedTroops = sessionDeploymentHelper.readSelectedTroopCount(
                        preflightFrame.frame());
                if (selectedTroops < 0) {
                    returnToWarListFromFormation();
                    return BearSessionCoordinator.JoinOutcome.OCR_MISS;
                }
                formationTroopCounts.put(formation, selectedTroops);
                if (!candidate.accepts(selectedTroops, now())) {
                    logInfo(routineLogBearTrapLine(
                            "Skipping rally at row " + candidate.rowY()
                                    + ": formation #" + formation + " no longer has a safe deploy "
                                    + "window or its " + selectedTroops + " troops no longer fit the "
                                    + candidate.remainingCapacity() + " remaining capacity"));
                    rejected.add(candidate.stableKey());
                    returnToWarListFromFormation();
                    return BearSessionCoordinator.JoinOutcome.RALLY_FULL;
                }
                if (ui.transition(
                        BearUiAction.DEPLOY_JOIN,
                        authorization -> tapTemplateFrom(
                                authorization, BEAR_DEPLOY_BUTTON, 90, "deploy-joined-rally"))
                        != BearVerifiedActionExecutor.Outcome.CONFIRMED) {
                    rejected.add(candidate.stableKey());
                    return BearSessionCoordinator.JoinOutcome.RALLY_GONE;
                }
                DeploymentPostTapRead postTap = sessionDeploymentHelper.readPostTapScreen(
                        ui.current().frame());
                if (postTap.marchQueueFull()) {
                    backToVerifiedParent(BearNavigationPolicy.Screen.MARCH_QUEUE_FULL);
                    return BearSessionCoordinator.JoinOutcome.MARCH_QUEUE_FULL;
                }
                if (postTap.sameTargetDialog()) {
                    leaveVerifiedFormationScreen();
                    rejected.add(candidate.stableKey());
                    return BearSessionCoordinator.JoinOutcome.ALREADY_JOINED_OR_MARCHING;
                }
                if (postTap.confirmationDialog().isFound()) {
                    dismissDeployConfirmationAndLeaveFormation();
                    rejected.add(candidate.stableKey());
                    return BearSessionCoordinator.JoinOutcome.RALLY_FULL;
                }
                if (postTap.deployButton().isFound()) {
                    rejected.add(candidate.stableKey());
                    return BearSessionCoordinator.JoinOutcome.RALLY_FULL;
                }

                // A successful Bear join returns to the War list. Preserve that proven route so
                // confirmation can go back exactly once before reading the World-only march UI.
                warListKnown = true;
                if (awaitNewOccupiedSlot(before)) {
                    return BearSessionCoordinator.JoinOutcome.JOINED;
                }
                rejected.add(candidate.stableKey());
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

        @Override
        public boolean recover(BearSessionCoordinator.State resumeState) {
            boolean recovered = recoverOnce(resumeState);
            if (recovered) {
                consecutiveRecoveryFailures = 0;
                return true;
            }
            consecutiveRecoveryFailures++;
            if (consecutiveRecoveryFailures >= 3) {
                throw protectedFailure(
                        BearSessionExecutionException.FailureKind.VISUAL_UNKNOWN,
                        BearSessionExecutionException.RecoveryDirective.DEGRADED_WAIT,
                        "recover-budget-" + resumeState,
                        null);
            }
            return false;
        }

        private boolean recoverOnce(BearSessionCoordinator.State resumeState) {
            try {
                ui.phase(BearUiStateMachine.Phase.RECOVERING);
                BearNavigationPolicy.Screen screen = observeBearScreen();
                if (screen == BearNavigationPolicy.Screen.FORMATION
                        || screen == BearNavigationPolicy.Screen.RALLY_TIMER_PANEL
                        || screen == BearNavigationPolicy.Screen.BEAR_RALLY_PANEL
                        || screen == BearNavigationPolicy.Screen.ALLIANCE_MENU
                        || screen == BearNavigationPolicy.Screen.WAR_LIST) {
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
                    if (screen == BearNavigationPolicy.Screen.APP_LOADING
                            || screen == BearNavigationPolicy.Screen.TRANSITIONING) {
                        boolean recovered = ui.await(
                                Duration.ofSeconds(2),
                                frame -> frame.screen() != BearNavigationPolicy.Screen.APP_LOADING
                                        && frame.screen() != BearNavigationPolicy.Screen.TRANSITIONING
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
                throw protectedFailure(
                        BearSessionExecutionException.FailureKind.VISUAL_UNKNOWN,
                        BearSessionExecutionException.RecoveryDirective.DEGRADED_WAIT,
                        "recover-" + resumeState,
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
                if (screen != BearNavigationPolicy.Screen.WORLD
                        && screen != BearNavigationPolicy.Screen.WORLD_AT_BEAR
                        && screen != BearNavigationPolicy.Screen.WORLD_AT_VERIFIED_BEAR
                        && screen != BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY) {
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
            if (specialRallyPreparing) {
                return trackedOwnSlot.isPresent()
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

            if (mayAdoptExisting) {
                Optional<MarchSlotState> existing = slots.stream()
                        .filter(slot -> slot.activityType() == MarchActivityType.RALLY)
                        .findFirst();
                if (existing.isPresent()) {
                    MarchSlotState slot = existing.get();
                    return BearSessionCoordinator.OwnRallyObservation.unclassifiedActive(slot.slot());
                }
            }
            return BearSessionCoordinator.OwnRallyObservation.absent();
        }

        private OptionalInt existingRallySlot(List<MarchSlotState> slots) {
            return slots.stream()
                    .filter(slot -> slot.activityType() == MarchActivityType.RALLY)
                    .mapToInt(MarchSlotState::slot)
                    .findFirst();
        }

        private OptionalInt newlyOccupiedRallySlot(
                List<MarchSlotState> before, List<MarchSlotState> after) {
            return after.stream()
                    .filter(slot -> slot.activityType() == MarchActivityType.RALLY)
                    .filter(slot -> before.stream()
                            .filter(previous -> previous.slot() == slot.slot())
                            .noneMatch(previous -> previous.activityType() == MarchActivityType.RALLY))
                    .mapToInt(MarchSlotState::slot)
                    .findFirst();
        }

        private OptionalInt awaitNewRallySlot(List<MarchSlotState> before) {
            long deadline = System.nanoTime()
                    + Duration.ofMillis(POST_DEPLOY_CONFIRMATION_TIMEOUT_MS).toNanos();
            BearNavigationPolicy.Screen postDeploy = observeBearScreen();
            if (postDeploy != BearNavigationPolicy.Screen.WORLD
                    && postDeploy != BearNavigationPolicy.Screen.WORLD_AT_BEAR
                    && postDeploy != BearNavigationPolicy.Screen.WORLD_AT_VERIFIED_BEAR
                    && postDeploy != BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY) {
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
                    OptionalInt confirmed = newlyOccupiedRallySlot(before, after.slots());
                    if (confirmed.isPresent()) {
                        return confirmed;
                    }
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
                        warListKnown = false;
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
                BearNavigationPolicy.Action action = BearNavigationPolicy.next(
                        screen, BearNavigationPolicy.Goal.OWN_RALLY,
                        BearNavigationPolicy.Phase.ACTIVE);
                switch (action) {
                    case READY -> {
                        return true;
                    }
                    case TAP_BEAR_ANCHOR -> {
                        BearVerifiedActionExecutor.Outcome outcome = ui.transition(
                                BearUiAction.OPEN_BEAR_PANEL,
                                authorization -> tapInside(BEAR_CENTER_POINT_VALUE, BEAR_CENTER_POINT_VALUE),
                                frame -> frame.screen() == BearNavigationPolicy.Screen.WORLD_AT_BEAR
                                        || frame.screen() == BearNavigationPolicy.Screen.WORLD_AT_VERIFIED_BEAR,
                                1);
                        if (outcome == BearVerifiedActionExecutor.Outcome.CONFIRMED) {
                            warListKnown = false;
                            return true;
                        }
                    }
                    case TAP_ACTIVE_BEAR_ICON -> {
                        BearVerifiedActionExecutor.Outcome arrived = ui.transition(
                                BearUiAction.OPEN_ACTIVE_BEAR,
                                authorization -> tapTemplateFrom(
                                        authorization, BEAR_HUNT_IS_RUNNING, 90, "active-bear-icon"));
                        if (arrived != BearVerifiedActionExecutor.Outcome.CONFIRMED) {
                            return false;
                        }
                        BearVerifiedActionExecutor.Outcome opened = ui.transition(
                                BearUiAction.OPEN_BEAR_PANEL,
                                authorization -> tapInside(BEAR_CENTER_POINT_VALUE, BEAR_CENTER_POINT_VALUE));
                        if (opened == BearVerifiedActionExecutor.Outcome.CONFIRMED) {
                            return true;
                        }
                    }
                    case ROUTE_TO_BEAR -> {
                        return false;
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
            // The game does not reliably reorder an already-open War list. Every join attempt must
            // return to World and tap the red rally indicator so the newest joinable rally is on top.
            for (int transition = 0; transition < 4; transition++) {
                BearNavigationPolicy.Screen screen = observeBearScreen();
                BearNavigationPolicy.Action action = BearNavigationPolicy.next(
                        screen, BearNavigationPolicy.Goal.FRESH_WAR_LIST);
                switch (action) {
                    case READY -> {
                        return true;
                    }
                    case TAP_WAR -> {
                        warListKnown = true;
                        BearVerifiedActionExecutor.Outcome opened = ui.transition(
                                BearUiAction.OPEN_WAR_LIST,
                                authorization -> tapTemplateFrom(
                                        authorization, RALLY_INDICATOR, 80, "rally-indicator"));
                        if (opened != BearVerifiedActionExecutor.Outcome.CONFIRMED) {
                            warListKnown = false;
                            return false;
                        }
                        warListKnown = true;
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
                    case TAP_BEAR_ANCHOR -> throw new IllegalStateException("Unexpected War navigation action");
                }
            }
            return false;
        }

        private void tapTemplateFrom(
                BearFrameStream.Snapshot<RawImageData> authorization,
                TemplatesEnum template,
                int threshold,
                String operation) {
            ImageSearchResultData hit = emuManager.locatePattern(
                    EMULATOR_NUMBER, authorization.frame(), template, threshold);
            if (!hit.isFound()) {
                throw protectedFailure(
                        BearSessionExecutionException.FailureKind.VISUAL_UNKNOWN,
                        BearSessionExecutionException.RecoveryDirective.DEGRADED_WAIT,
                        operation + "-missing-in-authorizing-frame",
                        null);
            }
            tapInside(hit);
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

        private boolean backToVerifiedParent(BearNavigationPolicy.Screen source) {
            BearUiAction action = source == BearNavigationPolicy.Screen.DEPLOY_CONFIRMATION
                    || source == BearNavigationPolicy.Screen.MARCH_QUEUE_FULL
                    ? BearUiAction.DISMISS_DEPLOY_DIALOG
                    : BearUiAction.BACK_TO_PARENT;
            BearVerifiedActionExecutor.Outcome outcome = ui.transition(
                    action,
                    authorization -> pressBack(),
                    "newer frame at the verified parent of " + source,
                    frame -> BearUiAction.confirmsBackFrom(source, frame.screen()),
                    frame -> frame.screen() == source,
                    0);
            if (source == BearNavigationPolicy.Screen.WAR_LIST
                    && outcome == BearVerifiedActionExecutor.Outcome.CONFIRMED) {
                warListKnown = false;
            }
            return outcome == BearVerifiedActionExecutor.Outcome.CONFIRMED;
        }

        private void leaveVerifiedFormationScreen() {
            BearNavigationPolicy.Screen screen = observeBearScreen();
            if (screen == BearNavigationPolicy.Screen.DEPLOY_CONFIRMATION
                    || screen == BearNavigationPolicy.Screen.MARCH_QUEUE_FULL) {
                backToVerifiedParent(screen);
                screen = observeBearScreen();
            }
            if (screen == BearNavigationPolicy.Screen.FORMATION) {
                backToVerifiedParent(screen);
            }
        }

        private void returnToWarListFromFormation() {
            BearNavigationPolicy.Screen screen = observeBearScreen();
            warListKnown = screen == BearNavigationPolicy.Screen.FORMATION
                    && backToVerifiedParent(screen)
                    && ui.current() != null
                    && ui.current().screen() == BearNavigationPolicy.Screen.WAR_LIST;
        }

        private void dismissDeployConfirmationAndLeaveFormation() {
            // The live Bear capacity warning must never trigger Equalize or alter the configured
            // flag. Back dismisses the proven dialog; a second verified transition leaves formation.
            BearNavigationPolicy.Screen screen = observeBearScreen();
            if (screen == BearNavigationPolicy.Screen.DEPLOY_CONFIRMATION) {
                backToVerifiedParent(screen);
            }
            if (observeBearScreen() == BearNavigationPolicy.Screen.FORMATION) {
                returnToWarListFromFormation();
            }
        }

        private BearNavigationPolicy.Screen observeBearScreen() {
            checkPreemption();
            lastObservedFrame = ui.observe();
            return lastObservedFrame.screen();
        }

        private BearNavigationPolicy.Screen classifyBearScreen(RawImageData frame) {
            boolean joinButtonVisible = emuManager.locatePattern(
                    EMULATOR_NUMBER, frame, BEAR_JOIN_PLUS_ICON, 80).isFound();
            boolean warCloseVisible = emuManager.locatePattern(
                    EMULATOR_NUMBER, frame, BEAR_WAR_LIST_CLOSE, 85).isFound();
            boolean world = emuManager.locatePattern(EMULATOR_NUMBER, frame, GAME_HOME_WORLD, 90).isFound();
            boolean war = BearWarListIdentity.isVisible(
                    warListKnown, warCloseVisible, joinButtonVisible ? 1 : 0);
            BearNavigationPolicy.Screen screen = BearScreenClassifier.classify(
                    new BearScreenClassifier.Evidence(
                            emuManager.locatePattern(EMULATOR_NUMBER, frame, GAME_HOME_RECONNECT, 85).isFound(),
                            emuManager.locatePattern(EMULATOR_NUMBER, frame,
                                    GAME_START_WELCOME_BACK_TITLE, 85).isFound()
                                    || emuManager.locatePattern(EMULATOR_NUMBER, frame,
                                            GAME_START_DOWNLOAD_NOW, 85).isFound()
                                    || emuManager.locatePattern(EMULATOR_NUMBER, frame,
                                            GAME_START_MANDATORY_UPDATE_TITLE, 85).isFound(),
                            emuManager.locatePattern(EMULATOR_NUMBER, frame,
                                    RALLY_MARCH_QUEUE_FULL, 85).isFound(),
                            emuManager.locatePattern(EMULATOR_NUMBER, frame,
                                    DEPLOY_CONFIRMATION_DIALOG, 90).isFound()
                                    || emuManager.locatePattern(EMULATOR_NUMBER, frame,
                                            TROOPS_ALREADY_MARCHING, 90).isFound(),
                            false,
                            emuManager.locatePattern(EMULATOR_NUMBER, frame, BEAR_DEPLOY_BUTTON, 90).isFound(),
                            emuManager.locatePattern(EMULATOR_NUMBER, frame, RALLY_HOLD_BUTTON, 90).isFound(),
                            emuManager.locatePattern(EMULATOR_NUMBER, frame, BEAR_RALLY_BUTTON, 80).isFound(),
                            war,
                            false,
                            false,
                            emuManager.locatePattern(EMULATOR_NUMBER, frame,
                                    ALLIANCE_TERRITORY_BUTTON, 80).isFound(),
                            false,
                            BearSpecialBuildingsScreenClassifier.isGoButtonReady(
                                    ImageConverter.toBufferedImage(frame), trapNumber),
                            emuManager.locatePattern(EMULATOR_NUMBER, frame,
                                    PETS_INFO_SKILLS, 85).isFound(),
                            false,
                            emuManager.locatePattern(EMULATOR_NUMBER, frame,
                                    PETS_SKILL_USE, 85).isFound(),
                            SidebarFrameClassifier.selectedSection(
                                    ImageConverter.toBufferedImage(frame))
                                    .orElse(null) == SidebarSection.WILDERNESS,
                            SidebarFrameClassifier.selectedSection(
                                    ImageConverter.toBufferedImage(frame))
                                    .filter(section -> section != SidebarSection.WILDERNESS)
                                    .isPresent(),
                            world,
                            world && emuManager.locatePattern(
                                    EMULATOR_NUMBER, frame, BEAR_HUNT_IS_RUNNING, 90).isFound(),
                            false,
                            false));
            warListKnown = screen == BearNavigationPolicy.Screen.WAR_LIST;
            return screen;
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
                                || screen == BearNavigationPolicy.Screen.WORLD_AT_BEAR
                                || screen == BearNavigationPolicy.Screen.WORLD_AT_VERIFIED_BEAR
                                || screen == BearNavigationPolicy.Screen.BEAR_RALLY_PANEL;
                case FILL_JOIN_SLOTS, WAIT_FOR_NEXT_USEFUL_DEADLINE ->
                        screen == BearNavigationPolicy.Screen.WORLD
                                || screen == BearNavigationPolicy.Screen.WORLD_AT_BEAR
                                || screen == BearNavigationPolicy.Screen.WORLD_AT_VERIFIED_BEAR
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
