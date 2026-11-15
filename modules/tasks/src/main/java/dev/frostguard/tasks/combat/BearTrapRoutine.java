package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.FormationSlots;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.MarchSlotState;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.helper.BearTrapHelper;
import dev.frostguard.engine.helper.FormationSelectionResult;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.BearTrapParticipationSchedule;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.vision.convert.ImageConverter;

import java.awt.image.BufferedImage;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.*;
import static dev.frostguard.api.configs.TemplatesEnum.*;

public class BearTrapRoutine extends DelayedTask {

private boolean ownRallyActive;

private Integer ownRallySlot;

private Instant ownRallyFallbackReleaseAt;

private Instant nextOwnRallyCheck = Instant.EPOCH;

private Instant nextOwnRallyAttempt = Instant.EPOCH;

private Instant nextJoinAttempt = Instant.EPOCH;

private int consecutiveOwnRallyFailures;

private final BearJoinAttemptLedger joinAttempts =
        new BearJoinAttemptLedger(Duration.ofSeconds(JOIN_ROW_RETRY_COOLDOWN_SECONDS));

private List<Integer> joinFlags = new ArrayList<>();

private int currentJoinFlagIndex = 0;

private static final int TRAP_DURATION_MINUTES_VALUE = 30;

private static final int TRAP_ACTIVATION_OFFSET_MINUTES_VALUE = 30;

private static final int STATUS_LOG_INTERVAL_VALUE = 10;

private static final int OWN_RALLY_ABSOLUTE_MIN_REMAINING_SECONDS = 45;

private static final int DEFAULT_RALLY_SET_TIME_SECONDS = 300;

private static final int RALLY_ARRIVAL_SAFETY_SECONDS = 10;

private static final int OWN_RALLY_STATE_CHECK_SECONDS = 10;

private static final int RETURN_IMMINENT_SECONDS = 15;

private static final int MAX_OWN_RALLY_FAILURES_BEFORE_JOINS = 3;

// A Bear rally normally remains listed through its preparation phase. Keeping a rejected row out
// for that whole phase prevents the observed "already joined" loop without pretending the UI gave
// us a stable rally identity.
private static final int JOIN_ROW_RETRY_COOLDOWN_SECONDS = 300;

private static final int MAX_JOIN_CANDIDATES_PER_PASS = 8;

private static final int MAX_GATHER_RECALL_ATTEMPTS_LIMIT = 120;

private static final int TEMPLATE_SEARCH_RETRIES_VALUE = 3;

private static final int TEMPLATE_SEARCH_RETRIES_EXTENDED_VALUE = 5;

private static final int TEMPLATE_SEARCH_RETRIES_MAX_VALUE = 10;

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

private boolean isVisuallyTriggered = false;

public BearTrapRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

@Override
    protected boolean acceptsInjections() {
        return false;
    }

@Override
    protected void execute() {
        hydrateConfiguration();


        if (!confirmExecutionWindow()) {
            deferToNextWindow();
            return;
        }


        try {
            TrapTimingShape timing;
            if (isVisuallyTriggered) {
                logInfo(routineLogBearTrapLine("Task was Visually Triggered! Bypassing scheduled configuration and forcing 30-minute Active execution."));
                LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
                timing = new TrapTimingShape(now, now, now.plusMinutes(TRAP_DURATION_MINUTES_VALUE));
            } else {
                timing = computeTrapTiming();
                logTrapTimingFlow(timing);
            }

            LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));

            if (now.isBefore(timing.activationTime)) {
                performPreparationPhase(timing.activationTime);
            } else {
                logInfo(routineLogBearTrapLine("Trap is already ACTIVE (preparation time passed)"));


                logInfo(routineLogBearTrapLine("Executing essential setup (pets and navigation)..."));
                if (usePets) {
                    logInfo(routineLogBearTrapLine("Activating pets..."));
                    enablePetsFlow();
                }
                logInfo(routineLogBearTrapLine("Moving camera to Bear Trap " + trapNumber));
                reachBearTrap(trapNumber);
                sleepTask(1000);

            }

            now = LocalDateTime.now(ZoneId.of("UTC"));

            if (now.isBefore(timing.endTime)) {
                performTrapActivePhase(timing.endTime);
            } else {
                logInfo(routineLogBearTrapLine("Trap already ended for this window"));
            }
        } catch (Exception e) {
            logError(routineLogBearTrapLine("Issue while Bear Trap execution: " + e.getMessage()), e);
        } finally {


            cleanupFlow();
            deferToNextWindow();
        }
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

private static class MarchStatusShape {
        final boolean hasRecallButton;
        final boolean hasViewButton;
        final boolean hasSpeedupButton;

        MarchStatusShape(boolean hasRecallButton, boolean hasViewButton, boolean hasSpeedupButton) {
            this.hasRecallButton = hasRecallButton;
            this.hasViewButton = hasViewButton;
            this.hasSpeedupButton = hasSpeedupButton;
        }

        boolean noMarchesFound() {
            return !hasRecallButton && !hasViewButton && !hasSpeedupButton;
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

        sleepTask(1000);

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

private void performPreparationPhase(LocalDateTime activationTime) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
        long secondsUntilActivation = ChronoUnit.SECONDS.between(now, activationTime);

        logInfo(routineLogBearTrapLine("PREPARATION PHASE: " + secondsUntilActivation + " seconds until trap auto-activates"));

        prepareForTrapFlow();

        now = LocalDateTime.now(ZoneId.of("UTC"));
        secondsUntilActivation = ChronoUnit.SECONDS.between(now, activationTime);

        if (secondsUntilActivation > 0) {
            logInfo(routineLogBearTrapLine("Waiting for trap auto-activation in " + secondsUntilActivation + " seconds..."));
            sleepTask((secondsUntilActivation * 1000) + 2000);

        }

        logInfo(routineLogBearTrapLine("Trap has been ACTIVATED automatically!"));
    }

private String routineLogBearTrapLine(String note) {
        return "BearTrapRoutine | " + note;
    }

private void recallGatherTroopsFlow() {
        // pernerch/2026-07-02: record recall timestamp in profile config BEFORE recalling.
        // GatherRoutine reads GATHER_LAST_RECALL_TIME_STRING on startup and uses it to wait
        // for troops to return home before re-deploying (checkTroopReturnPending).
        writeProfileSetting(
            dev.frostguard.api.configs.ConfigurationKeyEnum.GATHER_LAST_RECALL_TIME_STRING,
            java.time.LocalDateTime.now().toString());
        logInfo(routineLogBearTrapLine("Gather recall timestamp stored for troop-return tracking."));

        int attempt = 0;

        while (attempt < MAX_GATHER_RECALL_ATTEMPTS_LIMIT) {
            attempt++;

            MarchStatusShape status = inspectMarchStatus();

            logDebug(routineLogBearTrapLine(String.format(
                    "recallGatherTroopsFlow status => returning:%b view:%b speedup:%b (attempt %d)",
                    status.hasRecallButton, status.hasViewButton, status.hasSpeedupButton, attempt)));

            if (status.noMarchesFound()) {
                logInfo(routineLogBearTrapLine("Zero march indicators detected. All gather troops are recalled or none present."));
                return;
            }

            if (status.hasRecallButton) {
                recallMarchFlow();
            }

            if (status.hasViewButton || status.hasSpeedupButton) {
                logInfo(routineLogBearTrapLine("Troops are still marching - waiting for them to return"));
                sleepTask(1000);

            }

            sleepTask(200);

        }

        logError(routineLogBearTrapLine("recallGatherTroopsFlow exceeded max attempts (" + MAX_GATHER_RECALL_ATTEMPTS_LIMIT +
                "), exiting to avoid deadlock"));
    }

private void performTrapActivePhase(LocalDateTime trapEndTime) {
        logInfo(routineLogBearTrapLine("=== TRAP IS NOW ACTIVE - Starting strategy execution ==="));

        LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
        long iterationCount = 0;

        while (now.isBefore(trapEndTime)) {
            checkPreemption();

            iterationCount++;
            long secondsRemaining = ChronoUnit.SECONDS.between(now, trapEndTime);

            boolean ownRallyNeedsPriority = tryStartOwnRallyFlow(secondsRemaining);
            if (!ownRallyNeedsPriority) {
                handleJoinRallies();
            }

            logPeriodicStatusFlow(iterationCount, secondsRemaining);

            now = LocalDateTime.now(ZoneId.of("UTC"));
            sleepTask(1000);

        }

        logInfo(routineLogBearTrapLine("=== TRAP ENDED - Strategy execution completed ==="));
    }

private boolean confirmExecutionWindow() {


        isVisuallyTriggered = false;


        try {
            ImageSearchResultData result = emuManager.locatePattern(
                    profile.getEmulatorNumber(),
                    TemplatesEnum.BEAR_HUNT_IS_RUNNING,
                    90);
            if (result.isFound()) {
                logInfo(routineLogBearTrapLine("Confirmed: Bear Hunt is VISUALLY ACTIVE. Overriding time window check."));
                isVisuallyTriggered = true;
                return true;
            }
        } catch (Exception e) {
            logWarning(routineLogBearTrapLine("Visual check did not complete in confirmExecutionWindow: " + e.getMessage()));
        }

        if (!hasInsideWindow()) {
            logWarning(routineLogBearTrapLine("Execute called OUTSIDE valid window. Planning next run..."));
            return false;
        }

        logInfo(routineLogBearTrapLine("Confirmed: We are INSIDE a valid execution window"));
        return true;
    }

private boolean touchBearTrapGoButton(int trapNumber) {
        switch (trapNumber) {
            case 1:
                tapInside(BEAR_TRAP_1_GO_BUTTON_TL_VALUE, BEAR_TRAP_1_GO_BUTTON_BR_VALUE, 1, 300);
                return true;
            case 2:
                tapInside(BEAR_TRAP_2_GO_BUTTON_TL_VALUE, BEAR_TRAP_2_GO_BUTTON_BR_VALUE, 1, 300);
                return true;
            default:
                logError(routineLogBearTrapLine("Invalid trap number: " + trapNumber));
                return false;
        }
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

private void logPeriodicStatusFlow(long iterationCount, long secondsRemaining) {
        if (iterationCount % STATUS_LOG_INTERVAL_VALUE == 0) {
            long minutesRemaining = secondsRemaining / 60;
            logInfo(routineLogBearTrapLine("Trap active - " + minutesRemaining + " minutes " +
                    (secondsRemaining % 60) + " seconds remaining"));
        }
    }

private boolean tryStartOwnRallyFlow(long secondsRemaining) {
        if (!callOwnRally) {
            return false;
        }

        Instant now = Instant.now();
        if (ownRallyActive) {
            return refreshOwnRallyState(now);
        }
        if (secondsRemaining <= OWN_RALLY_ABSOLUTE_MIN_REMAINING_SECONDS) {
            logInfo(routineLogBearTrapLine("Own rally disabled for this session: only " + secondsRemaining
                    + "s remain, below the absolute safe-start floor"));
            callOwnRally = false;
            return false;
        }
        if (now.isBefore(nextOwnRallyAttempt)) {
            return consecutiveOwnRallyFailures < MAX_OWN_RALLY_FAILURES_BEFORE_JOINS;
        }

        try {
            List<MarchSlotState> before = marchHelper.readMarchQueue();
            if (!before.isEmpty() && before.stream().noneMatch(MarchSlotState::isIdle)) {
                nextOwnRallyAttempt = now.plusSeconds(OWN_RALLY_STATE_CHECK_SECONDS);
                logInfo(routineLogBearTrapLine("Own rally waiting: March Queue has no idle slot"));
                return true;
            }

            OwnRallyLaunchResult result = beginOwnRally(secondsRemaining);
            if (result.outcome() == OwnRallyLaunchOutcome.CONFIRMED) {
                List<MarchSlotState> after = marchHelper.readMarchQueue();
                ownRallySlot = BearOwnRallyTracker.identifyNewRallySlot(before, after);
                ownRallyActive = true;
                consecutiveOwnRallyFailures = 0;
                ownRallyFallbackReleaseAt = now.plusSeconds(
                        result.rallySetTimeSeconds() + result.travelTimeSeconds() * 2L
                                + RALLY_ARRIVAL_SAFETY_SECONDS);
                nextOwnRallyCheck = now.plusSeconds(OWN_RALLY_STATE_CHECK_SECONDS);
                logInfo(routineLogBearTrapLine("Own rally confirmed: slot="
                        + (ownRallySlot == null ? "unresolved" : ownRallySlot)
                        + " setTime=" + result.rallySetTimeSeconds() + "s travel="
                        + result.travelTimeSeconds() + "s fallbackRelease=" + ownRallyFallbackReleaseAt));
                return false;
            }

            navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
            if (result.structural()) {
                callOwnRally = false;
                logWarning(routineLogBearTrapLine("Own rally disabled for this session: " + result.detail()));
                return false;
            }

            consecutiveOwnRallyFailures++;
            nextOwnRallyAttempt = now.plusSeconds(5);
            logWarning(routineLogBearTrapLine("Own rally attempt " + consecutiveOwnRallyFailures + "/"
                    + MAX_OWN_RALLY_FAILURES_BEFORE_JOINS + " failed: " + result.detail()));
            return consecutiveOwnRallyFailures < MAX_OWN_RALLY_FAILURES_BEFORE_JOINS;
        } catch (dev.frostguard.engine.error.ADBConnectionException e) {
            consecutiveOwnRallyFailures++;
            nextOwnRallyAttempt = now.plusSeconds(5);
            logWarning(routineLogBearTrapLine("ADB connection error during own rally: " + e.getMessage()));
            return consecutiveOwnRallyFailures < MAX_OWN_RALLY_FAILURES_BEFORE_JOINS;
        } catch (Exception e) {
            consecutiveOwnRallyFailures++;
            nextOwnRallyAttempt = now.plusSeconds(5);
            logError(routineLogBearTrapLine("Unexpected error during own rally: " + e.getMessage()), e);
            return consecutiveOwnRallyFailures < MAX_OWN_RALLY_FAILURES_BEFORE_JOINS;
        }
    }

private boolean refreshOwnRallyState(Instant now) {
        if (now.isBefore(nextOwnRallyCheck)) {
            return false;
        }

        List<MarchSlotState> slots = marchHelper.readMarchQueue();
        BearOwnRallyTracker.Observation observation = BearOwnRallyTracker.observe(ownRallySlot, slots);
        if (observation.state() == BearOwnRallyTracker.State.RETURNED) {
            ownRallyActive = false;
            ownRallySlot = null;
            nextOwnRallyAttempt = Instant.EPOCH;
            logInfo(routineLogBearTrapLine("Own rally march returned according to March Queue; own rally gets priority now"));
            return true;
        }
        if (observation.state() == BearOwnRallyTracker.State.RETURNING
                && observation.releaseCountdown().getSeconds() <= RETURN_IMMINENT_SECONDS) {
            nextOwnRallyCheck = now.plusSeconds(2);
            logDebug(routineLogBearTrapLine("Own rally return is imminent ("
                    + observation.releaseCountdown().getSeconds() + "s); pausing new joins"));
            return true;
        }
        if (observation.state() == BearOwnRallyTracker.State.UNKNOWN
                && ownRallyFallbackReleaseAt != null && !now.isBefore(ownRallyFallbackReleaseAt)) {
            ownRallyActive = false;
            ownRallySlot = null;
            nextOwnRallyAttempt = Instant.EPOCH;
            logWarning(routineLogBearTrapLine("Own rally slot could not be tracked; fallback release time elapsed, retrying conservatively"));
            return true;
        }

        long checkDelay = observation.releaseCountdown() == null
                ? OWN_RALLY_STATE_CHECK_SECONDS
                : Math.max(2, Math.min(OWN_RALLY_STATE_CHECK_SECONDS,
                        observation.releaseCountdown().getSeconds()));
        nextOwnRallyCheck = now.plusSeconds(checkDelay);
        return false;
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
        this.currentJoinFlagIndex = 0;
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

private void disableAutojoinFlow() {
        tapInside(ALLIANCE_BUTTON_TL_VALUE, ALLIANCE_BUTTON_BR_VALUE);
        sleepTask(3000);


        ImageSearchResultData warButton = templateSearchHelper.locatePattern(
                ALLIANCE_WAR_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_EXTENDED_VALUE)
                        .build());

        if (!warButton.isFound()) {
            logError(routineLogBearTrapLine("Alliance War button not detected to disable autojoin"));
            return;
        }

        tapInside(warButton.getPoint(), warButton.getPoint(), 1, 1000);
        sleepTask(1000);


        tapInside(AUTOJOIN_BUTTON_TL_VALUE, AUTOJOIN_BUTTON_BR_VALUE, 1, 1500);
        sleepTask(500);


        tapInside(AUTOJOIN_STOP_BUTTON_TL_VALUE, AUTOJOIN_STOP_BUTTON_BR_VALUE, 1, 500);
        sleepTask(500);


        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
    }

private BearTrapHelper.WindowResult resolveWindowState() {
        Instant referenceUTC = referenceTrapTime.atZone(ZoneId.of("UTC")).toInstant();
        return BearTrapHelper.calculateWindow(referenceUTC, trapPreparationTime);
    }

private void prepareForTrapFlow() {
        logInfo(routineLogBearTrapLine("Preparing for Bear Trap event..."));

        logInfo(routineLogBearTrapLine("Disabling autojoin..."));
        disableAutojoinFlow();

        if (recallTroops) {
            logInfo(routineLogBearTrapLine("Recalling all gather troops to the city..."));
            recallGatherTroopsFlow();
        }

        if (usePets) {
            logInfo(routineLogBearTrapLine("Activating pets..."));
            enablePetsFlow();
        }

        logInfo(routineLogBearTrapLine("Moving camera to Bear Trap " + trapNumber));
        reachBearTrap(trapNumber);
        sleepTask(1000);

    }

private MarchStatusShape inspectMarchStatus() {
        ImageSearchResultData returningArrow = templateSearchHelper.locatePattern(
                MARCHES_AREA_RECALL_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_VALUE)
                        .build());

        ImageSearchResultData marchView = templateSearchHelper.locatePattern(
                MARCHES_AREA_VIEW_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_VALUE)
                        .build());

        ImageSearchResultData marchSpeedup = templateSearchHelper.locatePattern(
                MARCHES_AREA_SPEEDUP_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_VALUE)
                        .build());

        return new MarchStatusShape(
                returningArrow != null && returningArrow.isFound(),
                marchView != null && marchView.isFound(),
                marchSpeedup != null && marchSpeedup.isFound());
    }

private void handleJoinRallies() {
        if (!joinRally || sharedEmulator) {
            if (sharedEmulator) {
                logInfo(routineLogBearTrapLine("Skipping rally joining because this profile shares an emulator with another account."));
            } else {
                logDebug(routineLogBearTrapLine("Skipping rally joining because joining is disabled."));
            }
            return;
        }
        if (Instant.now().isBefore(nextJoinAttempt)) {
            return;
        }

        try {
            ImageSearchResultData warButton = templateSearchHelper.locatePattern(
                    GAME_HOME_WAR,
                    SearchConfig.builder().withThreshold(90).withMaxAttempts(2).build());
            if (!warButton.isFound()) {
                nextJoinAttempt = Instant.now().plusSeconds(3);
                logDebug(routineLogBearTrapLine("War button unavailable; deferring join scan"));
                return;
            }
            tapInside(warButton);
            sleepTask(500);
            manageJoinRallies();
        } catch (dev.frostguard.engine.error.ADBConnectionException e) {
            logWarning(routineLogBearTrapLine("ADB connection error during rally joining (emulator may be lagging): " + e.getMessage()));
            nextJoinAttempt = Instant.now().plusSeconds(3);
        } catch (Exception e) {
            logError(routineLogBearTrapLine("Unexpected error during rally joining: " + e.getMessage()), e);
            nextJoinAttempt = Instant.now().plusSeconds(3);
        }
    }

private void manageJoinRallies() {
        RawImageData raw = emuManager.captureScreen(EMULATOR_NUMBER);
        List<ImageSearchResultData> plusIcons = templateSearchHelper.locateAllPatternsMono(
                BEAR_JOIN_PLUS_ICON,
                raw,
                SearchConfig.builder()
                        .withThreshold(80)
                        .withMaxAttempts(1)
                        .withMaxResults(MAX_JOIN_CANDIDATES_PER_PASS)
                        .build());

        if (plusIcons == null || plusIcons.isEmpty()) {
            logDebug(routineLogBearTrapLine("No rally plus controls visible"));
            pressBack();
            nextJoinAttempt = Instant.now().plusSeconds(3);
            return;
        }

        BufferedImage image = ImageConverter.toBufferedImage(raw);
        List<ImageSearchResultData> candidates = plusIcons.stream()
                .sorted(Comparator.comparingInt(hit -> hit.getPoint().getY()))
                .filter(hit -> joinAttempts.canAttempt(hit.getPoint(), Instant.now()))
                .filter(hit -> {
                    BearJoinButtonClassifier.Evidence evidence = BearJoinButtonClassifier.inspect(image, hit.getPoint());
                    if (!evidence.enabled()) {
                        logDebug(routineLogBearTrapLine("Skipping grey rally plus at " + hit.getPoint()
                                + " colouredPixels=" + evidence.colouredPixels()));
                    }
                    return evidence.enabled();
                })
                .toList();

        if (candidates.isEmpty()) {
            logDebug(routineLogBearTrapLine("All visible rally plus controls are grey or cooling down"));
            pressBack();
            nextJoinAttempt = Instant.now().plusSeconds(3);
            return;
        }

        for (ImageSearchResultData candidate : candidates) {
            checkPreemption();
            logInfo(routineLogBearTrapLine("Trying active rally plus at " + candidate.getPoint()));
            tapInside(candidate.getPoint(), candidate.getPoint(), 1, 100);
            sleepTask(500);

            if (deploymentHelper.isMarchQueueFull()) {
                logInfo(routineLogBearTrapLine("Join paused: March Queue is full"));
                pressBack();
                nextJoinAttempt = Instant.now().plusSeconds(OWN_RALLY_STATE_CHECK_SECONDS);
                return;
            }
            if (deploymentHelper.hasNoDeployableTroops()) {
                logWarning(routineLogBearTrapLine("Join paused: formation screen reports no deployable troops"));
                pressBack();
                sleepTask(300);
                pressBack();
                nextJoinAttempt = Instant.now().plusSeconds(OWN_RALLY_STATE_CHECK_SECONDS);
                return;
            }

            FormationSelectionResult formation = selectNextJoinFormation();
            if (!formation.successful()) {
                logWarning(routineLogBearTrapLine("Join candidate rejected: no configured formation could be selected; last="
                        + formation.status() + " detail=" + formation.detail()));
                pressBack();
                sleepTask(300);
                pressBack();
                if (formation.status() != FormationSelectionResult.Status.SCREEN_UNREADABLE) {
                    joinRally = false;
                    logWarning(routineLogBearTrapLine("Joining disabled for this session because every configured formation is structurally unavailable"));
                } else {
                    nextJoinAttempt = Instant.now().plusSeconds(OWN_RALLY_STATE_CHECK_SECONDS);
                }
                return;
            }

            ImageSearchResultData deploy = templateSearchHelper.locatePattern(
                    BEAR_DEPLOY_BUTTON,
                    SearchConfig.builder().withThreshold(90).withMaxAttempts(3).withDelay(250).build());
            if (!deploy.isFound()) {
                logWarning(routineLogBearTrapLine("Join candidate rejected before Deploy: rally may already be joined, full, expired, or unable to fit the selected formation; row="
                        + candidate.getPoint() + " formation=" + formation.formation()));
                joinAttempts.reject(candidate.getPoint(), Instant.now());
                pressBack();
                sleepTask(400);
                continue;
            }

            DeployVerification verification = pressDeployAndVerify(deploy);
            if (verification == DeployVerification.CONFIRMED) {
                logInfo(routineLogBearTrapLine("Join confirmed after Deploy: row=" + candidate.getPoint()
                        + " formation=" + formation.formation()));
                nextJoinAttempt = Instant.EPOCH;
                return;
            }

            logWarning(routineLogBearTrapLine("Join not confirmed after Deploy: outcome=" + verification
                    + " row=" + candidate.getPoint() + " formation=" + formation.formation()));
            joinAttempts.reject(candidate.getPoint(), Instant.now());
            if (verification == DeployVerification.DESTINATION_UNVERIFIED) {
                navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
                nextJoinAttempt = Instant.now().plusSeconds(3);
                return;
            }
            pressBack();
            sleepTask(400);
        }

        pressBack();
        nextJoinAttempt = Instant.now().plusSeconds(3);
    }

private FormationSelectionResult selectNextJoinFormation() {
        FormationSelectionResult last = new FormationSelectionResult(
                FormationSelectionResult.Status.EMPTY_OR_MISSING, null, "No join formations configured");
        for (int attempt = 0; attempt < joinFlags.size(); attempt++) {
            int flag = joinFlags.get(currentJoinFlagIndex);
            FormationSelectionResult result = marchHelper.selectFormation(flag);
            currentJoinFlagIndex = (currentJoinFlagIndex + 1) % joinFlags.size();
            if (result.successful()) {
                logInfo(routineLogBearTrapLine("Join formation #" + flag + " selected from rotation " + joinFlags));
                return result;
            }
            logWarning(routineLogBearTrapLine("Join formation #" + flag + " skipped: "
                    + result.status() + " (" + result.detail() + ")"));
            last = result;
        }
        return last;
    }

private DeployVerification pressDeployAndVerify(ImageSearchResultData deploy) {
        tapInside(deploy);
        sleepTask(2000);

        if (deploymentHelper.isSameTargetDialog()) {
            pressBack();
            sleepTask(300);
            return DeployVerification.REJECTED_BY_DIALOG;
        }
        ImageSearchResultData stillVisible = templateSearchHelper.locatePattern(
                BEAR_DEPLOY_BUTTON,
                SearchConfig.builder().withThreshold(90).withMaxAttempts(2).withDelay(300).build());
        if (stillVisible.isFound()) {
            return DeployVerification.DEPLOY_STILL_VISIBLE;
        }
        ImageSearchResultData world = templateSearchHelper.locatePattern(
                GAME_HOME_WORLD,
                SearchConfig.builder().withThreshold(90).withMaxAttempts(5).withDelay(400).build());
        return world.isFound() ? DeployVerification.CONFIRMED : DeployVerification.DESTINATION_UNVERIFIED;
    }

private enum DeployVerification {
        CONFIRMED,
        REJECTED_BY_DIALOG,
        DEPLOY_STILL_VISIBLE,
        DESTINATION_UNVERIFIED
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

private void recallMarchFlow() {
        logInfo(routineLogBearTrapLine("Returning arrow detected - attempting to tap recall button"));

        ImageSearchResultData recallButton = templateSearchHelper.locatePattern(
                MARCHES_AREA_RECALL_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_VALUE)
                        .build());

        if (recallButton.isFound()) {
            tapInside(recallButton.getPoint(), recallButton.getPoint(), 1, 300);
            sleepTask(300);


            tapInside(RECALL_CONFIRM_BUTTON_TL_VALUE, RECALL_CONFIRM_BUTTON_BR_VALUE, 1, 200);
            sleepTask(500);

        }
    }

private boolean hasInsideWindow() {
        Instant referenceUTC = referenceTrapTime.atZone(ZoneId.of("UTC")).toInstant();
        BearTrapHelper.WindowResult result = BearTrapHelper.calculateWindow(referenceUTC, trapPreparationTime);
        return result.getState() == BearTrapHelper.WindowState.INSIDE;
    }

private List<Integer> decodeJoinFlags() {
        String flagConfig = profile.getConfig(BEAR_TRAP_JOIN_FLAG_INT, String.class);
        List<Integer> flags = new ArrayList<>();

        if (flagConfig != null && !flagConfig.trim().isEmpty()) {
            String[] parts = flagConfig.split(",");
            for (String part : parts) {
                try {
                    int flag = Integer.parseInt(part.trim());
                    if (FormationSlots.supports(flag)) {
                        flags.add(flag);
                    }
                } catch (NumberFormatException e) {
                    logWarning(routineLogBearTrapLine("Invalid join flag value: " + part));
                }
            }
        }


        if (flags.isEmpty()) {
            flags.add(DEFAULT_JOIN_RALLY_FLAG_VALUE);
        }


        flags.sort(Integer::compareTo);

        return flags;
    }

private void enablePetsFlow() {
        ImageSearchResultData petsButton = templateSearchHelper.locatePattern(
                GAME_HOME_PETS,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_EXTENDED_VALUE)
                        .build());

        if (!petsButton.isFound()) {
            logError(routineLogBearTrapLine("Pets button not detected to enable pets"));
            return;
        }

        tapInside(petsButton.getPoint(), petsButton.getPoint(), 1, 500);
        sleepTask(1000);


        tapInside(PET_RAZORBACK_TL_VALUE, PET_RAZORBACK_BR_VALUE, 1, 500);
        sleepTask(300);


        tapInside(PET_QUICK_USE_BUTTON_TL_VALUE, PET_QUICK_USE_BUTTON_BR_VALUE, 1, 500);
        sleepTask(300);


        tapInside(PET_USE_BUTTON_TL_VALUE, PET_USE_BUTTON_BR_VALUE, 1, 100);
        sleepTask(500);


        pressBack();
        sleepTask(300);


        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
    }

private boolean reachBearTrap(int trapNumber) {
        tapInside(ALLIANCE_BUTTON_TL_VALUE, ALLIANCE_BUTTON_BR_VALUE);
        sleepTask(3000);


        ImageSearchResultData territoryButton = templateSearchHelper.locatePattern(
                ALLIANCE_TERRITORY_BUTTON,
                SearchConfig.builder()
                        .withMaxAttempts(1)
                        .build());

        if (!territoryButton.isFound()) {
            logError(routineLogBearTrapLine("Territory button not detected to go to bear trap"));
            return false;
        }

        tapInside(territoryButton.getPoint(), territoryButton.getPoint(), 1, 2000);
        sleepTask(1000);


        tapInside(SPECIAL_BUILDINGS_BUTTON_TL_VALUE, SPECIAL_BUILDINGS_BUTTON_BR_VALUE, 1, 300);
        sleepTask(500);


        boolean success = touchBearTrapGoButton(trapNumber);

        if (success) {
            sleepTask(2000);

        }

        return success;
    }

private OwnRallyLaunchResult beginOwnRally(long secondsRemaining) {
        logInfo(routineLogBearTrapLine("Calling own rally..."));

        tapInside(BEAR_CENTER_POINT_VALUE, BEAR_CENTER_POINT_VALUE, 1, 200);
        sleepTask(500);


        ImageSearchResultData rallyButton = templateSearchHelper.locatePattern(
                BEAR_RALLY_BUTTON,
                SearchConfig.builder()
                        .withThreshold(80)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_MAX_VALUE)
                        .build());

        if (!rallyButton.isFound()) {
            logError(routineLogBearTrapLine("Rally button not detected!"));
            return OwnRallyLaunchResult.transientFailure(OwnRallyLaunchOutcome.RALLY_BUTTON_MISSING,
                    "Rally button not detected");
        }

        logInfo(routineLogBearTrapLine("Entering rally menu..."));
        tapInside(rallyButton.getPoint(), rallyButton.getPoint(), 1, 200);
        sleepTask(500);


        ImageSearchResultData holdRallyButton = templateSearchHelper.locatePattern(
                RALLY_HOLD_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_MAX_VALUE)
                        .build());

        if (!holdRallyButton.isFound()) {
            logError(routineLogBearTrapLine("Hold Rally button not detected!"));
            pressBack();
            return OwnRallyLaunchResult.transientFailure(OwnRallyLaunchOutcome.HOLD_BUTTON_MISSING,
                    "Hold Rally button not detected");
        }

        int rallySetTimeSeconds = deploymentHelper.readRallySetTimeSeconds(DEFAULT_RALLY_SET_TIME_SECONDS);
        tapInside(holdRallyButton.getPoint(), holdRallyButton.getPoint(), 1, 200);
        sleepTask(300);

        if (deploymentHelper.hasNoDeployableTroops()) {
            pressBack();
            return OwnRallyLaunchResult.transientFailure(OwnRallyLaunchOutcome.NO_TROOPS,
                    "Formation screen reports no deployable troops");
        }

        FormationSelectionResult formation = marchHelper.selectFormation(ownRallyFlag);
        if (!formation.successful()) {
            pressBack();
            boolean structural = formation.status() == FormationSelectionResult.Status.UNSUPPORTED
                    || formation.status() == FormationSelectionResult.Status.LOCKED
                    || formation.status() == FormationSelectionResult.Status.EMPTY_OR_MISSING;
            return new OwnRallyLaunchResult(OwnRallyLaunchOutcome.FORMATION_UNAVAILABLE,
                    "Own formation #" + ownRallyFlag + " is " + formation.status()
                            + " (" + formation.detail() + ")",
                    structural, 0, rallySetTimeSeconds);
        }

        long marchSeconds = deploymentHelper.readTravelTimeSeconds();
        if (marchSeconds <= 0) {
            logWarning(routineLogBearTrapLine("Own rally travel time unreadable; using conservative 30s estimate"));
            marchSeconds = 30;
        }
        long requiredArrivalSeconds = rallySetTimeSeconds + marchSeconds + RALLY_ARRIVAL_SAFETY_SECONDS;
        if (requiredArrivalSeconds >= secondsRemaining) {
            pressBack();
            return new OwnRallyLaunchResult(OwnRallyLaunchOutcome.TOO_LATE,
                    "Rally would reach the trap too late: required=" + requiredArrivalSeconds
                            + "s remaining=" + secondsRemaining + "s",
                    true, marchSeconds, rallySetTimeSeconds);
        }

        ImageSearchResultData deploy = templateSearchHelper.locatePattern(
                BEAR_DEPLOY_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(3)
                        .withDelay(250)
                        .build());

        if (!deploy.isFound()) {
            logWarning(routineLogBearTrapLine("Deploy button not detected after selecting flag."));
            pressBack();
            return OwnRallyLaunchResult.transientFailure(OwnRallyLaunchOutcome.DEPLOY_MISSING,
                    "Deploy button not detected after selecting own formation");
        }

        DeployVerification verification = pressDeployAndVerify(deploy);
        if (verification != DeployVerification.CONFIRMED) {
            pressBack();
            return OwnRallyLaunchResult.transientFailure(OwnRallyLaunchOutcome.DEPLOY_NOT_CONFIRMED,
                    "Deploy verification ended with " + verification);
        }

        return new OwnRallyLaunchResult(OwnRallyLaunchOutcome.CONFIRMED,
                "World verified after Deploy", false, marchSeconds, rallySetTimeSeconds);
    }

private void cleanupFlow() {
        logInfo(routineLogBearTrapLine("Cleaning up Bear Trap state"));

        ownRallyActive = false;
        ownRallySlot = null;
        joinAttempts.clear();

        requeueDisabledTasksFlow();
    }

private enum OwnRallyLaunchOutcome {
        CONFIRMED,
        RALLY_BUTTON_MISSING,
        HOLD_BUTTON_MISSING,
        NO_TROOPS,
        FORMATION_UNAVAILABLE,
        TOO_LATE,
        DEPLOY_MISSING,
        DEPLOY_NOT_CONFIRMED
    }

private record OwnRallyLaunchResult(OwnRallyLaunchOutcome outcome, String detail, boolean structural,
                                    long travelTimeSeconds, int rallySetTimeSeconds) {

        static OwnRallyLaunchResult transientFailure(OwnRallyLaunchOutcome outcome, String detail) {
            return new OwnRallyLaunchResult(outcome, detail, false, 0, DEFAULT_RALLY_SET_TIME_SECONDS);
        }
    }
}
