package dev.frostguard.tasks.lifecycle;

import dev.frostguard.engine.schedule.LaunchPoint;


import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.error.ActionRequiredContext;
import dev.frostguard.engine.error.ProfileCooldownException;
import dev.frostguard.engine.error.StopExecutionException;

import java.time.LocalDateTime;

/**
 * Skip Tutorial Task.
 * This task is intended to skip the initial tutorial of the game.
 * It spam clicks a specific area and occasionally searches for a hand pointer to click.
 */
public class SkipTutorialRoutine extends DelayedTask {
    private static final int HAND_CLICK_OFFSET_X = -73;
    private static final int HAND_CLICK_OFFSET_Y = 88;


    private boolean isStarted = false;

    public SkipTutorialRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    private void ensureEmulatorRunning() {
        logInfo("Checking emulator status...");

        if (!isStarted) {
            InitializeRoutine.awaitEmulatorRunning(
                    () -> emuManager.isRunning(EMULATOR_NUMBER),
                    () -> {
                        logInfo("Emulator not found. Attempting to start it...");
                        emuManager.launchEmulator(EMULATOR_NUMBER);
                    },
                    () -> {
                        logInfo("Waiting 10 seconds before checking again.");
                        sleepTask(10000);
                    },
                    this::checkPreemption,
                    StartupRecoveryPolicy.MAX_EMULATOR_BOOT_ATTEMPTS,
                    this::deferEmulatorBootTimeout);
            isStarted = true;
            logInfo("Emulator is running.");
        }
    }

    private void deferEmulatorBootTimeout() {
        String reason = "Emulator failed to start after bounded launch attempts";
        LocalDateTime retryAt = LocalDateTime.now().plus(StartupRecoveryPolicy.EMULATOR_BOOT_FAILURE_COOLDOWN);
        logError("Skip Tutorial blocked for profile=" + profile.getName()
                + ", expected=emulator-running, observed=emulator-not-running"
                + ", lastAction=launched emulator " + EMULATOR_NUMBER
                + ", attempts=" + StartupRecoveryPolicy.MAX_EMULATOR_BOOT_ATTEMPTS
                + ", fallback=pause-profile-and-release-slot, retryAt=" + retryAt
                + ", reason=" + reason + ".");
        throw new ProfileCooldownException(reason, retryAt, new ActionRequiredContext(
                "skip-tutorial.emulator-launch-timeout",
                "Emulator failed to start for Skip Tutorial",
                "emulator running",
                "emulator remained stopped after " + StartupRecoveryPolicy.MAX_EMULATOR_BOOT_ATTEMPTS + " launch attempts",
                "Launched emulator and polled running state",
                "Check emulator configuration, available disk space, and VM health"));
    }

    private void ensureGameRunning() {
        if (!emuManager.isPackageRunning(EMULATOR_NUMBER, EmulatorController.GAME.getPackageName())) {
            logInfo("Whiteout Survival is not running. Launching the game...");
            emuManager.launchApp(EMULATOR_NUMBER, EmulatorController.GAME.getPackageName());
            sleepTask(10000); // Wait for game to launch
        } else {
            logInfo("Whiteout Survival is already running.");
        }
    }

    @Override
    protected void execute() {

        // Ensure emulator and game are running since InitializeRoutine might have been skipped
        ensureEmulatorRunning();
        ensureGameRunning();

        PointData clickBoxTopLeft = new PointData(588, 73);
        PointData clickBoxBottomRight = new PointData(677, 113);

        while (true) {
            checkPreemption();

            // Check if task is still enabled in configuration
            try {
                AccountDescriptor currentProfile = ProfileService.obtain().fetchAllAccounts().stream()
                        .filter(p -> p.getId().equals(profile.getId()))
                        .findFirst()
                        .orElse(null);

                if (currentProfile != null) {
                    Boolean skipEnabled = currentProfile.getConfig(ConfigurationKeyEnum.SKIP_TUTORIAL_ENABLED_BOOL, Boolean.class);
                    Boolean createSkipEnabled = currentProfile.getConfig(ConfigurationKeyEnum.CREATE_CHARACTER_SKIP_TUTORIAL_BOOL, Boolean.class);
                    
                    boolean isEnabled = (skipEnabled != null && skipEnabled) || (!isRecurring() && createSkipEnabled != null && createSkipEnabled);

                    if (!isEnabled) {
                        logInfo("Skip Tutorial Task disabled in configs (or not triggered from Create Character). Stopping execution.");
                        break;
                    }
                }
            } catch (Exception e) {
                logWarning("Failed to check Skip Tutorial task status: " + e.getMessage());
            }


            // Take a screenshot
            logInfo("Taking screenshot...");
            RawImageData screenshot = emuManager.captureScreen(EMULATOR_NUMBER);

            // Check for skip button within specific coordinates (537,44 to 715,140)
            if (screenshot != null) {
                ImageSearchResultData skipResult = emuManager.locatePattern(EMULATOR_NUMBER, screenshot, TemplatesEnum.SKIP_TUTORIAL_BUTTON, 
                        new PointData(537, 44), new PointData(715, 140), 80.0);
                if (skipResult != null && skipResult.isFound()) {
                    logInfo("Skip button found! Clicking it.");
                    tapInside(skipResult);
                    sleepTask(50);
                }
            }

            // Search for the hand template and its mirror
            logInfo("Searching for hand template and mirror...");
            if (screenshot != null) {
                ImageSearchResultData result = emuManager.locatePattern(EMULATOR_NUMBER, screenshot, TemplatesEnum.SKIP_TUTORIAL_HAND, 80.0);
                ImageSearchResultData mirrorResult = emuManager.locatePattern(EMULATOR_NUMBER, screenshot, TemplatesEnum.SKIP_TUTORIAL_HAND_MIRROR, 80.0);

                if ((result != null && result.isFound()) || (mirrorResult != null && mirrorResult.isFound())) {
                    logInfo("Hand template or mirror found! Clicking it with offset.");
                    PointData adjustedPoint;
                    if (result != null && result.isFound()) {
                        PointData handPoint = result.getPoint();
                        adjustedPoint = new PointData(handPoint.getX() + HAND_CLICK_OFFSET_X, handPoint.getY() + HAND_CLICK_OFFSET_Y);
                    } else {
                        // For mirrored hand, mirror the X offset as well
                        PointData handPoint = mirrorResult.getPoint();
                        adjustedPoint = new PointData(handPoint.getX() - HAND_CLICK_OFFSET_X, handPoint.getY() + HAND_CLICK_OFFSET_Y);
                    }
                    tapNear(adjustedPoint);
                }
            }
            
            
        }
        
    }
}
