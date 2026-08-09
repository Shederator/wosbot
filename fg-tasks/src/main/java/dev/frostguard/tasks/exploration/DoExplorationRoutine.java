package dev.frostguard.tasks.exploration;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.SizeData;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;

import java.time.LocalDateTime;

public class DoExplorationRoutine extends DelayedTask {

    private static final PointData EXPLORATION_ENTRY_TOP_LEFT = new PointData(40, 1190);
    private static final PointData EXPLORATION_ENTRY_BOTTOM_RIGHT = new PointData(100, 1250);
    private static final PointData QUICK_DEPLOY_TOP_LEFT = new PointData(55, 1170);
    private static final PointData QUICK_DEPLOY_BOTTOM_RIGHT = new PointData(330, 1220);
    private static final PointData FIGHT_BUTTON_TOP_LEFT = new PointData(390, 1170);
    private static final PointData FIGHT_BUTTON_BOTTOM_RIGHT = new PointData(670, 1220);
    private static final PointData VICTORY_CONTINUE_TOP_LEFT = new PointData(400, 990);
    private static final PointData VICTORY_CONTINUE_BOTTOM_RIGHT = new PointData(658, 1038);

    private static final long MAX_FIGHTING_WINDOW_MS = 120_000L;
    private static final long RESULT_SEARCH_START_DELAY_MS = 15_000L;
    private static final long RESULT_DETECTION_WINDOW_MS = 25_000L;
    private static final long RESULT_DETECTION_DELAY_MS = 1_000L;
    private static final long EXPLORATION_SCREEN_LOAD_DELAY_MS = 500L;
    private static final long EXPLORE_BUTTON_TAP_DELAY_MS = 300L;
    private static final long QUICK_DEPLOY_DELAY_MS = 300L;
    private static final long VICTORY_CONTINUE_DELAY_MS = 200L;

    public DoExplorationRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    protected void execute() {
        logInfo("Opening exploration screen...");
        openExplorationScreen();

        logInfo("Searching exploration button...");
        ImageSearchResultData exploreButton = detectExploreButton();
        if (exploreButton == null || !exploreButton.isFound()) {
            logWarning("Exploration button not found");
            rescheduleInOneHour();
            return;
        }

        logInfo("Exploring...");
        tapExploreButton(exploreButton);

        boolean fightingWindowReached = fightUntilStoppedOrExpired();
        if (fightingWindowReached) {
            logWarning("Exploration fighting window reached 2 minutes. Rescheduling...");
        }

        rescheduleInOneHour();
    }

    private void openExplorationScreen() {
        logInfo("Tapping exploration entry area...");
        tapInside(EXPLORATION_ENTRY_TOP_LEFT, EXPLORATION_ENTRY_BOTTOM_RIGHT);
        sleepTask(EXPLORATION_SCREEN_LOAD_DELAY_MS);
    }

    private ImageSearchResultData detectExploreButton() {
        return templateSearchHelper.locatePattern(
                TemplatesEnum.EXPLORATION_BUTTON,
                SearchConfig.builder().withMaxAttempts(3).withDelay(1000L).build());
    }

    private void tapExploreButton(ImageSearchResultData exploreButton) {
        logInfo("Exploration button found at " + exploreButton.getPoint()
                + " with size " + exploreButton.getTemplateSize() + ". Tapping detected area...");
        tapSearchResultArea(exploreButton);
        sleepTask(EXPLORE_BUTTON_TAP_DELAY_MS);
    }

    private boolean fightUntilStoppedOrExpired() {
        long fightingStartedAt = System.nanoTime();
        int battleNumber = 1;

        while (!isFightingWindowExpired(fightingStartedAt)) {
            logInfo("Starting exploration battle #" + battleNumber + "...");
            startBattle();

            logInfo("Waiting " + RESULT_SEARCH_START_DELAY_MS / 1_000L
                    + " seconds before battle result detection...");
            if (!sleepWithinFightingWindow(RESULT_SEARCH_START_DELAY_MS, fightingStartedAt)) {
                break;
            }

            logInfo("Waiting for battle result...");
            BattleResult battleResult = waitForBattleResult(fightingStartedAt);
            if (battleResult == BattleResult.VICTORY) {
                handleVictory();
                battleNumber++;
            } else if (battleResult == BattleResult.DEFEAT) {
                handleDefeat();
                return false;
            } else {
                logWarning("Battle locked: Neither victory nor defeat appeared after battle animation.");
                return false;
            }
        }

        return true;
    }

    private void startBattle() {
        logInfo("Tapping quick deploy...");
        tapInside(QUICK_DEPLOY_TOP_LEFT, QUICK_DEPLOY_BOTTOM_RIGHT);
        sleepTask(QUICK_DEPLOY_DELAY_MS);
        logInfo("Tapping fight button...");
        tapInside(FIGHT_BUTTON_TOP_LEFT, FIGHT_BUTTON_BOTTOM_RIGHT);
    }

    private void handleVictory() {
        logInfo("Victory! Continue...");
        StatisticsService.obtain().addToCounter(profile, "Exploration Fights Won", 1);
        logInfo("Tapping victory continue area...");
        tapInside(VICTORY_CONTINUE_TOP_LEFT, VICTORY_CONTINUE_BOTTOM_RIGHT);
        sleepTask(VICTORY_CONTINUE_DELAY_MS);
    }

    private void handleDefeat() {
        logInfo("Defeated.. Rescheduling...");
        StatisticsService.obtain().addToCounter(profile, "Exploration Fights Lost", 1);
    }

    private BattleResult waitForBattleResult(long fightingStartedAt) {
        long detectionStartedAt = System.nanoTime();
        int attempt = 1;

        while (!isElapsed(detectionStartedAt, RESULT_DETECTION_WINDOW_MS)
                && !isFightingWindowExpired(fightingStartedAt)) {
            logInfo("Battle result detection attempt #" + attempt + "...");
            if (isTemplateFound(TemplatesEnum.EXPLORATION_VICTORY)) {
                logInfo("Victory template detected.");
                return BattleResult.VICTORY;
            }

            if (isTemplateFound(TemplatesEnum.EXPLORATION_DEFEAT)) {
                logInfo("Defeat template detected.");
                return BattleResult.DEFEAT;
            }

            logInfo("Battle result not detected. Retrying in "
                    + RESULT_DETECTION_DELAY_MS / 1_000L + " second...");
            sleepWithinDetectionWindow(detectionStartedAt, fightingStartedAt);
            attempt++;
        }

        return BattleResult.NOT_FOUND;
    }

    private boolean isTemplateFound(TemplatesEnum template) {
        ImageSearchResultData result = templateSearchHelper.locatePattern(
                template,
                SearchConfig.builder().withMaxAttempts(1).withDelay(0L).build());
        return result != null && result.isFound();
    }

    private void tapSearchResultArea(ImageSearchResultData result) {
        SizeData templateSize = result.getTemplateSize();
        if (templateSize == null || templateSize.getWidth() <= 0 || templateSize.getHeight() <= 0) {
            tapInside(result);
            return;
        }

        PointData center = result.getPoint();
        PointData topLeft = new PointData(
                center.getX() - templateSize.getWidth() / 2,
                center.getY() - templateSize.getHeight() / 2);
        PointData bottomRight = new PointData(
                center.getX() + templateSize.getWidth() / 2,
                center.getY() + templateSize.getHeight() / 2);
        tapInside(topLeft, bottomRight);
    }

    private boolean sleepWithinFightingWindow(long durationMs, long fightingStartedAt) {
        long remainingMs = remainingMs(fightingStartedAt, MAX_FIGHTING_WINDOW_MS);
        if (remainingMs <= 0L) {
            return false;
        }

        sleepTask(Math.min(durationMs, remainingMs));
        return !isFightingWindowExpired(fightingStartedAt);
    }

    private void sleepWithinDetectionWindow(long detectionStartedAt, long fightingStartedAt) {
        long detectionRemainingMs = remainingMs(detectionStartedAt, RESULT_DETECTION_WINDOW_MS);
        long fightingRemainingMs = remainingMs(fightingStartedAt, MAX_FIGHTING_WINDOW_MS);
        long sleepMs = Math.min(RESULT_DETECTION_DELAY_MS, Math.min(detectionRemainingMs, fightingRemainingMs));

        if (sleepMs > 0L) {
            sleepTask(sleepMs);
        }
    }

    private boolean isFightingWindowExpired(long fightingStartedAt) {
        return isElapsed(fightingStartedAt, MAX_FIGHTING_WINDOW_MS);
    }

    private boolean isElapsed(long startedAt, long durationMs) {
        return remainingMs(startedAt, durationMs) <= 0L;
    }

    private long remainingMs(long startedAt, long durationMs) {
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        return durationMs - elapsedMs;
    }

    private void rescheduleInOneHour() {
        logInfo("Exploration task finished. Rescheduling in 1 hour...");
        reschedule(LocalDateTime.now().plusHours(1));
    }

    private enum BattleResult {
        VICTORY,
        DEFEAT,
        NOT_FOUND
    }
}
