package dev.frostguard.tasks.alliance;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.BotOcrEngine;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;
import java.awt.Color;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AllianceMobilizationRoutine extends DelayedTask {

private static final class OcrSpec {

        static final Pattern ATTEMPTS_PATTERN = Pattern.compile("(\\d{1,2})\\s*/\\s*(\\d{0,3})");
    }

private static final class ScreenSpots {


        static final PointData ATTEMPTS_COUNTER_TOP_LEFT = new PointData(168, 528);
        static final PointData ATTEMPTS_COUNTER_BOTTOM_RIGHT = new PointData(235, 565);


        static final PointData REFRESH_BUTTON = new PointData(200, 805);
        static final PointData REFRESH_CONFIRM_BUTTON = new PointData(510, 790);
        static final PointData ACCEPT_BUTTON = new PointData(500, 805);
        static final PointData FREE_MISSION_CONFIRM = new PointData(340, 780);
        static final PointData BACK_BUTTON = new PointData(50, 50);


        static final PointData[] MONUMENT_CLICKS = {
                new PointData(366, 1014),
                new PointData(250, 870),
                new PointData(366, 1014),
                new PointData(154, 1002),
                new PointData(245, 483)
        };


        static final PointData FREE_MISSION_EXPECTED = new PointData(256, 527);


        static final PointData LEFT_TIMER_TOP_LEFT = new PointData(162, 705);
        static final PointData LEFT_TIMER_BOTTOM_RIGHT = new PointData(280, 743);
        static final PointData RIGHT_TIMER_TOP_LEFT = new PointData(486, 705);
        static final PointData RIGHT_TIMER_BOTTOM_RIGHT = new PointData(595, 743);
    }

private static final class SearchOffsets {


        static final int POINTS_X = 112;
        static final int POINTS_Y = 158;
        static final int POINTS_WIDTH = 75;
        static final int POINTS_HEIGHT = 34;


        static final int TASK_TYPE_MAX_DELTA_X = 150;
        static final int TASK_TYPE_MAX_DELTA_Y = 100;


        static final int RUNNING_TASK_X = -50;
        static final int RUNNING_TASK_Y = 100;
        static final int RUNNING_TASK_WIDTH = 300;
        static final int RUNNING_TASK_HEIGHT = 150;


        static final int FREE_MISSION_TOLERANCE = 50;
    }

private static final class RoutineLimits {
        static final int MAX_NAVIGATION_ATTEMPTS = 3;
        static final int MAX_TEMPLATE_SEARCH_RESULTS_TASK_TYPE = 5;
        static final int MAX_TEMPLATE_SEARCH_RESULTS_120 = 2;
        static final int MONUMENT_BACK_CLICKS_COUNT = 2;
    }

private static final class TimingPlan {
        static final int DEFAULT_COOLDOWN_SECONDS = 300;

        static final int LONG_COOLDOWN_SECONDS = 1800;

        static final int RESCHEDULE_BUFFER_SECONDS = 5;
        static final int RESCHEDULE_WAIT_HOURS = 1;
    }

private static final class MatchGates {
        static final int BONUS = 85;
        static final int TASK_TYPE = 85;
        static final int RUNNING = 85;
        static final int COMPLETED = 85;
        static final int FREE_MISSION = 90;
        static final int MONUMENTS = 94;
    }

private static final class DefaultsShape {
        static final boolean AUTO_ACCEPT = false;
        static final String REWARDS_PERCENTAGE = "Any";
        static final int MINIMUM_POINTS = 0;
        static final boolean TASK_TYPE_ENABLED = false;
    }

private boolean autoAcceptEnabled;

private String rewardsPercentage;

private int minimumPoints200;

private int minimumPoints120;

private boolean buildSpeedupsEnabled;

private boolean buyPackageEnabled;

private boolean chiefGearCharmEnabled;

private boolean chiefGearScoreEnabled;

private boolean defeatBeastsEnabled;

private boolean fireCrystalEnabled;

private boolean gatherResourcesEnabled;

private boolean heroGearStoneEnabled;

private boolean mythicShardEnabled;

private boolean rallyEnabled;

private boolean trainTroopsEnabled;

private boolean trainingSpeedupsEnabled;

private boolean useGemsEnabled;

private boolean useSpeedupsEnabled;

private ResilientOcrExecutor<Duration> durationHelper;

private int consecutiveNoMissionsCount = 0;

private int consecutiveOnlyRunningMissionCount = 0;

public AllianceMobilizationRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

@Override
    protected void execute() {
        initializeOCRHelpersFlow();
        hydrateConfiguration();

        if (!navigateWithRetryFlow()) {
            manageNavigationFailure();
            return;
        }

        AttemptStatusShape attemptStatus = scanAttemptsCounter();

        if (!validateAttemptsRemainingFlow(attemptStatus)) {
            return;
        }

        boolean rescheduleWasSet = seekAndProcessAllTasks();

        if (!rescheduleWasSet) {
            applyFallbackRescheduleFlow();
        }

    }

@Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.ANY;
    }

@Override
    public boolean provideDailyMissionProgress() {
        return false;
    }

@Override
    protected boolean consumesStamina() {
        return false;
    }

private record AttemptStatusShape(int remaining, Integer total) {
    }

private record TaskPassResult(boolean shouldStopProcessing, int shortestCooldown, boolean missionFound,
            boolean onlyRunningMission) {
    }

private record TaskFilterPack(
            boolean search200,
            boolean search120,
            boolean accept200,
            boolean accept120) {
    }

private boolean inspectTaskAvailabilityTimersAndReschedule() {
        int timerSeconds = scanTaskAvailabilityTimers();

        if (timerSeconds > 0) {
            LocalDateTime nextRun = LocalDateTime.now().plusSeconds(timerSeconds + 10);

            reschedule(nextRun);
            logInfo(routineLogAllianceMobilizationLine("Next check in " + (timerSeconds / 60) + "min (task availability timer)"));
            return true;
        }

        return false;
    }

private String routineLogAllianceMobilizationLine(String note) {
        return "AllianceMobilizationRoutine | " + note;
    }

private void inspectAndCollectCompletedTasks() {
        logDebug(routineLogAllianceMobilizationLine("Inspecting for completed tasks to collect rewards..."));

        ImageSearchResultData completedResult = templateSearchHelper.locatePattern(
                TemplatesEnum.AM_COMPLETED,
                SearchConfig.builder()
                        .withThreshold(MatchGates.COMPLETED)
                        .withMaxAttempts(1)
                        .build());

        if (completedResult.isFound()) {
            logInfo(routineLogAllianceMobilizationLine("Completed task detected at " + completedResult.getPoint() + " - collecting rewards"));
            tapInside(completedResult);
            sleepTask(1500);

            logInfo(routineLogAllianceMobilizationLine("Rewards collected from completed task"));
        } else {
            logDebug(routineLogAllianceMobilizationLine("Zero completed tasks detected"));
        }
    }

private int scanRefreshCooldownFromMission(PointData bonusLocation) {


        PointData topLeft;
        PointData bottomRight;
        String missionSide;

        if (bonusLocation.getX() < 360) {


            topLeft = new PointData(75, 712);
            bottomRight = new PointData(299, 798);
            missionSide = "Left";
        } else {


            topLeft = new PointData(410, 712);
            bottomRight = new PointData(627, 798);
            missionSide = "Right";
        }

        logDebug(routineLogAllianceMobilizationLine("Reading refresh cooldown from " + missionSide + " mission area: " + topLeft + " to " + bottomRight));
        sleepTask(500);


        OcrSettingsData timeSettings = OcrSettingsData.assembler()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)

                .stripBackground(true)
                .setTextColor(new Color(158, 14, 14))
                .charWhitelist("0123456789d:")
                .build();

        Duration cooldownTime = durationHelper.attemptRecognition(
                topLeft,
                bottomRight,
                3,

                200L,

                timeSettings,
                GameTimeUtils::isAcceptedFormat,
                GameTimeUtils::parseDuration);

        if (cooldownTime == null) {
            logWarning(routineLogAllianceMobilizationLine("Could not read cooldown time from " + missionSide + " mission area"));
            return TimingPlan.DEFAULT_COOLDOWN_SECONDS;
        }

        int totalSeconds = (int) cooldownTime.getSeconds();
        if (totalSeconds > 0) {
            logInfo(routineLogAllianceMobilizationLine("Cooldown from " + missionSide + " mission: " + totalSeconds + " seconds"));
            return totalSeconds;
        }

        logWarning(routineLogAllianceMobilizationLine("Could not read cooldown from " + missionSide + " mission area, using default 5 minutes"));
        return TimingPlan.DEFAULT_COOLDOWN_SECONDS;
    }

private void inspectAndUseAllianceMonuments() {
        logDebug(routineLogAllianceMobilizationLine("Inspecting for Alliance Monuments button..."));

        ImageSearchResultData monumentsResult = templateSearchHelper.locatePattern(
                TemplatesEnum.AM_ALLIANCE_MONUMENTS,
                SearchConfig.builder()
                        .withThreshold(MatchGates.MONUMENTS)
                        .withMaxAttempts(1)
                        .build());

        if (!monumentsResult.isFound()) {
            logDebug(routineLogAllianceMobilizationLine("Zero Alliance Monuments button detected"));
            return;
        }

        PointData location = monumentsResult.getPoint();
        logInfo(routineLogAllianceMobilizationLine("Alliance Monuments button detected at " + location + " - using it"));

        tapNear(location);
        sleepTask(1500);


        handleMonumentImageRecognition();


        for (int i = 0; i < ScreenSpots.MONUMENT_CLICKS.length; i++) {
            logInfo(routineLogAllianceMobilizationLine("Pressing monument position " + (i + 1) + "/" + ScreenSpots.MONUMENT_CLICKS.length + " at: " +
                    ScreenSpots.MONUMENT_CLICKS[i]));
            tapNear(ScreenSpots.MONUMENT_CLICKS[i]);
            sleepTask(i < 2 ? 1000 : 500);

        }


        for (int i = 1; i <= RoutineLimits.MONUMENT_BACK_CLICKS_COUNT; i++) {
            logInfo(routineLogAllianceMobilizationLine("Pressing back button (" + i + "/" + RoutineLimits.MONUMENT_BACK_CLICKS_COUNT + ") at: "
                    + ScreenSpots.BACK_BUTTON));
            tapNear(ScreenSpots.BACK_BUTTON);
            sleepTask(500);

        }

        logInfo(routineLogAllianceMobilizationLine("Alliance Monuments used finished cleanly"));
    }

private void handleMonumentImageRecognition() {


        TemplatesEnum[] monumentImages = {


        };


        if (monumentImages.length == 0) {
            return;
        }

        logInfo(routineLogAllianceMobilizationLine("Initiating monument image recognition loop..."));
        int clickCount = 0;
        boolean imageFound;

        do {
            imageFound = false;


            for (TemplatesEnum imageTemplate : monumentImages) {
                ImageSearchResultData imageResult = templateSearchHelper.locatePattern(
                        imageTemplate,
                        SearchConfig.builder()
                                .withThreshold(85)
                                .withMaxAttempts(1)
                                .build());

                if (imageResult.isFound()) {
                    imageFound = true;
                    clickCount++;
                    PointData imageLocation = imageResult.getPoint();

                    logInfo(routineLogAllianceMobilizationLine("Monument image detected (" + imageTemplate.name() + ") at " + imageLocation +
                            " - pressing (click #" + clickCount + ")"));


                    tapNear(imageLocation);
                    sleepTask(500);


                    logInfo(routineLogAllianceMobilizationLine("Second click on same position"));
                    tapNear(imageLocation);
                    sleepTask(500);


                    break;
                }
            }

            if (!imageFound) {
                logInfo(routineLogAllianceMobilizationLine("Zero additional monument images detected. Proceeding with fixed clicks. Total recognition clicks: " +
                        clickCount));
            }

        } while (imageFound);
    }

private TaskFilterPack determineTaskSearchFiltersFlow() {
        boolean accept200 = rewardsPercentage.equals("200%") ||
                rewardsPercentage.equals("Both") ||
                rewardsPercentage.equals("Any");

        boolean accept120 = rewardsPercentage.equals("120%") ||
                rewardsPercentage.equals("Both") ||
                rewardsPercentage.equals("Any");


        boolean search200 = accept200 || rewardsPercentage.equals("120%");
        boolean search120 = accept120 || rewardsPercentage.equals("200%");

        return new TaskFilterPack(search200, search120, accept200, accept120);
    }

private boolean validateAttemptsRemainingFlow(AttemptStatusShape attemptStatus) {
        if (attemptStatus != null) {
            String totalDisplay = attemptStatus.total() != null && attemptStatus.total() > 0
                    ? attemptStatus.total().toString()
                    : "?";
            logInfo(routineLogAllianceMobilizationLine("Detected attempts counter: " + attemptStatus.remaining() + "/" + totalDisplay));

            if (attemptStatus.remaining() <= 0) {
                LocalDateTime nextReset = GameTimeUtils.dailyResetTime();
                logInfo(routineLogAllianceMobilizationLine("Zero attempts remaining. Planning next run for next UTC reset at "
                        + nextReset.format(DATETIME_FORMATTER) + "."));
                reschedule(nextReset);
                return false;
            }
        } else {
            logWarning(routineLogAllianceMobilizationLine("Could not detect attempts counter. Proceeding with default processing."));
        }
        return true;
    }

private TemplatesEnum detectTaskTypeNearBonusFlow(PointData bonusLocation) {
        logDebug(routineLogAllianceMobilizationLine("Detecting task type near bonus location: " + bonusLocation));

        TemplatesEnum[] taskTypeTemplates = {
                TemplatesEnum.AM_BUILD_SPEEDUPS,
                TemplatesEnum.AM_BUY_PACKAGE,
                TemplatesEnum.AM_CHIEF_GEAR_CHARM,
                TemplatesEnum.AM_CHIEF_GEAR_SCORE,
                TemplatesEnum.AM_DEFEAT_BEASTS,
                TemplatesEnum.AM_FIRE_CRYSTAL,
                TemplatesEnum.AM_GATHER_RESOURCES,
                TemplatesEnum.AM_HERO_GEAR_STONE,
                TemplatesEnum.AM_MYTHIC_SHARD,
                TemplatesEnum.AM_RALLY,
                TemplatesEnum.AM_TRAIN_TROOPS,
                TemplatesEnum.AM_TRAINING_SPEEDUPS,
                TemplatesEnum.AM_USE_GEMS,
                TemplatesEnum.AM_USE_SPEEDUPS
        };

        for (TemplatesEnum template : taskTypeTemplates) {
            List<ImageSearchResultData> results = templateSearchHelper.locateAllPatterns(
                    template,
                    SearchConfig.builder()
                            .withThreshold(MatchGates.TASK_TYPE)
                            .withMaxAttempts(3)
                            .withMaxResults(RoutineLimits.MAX_TEMPLATE_SEARCH_RESULTS_TASK_TYPE)
                            .build());

            if (results != null && !results.isEmpty()) {
                for (ImageSearchResultData result : results) {
                    int deltaX = Math.abs(result.getPoint().getX() - bonusLocation.getX());
                    int deltaY = Math.abs(result.getPoint().getY() - bonusLocation.getY());

                    logDebug(routineLogAllianceMobilizationLine(template.name() + " detected at (" + result.getPoint().getX() + "," +
                            result.getPoint().getY() + ") - Distance from bonus: deltaX=" + deltaX + "px, deltaY="
                            + deltaY + "px"));


                    if (deltaX < SearchOffsets.TASK_TYPE_MAX_DELTA_X && deltaY < SearchOffsets.TASK_TYPE_MAX_DELTA_Y) {
                        logInfo(routineLogAllianceMobilizationLine("Detected task type: " + template.name() + " at " + result.getPoint() +
                                " (deltaX=" + deltaX + "px, deltaY=" + deltaY + "px)"));
                        return template;
                    } else {
                        logDebug(routineLogAllianceMobilizationLine("Too far from bonus (max: deltaX=" + SearchOffsets.TASK_TYPE_MAX_DELTA_X +
                                "px, deltaY=" + SearchOffsets.TASK_TYPE_MAX_DELTA_Y + "px)"));
                    }
                }
            }
        }

        logWarning(routineLogAllianceMobilizationLine("Zero task type detected near bonus location"));
        return null;
    }

private void applyFallbackRescheduleFlow() {
        logInfo(routineLogAllianceMobilizationLine("Zero tasks processed. Inspecting again in " + (TimingPlan.DEFAULT_COOLDOWN_SECONDS / 60) + " minutes."));
        reschedule(LocalDateTime.now().plusSeconds(TimingPlan.DEFAULT_COOLDOWN_SECONDS));
    }

private int scanTaskAvailabilityTimers() {
        logDebug(routineLogAllianceMobilizationLine("Reading task availability timers from empty task slots..."));

        int leftTimerSeconds = scanTimerFromRegion(
                ScreenSpots.LEFT_TIMER_TOP_LEFT,
                ScreenSpots.LEFT_TIMER_BOTTOM_RIGHT,
                "Left");

        int rightTimerSeconds = scanTimerFromRegion(
                ScreenSpots.RIGHT_TIMER_TOP_LEFT,
                ScreenSpots.RIGHT_TIMER_BOTTOM_RIGHT,
                "Right");

        if (leftTimerSeconds > 0 && rightTimerSeconds > 0) {
            int shortestTimer = Math.min(leftTimerSeconds, rightTimerSeconds);
            logInfo(routineLogAllianceMobilizationLine("Detected task availability timers - using shortest: " + shortestTimer + " seconds"));
            return shortestTimer;
        }

        if (leftTimerSeconds > 0) {
            logInfo(routineLogAllianceMobilizationLine("Detected left timer: " + leftTimerSeconds + " seconds"));
            return leftTimerSeconds;
        }

        if (rightTimerSeconds > 0) {
            logInfo(routineLogAllianceMobilizationLine("Detected right timer: " + rightTimerSeconds + " seconds"));
            return rightTimerSeconds;
        }

        logDebug(routineLogAllianceMobilizationLine("Zero task availability timers detected"));
        return 0;
    }

private boolean reachAllianceMobilization() {
        logInfo(routineLogAllianceMobilizationLine("Moving to Alliance Mobilization..."));

        boolean success = navigationHelper.navigateToEventMenu(
                dev.frostguard.engine.helper.NavigationHelper.EventMenu.ALLIANCE_MOBILIZATION);

        if (!success) {
            logWarning(routineLogAllianceMobilizationLine("Could not navigate to Alliance Mobilization event"));
            return false;
        }

        sleepTask(2000);
        return true;
    }

private void inspectAndUseFreeMission() {
        logDebug(routineLogAllianceMobilizationLine("Inspecting for free mission button..."));

        ImageSearchResultData freeMissionResult = templateSearchHelper.locatePattern(
                TemplatesEnum.AM_PLUS_1_FREE_MISSION,
                SearchConfig.builder()
                        .withThreshold(MatchGates.FREE_MISSION)
                        .withMaxAttempts(1)
                        .build());

        if (!freeMissionResult.isFound()) {
            logDebug(routineLogAllianceMobilizationLine("Zero free mission button detected"));
            return;
        }

        PointData location = freeMissionResult.getPoint();
        int deltaX = Math.abs(location.getX() - ScreenSpots.FREE_MISSION_EXPECTED.getX());
        int deltaY = Math.abs(location.getY() - ScreenSpots.FREE_MISSION_EXPECTED.getY());

        if (deltaX <= SearchOffsets.FREE_MISSION_TOLERANCE && deltaY <= SearchOffsets.FREE_MISSION_TOLERANCE) {
            logInfo(routineLogAllianceMobilizationLine("Free mission button detected at " + location + " (near expected position) - using it"));
            tapNear(location);
            sleepTask(1500);


            logInfo(routineLogAllianceMobilizationLine("Pressing confirm at: " + ScreenSpots.FREE_MISSION_CONFIRM));
            tapNear(ScreenSpots.FREE_MISSION_CONFIRM);
            sleepTask(1500);


            logInfo(routineLogAllianceMobilizationLine("Free mission used finished cleanly"));
        } else {
            logDebug(routineLogAllianceMobilizationLine("Free mission button detected at " + location +
                    " but too far from expected position " + ScreenSpots.FREE_MISSION_EXPECTED + ", skipping"));
        }
    }

private void initializeOCRHelpersFlow() {
        BotOcrEngine provider = new BotOcrEngine(emuManager, EMULATOR_NUMBER);
        this.durationHelper = new ResilientOcrExecutor<>(provider);
    }

private void deferForShortestCooldown(int shortestCooldownSeconds) {
        LocalDateTime nextRun = LocalDateTime.now()
                .plusSeconds(shortestCooldownSeconds + TimingPlan.RESCHEDULE_BUFFER_SECONDS);

        reschedule(nextRun);
        logInfo(routineLogAllianceMobilizationLine("Planning next run based on shortest cooldown: " + shortestCooldownSeconds + " seconds -> "
                + nextRun.format(DATETIME_FORMATTER)));
    }

private AttemptStatusShape decodeZeroAttemptsFormat(String normalized) {
        String condensed = normalized.replaceAll("\\s+", "");
        if (condensed.startsWith("0/")) {
            Integer total = null;
            String afterSlash = condensed.substring(2).replaceAll("[^0-9]", "");
            if (!afterSlash.isEmpty()) {
                total = Integer.parseInt(afterSlash);
            }
            return new AttemptStatusShape(0, total);
        }
        return null;
    }

private void manageNavigationFailure() {
        LocalDateTime nextMonday = GameTimeUtils.nextWeekStart();
        logInfo(routineLogAllianceMobilizationLine("Could not navigate after " + RoutineLimits.MAX_NAVIGATION_ATTEMPTS +
                " attempts. Event may not be active. Retrying on next Monday at "
                + nextMonday.format(DATETIME_FORMATTER) + "."));
        reschedule(nextMonday);
    }

private boolean seekAndProcessAllTasks() {
        logInfo(routineLogAllianceMobilizationLine("Scanning for tasks (Filter: " + rewardsPercentage +
                ", Min points 200%: " + minimumPoints200 +
                ", Min points 120%: " + minimumPoints120 +
                ", Auto-accept: " + autoAcceptEnabled + ")"));

        inspectAndCollectCompletedTasks();
        inspectAndUseFreeMission();

        TaskFilterPack filters = determineTaskSearchFiltersFlow();
        boolean anyTaskRunning = inspectForRunningTasks(filters);

        if (anyTaskRunning) {
            logInfo(routineLogAllianceMobilizationLine("A task is already running - only one task can run at a time"));
            logInfo(routineLogAllianceMobilizationLine("Other tasks will be refreshed to get better options"));
        }

        int shortestCooldownSeconds = Integer.MAX_VALUE;
        boolean rescheduleWasSet = false;
        boolean anyMissionFound = false;

        boolean onlyRunningMissions = false;


        if (filters.search200) {
            TaskPassResult result = handle200PercentTask(
                    filters.accept200,
                    anyTaskRunning,
                    shortestCooldownSeconds);

            shortestCooldownSeconds = result.shortestCooldown;
            if (result.missionFound) {
                anyMissionFound = true;
                onlyRunningMissions = result.onlyRunningMission;
            }
            if (result.shouldStopProcessing) {
                return true;

            }
        }

        if (filters.search120 && !anyMissionFound) {
            TaskPassResult result = handle120PercentTasks(
                    filters.accept120,
                    anyTaskRunning,
                    shortestCooldownSeconds);

            shortestCooldownSeconds = result.shortestCooldown;
            if (result.missionFound) {
                anyMissionFound = true;
                onlyRunningMissions = result.onlyRunningMission;
            }
            if (result.shouldStopProcessing) {
                return true;

            }
        }


        if (!anyMissionFound) {
            consecutiveNoMissionsCount++;
            logInfo(routineLogAllianceMobilizationLine("Zero missions detected. Consecutive count: " + consecutiveNoMissionsCount + "/3"));

            if (consecutiveNoMissionsCount >= 3) {
                logInfo(routineLogAllianceMobilizationLine("Zero missions detected for 3 consecutive runs - both mission slots completed for today"));
                deferForGameReset();
                return true;
            }
        } else {
            if (consecutiveNoMissionsCount > 0) {
                logDebug(routineLogAllianceMobilizationLine("Missions detected again - resetting consecutive no-mission counter"));
                consecutiveNoMissionsCount = 0;
            }
        }


        if (onlyRunningMissions) {
            consecutiveOnlyRunningMissionCount++;
            logInfo(routineLogAllianceMobilizationLine("Only running mission(s) detected. Consecutive count: " + consecutiveOnlyRunningMissionCount + "/3"));

            if (consecutiveOnlyRunningMissionCount >= 3) {
                logInfo(routineLogAllianceMobilizationLine("Only running missions detected for 3 consecutive runs - planning next run for 30 minutes"));
                reschedule(LocalDateTime.now().plusSeconds(TimingPlan.LONG_COOLDOWN_SECONDS));
                return true;
            }
        } else {
            if (consecutiveOnlyRunningMissionCount > 0) {
                logDebug(routineLogAllianceMobilizationLine("Available mission(s) detected again - resetting only-running-mission counter"));
                consecutiveOnlyRunningMissionCount = 0;
            }
        }

        if (shortestCooldownSeconds < Integer.MAX_VALUE) {
            deferForShortestCooldown(shortestCooldownSeconds);
            rescheduleWasSet = true;
        }

        if (!rescheduleWasSet) {
            rescheduleWasSet = inspectTaskAvailabilityTimersAndReschedule();
        }

        inspectAndUseAllianceMonuments();

        return rescheduleWasSet;
    }

private boolean inspectForRunningTasks(TaskFilterPack filters) {
        if (filters.search200) {
            ImageSearchResultData result200 = templateSearchHelper.locatePattern(
                    TemplatesEnum.AM_200_PERCENT,
                    SearchConfig.builder()
                            .withThreshold(MatchGates.BONUS)
                            .withMaxAttempts(1)
                            .build());

            if (result200.isFound() && hasTaskAlreadyRunning(result200.getPoint())) {
                logInfo(routineLogAllianceMobilizationLine("Task at 200% is already running"));
                return true;
            }
        }

        if (filters.search120) {
            List<ImageSearchResultData> results120 = templateSearchHelper.locateAllPatterns(
                    TemplatesEnum.AM_120_PERCENT,
                    SearchConfig.builder()
                            .withThreshold(MatchGates.BONUS)
                            .withMaxAttempts(3)
                            .withMaxResults(RoutineLimits.MAX_TEMPLATE_SEARCH_RESULTS_120)
                            .build());

            if (results120 != null && !results120.isEmpty()) {
                for (ImageSearchResultData result120 : results120) {
                    if (hasTaskAlreadyRunning(result120.getPoint())) {
                        logInfo(routineLogAllianceMobilizationLine("Task at 120% (" + result120.getPoint() + ") is already running"));
                        return true;
                    }
                }
            }
        }

        return false;
    }

private boolean navigateWithRetryFlow() {
        for (int attempt = 1; attempt <= RoutineLimits.MAX_NAVIGATION_ATTEMPTS; attempt++) {
            logInfo(routineLogAllianceMobilizationLine("Navigation attempt " + attempt + "/" + RoutineLimits.MAX_NAVIGATION_ATTEMPTS));

            if (reachAllianceMobilization()) {
                return true;
            }

            if (attempt < RoutineLimits.MAX_NAVIGATION_ATTEMPTS) {
                logWarning(routineLogAllianceMobilizationLine("Navigation did not complete. Returning to home screen and retrying..."));
                restoreHomeScreen();
                sleepTask(2000);

            }
        }
        return false;
    }

private boolean hasTaskAlreadyRunning(PointData bonusLocation) {
        logDebug(routineLogAllianceMobilizationLine("Inspecting if task is already running near: " + bonusLocation));

        PointData searchTopLeft = new PointData(
                bonusLocation.getX() + SearchOffsets.RUNNING_TASK_X,
                bonusLocation.getY() + SearchOffsets.RUNNING_TASK_Y);

        PointData searchBottomRight = new PointData(
                searchTopLeft.getX() + SearchOffsets.RUNNING_TASK_WIDTH,
                searchTopLeft.getY() + SearchOffsets.RUNNING_TASK_HEIGHT);

        ImageSearchResultData barResult = templateSearchHelper.locatePattern(
                TemplatesEnum.AM_BAR_X,
                SearchConfig.builder()
                        .withThreshold(MatchGates.RUNNING)
                        .withMaxAttempts(1)
                        .withDelay(100L)
                        .withArea(new AreaData(searchTopLeft, searchBottomRight))
                        .build());

        if (barResult.isFound()) {
            logInfo(routineLogAllianceMobilizationLine("Timer bar detected at " + barResult.getPoint() + " - task is already running"));
            return true;
        }

        logDebug(routineLogAllianceMobilizationLine("Zero timer bar detected - task is available"));
        return false;
    }

private TaskPassResult handle120PercentTasks(
            boolean shouldAccept,
            boolean anyTaskRunning,
            int currentShortestCooldown) {

        logDebug(routineLogAllianceMobilizationLine("Scanning for 120% bonus tasks (max 2 positions)..."));

        List<ImageSearchResultData> results120 = templateSearchHelper.locateAllPatterns(
                TemplatesEnum.AM_120_PERCENT,
                SearchConfig.builder()
                        .withThreshold(MatchGates.BONUS)
                        .withMaxAttempts(3)
                        .withMaxResults(RoutineLimits.MAX_TEMPLATE_SEARCH_RESULTS_120)
                        .build());

        if (results120 == null || results120.isEmpty()) {
            logDebug(routineLogAllianceMobilizationLine("Zero 120% bonus tasks detected"));
            return new TaskPassResult(false, currentShortestCooldown, false, false);
        }

        logInfo(routineLogAllianceMobilizationLine("Detected " + results120.size() + " x 120% bonus task(s)"));

        int shortestCooldown = currentShortestCooldown;
        boolean anyMissionFound = false;
        boolean anyAvailableMissionFound = false;

        for (ImageSearchResultData result120 : results120) {
            if (hasTaskAlreadyRunning(result120.getPoint())) {
                logInfo(routineLogAllianceMobilizationLine("Task at 120% (" + result120.getPoint() + ") is already running - skipping this one"));
                anyMissionFound = true;

                continue;

            }

            anyAvailableMissionFound = true;


            TaskPassResult result = handleIndividualTask(
                    result120.getPoint(),
                    shouldAccept,
                    anyTaskRunning,
                    minimumPoints120,
                    shortestCooldown,
                    "120%");

            shortestCooldown = result.shortestCooldown;
            anyMissionFound = anyMissionFound || result.missionFound;

            if (result.shouldStopProcessing) {
                return new TaskPassResult(result.shouldStopProcessing, result.shortestCooldown, true, false);


            }
        }


        boolean onlyRunningMission = anyMissionFound && !anyAvailableMissionFound;
        return new TaskPassResult(false, shortestCooldown, anyMissionFound, onlyRunningMission);
    }

private AttemptStatusShape decodeAttemptsFromOCR(String ocrResult) {
        if (ocrResult == null || ocrResult.trim().isEmpty()) {
            return null;
        }

        String normalized = normalizeOCRTextFlow(ocrResult);

        Matcher matcher = OcrSpec.ATTEMPTS_PATTERN.matcher(normalized);
        if (matcher.find()) {
            int remaining = Integer.parseInt(matcher.group(1));
            Integer total = decodeTotalAttempts(matcher.group(2));


            if (total != null && total == 0) {
                logDebug(routineLogAllianceMobilizationLine("OcrSpec attempts counter returned total=0, treating as invalid"));
                return null;
            }
            return new AttemptStatusShape(remaining, total);
        }

        return decodeZeroAttemptsFormat(normalized);
    }

private boolean resolveConfigBoolean(ConfigurationKeyEnum key, boolean defaultValue) {
        Boolean value = profile.getConfig(key, Boolean.class);
        return (value != null) ? value : defaultValue;
    }

private String normalizeOCRTextFlow(String text) {
        return text
                .replace('O', '0')
                .replace('o', '0')
                .replace('I', '1')
                .replace('l', '1')
                .replace('|', '1')
                .replaceAll("[^0-9/]+", " ")
                .trim();
    }

private TaskPassResult makeTaskDecisionFlow(
            PointData bonusLocation,
            TemplatesEnum taskType,
            boolean hasTaskTypeEnabled,
            int detectedPoints,
            int minimumPoints,
            boolean shouldAcceptBonusLevel,
            boolean anyTaskRunning,
            int currentShortestCooldown,
            String bonusPercentage) {

        logInfo(routineLogAllianceMobilizationLine("Detected: " + taskType.name() + " (" + detectedPoints + "pts, " + bonusPercentage +
                ", enabled: " + hasTaskTypeEnabled + ")"));


        if (!shouldAcceptBonusLevel) {
            logInfo(routineLogAllianceMobilizationLine("Refreshing (bonus level " + bonusPercentage + " not selected in filter)"));
            int cooldown = pressAndRefreshTask(bonusLocation);
            int newShortest = Math.min(cooldown, currentShortestCooldown);
            return new TaskPassResult(false, newShortest, true, false);

        }


        if (!hasTaskTypeEnabled) {
            logInfo(routineLogAllianceMobilizationLine("Refreshing (mission disabled)"));
            int cooldown = pressAndRefreshTask(bonusLocation);
            int newShortest = Math.min(cooldown, currentShortestCooldown);
            return new TaskPassResult(false, newShortest, true, false);

        }


        if (detectedPoints < minimumPoints) {
            logInfo(routineLogAllianceMobilizationLine("Refreshing (low points: " + detectedPoints + " < " + minimumPoints + ")"));
            int cooldown = pressAndRefreshTask(bonusLocation);
            int newShortest = Math.min(cooldown, currentShortestCooldown);
            return new TaskPassResult(false, newShortest, true, false);

        }


        if (anyTaskRunning) {
            logInfo(routineLogAllianceMobilizationLine("Waiting 1h (task good but another task running)"));
            LocalDateTime nextRun = LocalDateTime.now().plusHours(TimingPlan.RESCHEDULE_WAIT_HOURS);
            reschedule(nextRun);
            return new TaskPassResult(true, 0, true, false);


        }


        if (autoAcceptEnabled) {
            logInfo(routineLogAllianceMobilizationLine("Accepting task"));
            pressAndAcceptTask(bonusLocation);
            return new TaskPassResult(false, currentShortestCooldown, true, false);

        }


        logInfo(routineLogAllianceMobilizationLine("Skipping (auto-accept disabled - user will accept manually)"));
        return new TaskPassResult(false, currentShortestCooldown, true, false);


    }

private void hydrateTaskTypeFlags() {
        this.buildSpeedupsEnabled = resolveConfigBoolean(
                ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_BUILD_SPEEDUPS_BOOL,
                DefaultsShape.TASK_TYPE_ENABLED);

        this.buyPackageEnabled = resolveConfigBoolean(
                ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_BUY_PACKAGE_BOOL,
                DefaultsShape.TASK_TYPE_ENABLED);

        this.chiefGearCharmEnabled = resolveConfigBoolean(
                ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_CHIEF_GEAR_CHARM_BOOL,
                DefaultsShape.TASK_TYPE_ENABLED);

        this.chiefGearScoreEnabled = resolveConfigBoolean(
                ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_CHIEF_GEAR_SCORE_BOOL,
                DefaultsShape.TASK_TYPE_ENABLED);

        this.defeatBeastsEnabled = resolveConfigBoolean(
                ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_DEFEAT_BEASTS_BOOL,
                DefaultsShape.TASK_TYPE_ENABLED);

        this.fireCrystalEnabled = resolveConfigBoolean(
                ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_FIRE_CRYSTAL_BOOL,
                DefaultsShape.TASK_TYPE_ENABLED);

        this.gatherResourcesEnabled = resolveConfigBoolean(
                ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_GATHER_RESOURCES_BOOL,
                DefaultsShape.TASK_TYPE_ENABLED);

        this.heroGearStoneEnabled = resolveConfigBoolean(
                ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_HERO_GEAR_STONE_BOOL,
                DefaultsShape.TASK_TYPE_ENABLED);

        this.mythicShardEnabled = resolveConfigBoolean(
                ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_MYTHIC_SHARD_BOOL,
                DefaultsShape.TASK_TYPE_ENABLED);

        this.rallyEnabled = resolveConfigBoolean(
                ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_RALLY_BOOL,
                DefaultsShape.TASK_TYPE_ENABLED);

        this.trainTroopsEnabled = resolveConfigBoolean(
                ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_TRAIN_TROOPS_BOOL,
                DefaultsShape.TASK_TYPE_ENABLED);

        this.trainingSpeedupsEnabled = resolveConfigBoolean(
                ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_TRAINING_SPEEDUPS_BOOL,
                DefaultsShape.TASK_TYPE_ENABLED);

        this.useGemsEnabled = resolveConfigBoolean(
                ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_USE_GEMS_BOOL,
                DefaultsShape.TASK_TYPE_ENABLED);

        this.useSpeedupsEnabled = resolveConfigBoolean(
                ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_USE_SPEEDUPS_BOOL,
                DefaultsShape.TASK_TYPE_ENABLED);
    }

private void hydrateConfiguration() {
        this.autoAcceptEnabled = resolveConfigBoolean(
                ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_AUTO_ACCEPT_BOOL,
                DefaultsShape.AUTO_ACCEPT);

        this.rewardsPercentage = resolveConfigString(
                ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_REWARDS_PERCENTAGE_STRING,
                DefaultsShape.REWARDS_PERCENTAGE);


        this.minimumPoints200 = resolveConfigInt(
                ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_MINIMUM_POINTS_200_INT,
                DefaultsShape.MINIMUM_POINTS);

        this.minimumPoints120 = resolveConfigInt(
                ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_MINIMUM_POINTS_120_INT,
                DefaultsShape.MINIMUM_POINTS);


        hydrateTaskTypeFlags();

        logDebug(routineLogAllianceMobilizationLine(String.format(
                "Configuration loaded - Auto-accept: %s, Filter: %s, Min 200%%: %d, Min 120%%: %d",
                autoAcceptEnabled, rewardsPercentage, minimumPoints200, minimumPoints120)));
    }

private TaskPassResult handleIndividualTask(
            PointData bonusLocation,
            boolean shouldAcceptBonusLevel,
            boolean anyTaskRunning,
            int minimumPoints,
            int currentShortestCooldown,
            String bonusPercentage) {

        int detectedPoints = scanPointsNearBonus(bonusLocation);

        if (detectedPoints < 0) {
            logWarning(routineLogAllianceMobilizationLine("Could not read points for " + bonusPercentage + " task - skipping"));
            return new TaskPassResult(false, currentShortestCooldown, true, false);


        }

        TemplatesEnum taskType = detectTaskTypeNearBonusFlow(bonusLocation);

        if (taskType == null) {
            logInfo(routineLogAllianceMobilizationLine("Task type not detected at " + bonusLocation));
            return new TaskPassResult(false, currentShortestCooldown, true, false);


        }

        logInfo(routineLogAllianceMobilizationLine("Task type detected: " + taskType.name()));
        boolean isEnabled = hasTaskTypeEnabled(taskType);

        return makeTaskDecisionFlow(
                bonusLocation,
                taskType,
                isEnabled,
                detectedPoints,
                minimumPoints,
                shouldAcceptBonusLevel,
                anyTaskRunning,
                currentShortestCooldown,
                bonusPercentage);
    }

private TaskPassResult handle200PercentTask(
            boolean shouldAccept,
            boolean anyTaskRunning,
            int currentShortestCooldown) {

        logDebug(routineLogAllianceMobilizationLine("Scanning for 200% bonus task..."));

        ImageSearchResultData result200 = templateSearchHelper.locatePattern(
                TemplatesEnum.AM_200_PERCENT,
                SearchConfig.builder()
                        .withThreshold(MatchGates.BONUS)
                        .withMaxAttempts(1)
                        .build());

        if (!result200.isFound()) {
            logDebug(routineLogAllianceMobilizationLine("Zero 200% bonus task detected"));
            return new TaskPassResult(false, currentShortestCooldown, false, false);
        }

        if (hasTaskAlreadyRunning(result200.getPoint())) {
            logInfo(routineLogAllianceMobilizationLine("Task at 200% is already running - skipping this one"));
            return new TaskPassResult(false, currentShortestCooldown, true, true);


        }

        return handleIndividualTask(
                result200.getPoint(),
                shouldAccept,
                anyTaskRunning,
                minimumPoints200,
                currentShortestCooldown,
                "200%");
    }

private int scanTimerFromRegion(PointData topLeft, PointData bottomRight, String timerName) {
        OcrSettingsData timeSettings = OcrSettingsData.assembler()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)

                .charWhitelist("0123456789d:")
                .build();

        Duration cooldownTime = durationHelper.attemptRecognition(
                topLeft,
                bottomRight,
                3,

                200L,

                timeSettings,
                GameTimeUtils::isAcceptedFormat,
                GameTimeUtils::parseDuration);

        if (cooldownTime == null) {
            logDebug(routineLogAllianceMobilizationLine("Could not read " + timerName + " timer from screen"));
            return 0;
        }

        int totalSeconds = (int) cooldownTime.getSeconds();
        if (totalSeconds > 0) {
            logInfo(routineLogAllianceMobilizationLine("Cooldown from " + timerName + " timer: " + totalSeconds + " seconds"));
            return totalSeconds;
        }

        return 0;
    }

private int scanPointsNearBonus(PointData bonusLocation) {
        logDebug(routineLogAllianceMobilizationLine("Reading points near bonus location: " + bonusLocation));

        PointData topLeft = new PointData(
                bonusLocation.getX() + SearchOffsets.POINTS_X,
                bonusLocation.getY() + SearchOffsets.POINTS_Y);

        PointData bottomRight = new PointData(
                topLeft.getX() + SearchOffsets.POINTS_WIDTH,
                topLeft.getY() + SearchOffsets.POINTS_HEIGHT);

        logDebug(routineLogAllianceMobilizationLine("OcrSpec area for points: " + topLeft + " to " + bottomRight));

        String ocrResult = stringHelper.attemptRecognition(
                topLeft,
                bottomRight,
                1,
                300L,
                null,
                s -> !s.isEmpty(),
                s -> s);

        if (ocrResult != null && !ocrResult.trim().isEmpty()) {
            String numericValue = ocrResult.replaceAll("[^0-9]", "");

            if (!numericValue.isEmpty()) {
                int points = Integer.parseInt(numericValue);
                logInfo(routineLogAllianceMobilizationLine("Detected points on overview: " + points));
                return points;
            }
        }

        logWarning(routineLogAllianceMobilizationLine("Could not read points near bonus after OcrSpec attempts"));
        return -1;
    }

private AttemptStatusShape scanAttemptsCounter() {
        String ocrResult = stringHelper.attemptRecognition(
                ScreenSpots.ATTEMPTS_COUNTER_TOP_LEFT,
                ScreenSpots.ATTEMPTS_COUNTER_BOTTOM_RIGHT,
                1,
                300L,
                null,
                s -> !s.isEmpty(),
                s -> s);

        logDebug(routineLogAllianceMobilizationLine("Attempts counter OcrSpec result: '" + ocrResult + "'"));

        return decodeAttemptsFromOCR(ocrResult);
    }

private String resolveConfigString(ConfigurationKeyEnum key, String defaultValue) {
        String value = profile.getConfig(key, String.class);
        return (value != null && !value.trim().isEmpty()) ? value : defaultValue;
    }

private int pressAndRefreshTask(PointData bonusLocation) {
        tapNear(bonusLocation);
        sleepTask(2000);


        tapNear(ScreenSpots.REFRESH_BUTTON);
        sleepTask(1500);


        tapNear(ScreenSpots.REFRESH_CONFIRM_BUTTON);
        sleepTask(1500);


        int cooldownSeconds = scanRefreshCooldownFromMission(bonusLocation);

        logInfo(routineLogAllianceMobilizationLine("Cooldown: " + cooldownSeconds + "s"));
        return cooldownSeconds;
    }

private Integer decodeTotalAttempts(String totalGroup) {
        if (totalGroup != null && !totalGroup.isEmpty()) {
            return Integer.parseInt(totalGroup);
        }
        return null;
    }

private void pressAndAcceptTask(PointData bonusLocation) {
        tapNear(bonusLocation);
        sleepTask(2000);


        tapNear(ScreenSpots.ACCEPT_BUTTON);
        sleepTask(1500);

    }

private boolean hasTaskTypeEnabled(TemplatesEnum taskType) {
        logDebug(routineLogAllianceMobilizationLine("Inspecting if task type is enabled: " + taskType.name()));

        switch (taskType) {
            case AM_BUILD_SPEEDUPS:
                return buildSpeedupsEnabled;
            case AM_BUY_PACKAGE:
                return buyPackageEnabled;
            case AM_CHIEF_GEAR_CHARM:
                return chiefGearCharmEnabled;
            case AM_CHIEF_GEAR_SCORE:
                return chiefGearScoreEnabled;
            case AM_DEFEAT_BEASTS:
                return defeatBeastsEnabled;
            case AM_FIRE_CRYSTAL:
                return fireCrystalEnabled;
            case AM_GATHER_RESOURCES:
                return gatherResourcesEnabled;
            case AM_HERO_GEAR_STONE:
                return heroGearStoneEnabled;
            case AM_MYTHIC_SHARD:
                return mythicShardEnabled;
            case AM_RALLY:
                return rallyEnabled;
            case AM_TRAIN_TROOPS:
                return trainTroopsEnabled;
            case AM_TRAINING_SPEEDUPS:
                return trainingSpeedupsEnabled;
            case AM_USE_GEMS:
                return useGemsEnabled;
            case AM_USE_SPEEDUPS:
                return useSpeedupsEnabled;
            default:
                logWarning(routineLogAllianceMobilizationLine("Unknown task type: " + taskType.name()));
                return false;
        }
    }

private void restoreHomeScreen() {
        try {
            navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
        } catch (Exception e) {
            logWarning(routineLogAllianceMobilizationLine("Could not return to home screen: " + e.getMessage()));
        }
    }

private void deferForGameReset() {
        LocalDateTime nextReset = GameTimeUtils.dailyResetTime();
        logInfo(routineLogAllianceMobilizationLine("Planning next run for game reset at " + nextReset.format(DATETIME_FORMATTER) + " (next day 00:00 UTC)"));
        reschedule(nextReset);
    }

private int resolveConfigInt(ConfigurationKeyEnum key, int defaultValue) {
        Integer value = profile.getConfig(key, Integer.class);
        return (value != null) ? value : defaultValue;
    }
}
