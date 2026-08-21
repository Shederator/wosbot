package dev.frostguard.tasks.exploration;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.nav.SearchConfigConstants;

import java.time.LocalDateTime;

/**
 * Task responsible for completing daily labyrinth challenges.
 * This task navigates to the labyrinth menu and executes appropriate challenges
 * in the currently active Land of Heroes dungeon.
 */
public class DailyLabyrinthRoutine extends DelayedTask {

    // =========================== CONSTANTS ===========================

    // Navigation points
    private static final PointData SKIP_BUTTON = new PointData(71, 827);
    private static final PointData RESULT_SKIP_BUTTON = new PointData(640, 175);

    // Timing constants
    private static final int MENU_NAVIGATION_DELAY = 1000;
    private static final int BATTLE_COMPLETION_DELAY = 3000;

    // =========================== CONSTRUCTOR ===========================

    public DailyLabyrinthRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    // =========================== TASK OVERRIDES ===========================

    @Override
    public boolean provideDailyMissionProgress() {
        return true;
    }

    @Override
    public LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    protected void execute() {

        try {
            // Step 1: Navigate to labyrinth menu
            if (!navigateToLabyrinthMenu()) {
                rescheduleOneHourLater("Failed to navigate to the Labyrinth menu");
                return;
            }

            // The Daily sidebar opens the currently active Land of Heroes dungeon directly.
            executeCurrentChallenge();

            reschedule(GameTimeUtils.dailyResetTime());

        } catch (Exception e) {
            logError("An error occurred during the Labyrinth task: " + e.getMessage());
            rescheduleOneHourLater("Unexpected error during execution: " + e.getMessage());
        }
    }

    // =========================== NAVIGATION METHODS ===========================

    /**
     * Opens the side menu, switches to city tab, scrolls down and searches for
     * labyrinth
     * 
     * @return true if navigation was successful, false otherwise
     */
    private boolean navigateToLabyrinthMenu() {
        logInfo("Navigating to the Labyrinth menu...");

        if (navigationHelper.navigateToLabyrinth()) {
            logInfo("Successfully navigated to the Labyrinth menu.");
            return true;
        }
        logWarning("Labyrinth menu item not found.");
        return false;
    }

    // =========================== CHALLENGE EXECUTION ===========================

    private boolean executeCurrentChallenge() {
        logInfo("Executing the active Land of Heroes challenge.");
        // Try quick challenge first
        if (attemptQuickChallenge()) {
            return true;
        }

        // Try raid challenge
        if (attemptRaidChallenge()) {
            return true;
        }

        // Try normal challenge
        boolean completed = attemptNormalChallenge();
        if (!completed) {
            logWarning("No supported challenge action was available in Land of Heroes.");
        }
        return completed;
    }

    /**
     * Attempts to execute a quick challenge
     */
    private boolean attemptQuickChallenge() {
        ImageSearchResultData quickChallengeResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_QUICK_CHALLENGE_CURRENT,
                SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!quickChallengeResult.isFound()) {
            quickChallengeResult = templateSearchHelper.locatePattern(
                    TemplatesEnum.LABYRINTH_QUICK_CHALLENGE,
                    SearchConfigConstants.SINGLE_WITH_RETRIES);
        }
        if (quickChallengeResult.isFound()) {
            logInfo("'Quick Challenge' is available in Land of Heroes.");
            tapInside(quickChallengeResult);
            sleepTask(MENU_NAVIGATION_DELAY);

            // Skip battle animation
            tapNear(SKIP_BUTTON);
            sleepTask(300);
            tapInside(SKIP_BUTTON, SKIP_BUTTON, 10, 50);
            pressBack();
            return true;
        }
        return false;
    }

    /**
     * Attempts to execute a raid challenge
     */
    private boolean attemptRaidChallenge() {
        ImageSearchResultData raidResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_RAID_CHALLENGE,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (raidResult.isFound()) {
            logInfo("'Raid Challenge' is available in Land of Heroes.");
            tapInside(raidResult);
            sleepTask(400);
            tapInside(SKIP_BUTTON, SKIP_BUTTON, 10, 50);
            pressBack();
            sleepTask(400);
            pressBack();
            return true;
        }
        return false;
    }

    /**
     * Attempts to execute a normal challenge
     */
    private boolean attemptNormalChallenge() {
        ImageSearchResultData normalChallengeResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_NORMAL_CHALLENGE,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (!normalChallengeResult.isFound()) {
            logDebug("No 'Normal Challenge' button found in Land of Heroes.");
            return false;
        }

        tapInside(normalChallengeResult);
        sleepTask(300);

        // Try quick deploy first
        ImageSearchResultData quickDeployResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_QUICK_DEPLOY,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (quickDeployResult.isFound()) {
            logInfo("'Quick Deploy' button found. Deploying for Land of Heroes.");
            tapInside(quickDeployResult);
            sleepTask(100);
        }

        // Deploy troops
        ImageSearchResultData deployResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_DEPLOY,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (deployResult.isFound()) {
            logInfo("'Deploy' button found. Deploying troops for Land of Heroes.");
            tapInside(deployResult);
            sleepTask(BATTLE_COMPLETION_DELAY);

            // Skip battle results
            tapInside(RESULT_SKIP_BUTTON, RESULT_SKIP_BUTTON, 10, 50);
            pressBack();
            return true;
        }

        logWarning("Could not find 'Deploy' button for Land of Heroes.");
        return false;
    }

    /**
     * Reschedules the task for one hour later with a reason
     * 
     * @param reason the reason for rescheduling
     */
    private void rescheduleOneHourLater(String reason) {
        LocalDateTime nextExecution = LocalDateTime.now().plusHours(1);
        logWarning(reason + ". Rescheduling task for one hour later.");
        this.reschedule(nextExecution);
    }

}
