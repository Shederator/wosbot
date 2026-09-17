package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.FormationSlots;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.MarchActivityType;
import dev.frostguard.api.domain.MarchMovementPhase;
import dev.frostguard.api.domain.MarchSlotAvailability;
import dev.frostguard.api.domain.MarchSlotState;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.error.StopExecutionException;
import dev.frostguard.engine.helper.BearTrapHelper;
import dev.frostguard.engine.helper.DeploymentPostTapRead;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.BearTrapParticipationSchedule;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.ProfileService;
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

private BearSessionCoordinator.ExitReason activeSessionExit;

private boolean bearAnchorKnown;

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


        try {
            TrapTimingShape timing;
            if (isVisuallyTriggered) {
                LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
                TrapTimingShape configuredTiming = computeTrapTiming();
                if (!now.isBefore(configuredTiming.activationTime) && now.isBefore(configuredTiming.endTime)) {
                    timing = configuredTiming;
                    logInfo(routineLogBearTrapLine(
                            "Task was visually triggered; using the configured event end instead of granting a new 30-minute window."));
                } else {
                    timing = new TrapTimingShape(now, now, now.plusMinutes(TRAP_DURATION_MINUTES_VALUE));
                    logWarning(routineLogBearTrapLine(
                            "Visual Bear marker does not align with the configured window; using a bounded 30-minute fallback."));
                }
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
                activeSessionExit = performTrapActivePhase(timing.endTime);
            } else {
                logInfo(routineLogBearTrapLine("Trap already ended for this window"));
            }
        } catch (StopExecutionException e) {
            if (e.isCancellation() || Thread.currentThread().isInterrupted()) {
                activeSessionExit = BearSessionCoordinator.ExitReason.CANCELLED;
                logInfo(routineLogBearTrapLine("Bear session cancelled by the operator"));
            } else {
                logError(routineLogBearTrapLine("Bear session stopped: " + e.getMessage()), e);
            }
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted() || e.getCause() instanceof InterruptedException) {
                activeSessionExit = BearSessionCoordinator.ExitReason.CANCELLED;
                logInfo(routineLogBearTrapLine("Bear session interrupted by the operator"));
            } else {
                logError(routineLogBearTrapLine("Issue while Bear Trap execution: " + e.getMessage()), e);
            }
        } finally {
            boolean resumeNormalTasks = BearSessionCoordinator.shouldResumeNormalTasks(activeSessionExit);
            cleanupFlow(resumeNormalTasks);
            if (resumeNormalTasks) {
                deferToNextWindow();
            }
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

private BearSessionCoordinator.ExitReason performTrapActivePhase(LocalDateTime trapEndTime) {
        logInfo(routineLogBearTrapLine("=== TRAP IS NOW ACTIVE - Starting strategy execution ==="));
        BearSessionCoordinator coordinator = new BearSessionCoordinator(
                new LiveBearSessionDriver(trapEndTime.atZone(ZoneId.of("UTC")).toInstant()),
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
                tapInside(BEAR_TRAP_1_GO_BUTTON_TL_VALUE, BEAR_TRAP_1_GO_BUTTON_BR_VALUE);
                return true;
            case 2:
                tapInside(BEAR_TRAP_2_GO_BUTTON_TL_VALUE, BEAR_TRAP_2_GO_BUTTON_BR_VALUE);
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
        bearAnchorKnown = reachBearTrap(trapNumber);

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
        ImageSearchResultData territoryButton = findFreshTransition(
                ALLIANCE_TERRITORY_BUTTON, 80, 3_000);

        if (!territoryButton.isFound()) {
            logError(routineLogBearTrapLine("Territory button not detected to go to bear trap"));
            return false;
        }

        tapInside(territoryButton);
        if (!awaitTemplateGone(ALLIANCE_TERRITORY_BUTTON, 80, 2_000)) {
            logError(routineLogBearTrapLine("Territory screen did not open"));
            return false;
        }
        tapInside(SPECIAL_BUILDINGS_BUTTON_TL_VALUE, SPECIAL_BUILDINGS_BUTTON_BR_VALUE);
        // The fresh capture is the pacing boundary; it replaces a blind fixed settle.
        emuManager.captureScreen(EMULATOR_NUMBER);
        boolean success = touchBearTrapGoButton(trapNumber);
        if (!success) {
            return false;
        }
        boolean worldReady = findFreshTransition(GAME_HOME_WAR, 90, 3_000).isFound();
        bearAnchorKnown = worldReady;
        return worldReady;
    }

private ImageSearchResultData findFreshTransition(TemplatesEnum template, int threshold, long timeoutMs) {
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
        do {
            checkPreemption();
            RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
            ImageSearchResultData result = emuManager.locatePattern(
                    EMULATOR_NUMBER, frame, template, threshold);
            if (result.isFound()) {
                return result;
            }
        } while (System.nanoTime() < deadline);
        return ImageSearchResultData.miss();
    }

private boolean awaitTemplateGone(TemplatesEnum template, int threshold, long timeoutMs) {
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
        int consecutiveMisses = 0;
        do {
            checkPreemption();
            RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
            boolean present = emuManager.locatePattern(
                    EMULATOR_NUMBER, frame, template, threshold).isFound();
            consecutiveMisses = present ? 0 : consecutiveMisses + 1;
            if (consecutiveMisses >= 2) {
                return true;
            }
        } while (System.nanoTime() < deadline);
        return false;
    }

private final class LiveBearSessionDriver implements BearSessionCoordinator.Driver {

        private final Instant eventEnd;
        private List<MarchSlotState> lastMarches = List.of();
        private BearSessionCoordinator.State lastState;
        private final Map<Integer, Long> formationTroopCounts = new HashMap<>();
        private final Map<Integer, Set<String>> rejectedCandidatesByFormation = new HashMap<>();

        private LiveBearSessionDriver(Instant eventEnd) {
            this.eventEnd = eventEnd;
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
            try {
                List<MarchSlotState> slots = marchHelper.readMarchQueueSinglePass();
                boolean reliable = !slots.isEmpty() && slots.stream()
                        .anyMatch(slot -> slot.availability() != MarchSlotAvailability.UNKNOWN);
                if (!reliable) {
                    return new BearSessionCoordinator.MarchSnapshot(
                            false, 0, BearSessionCoordinator.OwnRallyObservation.active(
                                    trackedOwnSlot.orElse(0), BearSessionCoordinator.OwnRallyPhase.UNKNOWN, null));
                }

                lastMarches = slots;
                int freeSlots = (int) slots.stream().filter(MarchSlotState::isIdle).count();
                Duration earliestRelease = slots.stream()
                        .filter(MarchSlotState::hasExactReleaseCountdown)
                        .map(MarchSlotState::countdown)
                        .min(Duration::compareTo)
                        .orElse(null);
                BearSessionCoordinator.OwnRallyObservation own = observeOwnRally(
                        slots, trackedOwnSlot, mayAdoptExisting);
                return new BearSessionCoordinator.MarchSnapshot(true, freeSlots, own, earliestRelease);
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
            ImageSearchResultData rallyButton = findFresh(
                    BEAR_RALLY_BUTTON, 80, FRESH_TRANSITION_TIMEOUT_MS);
            if (!rallyButton.isFound()) {
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.STALE_SCREEN);
            }

            tapInside(rallyButton);
            ImageSearchResultData hold = findFresh(
                    RALLY_HOLD_BUTTON, 90, FRESH_TRANSITION_TIMEOUT_MS);
            if (!hold.isFound()) {
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.STALE_SCREEN);
            }

            int rallySeconds = deploymentHelper.readRallySetTimeSeconds(
                    RALLY_DURATION_BASE_MINUTES_VALUE * 60);
            tapInside(hold);
            if (!marchHelper.selectFlag(formation)) {
                pressBack();
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.FORMATION_UNAVAILABLE);
            }
            if (deploymentHelper.hasNoDeployableTroops()) {
                pressBack();
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.FORMATION_UNAVAILABLE);
            }

            long travelSeconds = deploymentHelper.readTravelTimeSeconds();
            if (travelSeconds <= 0) {
                pressBack();
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.OCR_MISS);
            }
            if (now().plusSeconds(rallySeconds + travelSeconds).isAfter(eventEnd)) {
                pressBack();
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.TOO_LATE);
            }

            ImageSearchResultData deploy = findFresh(
                    BEAR_DEPLOY_BUTTON, 90, FRESH_TRANSITION_TIMEOUT_MS);
            if (!deploy.isFound()) {
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.STALE_SCREEN);
            }
            tapInside(deploy);
            DeploymentPostTapRead postTap = awaitPostDeployState(FRESH_TRANSITION_TIMEOUT_MS);
            if (postTap.marchQueueFull()) {
                deploymentHelper.dismissMarchQueueFullPopup();
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.MARCH_QUEUE_FULL);
            }
            if (postTap.sameTargetDialog()) {
                leaveVerifiedFormationScreen();
                recover(BearSessionCoordinator.State.OWN_RALLY_STARTING);
                OptionalInt existing = existingRallySlot(readMarchRows());
                return existing.isPresent()
                        ? BearSessionCoordinator.OwnRallyStartResult.alreadyActive(existing.getAsInt())
                        : BearSessionCoordinator.OwnRallyStartResult.recoverable(
                                 BearSessionCoordinator.OwnRallyStartOutcome.DEPLOY_NOT_CONFIRMED);
            }
            if (postTap.deployButton().isFound() || postTap.confirmationDialog().isFound()) {
                return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.DEPLOY_NOT_CONFIRMED);
            }

            for (int attempt = 0; attempt < 3; attempt++) {
                List<MarchSlotState> after = readMarchRows();
                OptionalInt newRally = newlyOccupiedRallySlot(before, after);
                if (newRally.isPresent()) {
                    logInfo(routineLogBearTrapLine("Own rally confirmed in march slot #" + newRally.getAsInt()));
                    return BearSessionCoordinator.OwnRallyStartResult.confirmed(
                            newRally.getAsInt(), Duration.ofSeconds(rallySeconds), Duration.ofSeconds(travelSeconds));
                }
            }
            return BearSessionCoordinator.OwnRallyStartResult.recoverable(
                    BearSessionCoordinator.OwnRallyStartOutcome.DEPLOY_NOT_CONFIRMED);
        }

        @Override
        public BearSessionCoordinator.JoinOutcome joinNext(int formation) {
            int freeBefore = (int) lastMarches.stream().filter(MarchSlotState::isIdle).count();
            try {
                navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);
                ImageSearchResultData war = findFresh(
                        GAME_HOME_WAR, 90, FRESH_TRANSITION_TIMEOUT_MS);
                if (!war.isFound()) {
                    return BearSessionCoordinator.JoinOutcome.NAVIGATION_FAILURE;
                }
                tapInside(war);
                if (!awaitAnyFresh(BEAR_JOIN_PLUS_ICON, 80, FRESH_TRANSITION_TIMEOUT_MS)) {
                    return BearSessionCoordinator.JoinOutcome.NO_JOINABLE_RALLY;
                }

                long knownTroops = formationTroopCounts.getOrDefault(formation, 0L);
                Set<String> rejected = rejectedCandidatesByFormation.computeIfAbsent(
                        formation, ignored -> new HashSet<>());
                BearRallyScanner.ScanResult scan = new BearRallyScanner(templateSearchHelper)
                        .scan(now());
                Optional<BearRallyCandidate> selected = BearRallyCandidateSelector.selectBest(
                        scan.candidates(), knownTroops, rejected);
                if (selected.isEmpty()) {
                    return scan.ocrFailure()
                            ? BearSessionCoordinator.JoinOutcome.OCR_MISS
                            : BearSessionCoordinator.JoinOutcome.NO_JOINABLE_RALLY;
                }
                BearRallyCandidate candidate = selected.get();
                tapInside(candidate.joinButtonArea());
                ImageSearchResultData deployReady = findFresh(
                        BEAR_DEPLOY_BUTTON, 90, FRESH_TRANSITION_TIMEOUT_MS);
                if (!deployReady.isFound()) {
                    rejected.add(candidate.stableKey());
                    return BearSessionCoordinator.JoinOutcome.RALLY_DEPARTED;
                }
                if (!marchHelper.selectFlag(formation)) {
                    pressBack();
                    return BearSessionCoordinator.JoinOutcome.FORMATION_UNAVAILABLE;
                }
                if (deploymentHelper.hasNoDeployableTroops()) {
                    pressBack();
                    return BearSessionCoordinator.JoinOutcome.FORMATION_UNAVAILABLE;
                }
                long selectedTroops = deploymentHelper.readSelectedTroopCount();
                if (selectedTroops < 0) {
                    pressBack();
                    return BearSessionCoordinator.JoinOutcome.OCR_MISS;
                }
                formationTroopCounts.put(formation, selectedTroops);
                if (!candidate.accepts(selectedTroops)) {
                    logInfo(routineLogBearTrapLine(
                            "Skipping rally at row " + candidate.rowY()
                                    + ": formation #" + formation + " has " + selectedTroops
                                    + " troops but only " + candidate.remainingCapacity() + " fit"));
                    rejected.add(candidate.stableKey());
                    pressBack();
                    return BearSessionCoordinator.JoinOutcome.RALLY_FULL;
                }
                ImageSearchResultData deploy = findFresh(
                        BEAR_DEPLOY_BUTTON, 90, FRESH_TRANSITION_TIMEOUT_MS);
                if (!deploy.isFound()) {
                    rejected.add(candidate.stableKey());
                    return BearSessionCoordinator.JoinOutcome.RALLY_DEPARTED;
                }
                tapInside(deploy);
                DeploymentPostTapRead postTap = awaitPostDeployState(FRESH_TRANSITION_TIMEOUT_MS);
                if (postTap.marchQueueFull()) {
                    deploymentHelper.dismissMarchQueueFullPopup();
                    return BearSessionCoordinator.JoinOutcome.MARCH_QUEUE_FULL;
                }
                if (postTap.sameTargetDialog()) {
                    leaveVerifiedFormationScreen();
                    rejected.add(candidate.stableKey());
                    return BearSessionCoordinator.JoinOutcome.ALREADY_JOINED_OR_MARCHING;
                }
                if (postTap.deployButton().isFound() || postTap.confirmationDialog().isFound()) {
                    rejected.add(candidate.stableKey());
                    return BearSessionCoordinator.JoinOutcome.RALLY_FULL;
                }

                List<MarchSlotState> after = readMarchRows();
                int freeAfter = (int) after.stream().filter(MarchSlotState::isIdle).count();
                if (freeAfter < freeBefore) {
                    lastMarches = after;
                    return BearSessionCoordinator.JoinOutcome.JOINED;
                }
                rejected.add(candidate.stableKey());
                return BearSessionCoordinator.JoinOutcome.RALLY_GONE;
            } catch (StopExecutionException e) {
                throw e;
            } catch (Exception e) {
                logWarning(routineLogBearTrapLine("Join attempt lost its screen: " + e.getMessage()));
                return BearSessionCoordinator.JoinOutcome.STALE_SCREEN;
            }
        }

        @Override
        public boolean recover(BearSessionCoordinator.State resumeState) {
            try {
                navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);
                return true;
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
            if (!duration.isZero() && !duration.isNegative()) {
                sleepTask(duration.toMillis());
            }
        }

        @Override
        public void stateChanged(BearSessionCoordinator.State state) {
            if (lastState != state) {
                if (state != BearSessionCoordinator.State.FILL_JOIN_SLOTS
                        && state != BearSessionCoordinator.State.WAIT_FOR_NEXT_USEFUL_DEADLINE
                        && state != BearSessionCoordinator.State.LOCATE_BEAR) {
                    logInfo(routineLogBearTrapLine("Session state: " + state));
                }
                lastState = state;
            }
        }

        private List<MarchSlotState> readMarchRows() {
            List<MarchSlotState> rows = marchHelper.readMarchQueueSinglePass();
            if (!rows.isEmpty()) {
                lastMarches = rows;
            }
            return rows;
        }

        private BearSessionCoordinator.OwnRallyObservation observeOwnRally(
                List<MarchSlotState> slots, OptionalInt trackedOwnSlot, boolean mayAdoptExisting) {
            if (trackedOwnSlot.isPresent()) {
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

        private boolean openBearRallyFromAnchor() {
            if (!recover(BearSessionCoordinator.State.OWN_RALLY_STARTING)) {
                return false;
            }
            if (bearAnchorKnown) {
                tapInside(BEAR_CENTER_POINT_VALUE, BEAR_CENTER_POINT_VALUE);
                if (findFresh(BEAR_RALLY_BUTTON, 80, FRESH_TRANSITION_TIMEOUT_MS).isFound()) {
                    return true;
                }
                bearAnchorKnown = false;
            }

            if (!reachBearTrap(trapNumber)) {
                return false;
            }
            bearAnchorKnown = true;
            tapInside(BEAR_CENTER_POINT_VALUE, BEAR_CENTER_POINT_VALUE);
            return findFresh(BEAR_RALLY_BUTTON, 80, FRESH_TRANSITION_TIMEOUT_MS).isFound();
        }

        private boolean awaitAnyFresh(TemplatesEnum template, int threshold, long timeoutMs) {
            return findFresh(template, threshold, timeoutMs).isFound();
        }

        private DeploymentPostTapRead awaitPostDeployState(long timeoutMs) {
            long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
            DeploymentPostTapRead last = new DeploymentPostTapRead(
                    false, ImageSearchResultData.miss(), false, ImageSearchResultData.miss());
            do {
                checkPreemption();
                last = deploymentHelper.readPostTapScreen();
                if (last.marchQueueFull()
                        || last.confirmationDialog().isFound()
                        || last.sameTargetDialog()
                        || !last.deployButton().isFound()) {
                    return last;
                }
            } while (System.nanoTime() < deadline);
            return last;
        }

        private void leaveVerifiedFormationScreen() {
            pressBack();
            if (findFresh(BEAR_DEPLOY_BUTTON, 90, FRESH_TRANSITION_TIMEOUT_MS).isFound()) {
                pressBack();
            }
        }

        private ImageSearchResultData findFresh(TemplatesEnum template, int threshold, long timeoutMs) {
            long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
            while (System.nanoTime() < deadline) {
                checkPreemption();
                RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
                ImageSearchResultData result = emuManager.locatePattern(
                        EMULATOR_NUMBER, frame, template, threshold);
                if (result.isFound()) {
                    return result;
                }
            }
            return ImageSearchResultData.miss();
        }
    }

private void cleanupFlow(boolean requeueNormalTasks) {
        logInfo(routineLogBearTrapLine("Cleaning up Bear Trap state"));

        if (requeueNormalTasks) {
            requeueDisabledTasksFlow();
        } else {
            logInfo(routineLogBearTrapLine("Shutdown in progress; Gather and Autojoin remain stopped"));
        }
    }
}
