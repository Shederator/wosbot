package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.engine.helper.BearTrapHelper;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.nav.RallyFlagCoordinates;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.service.BotOcrEngine;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.RegexNumberParser;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.List;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.*;
import static dev.frostguard.api.configs.TemplatesEnum.*;

public class BearTrapRoutine extends DelayedTask {

private final AtomicBoolean ownRallyActive = new AtomicBoolean(false);

private ScheduledExecutorService rallyScheduler;

private ScheduledFuture<?> rallyResetTask;

private List<Integer> joinFlags = new ArrayList<>();

private int currentJoinFlagIndex = 0;

private ResilientOcrExecutor<Duration> durationHelper;

private static final int TRAP_DURATION_MINUTES_VALUE = 30;

private static final int TRAP_ACTIVATION_OFFSET_MINUTES_VALUE = 30;

private static final int STATUS_LOG_INTERVAL_VALUE = 10;

private static final int OWN_RALLY_MIN_REMAINING_SECONDS_VALUE = 360;

private static final int RALLY_DURATION_BASE_MINUTES_VALUE = 5;

private static final int RALLY_DURATION_BUFFER_SECONDS_VALUE = 3;

private static final int RALLY_RETURN_BUFFER_MINUTES_VALUE = 5;

private static final int MAX_GATHER_RECALL_ATTEMPTS_LIMIT = 120;

private static final int MARCH_TIME_OCR_MAX_RETRIES_MS = 5;

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

private static final PointData MARCH_TIME_OCR_TL_MS = new PointData(504, 1134);

private static final PointData MARCH_TIME_OCR_BR_MS = new PointData(622, 1162);

private static final PointData FREE_MARCHES_OCR_TL_VALUE = new PointData(203, 200);

private static final PointData FREE_MARCHES_OCR_BR_VALUE = new PointData(246, 226);

private static final int DEFAULT_TRAP_NUMBER_VALUE = 1;

private static final int DEFAULT_PREPARATION_TIME_MINUTES_MS = 5;

private static final int DEFAULT_OWN_RALLY_FLAG_VALUE = 1;

private static final int DEFAULT_JOIN_RALLY_FLAG_VALUE = 1;

private static final boolean DEFAULT_CALL_OWN_RALLY_VALUE = false;

private static final boolean DEFAULT_JOIN_RALLY_VALUE = false;

private static final boolean DEFAULT_USE_PETS_VALUE = false;

private static final boolean DEFAULT_RECALL_TROOPS_VALUE = false;

private static final int DEFAULT_FREE_MARCHES_FALLBACK_VALUE = 1;

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

private static final TesseractSettingsData FREE_MARCHES_OCR_SETTINGS_VALUE = TesseractSettingsData.assembler()
            .charWhitelist("0123456789/")
            .stripBackground(true)
            .setTextColor(new Color(253, 253, 253))
            .setReuseLastImage(true)
            .build();

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
            initializeOCRHelpersFlow();

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
                BEAR_TRAP_SCHEDULE_DATETIME_STRING,
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

private void initializeOCRHelpersFlow() {
        BotOcrEngine provider = new BotOcrEngine(emuManager, EMULATOR_NUMBER);
        this.durationHelper = new ResilientOcrExecutor<>(provider);
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
        profile.setConfig(
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

            tryStartOwnRallyFlow(secondsRemaining);
            handleJoinRallies2();

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
                tapRandomPoint(BEAR_TRAP_1_GO_BUTTON_TL_VALUE, BEAR_TRAP_1_GO_BUTTON_BR_VALUE, 1, 300);
                return true;
            case 2:
                tapRandomPoint(BEAR_TRAP_2_GO_BUTTON_TL_VALUE, BEAR_TRAP_2_GO_BUTTON_BR_VALUE, 1, 300);
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

private void tryStartOwnRallyFlow(long secondsRemaining) {
        if (!callOwnRally || ownRallyActive.get() || secondsRemaining <= OWN_RALLY_MIN_REMAINING_SECONDS_VALUE) {
            return;
        }

        try {
            long marchDurationSeconds = beginOwnRally();

            if (marchDurationSeconds > 0) {
                LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
                LocalDateTime returnTime = now.plusSeconds(marchDurationSeconds * 2 + 3)
                        .plusMinutes(RALLY_RETURN_BUFFER_MINUTES_VALUE);

                logInfo(routineLogBearTrapLine("Own rally started finished cleanly, returning in: " + returnTime.format(TIME_FORMATTER)));
                ownRallyActive.set(true);
                queueRallyFlagReset(marchDurationSeconds);
                sleepTask(500);

            } else {
                logWarning(routineLogBearTrapLine("Could not start rally (may already be active)"));
            }
        } catch (dev.frostguard.engine.error.ADBConnectionException e) {
            logWarning(routineLogBearTrapLine("ADB connection error during rally startup (emulator may be lagging): " + e.getMessage()));
            logDebug(routineLogBearTrapLine("Skipping this rally startup attempt, will retry on next cycle"));
            ownRallyActive.set(false);


        } catch (Exception e) {
            logError(routineLogBearTrapLine("Unexpected error during rally startup: " + e.getMessage()), e);
            ownRallyActive.set(false);


        }
    }

private boolean resolveConfigBoolean(ConfigurationKeyEnum key, boolean defaultValue) {
        Boolean value = profile.getConfig(key, Boolean.class);
        return (value != null) ? value : defaultValue;
    }

private void hydrateConfiguration() {
        this.referenceTrapTime = resolveConfigDateTime(BEAR_TRAP_SCHEDULE_DATETIME_STRING);
        this.trapPreparationTime = resolveConfigInt(BEAR_TRAP_PREPARATION_TIME_INT, DEFAULT_PREPARATION_TIME_MINUTES_MS);
        this.trapNumber = resolveConfigInt(BEAR_TRAP_NUMBER_INT, DEFAULT_TRAP_NUMBER_VALUE);
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

private int resolveNextJoinFlag() {
        if (joinFlags.isEmpty()) {
            return DEFAULT_JOIN_RALLY_FLAG_VALUE;
        }

        int flag = joinFlags.get(currentJoinFlagIndex);
        currentJoinFlagIndex = (currentJoinFlagIndex + 1) % joinFlags.size();

        return flag;
    }

private void queueRallyFlagReset(long marchSeconds) {
        long durationSeconds = RALLY_DURATION_BASE_MINUTES_VALUE * 60 +
                marchSeconds * 2 -
                RALLY_DURATION_BUFFER_SECONDS_VALUE;

        if (rallyScheduler == null || rallyScheduler.isShutdown() || rallyScheduler.isTerminated()) {
            rallyScheduler = Executors.newSingleThreadScheduledExecutor();
        }
        rallyResetTask = rallyScheduler.schedule(
                () -> {
                    ownRallyActive.set(false);
                    logInfo(routineLogBearTrapLine("Rally active flag automatically reset after duration"));
                },
                durationSeconds,
                TimeUnit.SECONDS);

        logDebug(routineLogBearTrapLine("Scheduled rally flag reset in " + durationSeconds + " seconds"));
    }

private void disableAutojoinFlow() {
        tapRandomPoint(ALLIANCE_BUTTON_TL_VALUE, ALLIANCE_BUTTON_BR_VALUE);
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

        tapRandomPoint(warButton.getPoint(), warButton.getPoint(), 1, 1000);
        sleepTask(1000);


        tapRandomPoint(AUTOJOIN_BUTTON_TL_VALUE, AUTOJOIN_BUTTON_BR_VALUE, 1, 1500);
        sleepTask(500);


        tapRandomPoint(AUTOJOIN_STOP_BUTTON_TL_VALUE, AUTOJOIN_STOP_BUTTON_BR_VALUE, 1, 500);
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

private int inspectFreeMarches() {
        checkPreemption();

        emuManager.captureScreen(EMULATOR_NUMBER);

        Integer used = integerHelper.attemptRecognition(
                FREE_MARCHES_OCR_TL_VALUE,
                FREE_MARCHES_OCR_BR_VALUE,
                5,
                10L,
                FREE_MARCHES_OCR_SETTINGS_VALUE,
                RegexNumberParser::hasFractionSyntax,
                RegexNumberParser::numerator);

        checkPreemption();


        Integer total = integerHelper.attemptRecognition(
                FREE_MARCHES_OCR_TL_VALUE,
                FREE_MARCHES_OCR_BR_VALUE,
                5,
                10L,
                FREE_MARCHES_OCR_SETTINGS_VALUE,
                RegexNumberParser::hasFractionSyntax,
                RegexNumberParser::denominator);

        int freeMarches;

        if (used != null && total != null) {
            freeMarches = total - used;
            logInfo(routineLogBearTrapLine("Free marches: " + freeMarches));
        } else {


            freeMarches = DEFAULT_FREE_MARCHES_FALLBACK_VALUE;
            logInfo(routineLogBearTrapLine("Could not read marches (counter may not be visible yet), using default value: " + freeMarches));
        }

        return freeMarches;
    }

private void handleJoinRallies2() {
		// Changed by pernerch | Date: 2026-07-02 | Why: skip rally joining on shared emulators while keeping other Bear Trap actions active.
        if (!joinRally || sharedEmulator) {
            if (sharedEmulator) {
                logInfo(routineLogBearTrapLine("Skipping rally joining because this profile shares an emulator with another account."));
            } else {
                logDebug(routineLogBearTrapLine("Skipping rally joining because joining is disabled."));
            }
            return;
        }

        try {
            int freeMarches = inspectFreeMarches();

            if (freeMarches > 0) {
                ImageSearchResultData warButton = templateSearchHelper.locatePattern(
                        GAME_HOME_WAR,
                        SearchConfig.builder()
                                .withThreshold(90)
                                .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_VALUE)
                                .build());

                if (warButton.isFound()) {
                    logInfo(routineLogBearTrapLine("Entering war section to check for rallies"));
                    tapPoint(warButton.getPoint());
                    manageJoinRallies(freeMarches);
                }
            }
        } catch (dev.frostguard.engine.error.ADBConnectionException e) {
            logWarning(routineLogBearTrapLine("ADB connection error during rally joining (emulator may be lagging): " + e.getMessage()));
            logDebug(routineLogBearTrapLine("Skipping this rally join iteration, will retry on next cycle"));


        } catch (Exception e) {
            logError(routineLogBearTrapLine("Unexpected error during rally joining: " + e.getMessage()), e);


        }
    }

private void manageJoinRallies(int freeMarches) {
        ImageSearchResultData plusIcon = templateSearchHelper.locatePattern(
                BEAR_JOIN_PLUS_ICON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(2)
                        .build());

        if (!plusIcon.isFound()) {
            logWarning(routineLogBearTrapLine("Zero joinable rallies detected (plus icon not present)"));
            navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
            return;
        }

        int selectedFlag = resolveNextJoinFlag();
        logInfo(routineLogBearTrapLine("Joining rally with flag #" + selectedFlag + " (rotation: " + joinFlags + ")"));

        tapRandomPoint(plusIcon.getPoint(), plusIcon.getPoint(), 1, 100);
        sleepTask(300);


        PointData flagPoint = RallyFlagCoordinates.pointForFlag(selectedFlag);
        tapRandomPoint(flagPoint, flagPoint, 1, 0);
        sleepTask(300);


        ImageSearchResultData deploy = templateSearchHelper.locatePattern(
                BEAR_DEPLOY_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_MAX_VALUE)
                        .build());

        if (!deploy.isFound()) {
            logWarning(routineLogBearTrapLine("Deploy button not detected after selecting flag."));
        } else {
            tapPoint(deploy.getPoint());
            sleepTask(500);

        }

        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
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
            tapRandomPoint(recallButton.getPoint(), recallButton.getPoint(), 1, 300);
            sleepTask(300);


            tapRandomPoint(RECALL_CONFIRM_BUTTON_TL_VALUE, RECALL_CONFIRM_BUTTON_BR_VALUE, 1, 200);
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
                    if (flag >= 1 && flag <= 8) {
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

        tapRandomPoint(petsButton.getPoint(), petsButton.getPoint(), 1, 500);
        sleepTask(1000);


        tapRandomPoint(PET_RAZORBACK_TL_VALUE, PET_RAZORBACK_BR_VALUE, 1, 500);
        sleepTask(300);


        tapRandomPoint(PET_QUICK_USE_BUTTON_TL_VALUE, PET_QUICK_USE_BUTTON_BR_VALUE, 1, 500);
        sleepTask(300);


        tapRandomPoint(PET_USE_BUTTON_TL_VALUE, PET_USE_BUTTON_BR_VALUE, 1, 100);
        sleepTask(500);


        pressBack();
        sleepTask(300);


        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
    }

private long scanMarchTime() {
        Duration marchingTime = durationHelper.attemptRecognition(
                MARCH_TIME_OCR_TL_MS,
                MARCH_TIME_OCR_BR_MS,
                MARCH_TIME_OCR_MAX_RETRIES_MS,
                200L,
                null,
                GameTimeUtils::isAcceptedFormat,
                GameTimeUtils::parseDuration);

        if (marchingTime != null) {
            return marchingTime.getSeconds();
        }

        return 0;
    }

private boolean reachBearTrap(int trapNumber) {
        tapRandomPoint(ALLIANCE_BUTTON_TL_VALUE, ALLIANCE_BUTTON_BR_VALUE);
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

        tapRandomPoint(territoryButton.getPoint(), territoryButton.getPoint(), 1, 2000);
        sleepTask(1000);


        tapRandomPoint(SPECIAL_BUILDINGS_BUTTON_TL_VALUE, SPECIAL_BUILDINGS_BUTTON_BR_VALUE, 1, 300);
        sleepTask(500);


        boolean success = touchBearTrapGoButton(trapNumber);

        if (success) {
            sleepTask(2000);

        }

        return success;
    }

private long beginOwnRally() {
        if (!ownRallyActive.compareAndSet(false, true)) {
            return 0;

        }

        logInfo(routineLogBearTrapLine("Calling own rally..."));

        tapRandomPoint(BEAR_CENTER_POINT_VALUE, BEAR_CENTER_POINT_VALUE, 1, 200);
        sleepTask(500);


        ImageSearchResultData rallyButton = templateSearchHelper.locatePattern(
                BEAR_RALLY_BUTTON,
                SearchConfig.builder()
                        .withThreshold(80)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_MAX_VALUE)
                        .build());

        if (!rallyButton.isFound()) {
            logError(routineLogBearTrapLine("Rally button not detected!"));
            ownRallyActive.set(false);
            return 0;
        }

        logInfo(routineLogBearTrapLine("Entering rally menu..."));
        tapRandomPoint(rallyButton.getPoint(), rallyButton.getPoint(), 1, 200);
        sleepTask(500);


        ImageSearchResultData holdRallyButton = templateSearchHelper.locatePattern(
                RALLY_HOLD_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_MAX_VALUE)
                        .build());

        if (!holdRallyButton.isFound()) {
            logError(routineLogBearTrapLine("Hold Rally button not detected!"));
            ownRallyActive.set(false);
            return 0;
        }

        tapRandomPoint(holdRallyButton.getPoint(), holdRallyButton.getPoint(), 1, 200);
        sleepTask(300);


        PointData flagPoint = RallyFlagCoordinates.pointForFlag(ownRallyFlag);
        tapRandomPoint(flagPoint, flagPoint, 1, 200);
        sleepTask(300);


        long marchSeconds = scanMarchTime();

        if (marchSeconds == 0) {
            logError(routineLogBearTrapLine("Could not read march time from screen, defaulting to 30 seconds"));
            marchSeconds = 30;

        }

        ImageSearchResultData deploy = templateSearchHelper.locatePattern(
                BEAR_DEPLOY_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_MAX_VALUE)
                        .build());

        if (!deploy.isFound()) {
            logWarning(routineLogBearTrapLine("Deploy button not detected after selecting flag."));
            ownRallyActive.set(false);
            return 0;
        }

        tapPoint(deploy.getPoint());
        sleepTask(500);


        logInfo(routineLogBearTrapLine("Rally deployed finished cleanly. March time: " + marchSeconds + " seconds"));
        return marchSeconds;
    }

private void cleanupFlow() {
        logInfo(routineLogBearTrapLine("Cleaning up Bear Trap state"));

        ownRallyActive.set(false);

        if (rallyResetTask != null && !rallyResetTask.isDone()) {
            rallyResetTask.cancel(false);
            logDebug(routineLogBearTrapLine("Cancelled pending rally reset task"));
        }

        if (rallyScheduler != null && !rallyScheduler.isShutdown()) {
            rallyScheduler.shutdown();
            try {
                if (!rallyScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    rallyScheduler.shutdownNow();
                }
                logDebug(routineLogBearTrapLine("Rally scheduler shutdown finished cleanly"));
            } catch (InterruptedException e) {
                rallyScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        requeueDisabledTasksFlow();
    }
}
