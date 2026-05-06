package cl.camodev.wosbot.serv.task.impl;

import cl.camodev.wosbot.console.enumerable.EnumConfigurationKey;
import cl.camodev.wosbot.console.enumerable.EnumTemplates;
import cl.camodev.wosbot.console.enumerable.TpDailyTaskEnum;
import cl.camodev.wosbot.emulator.EmulatorManager;
import cl.camodev.wosbot.ot.DTOImageSearchResult;
import cl.camodev.wosbot.ot.DTOPoint;
import cl.camodev.wosbot.ot.DTOProfiles;
import cl.camodev.wosbot.ot.DTORawImage;
import cl.camodev.wosbot.serv.impl.ServProfiles;
import cl.camodev.wosbot.serv.task.DelayedTask;

/**
 * Skip Tutorial Task.
 * This task is intended to skip the initial tutorial of the game.
 * It spam clicks a specific area and occasionally searches for a hand pointer to click.
 */
public class SkipTutorialTask extends DelayedTask {
    private static final int HAND_CLICK_OFFSET_X = -73;
    private static final int HAND_CLICK_OFFSET_Y = 88;


    private boolean isStarted = false;

    public SkipTutorialTask(DTOProfiles profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    private void ensureEmulatorRunning() {
        logInfo("Checking emulator status...");

        while (!isStarted) {
            if (emuManager.isRunning(EMULATOR_NUMBER)) {
                isStarted = true;
                logInfo("Emulator is running.");
            } else {
                logInfo("Emulator not found. Attempting to start it...");
                emuManager.launchEmulator(EMULATOR_NUMBER);
                logInfo("Waiting 10 seconds before checking again.");
                sleepTask(10000); // Wait for emulator to start
            }
        }
    }

    private void ensureGameRunning() {
        if (!emuManager.isPackageRunning(EMULATOR_NUMBER, EmulatorManager.GAME.getPackageName())) {
            logInfo("Whiteout Survival is not running. Launching the game...");
            emuManager.launchApp(EMULATOR_NUMBER, EmulatorManager.GAME.getPackageName());
            sleepTask(10000); // Wait for game to launch
        } else {
            logInfo("Whiteout Survival is already running.");
        }
    }

    @Override
    protected void execute() {

        // Ensure emulator and game are running since InitializeTask might have been skipped
        ensureEmulatorRunning();
        ensureGameRunning();

        DTOPoint clickBoxTopLeft = new DTOPoint(588, 73);
        DTOPoint clickBoxBottomRight = new DTOPoint(677, 113);

        while (true) {
            checkPreemption();

            // Check if task is still enabled in configuration
            try {
                DTOProfiles currentProfile = ServProfiles.getServices().getProfiles().stream()
                        .filter(p -> p.getId().equals(profile.getId()))
                        .findFirst()
                        .orElse(null);

                if (currentProfile != null) {
                    Boolean skipEnabled = currentProfile.getConfig(EnumConfigurationKey.SKIP_TUTORIAL_ENABLED_BOOL, Boolean.class);
                    Boolean createSkipEnabled = currentProfile.getConfig(EnumConfigurationKey.CREATE_CHARACTER_SKIP_TUTORIAL_BOOL, Boolean.class);
                    
                    boolean isEnabled = (skipEnabled != null && skipEnabled) || (!isRecurring() && createSkipEnabled != null && createSkipEnabled);

                    if (!isEnabled) {
                        logInfo("Skip Tutorial Task disabled in settings (or not triggered from Create Character). Stopping execution.");
                        break;
                    }
                }
            } catch (Exception e) {
                logWarning("Failed to check Skip Tutorial task status: " + e.getMessage());
            }


            // Take a screenshot
            logInfo("Taking screenshot...");
            DTORawImage screenshot = emuManager.captureScreenshotViaADB(EMULATOR_NUMBER);

            // Check for skip button within specific coordinates (537,44 to 715,140)
            if (screenshot != null) {
                DTOImageSearchResult skipResult = emuManager.searchTemplate(EMULATOR_NUMBER, screenshot, EnumTemplates.SKIP_TUTORIAL_BUTTON, 
                        new DTOPoint(537, 44), new DTOPoint(715, 140), 80.0);
                if (skipResult != null && skipResult.isFound()) {
                    logInfo("Skip button found! Clicking it.");
                    tapPoint(skipResult.getPoint());
                    sleepTask(50);
                }
            }

            // Search for the hand template and its mirror
            logInfo("Searching for hand template and mirror...");
            if (screenshot != null) {
                DTOImageSearchResult result = emuManager.searchTemplate(EMULATOR_NUMBER, screenshot, EnumTemplates.SKIP_TUTORIAL_HAND, 80.0);
                DTOImageSearchResult mirrorResult = emuManager.searchTemplate(EMULATOR_NUMBER, screenshot, EnumTemplates.SKIP_TUTORIAL_HAND_MIRROR, 80.0);

                if ((result != null && result.isFound()) || (mirrorResult != null && mirrorResult.isFound())) {
                    logInfo("Hand template or mirror found! Clicking it with offset.");
                    DTOPoint adjustedPoint;
                    if (result != null && result.isFound()) {
                        DTOPoint handPoint = result.getPoint();
                        adjustedPoint = new DTOPoint(handPoint.getX() + HAND_CLICK_OFFSET_X, handPoint.getY() + HAND_CLICK_OFFSET_Y);
                    } else {
                        // For mirrored hand, mirror the X offset as well
                        DTOPoint handPoint = mirrorResult.getPoint();
                        adjustedPoint = new DTOPoint(handPoint.getX() - HAND_CLICK_OFFSET_X, handPoint.getY() + HAND_CLICK_OFFSET_Y);
                    }
                    tapPoint(adjustedPoint);
                }
            }
            
            
        }
        
    }
}
