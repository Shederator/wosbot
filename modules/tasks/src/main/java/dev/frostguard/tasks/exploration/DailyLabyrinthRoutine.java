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

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Task responsible for completing daily labyrinth challenges.
 * This task navigates to the labyrinth menu and executes appropriate challenges
 * based on the current day of the week.
 */
public class DailyLabyrinthRoutine extends DelayedTask {

    // =========================== CONSTANTS ===========================

    // Navigation points
    private static final PointData SKIP_BUTTON = new PointData(71, 827);
    private static final PointData RESULT_SKIP_BUTTON = new PointData(640, 175);

    // Timing constants
    private static final int MENU_NAVIGATION_DELAY = 1000;
    private static final int TAB_SWITCH_DELAY = 500;
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

            // Step 2: Execute challenges based on current day
            executeLabyrinthChallenges();

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

    /**
     * Executes labyrinth challenges based on the current day of the week
     */
    private void executeLabyrinthChallenges() {
        DayOfWeek currentDay = LocalDateTime.now(ZoneOffset.UTC).getDayOfWeek();
        List<Integer> availableDungeons = getAvailableDungeons(currentDay);

        logInfo("Executing challenges for " + currentDay + ". Available dungeons: " + availableDungeons);

        boolean anyCompleted = false;
        for (Integer dungeonNumber : availableDungeons) {
            if (executeDungeonChallenge(dungeonNumber)) {
                logInfo("Successfully completed challenge for dungeon " + dungeonNumber + ".");
                anyCompleted = true;

            }
        }

        if (!anyCompleted) {
            logWarning("No dungeons were successfully completed today.");
        }
    }

    /**
     * Executes a specific dungeon challenge
     * 
     * @param dungeonNumber the dungeon number to challenge
     * @return true if challenge was completed successfully
     */
    private boolean executeDungeonChallenge(int dungeonNumber) {
        logInfo("Attempting to execute challenge for dungeon " + dungeonNumber + ".");

        ImageSearchResultData labyrinthResult = templateSearchHelper.locatePattern(
                getDungeonTemplate(dungeonNumber),
                SearchConfigConstants.DEFAULT_SINGLE);
        if (!labyrinthResult.isFound()) {
            logWarning("Dungeon " + dungeonNumber + " is not available today.");
            return false;
        }

        tapInside(labyrinthResult);
        sleepTask(TAB_SWITCH_DELAY);

        // Try quick challenge first
        if (attemptQuickChallenge(dungeonNumber)) {
            return true;
        }

        // Try raid challenge
        if (attemptRaidChallenge(dungeonNumber)) {
            return true;
        }

        // Try normal challenge
        return attemptNormalChallenge(dungeonNumber);
    }

    /**
     * Attempts to execute a quick challenge
     */
    private boolean attemptQuickChallenge(int dungeonNumber) {
        tapNear(new PointData(700, 1200));
        sleepTask(100);
        ImageSearchResultData quickChallengeResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_QUICK_CHALLENGE,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (quickChallengeResult.isFound()) {
            logInfo("'Quick Challenge' is available for dungeon " + dungeonNumber + ".");
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
    private boolean attemptRaidChallenge(int dungeonNumber) {
        ImageSearchResultData raidResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_RAID_CHALLENGE,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (raidResult.isFound()) {
            logInfo("'Raid Challenge' is available for dungeon " + dungeonNumber + ".");
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
    private boolean attemptNormalChallenge(int dungeonNumber) {
        ImageSearchResultData normalChallengeResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_NORMAL_CHALLENGE,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (!normalChallengeResult.isFound()) {
            logWarning("No 'Normal Challenge' button found for dungeon " + dungeonNumber + ".");
            return false;
        }

        tapInside(normalChallengeResult);
        sleepTask(300);

        // Try quick deploy first
        ImageSearchResultData quickDeployResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_QUICK_DEPLOY,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (quickDeployResult.isFound()) {
            logInfo("'Quick Deploy' button found. Deploying for dungeon " + dungeonNumber + ".");
            tapInside(quickDeployResult);
            sleepTask(100);
        }

        // Deploy troops
        ImageSearchResultData deployResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_DEPLOY,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (deployResult.isFound()) {
            logInfo("'Deploy' button found. Deploying troops for dungeon " + dungeonNumber + ".");
            tapInside(deployResult);
            sleepTask(BATTLE_COMPLETION_DELAY);

            // Skip battle results
            tapInside(RESULT_SKIP_BUTTON, RESULT_SKIP_BUTTON, 10, 50);
            pressBack();
            return true;
        }

        logWarning("Could not find 'Deploy' button for dungeon " + dungeonNumber + ".");
        return false;
    }

    // =========================== UTILITY METHODS ===========================

    /**
     * Returns the list of available dungeons based on the day of the week
     * 
     * @param dayOfWeek the current day of the week
     * @return list of available dungeon numbers
     */
    private List<Integer> getAvailableDungeons(DayOfWeek dayOfWeek) {
        List<Integer> dungeons = new ArrayList<>();

        switch (dayOfWeek) {
            case MONDAY, TUESDAY -> dungeons.add(1);
            case WEDNESDAY, THURSDAY -> {
                dungeons.add(2);
                dungeons.add(3);
            }
            case FRIDAY, SATURDAY -> {
                dungeons.add(4);
                dungeons.add(5);
            }
            case SUNDAY -> dungeons.add(6);
        }

        return dungeons;
    }

    /**
     * Returns the appropriate template for each dungeon number
     * 
     * @param dungeonNumber the dungeon number (1-6)
     * @return the corresponding template enum
     */
    private TemplatesEnum getDungeonTemplate(int dungeonNumber) {
        return switch (dungeonNumber) {
            case 1 -> TemplatesEnum.LABYRINTH_DUNGEON_1;
            case 2 -> TemplatesEnum.LABYRINTH_DUNGEON_2;
            case 3 -> TemplatesEnum.LABYRINTH_DUNGEON_3;
            case 4 -> TemplatesEnum.LABYRINTH_DUNGEON_4;
            case 5 -> TemplatesEnum.LABYRINTH_DUNGEON_5;
            case 6 -> TemplatesEnum.LABYRINTH_DUNGEON_6;
            default -> {
                logWarning("Invalid dungeon number: " + dungeonNumber + ". Using dungeon 1 as a fallback.");
                yield TemplatesEnum.LABYRINTH_DUNGEON_1;
            }
        };
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
