package dev.frostguard.tasks.events;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.data.entity.DailyTask;
import dev.frostguard.data.repository.DailyTaskRepository;
import dev.frostguard.data.repository.DailyTaskRepository;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.engine.service.TaskManagementService;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.helper.NavigationHelper.EventMenu;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.helper.DeploymentHelper;

import java.awt.Color;

public class HeroMissionEventRoutine extends DelayedTask {
    private final int refreshStaminaLevel = 180;
    private final int minStaminaLevel = 100;
    private final DailyTaskRepository iDailyTaskRepository = DailyTaskRepository.getRepository();
    private final TaskManagementService taskManagementService = TaskManagementService.shared();
    private int flagNumber = 0;
    private boolean useFlag = false;
    private boolean bearProtectionDeferred = false;

    public HeroMissionEventRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    protected void execute() {
        bearProtectionDeferred = false;

        flagNumber = profile.getConfig(ConfigurationKeyEnum.HERO_MISSION_FLAG_INT, Integer.class);
        useFlag = flagNumber > 0;

        if (eventHelper.isBearRunning()) {
            LocalDateTime rescheduleTo = LocalDateTime.now().plusMinutes(30);
            logInfo("Bear Hunt is running, rescheduling for " + rescheduleTo);
            reschedule(rescheduleTo);
            return;
        }
        logDebug("Bear Hunt is not running, continuing with Hero's Mission");

        if (profile.getConfig(ConfigurationKeyEnum.INTEL_BOOL, Boolean.class)
                && useFlag
                && taskManagementService.lookupTaskState(profile.getId(), TpDailyTaskEnum.INTEL.getId()).isScheduled()) {
            // Make sure intel isn't about to run
            DailyTask intel = iDailyTaskRepository.findByAccountIdAndTaskType(profile.getId(), TpDailyTaskEnum.INTEL);
            if (ChronoUnit.MINUTES.between(LocalDateTime.now(), intel.getScheduledAt()) < 5) {
                reschedule(LocalDateTime.now().plusMinutes(35)); // Reschedule in 35 minutes, after intel has run
                logWarning(
                        "Intel task is scheduled to run soon. Rescheduling Hero's Mission to run 30min after intel.");
                return;
            }
        }

        // Verify if there's enough stamina to hunt, if not, reschedule the task
        if (!staminaHelper.checkStaminaAndMarchesOrReschedule(minStaminaLevel, refreshStaminaLevel, this))
            return;

        int attempt = 0;
        while (attempt < 2) {
            boolean result = navigateToEventScreen();
            if (result) {
                logInfo("Successfully navigated to Hero's Mission event.");
                sleepTask(500);
                handleHeroMissionEvent();
                return;
            }

            logDebug("Failed to navigate to Hero's Mission event. Attempt " + (attempt + 1) + "/2.");
            sleepTask(300);
            pressBack();
            attempt++;
        }

        // If menu is not found after 2 attempts, cancel the task
        if (attempt >= 2) {
            logWarning(
                    "Could not find the Hero's Mission event tab. Assuming event is unavailable. Rescheduling for next reset.");
            reschedule(GameTimeUtils.dailyResetTime());
        }
    }

    private boolean navigateToEventScreen() {
        logInfo("Navigating to Hero's Mission event...");

        boolean success = navigationHelper.navigateToEventMenu(EventMenu.HERO_MISSION);

        if (!success) {
            logWarning("Failed to navigate to Hero's Mission event");
            return false;
        }

        sleepTask(2000);
        return true;
    }

    private void handleHeroMissionEvent() {
        ReaperAvailabilityResult reaperStatus = reapersAvailable();

        if (reaperStatus.isOcrError()) {
            logWarning("OCR error while checking reaper availability. Retrying in 5 minutes.");
            reschedule(LocalDateTime.now().plusMinutes(5));
            return;
        }

        if (!reaperStatus.isAvailable()) {
            logInfo("No reapers available. Rescheduling task for next reset.");
            reschedule(GameTimeUtils.dailyResetTime());
            return;
        }

        claimAllRewards();
        if (!rallyReaper() && !bearProtectionDeferred) {
            reschedule(LocalDateTime.now().plusMinutes(5));
        }
    }

    private boolean rallyReaper() {
        ImageSearchResultData button = templateSearchHelper.locatePattern(
                TemplatesEnum.HERO_MISSION_EVENT_TRACE_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(3)
                        .build());
        if (!button.isFound()) {
            button = templateSearchHelper.locatePattern(
                    TemplatesEnum.HERO_MISSION_EVENT_CAPTURE_BUTTON,
                    SearchConfig.builder()
                            .withThreshold(90)
                            .withMaxAttempts(3)
                            .build());
            if (!button.isFound()) {
                logWarning(
                        "Could not find 'Trace' or 'Capture' button to rally reapers. Rescheduling to try again in 5 minutes.");
                return false;
            }
        }
        tapInside(button);
        sleepTask(3000);
        tapNear(new PointData(360, 584)); // Tap on the center of the screen to select the reaper
        sleepTask(300);

        // Search for rally button
        ImageSearchResultData rallyButton = templateSearchHelper.locatePattern(
                TemplatesEnum.RALLY_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(3)
                        .build());

        if (!rallyButton.isFound()) {
            logDebug("Rally button not found. Rescheduling to try again in 5 minutes.");
            return false;
        }

        if (deferIfBearTrapBlocksRallyStart()) {
            bearProtectionDeferred = true;
            return false;
        }
        tapInside(rallyButton);
        sleepTask(1000);

        // Tap "Hold a Rally" button
        tapInside(new PointData(275, 821), new PointData(444, 856), 1, 400);
        sleepTask(500);

        // Select flag if needed
        if (useFlag) {
            marchHelper.selectFlag(flagNumber);
        }

        var deployment = deploymentHelper.readScreen(DeploymentHelper.MAX_RALLY_STAMINA_COST);
        long travelTimeSeconds = deployment.travelTimeSeconds();
        int spentStamina = deployment.staminaCost();
        if (deploymentHelper.hasNoDeployableTroops() || deploymentHelper.isDeployCostRed()) {
            logWarning("Deployment blocked by troops or stamina. No rally was sent or deducted.");
            pressBack();
            reschedule(LocalDateTime.now().plusMinutes(5));
            return false;
        }

        // Deploy march
        ImageSearchResultData deploy = templateSearchHelper.locatePattern(
                TemplatesEnum.DEPLOY_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(3)
                        .build());

        if (!deploy.isFound()) {
            logDebug("Deploy button not found. Rescheduling to try again in 5 minutes.");
            return false;
        }

        if (deferIfBearTrapBlocksRallyStart()) {
            bearProtectionDeferred = true;
            return false;
        }
        tapInside(deploy);
        sleepTask(2000);

        if (deploymentHelper.isSameTargetDialog()) {
            logInfo("Another march is already targeting this Reaper. Cancelling deployment.");
            pressBack();
            pressBack();
            reschedule(LocalDateTime.now().plusMinutes(1));
            return false;
        }

        deploy = templateSearchHelper.locatePattern(
                TemplatesEnum.DEPLOY_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(3)
                        .build());
        if (deploy.isFound()) {
            // Probably march got taken by auto-join or something
            logInfo("Deploy button still found after trying to deploy march. Rescheduling to try again in 5 minutes.");
            return false;
        }

        logInfo("March deployed successfully.");

        // Update stamina
        staminaHelper.subtractStamina(spentStamina, true);

        if (travelTimeSeconds <= 0) {
            logError("Failed to parse travel time via OCR. Rescheduling in 10 minutes as fallback.");
            LocalDateTime rescheduleTime = LocalDateTime.now().plusMinutes(10);
            reschedule(rescheduleTime);
            logInfo("Reaper rally scheduled to return in "
                    + GameTimeUtils.formatCountdown(rescheduleTime));
            return true;
        }

        LocalDateTime rescheduleTime = LocalDateTime.now().plusSeconds(travelTimeSeconds).plusMinutes(5);
        reschedule(rescheduleTime);
        logInfo("Reaper rally scheduled to return in " + GameTimeUtils.formatCountdown(rescheduleTime));
        return true;
    }

    private void claimAllRewards() {
        List<ImageSearchResultData> chests = templateSearchHelper.locateAllPatterns(
                TemplatesEnum.HERO_MISSION_EVENT_CHEST,
                SearchConfig.builder()
                        .withArea(new AreaData(new PointData(116, 950), new PointData(671, 1018)))
                        .withThreshold(90)
                        .withMaxResults(5)
                        .withMaxAttempts(5)
                        .build());

        if (!chests.isEmpty()) {
            logInfo("Found " + chests.size() + " chests to be claimed.");
        } else {
            logInfo("Didn't find any chests to be claimed.");
            return;
        }

        for (ImageSearchResultData chest : chests) {
            if (chest.isFound()) {
                tapInside(chest);
                sleepTask(300);
                pressBack();
            }
        }

    }

    private ReaperAvailabilityResult reapersAvailable() {
        TesseractSettingsData settingsRallied = TesseractSettingsData.assembler()
                .recognitionEngine(TesseractSettingsData.RecognitionEngine.LSTM_ONLY)
                .stripBackground(true)
                .setTextColor(new Color(254, 254, 254)) // White text
                .charWhitelist("0123456789") // Only allow digits
                .build();

        // Limited mode: Check how many reapers have been rallied
        Integer reapersRallied = readNumberValue(
                new PointData(68, 1062),
                new PointData(125, 1093),
                settingsRallied);

        if (reapersRallied == null) {
            logWarning("Failed to parse reapers rallied count via OCR: '" + reapersRallied + "'");
            sleepTask(500);
            return ReaperAvailabilityResult.OCR_ERROR_RALLIED_COUNT;
        }

        logInfo("Reapers rallied until now: " + reapersRallied);
        sleepTask(500);

        if (reapersRallied < 10) {
            return ReaperAvailabilityResult.AVAILABLE;
        } else {
            return ReaperAvailabilityResult.UNAVAILABLE;
        }
    }

    @Override
    public LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    @Override
    protected boolean consumesStamina() {
        return true;
    }

    /**
     * Represents the result of checking reaper availability
     */
    public enum ReaperAvailabilityResult {
        /**
         * Reapers are available (< 10 rallied)
         */
        AVAILABLE,

        /**
         * No reapers available (>= 10 rallied)
         */
        UNAVAILABLE,

        /**
         * Failed to read the OCR value for reapers rallied count
         */
        OCR_ERROR_RALLIED_COUNT;

        /**
         * Convenience method to check if reapers are available
         */
        public boolean isAvailable() {
            return this == AVAILABLE;
        }

        /**
         * Convenience method to check if result is an OCR error
         */
        public boolean isOcrError() {
            return this == OCR_ERROR_RALLIED_COUNT;
        }
    }

}
