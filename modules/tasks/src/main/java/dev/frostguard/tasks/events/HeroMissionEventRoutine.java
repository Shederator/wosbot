package dev.frostguard.tasks.events;

import java.time.LocalDateTime;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.data.entity.DailyTask;
import dev.frostguard.data.repository.DailyTaskRepository;
import dev.frostguard.data.repository.TaskFailureStreakRepository;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.engine.service.TaskManagementService;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.helper.NavigationHelper.EventMenu;
import dev.frostguard.engine.helper.NavigationHelper.EventMenuOpenResult;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.helper.DeploymentHelper;
import dev.frostguard.tasks.diagnostics.TaskDiagnosticSnapshots;
import dev.frostguard.tasks.diagnostics.TaskControlSignals;

public class HeroMissionEventRoutine extends DelayedTask {
    private final int refreshStaminaLevel = 180;
    private final int minStaminaLevel = 100;
    private final DailyTaskRepository iDailyTaskRepository = DailyTaskRepository.getRepository();
    private final TaskManagementService taskManagementService = TaskManagementService.shared();
    private int flagNumber = 0;
    private boolean useFlag = false;
    private boolean bearProtectionDeferred = false;
    private HeroMissionVisitBudget visitBudget;

    public HeroMissionEventRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    protected void execute() {
        bearProtectionDeferred = false;

        if (deferIfVisitBudgetExhausted()) {
            return;
        }

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

        EventMenuOpenResult opened = openHeroMenu();
        if (opened != EventMenuOpenResult.REACHED) {
            respondToMenu(opened);
            if (opened == EventMenuOpenResult.TAB_ABSENT) {
                sleepTask(300);
                pressBack();
            }
            return;
        }
        if (!respondToMenu(opened)) {
            return;
        }
        logInfo("Successfully navigated to Hero's Mission event.");
        sleepTask(500);
        handleHeroMissionEvent();
    }

    EventMenuOpenResult openHeroMenu() {
        logInfo("Navigating to Hero's Mission event...");

        EventMenuOpenResult opened = navigationHelper.openEventMenu(EventMenu.HERO_MISSION);

        if (opened != EventMenuOpenResult.REACHED) {
            logWarning("Failed to navigate to Hero's Mission event");
            return opened;
        }

        sleepTask(2000);
        return opened;
    }

    boolean respondToMenu(EventMenuOpenResult opened) {
        if (opened == EventMenuOpenResult.REACHED) {
            try {
                visitBudget().succeeded(HeroMissionVisitBudget.FailureKind.NAVIGATION);
                return true;
            } catch (RuntimeException failure) {
                return deferForBudgetFailure(failure);
            }
        }
        HeroMissionVisitBudget.Decision decision;
        try {
            decision = visitBudget().recordFailure(HeroMissionVisitBudget.FailureKind.NAVIGATION);
        } catch (RuntimeException failure) {
            return deferForBudgetFailure(failure);
        }
        String reason = opened == EventMenuOpenResult.PANEL_CLOSED
                ? "Events panel did not open" : "Hero's Mission tab was not in view";
        String snapshot = decision.exhausted()
                ? "; " + diagnosticSnapshot(opened == EventMenuOpenResult.PANEL_CLOSED
                        ? "event-panel" : "event-navigation")
                : "";
        logWarning(reason + "; navigation attempt " + decision.attempt() + "/3; next visit at "
                + decision.nextVisit().format(DATETIME_FORMATTER) + snapshot + ".");
        reschedule(decision.nextVisit());
        return false;
    }

    private void handleHeroMissionEvent() {
        HeroMissionProgressBar.State progress = readProgressBar();

        if (progress == HeroMissionProgressBar.State.UNKNOWN) {
            respondToProgressFailure();
            return;
        }

        try {
            visitBudget().succeeded(HeroMissionVisitBudget.FailureKind.PROGRESS);
        } catch (RuntimeException failure) {
            deferForBudgetFailure(failure);
            return;
        }

        if (progress == HeroMissionProgressBar.State.COMPLETE) {
            logInfo("Hero's Mission progress bar reaches the final reward; rescheduling for next reset.");
            reschedule(GameTimeUtils.dailyResetTime());
            return;
        }

        claimAllRewards();
        rallyReaper();
    }

    ImageSearchResultData findTraceButton() {
        return templateSearchHelper.locatePattern(
                TemplatesEnum.HERO_MISSION_EVENT_TRACE_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(3)
                        .build());
    }

    ImageSearchResultData findCaptureButton() {
        return templateSearchHelper.locatePattern(
                TemplatesEnum.HERO_MISSION_EVENT_CAPTURE_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(3)
                        .build());
    }

    ImageSearchResultData findRallyButton() {
        return templateSearchHelper.locatePattern(
                TemplatesEnum.RALLY_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(3)
                        .build());
    }

    ImageSearchResultData findDeployButton() {
        return templateSearchHelper.locatePattern(
                TemplatesEnum.DEPLOY_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(3)
                        .build());
    }

    String diagnosticSnapshot(String control) {
        return TaskDiagnosticSnapshots.capture(emuManager, EMULATOR_NUMBER, "heromission", control);
    }

    HeroMissionVisitBudget visitBudget() {
        if (visitBudget == null) {
            visitBudget = new HeroMissionVisitBudget(
                    TaskFailureStreakRepository.getRepository(), profile.getId(), Clock.systemDefaultZone());
        }
        return visitBudget;
    }

    private boolean deferIfVisitBudgetExhausted() {
        try {
            for (HeroMissionVisitBudget.FailureKind kind : HeroMissionVisitBudget.FailureKind.values()) {
                if (visitBudget().exhausted(kind)) {
                    LocalDateTime reset = visitBudget().nextResetAt();
                    logWarning("Hero's Mission " + kind + " visit budget exhausted; next visit after reset at "
                            + reset.format(DATETIME_FORMATTER) + ".");
                    reschedule(reset);
                    return true;
                }
            }
            return false;
        } catch (RuntimeException failure) {
            deferForBudgetFailure(failure);
            return true;
        }
    }

    private boolean deferForBudgetFailure(RuntimeException failure) {
        TaskControlSignals.rethrowControlSignal(failure);
        LocalDateTime reset = GameTimeUtils.dailyResetTime();
        logError("Hero's Mission visit counter could not be persisted ("
                + failure.getClass().getSimpleName() + "); next visit after reset at "
                + reset.format(DATETIME_FORMATTER) + ".", failure);
        reschedule(reset);
        return false;
    }

    void respondToProgressFailure() {
        HeroMissionVisitBudget.Decision decision;
        try {
            decision = visitBudget().recordFailure(HeroMissionVisitBudget.FailureKind.PROGRESS);
        } catch (RuntimeException failure) {
            deferForBudgetFailure(failure);
            return;
        }
        String snapshot = decision.exhausted() ? "; " + diagnosticSnapshot("progress-bar-unknown") : "";
        logWarning("Hero's Mission progress bar was unreadable; progress attempt " + decision.attempt()
                + "/3; next visit at " + decision.nextVisit().format(DATETIME_FORMATTER) + snapshot + ".");
        reschedule(decision.nextVisit());
    }

    void scheduleMissingControlRetry(String control) {
        LocalDateTime retryAt = LocalDateTime.now().plusMinutes(5);
        String snapshot = diagnosticSnapshot(control);
        logWarning(control + " was not detected; retrying at "
                + retryAt.format(DATETIME_FORMATTER) + "; " + snapshot + ".");
        reschedule(retryAt);
    }

    boolean rallyReaper() {
        ImageSearchResultData button = findTraceButton();
        if (!button.isFound()) {
            button = findCaptureButton();
            if (!button.isFound()) {
                LocalDateTime retryAt = LocalDateTime.now().plusMinutes(5);
                String snapshot = TaskDiagnosticSnapshots.capture(
                        emuManager, EMULATOR_NUMBER, "heromission", "action-controls");
                logWarning("Neither Trace nor Capture was detected; retrying at "
                        + retryAt.format(DATETIME_FORMATTER) + "; " + snapshot + ".");
                reschedule(retryAt);
                return false;
            }
        }
        tapInside(button);
        sleepTask(3000);
        tapNear(new PointData(360, 584)); // Tap on the center of the screen to select the reaper
        sleepTask(300);

        // Search for rally button
        ImageSearchResultData rallyButton = findRallyButton();

        if (!rallyButton.isFound()) {
            scheduleMissingControlRetry("rally-button");
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
            if (!marchHelper.selectFlag(flagNumber)) {
                logWarning("Configured formation #" + flagNumber + " is unavailable. Cancelling rally setup.");
                pressBack();
                reschedule(LocalDateTime.now().plusMinutes(5));
                return false;
            }
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
        ImageSearchResultData deploy = findDeployButton();

        if (!deploy.isFound()) {
            scheduleMissingControlRetry("deploy-button");
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

        DeploymentHelper.LaunchCheck launchCheck = deploymentHelper.verifyLaunchTransition();
        if (launchCheck != DeploymentHelper.LaunchCheck.WORLD_VERIFIED) {
            String snapshot = TaskDiagnosticSnapshots.capture(
                    emuManager, EMULATOR_NUMBER, "heromission", "deployment-unknown");
            logWarning("Hero Mission deployment was not verified (" + launchCheck
                    + "); stamina was not deducted. " + snapshot);
            reschedule(LocalDateTime.now().plusMinutes(5));
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

    private HeroMissionProgressBar.State readProgressBar() {
        var frame = ImageConverter.toBufferedImage(emuManager.captureScreen(EMULATOR_NUMBER));
        HeroMissionProgressBar.State state = HeroMissionProgressBar.read(frame);
        logInfo("Hero's Mission progress bar state: " + state + ".");
        return state;
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
