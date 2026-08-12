package dev.frostguard.tasks.events;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.data.entity.DailyTask;
import dev.frostguard.data.repository.DailyTaskRepository;
import dev.frostguard.data.repository.DailyTaskRepository;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.engine.service.TaskManagementService;
import dev.frostguard.engine.service.StaminaService;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.helper.NavigationHelper.EventMenu;
import dev.frostguard.engine.helper.DeploymentHelper;
import java.awt.Color;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

public class MercenaryEventRoutine extends DelayedTask {
    private final DailyTaskRepository iDailyTaskRepository = DailyTaskRepository.getRepository();
    private final TaskManagementService taskManagementService = TaskManagementService.shared();
    private Integer lastMercenaryLevel = null;
    private Integer lastStaminaSpent = null;
    private int attackAttempts = 0;
    private int flagNumber = 0;
    private boolean useFlag = false;
    private final int refreshStaminaLevel = 100;
    private final int minStaminaLevel = 40;
    private boolean scout = false;

    public MercenaryEventRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected void execute() {

        flagNumber = profile.getConfig(ConfigurationKeyEnum.MERCENARY_FLAG_INT, Integer.class);
        useFlag = flagNumber > 0;

        if (profile.getConfig(ConfigurationKeyEnum.INTEL_BOOL, Boolean.class)
                && useFlag
                && taskManagementService.lookupTaskState(profile.getId(), TpDailyTaskEnum.INTEL.getId()).isScheduled()) {
            // Make sure intel isn't about to run
            DailyTask intel = iDailyTaskRepository.findByAccountIdAndTaskType(profile.getId(), TpDailyTaskEnum.INTEL);
            if (ChronoUnit.MINUTES.between(LocalDateTime.now(), intel.getScheduledAt()) < 5) {
                reschedule(LocalDateTime.now().plusMinutes(35)); // Reschedule in 35 minutes, after intel has run
                logWarning(
                        "Intel task is scheduled to run soon. Rescheduling Mercenary Event to run 30min after intel.");
                return;
            }
        }

        // Verify if there's enough stamina to hunt, if not, reschedule the task
        if (!staminaHelper.checkStaminaAndMarchesOrReschedule(minStaminaLevel, refreshStaminaLevel, this))
            return;

        int attempt = 0;
        while (attempt < 2) {
            if (navigateToEventScreen()) {
                handleMercenaryEvent();
                return;
            }
            logDebug("Navigation to Mercenary event failed, attempt " + (attempt + 1));
            sleepTask(300);
            pressBack();
            attempt++;
        }

        logWarning("Could not find the Mercenary event tab. Assuming event is unavailable. Rescheduling to reset.");
        reschedule(GameTimeUtils.dailyResetTime());
    }

    private void handleMercenaryEvent() {
        try {
            // Select a mercenary event level if needed
            if (!selectMercenaryEventLevel()) {
                return; // If level selection failed, exit the task
            }

            // Check for scout or challenge buttons
            ImageSearchResultData eventButton = findMercenaryEventButton();

            if (eventButton == null) {
                logInfo("No scout or challenge button found, assuming event is completed. Rescheduling to reset.");
                reschedule(GameTimeUtils.dailyResetTime());
                return;
            }

            // Handle attack loss, if the attack was lost, skip flag selection to use
            // strongest march
            boolean sameLevelAsLastTime = false;
            logInfo("Previous mercenary level: " + lastMercenaryLevel);
            Integer currentLevel = checkMercenaryLevel();
            if (currentLevel != null) {
                sameLevelAsLastTime = (currentLevel.equals(lastMercenaryLevel));
                lastMercenaryLevel = currentLevel;
            }

            if (sameLevelAsLastTime) {
                attackAttempts++;
                staminaHelper.addStamina(lastStaminaSpent);
                logInfo("Mercenary level is the same as last time, indicating a possible attack loss. Skipping flag selection to use strongest march.");
            } else {
                attackAttempts = 0;
                logInfo("Mercenary level has changed since last time. Using flag selection if enabled.");
            }

            scoutAndAttack(eventButton, sameLevelAsLastTime);
        } catch (Exception e) {
            logError("An error occurred during the Mercenary Event task: " + e.getMessage(), e);
            reschedule(LocalDateTime.now().plusMinutes(30)); // Reschedule on error
        }
    }

    private Integer checkMercenaryLevel() {
        TesseractSettingsData configs = TesseractSettingsData.assembler()
                .pageAnalysis(TesseractSettingsData.PageAnalysis.SINGLE_LINE)
                .recognitionEngine(TesseractSettingsData.RecognitionEngine.LSTM_ONLY)
                .stripBackground(true)
                .setTextColor(new Color(255, 255, 255)) // White text
                .charWhitelist("0123456789") // Only allow digits and '/'
                .build();

        Integer level = readNumberValue(new PointData(322, 867), new PointData(454, 918), configs);
        if (level == null) {
            logWarning("No mercenary level found after OCR attempts.");
            return null;
        }

        logInfo("Current mercenary level: " + level);
        return level;
    }

    private boolean selectMercenaryEventLevel() {
        // Try each initiation type in order: Legends -> Epic -> Champions
        String[] initiationTypes = { "Legends", "Epic", "Champions" };
        TemplatesEnum[] unselectedTemplates = {
                TemplatesEnum.MERCENARY_LEGENDS_INITIATION_UNSELECTED,
                TemplatesEnum.MERCENARY_EPIC_INITIATION_UNSELECTED,
                TemplatesEnum.MERCENARY_CHAMPIONS_INITIATION_UNSELECTED
        };
        TemplatesEnum[] selectedTemplates = {
                TemplatesEnum.MERCENARY_LEGENDS_INITIATION_SELECTED,
                TemplatesEnum.MERCENARY_EPIC_INITIATION_SELECTED,
                TemplatesEnum.MERCENARY_CHAMPIONS_INITIATION_SELECTED
        };

        for (int i = 0; i < initiationTypes.length; i++) {
            boolean tabIsSelected = false;

            // First check if this initiation type is already selected
            ImageSearchResultData alreadySelectedTab = templateSearchHelper.locatePattern(
                    selectedTemplates[i],
                    SearchConfigConstants.SINGLE_WITH_RETRIES);

            if (alreadySelectedTab.isFound()) {
                logInfo(initiationTypes[i]
                        + " Initiation tab is already selected. Proceeding with difficulty selection.");
                tabIsSelected = true;
            } else {
                // Tab is not selected, check if it's unselected (available to select)
                ImageSearchResultData unselectedTab = templateSearchHelper.locatePattern(
                        unselectedTemplates[i],
                        SearchConfigConstants.SINGLE_WITH_RETRIES);

                if (unselectedTab.isFound()) {
                    logInfo("Found unselected " + initiationTypes[i] + " Initiation tab. Tapping to open.");

                    // Tap the unselected tab to open it
                    tapInside(unselectedTab);
                    sleepTask(1500);

                    // Verify that the tab changed to selected (not locked)
                    ImageSearchResultData selectedTab = templateSearchHelper.locatePattern(
                            selectedTemplates[i],
                            SearchConfigConstants.SINGLE_WITH_RETRIES);

                    if (selectedTab.isFound()) {
                        tabIsSelected = true;
                    } else {
                        logDebug(initiationTypes[i] + " Initiation tab is locked. Skipping to next type.");
                    }
                }
            }

            if (tabIsSelected) {
                logInfo(initiationTypes[i] + " Initiation tab is open. Attempting to select difficulty.");

                // Now select a difficulty within this initiation type
                // Define difficulties in order from highest to lowest
                record DifficultyLevel(String name, PointData point) {
                }
                DifficultyLevel[] difficultyLevels = {
                        new DifficultyLevel("Insane", new PointData(467, 1088)),
                        new DifficultyLevel("Nightmare", new PointData(252, 1088)),
                        new DifficultyLevel("Hard", new PointData(575, 817)),
                        new DifficultyLevel("Normal", new PointData(360, 817)),
                        new DifficultyLevel("Easy", new PointData(145, 817))
                };

                for (DifficultyLevel level : difficultyLevels) {
                    logDebug("Attempting to select difficulty: " + level.name() + " in " + initiationTypes[i]
                            + " Initiation.");
                    tapNear(level.point());
                    sleepTask(2000);
                    ImageSearchResultData challengeCheck = templateSearchHelper.locatePattern(
                            TemplatesEnum.MERCENARY_DIFFICULTY_CHALLENGE,
                            SearchConfigConstants.SINGLE_WITH_RETRIES);
                    if (challengeCheck.isFound()) {
                        sleepTask(1000);
                        tapInside(challengeCheck);
                        sleepTask(1000);
                        tapNear(new PointData(504, 788)); // Tap the confirm button
                        logInfo("Selected mercenary event difficulty: " + level.name() + " in " + initiationTypes[i]
                                + " Initiation.");
                        sleepTask(2000);
                        return true;
                    }
                    sleepTask(1000);
                    pressBack();
                }
            }
        }

        // If no tab was found at all, a level was already selected beforehand
        logInfo("No initiation type found, assuming one was already selected beforehand. Proceeding.");
        return true;
    }

    private boolean navigateToEventScreen() {
        logInfo("Navigating to Mercenary event...");

        boolean success = navigationHelper.navigateToEventMenu(EventMenu.MERCENARY);

        if (!success) {
            logWarning("Failed to navigate to Mercenary event");
            return false;
        }

        sleepTask(2000);
        return true;
    }

    /**
     * Finds either the scout button or challenge button for the mercenary event.
     * 
     * @return The search result of the found button, or null if neither button is
     *         found
     */
    private ImageSearchResultData findMercenaryEventButton() {
        logInfo("Checking for mercenary event buttons.");

        // First check for scout button
        ImageSearchResultData scoutButton = templateSearchHelper.locatePattern(
                TemplatesEnum.MERCENARY_SCOUT_BUTTON,
                SearchConfigConstants.SINGLE_WITH_RETRIES);

        if (scoutButton.isFound()) {
            scout = true;
            logInfo("Found scout button for mercenary event.");
            return scoutButton;
        }

        // If scout button not found, check for challenge button
        ImageSearchResultData challengeButton = templateSearchHelper.locatePattern(
                TemplatesEnum.MERCENARY_CHALLENGE_BUTTON,
                SearchConfigConstants.SINGLE_WITH_RETRIES);

        if (challengeButton.isFound()) {
            scout = false;
            logInfo("Found challenge button for mercenary event.");
            return challengeButton;
        }

        logInfo("Neither scout nor challenge button found for mercenary event.");
        return null;
    }

    private void scoutAndAttack(ImageSearchResultData eventButton, boolean sameLevelAsLastTime) {
        logInfo("Starting scout/attack process for mercenary event.");

        if (eventButton == null) {
            logInfo("No scout or challenge button found, assuming event is completed. Rescheduling to reset.");
            reschedule(GameTimeUtils.dailyResetTime());
            return;
        }

        if (scout) {
            logInfo("Scouting mercenary. Decreasing stamina by 15.");
            StaminaService.getServices().subtractStamina(profile.getId(), 15);
        }

        // Click on the button (whether it's scout or challenge)
        tapInside(eventButton);
        sleepTask(4000); // Wait to travel to mercenary location on map

        // Determine whether to rally or attack
        boolean rally = false;
        ImageSearchResultData attackOrRallyButton = null;

        if (attackAttempts > 3) {
            logWarning(
                    "Multiple consecutive attack attempts detected without level change. Rallying the mercenary instead of normal attack.");
            attackOrRallyButton = templateSearchHelper.locatePattern(
                    TemplatesEnum.RALLY_BUTTON,
                    SearchConfigConstants.SINGLE_WITH_RETRIES);
            rally = true;
        } else {
            attackOrRallyButton = templateSearchHelper.locatePattern(
                    TemplatesEnum.MERCENARY_ATTACK_BUTTON,
                    SearchConfigConstants.SINGLE_WITH_RETRIES);
        }

        if (attackOrRallyButton == null || !attackOrRallyButton.isFound()) {
            logWarning("Attack/Rally button not found after scouting/challenging. Retrying in 5 minutes.");
            reschedule(LocalDateTime.now().plusMinutes(5));
            return;
        }

        if (rally && deferIfBearTrapBlocksRallyStart()) {
            return;
        }
        logInfo(rally ? "Rallying mercenary." : "Attacking mercenary.");
        tapInside(attackOrRallyButton);
        sleepTask(1000);

        if (rally) {
            tapInside(new PointData(275, 821), new PointData(444, 856));
            sleepTask(500);
        }

        // Check if the march screen is open before proceeding
        ImageSearchResultData deployButton = templateSearchHelper.locatePattern(
                TemplatesEnum.DEPLOY_BUTTON,
                SearchConfigConstants.SINGLE_WITH_RETRIES);

        if (!deployButton.isFound()) {
            logError(
                    "March queue is full or another issue occurred. Cannot start a new march. Retrying in 10 minutes.");
            reschedule(LocalDateTime.now().plusMinutes(10));
            return;
        }

        // Check if we should use a specific flag
        if (useFlag && !sameLevelAsLastTime) {
            if (!marchHelper.selectFlag(flagNumber)) {
                logWarning("Configured formation #" + flagNumber + " is unavailable. Cancelling deployment.");
                pressBack();
                reschedule(LocalDateTime.now().plusMinutes(5));
                return;
            }
            sleepTask(300);
        }

        int maxStaminaCost = rally
                ? DeploymentHelper.MAX_RALLY_STAMINA_COST
                : DeploymentHelper.MAX_ATTACK_STAMINA_COST;
        var deployment = deploymentHelper.readScreen(maxStaminaCost);
        long travelTimeSeconds = deployment.travelTimeSeconds();
        int spentStamina = deployment.staminaCost();
        lastStaminaSpent = spentStamina;
        if (deploymentHelper.hasNoDeployableTroops() || deploymentHelper.isDeployCostRed()) {
            logWarning("Deployment blocked by troops or stamina. No march was sent or deducted.");
            pressBack();
            reschedule(LocalDateTime.now().plusMinutes(5));
            return;
        }

        if (rally && deferIfBearTrapBlocksRallyStart()) {
            return;
        }

        // Validate travel time before deploying
        if (travelTimeSeconds <= 0) {
            logError("Failed to parse valid march time via OCR. Using conservative 10 minute fallback reschedule.");
            tapInside(deployButton); // Deploy anyway since we're already in the march screen
            sleepTask(2000);

            if (deploymentHelper.isSameTargetDialog()) {
                logWarning("Another march is already targeting this mercenary. Cancelling deployment.");
                pressBack();
                pressBack();
                reschedule(LocalDateTime.now().plusMinutes(1));
                return;
            }
            ImageSearchResultData deployStillPresent = templateSearchHelper.locatePattern(
                    TemplatesEnum.DEPLOY_BUTTON,
                    SearchConfigConstants.SINGLE_WITH_2_RETRIES);
            if (deployStillPresent.isFound()) {
                logWarning("Deploy button remained visible; stamina was not deducted.");
                reschedule(LocalDateTime.now().plusMinutes(5));
                return;
            }

            // Update stamina with fallback
            staminaHelper.subtractStamina(spentStamina, rally);

            // Reschedule with conservative estimate
            LocalDateTime fallbackTime = LocalDateTime.now().plusMinutes(10);
            reschedule(fallbackTime);
            logInfo("Mercenary march deployed with unknown return time. Task will retry at " +
                    fallbackTime.format(DateTimeFormatter.ofPattern("HH:mm:ss")));
            return;
        }

        // Deploy march with known travel time
        tapInside(deployButton);
        sleepTask(2000);

        if (deploymentHelper.isSameTargetDialog()) {
            logWarning("Another march is already targeting this mercenary. Cancelling deployment.");
            pressBack();
            pressBack();
            reschedule(LocalDateTime.now().plusMinutes(1));
            return;
        }

        // Verify deployment succeeded
        ImageSearchResultData deployStillPresent = templateSearchHelper.locatePattern(
                TemplatesEnum.DEPLOY_BUTTON,
                SearchConfigConstants.SINGLE_WITH_2_RETRIES);
        if (deployStillPresent.isFound()) {
            logWarning(
                    "Deploy button still present after attempting to deploy. March may have failed. Retrying in 5 minutes.");
            reschedule(LocalDateTime.now().plusMinutes(5));
            return;
        }

        logInfo("March deployed successfully.");

        // Calculate return time
        long returnTimeSeconds = (travelTimeSeconds * 2) + 2;
        LocalDateTime rescheduleTime = rally
                ? LocalDateTime.now().plusSeconds(returnTimeSeconds).plusMinutes(5)
                : LocalDateTime.now().plusSeconds(returnTimeSeconds);

        reschedule(rescheduleTime);

        // Update stamina
        staminaHelper.subtractStamina(spentStamina, rally);

        logInfo("Mercenary march sent. Task will run again at " +
                rescheduleTime.format(DateTimeFormatter.ofPattern("HH:mm:ss")) +
                " (in " + (returnTimeSeconds / 60) + " minutes).");
    }

    @Override
    public LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    @Override
    protected boolean consumesStamina() {
        return true;
    }

}
