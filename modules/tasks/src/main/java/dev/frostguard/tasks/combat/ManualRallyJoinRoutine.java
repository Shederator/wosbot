package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.schedule.preempt.ManualRallyJoinPreemptionRule;
import dev.frostguard.vision.color.PixelStats;
import dev.frostguard.vision.ocr.OcrEngine;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.util.List;

public class ManualRallyJoinRoutine extends DelayedTask {

// An enabled Join button is green; a disabled one is greyed out.
private static final Color JOIN_BUTTON_GREEN = new Color(0x25, 0xB7, 0x56);
private static final int JOIN_BUTTON_GREEN_TOLERANCE = 40;
private static final int MIN_JOIN_BUTTON_GREEN_PIXELS = 5;

public ManualRallyJoinRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

@Override
    protected void execute() {


        logInfo(routineLogManualRallyJoinLine("Scanning for rally indicator..."));
        ImageSearchResultData indicator = templateSearchHelper.locatePattern(
                TemplatesEnum.RALLY_INDICATOR,
                SearchConfigConstants.DEFAULT_SINGLE);

        if (indicator.isFound()) {
            logInfo(routineLogManualRallyJoinLine("Rally indicator detected at " + indicator.getPoint()));
            tapInside(indicator);
            sleepTask(1000);


            String targetKey = profile.getConfig(ConfigurationKeyEnum.RALLY_TARGET_STRING, String.class);
            boolean isEverything = "everything".equalsIgnoreCase(targetKey);
            TemplatesEnum rallyTarget = isEverything ? null : resolveTargetTemplateFlow(targetKey);
            logInfo(routineLogManualRallyJoinLine("Using rally target template: " + (isEverything ? "Everything" : rallyTarget.name()) + " (key: "
                    + targetKey + ")"));

            Integer marchesConfig = profile.getConfig(ConfigurationKeyEnum.RALLY_MARCHES_INT, Integer.class);
            int maxMarches = (marchesConfig != null) ? marchesConfig : 1;
            int deployedCount = ManualRallyJoinPreemptionRule.getActiveDeploymentsCount(profile.getId());

            logInfo(routineLogManualRallyJoinLine("Entering deploy loop - will deploy up to " + maxMarches + " marches..."));
            while (deployedCount < maxMarches) {


                logInfo(routineLogManualRallyJoinLine("Scanning for targets and join buttons..."));
                List<ImageSearchResultData> targets = isEverything ? null
                        : templateSearchHelper.locateAllPatterns(
                                rallyTarget, SearchConfigConstants.MULTIPLE_RESULTS);
                List<ImageSearchResultData> joinButtons = templateSearchHelper.locateAllPatterns(
                        TemplatesEnum.RALLY_JOIN, SearchConfigConstants.MULTIPLE_RESULTS);

                PointData validJoinPoint = null;
                if (isEverything) {
                    if (joinButtons != null) {
                        for (ImageSearchResultData join : joinButtons) {
                            if (hasJoinButtonGreen(join.getPoint())) {
                                logInfo(routineLogManualRallyJoinLine("Detected an active green Join button at " + join.getPoint()
                                        + ". Pressing Join (Everything mode)."));
                                validJoinPoint = join.getPoint();
                                break;
                            } else {
                                logInfo(routineLogManualRallyJoinLine("Join button at " + join.getPoint()
                                        + " appears greyed out (not green). Skipping."));
                            }
                        }
                    }
                } else if (targets != null && joinButtons != null) {
                    outerLoop: for (ImageSearchResultData target : targets) {
                        for (ImageSearchResultData join : joinButtons) {
                            int yDiff = Math.abs(target.getPoint().getY() - join.getPoint().getY());
                            if (yDiff < 50) {
                                if (!hasJoinButtonGreen(join.getPoint())) {
                                    logInfo(routineLogManualRallyJoinLine("Join button at " + join.getPoint()
                                            + " appears greyed out (not green). Skipping."));
                                    continue;
                                }
                                logInfo(routineLogManualRallyJoinLine("Match detected! Target at " + target.getPoint() +
                                        " and Join at " + join.getPoint() +
                                        " (Diff: " + yDiff + "). Pressing Join."));
                                validJoinPoint = join.getPoint();
                                break outerLoop;
                            }
                        }
                    }
                }


                if (validJoinPoint == null) {
                    logInfo(routineLogManualRallyJoinLine("Zero matching target and join button detected. Ending task."));
                    reschedule(LocalDateTime.now().plusYears(100));
                    return;
                }


                tapNear(validJoinPoint);
                sleepTask(1000);


                logInfo(routineLogManualRallyJoinLine("Scanning for Deploy button..."));
                ImageSearchResultData deployBtn = templateSearchHelper.locatePattern(
                        TemplatesEnum.RALLY_DEPLOY_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);

                if (!deployBtn.isFound()) {
                    logError(routineLogManualRallyJoinLine("Deploy button not detected. Ending task."));
                    reschedule(LocalDateTime.now().plusYears(100));
                    return;
                }

                logInfo(routineLogManualRallyJoinLine("Deploy button detected. Proceeding with formation ocrPreset."));


                ConfigurationKeyEnum flagKey;
                switch (deployedCount) {
                    case 0:
                        flagKey = ConfigurationKeyEnum.RALLY_MARCH_1_FLAG_STRING;
                        break;
                    case 1:
                        flagKey = ConfigurationKeyEnum.RALLY_MARCH_2_FLAG_STRING;
                        break;
                    case 2:
                        flagKey = ConfigurationKeyEnum.RALLY_MARCH_3_FLAG_STRING;
                        break;
                    case 3:
                        flagKey = ConfigurationKeyEnum.RALLY_MARCH_4_FLAG_STRING;
                        break;
                    case 4:
                        flagKey = ConfigurationKeyEnum.RALLY_MARCH_5_FLAG_STRING;
                        break;
                    case 5:
                        flagKey = ConfigurationKeyEnum.RALLY_MARCH_6_FLAG_STRING;
                        break;
                    default:
                        flagKey = ConfigurationKeyEnum.RALLY_MARCH_1_FLAG_STRING;
                        break;
                }

                String currentFlagString = profile.getConfig(flagKey, String.class);
                boolean useFlag = false;
                int currentFlagNumber = 0;
                if (currentFlagString != null && !currentFlagString.trim().isEmpty()
                        && !currentFlagString.trim().equalsIgnoreCase("No Flag")) {
                    try {
                        currentFlagNumber = Integer.parseInt(currentFlagString.trim());
                        useFlag = true;
                    } catch (NumberFormatException e) {
                        logWarning(routineLogManualRallyJoinLine("Invalid flag number in config for march " + (deployedCount + 1) + ": "
                                + currentFlagString + ". Proceeding without flag."));
                    }
                }

                if (useFlag) {
                    logInfo(routineLogManualRallyJoinLine("Flag configuration detected for march " + (deployedCount + 1) + ": #" + currentFlagNumber
                            + ". Selecting flag."));
                    if (!marchHelper.selectFlag(currentFlagNumber)) {
                        logWarning(routineLogManualRallyJoinLine("Configured formation #" + currentFlagNumber
                                + " is unavailable. Cancelling this join."));
                        pressBack();
                        reschedule(LocalDateTime.now().plusMinutes(5));
                        return;
                    }
                    sleepTask(300);
                } else {
                    logInfo(routineLogManualRallyJoinLine("Zero flag configured for march " + (deployedCount + 1) + ". Scanning for Equalize button."));
                    ImageSearchResultData equalizeBtn = templateSearchHelper.locatePattern(
                            TemplatesEnum.RALLY_EQUALIZE_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
                    if (equalizeBtn.isFound()) {
                        tapInside(equalizeBtn);
                        sleepTask(300);
                    } else {
                        logWarning(routineLogManualRallyJoinLine("Equalize button not detected for march " + (deployedCount + 1) + "."));
                    }
                }


                long travelTimeSeconds = deploymentHelper.readTravelTimeSeconds();
                LocalDateTime returnTime;
                if (travelTimeSeconds > 0) {


                    returnTime = LocalDateTime.now().plusSeconds(travelTimeSeconds * 2).plusMinutes(5);
                    logInfo(routineLogManualRallyJoinLine("Travel time: " + travelTimeSeconds + "s -> march expected back at " + returnTime));
                } else {


                    returnTime = LocalDateTime.now().plusMinutes(7);
                    logWarning(routineLogManualRallyJoinLine("Could not read travel time via OCR. Using 7-minute fallback."));
                }

                logInfo(routineLogManualRallyJoinLine("Pressing Deploy button."));
                tapInside(deployBtn);
                sleepTask(500);
                ManualRallyJoinPreemptionRule.registerDeployment(profile.getId(), returnTime);
                ManualRallyJoinPreemptionRule.incrementSessionJoinedCount(profile.getId());
                deployedCount++;

                if (deployedCount >= maxMarches) {
                    logInfo(routineLogManualRallyJoinLine("Successfully deployed " + deployedCount
                            + " marches. Reached configured limit. Ending task."));
                    break;
                }

                logInfo(routineLogManualRallyJoinLine("March " + deployedCount + " deployed finished cleanly. Re-scanning for more available rallies..."));


            }
        } else {
            logInfo(routineLogManualRallyJoinLine("Rally indicator not detected."));
        }


        reschedule(LocalDateTime.now().plusYears(100));
    }

private String routineLogManualRallyJoinLine(String note) {
        return "ManualRallyJoinRoutine | " + note;
    }

private TemplatesEnum resolveTargetTemplateFlow(String key) {
        if (key == null)
            return TemplatesEnum.BERSERK_CRYPTID_TARGET;
        switch (key.trim()) {
            case "caveLion":
                return TemplatesEnum.CAVE_LION_TARGET;
            case "snowApe":
                return TemplatesEnum.SNOW_APE_TARGET;
            case "berserkCryptid":
            default:
                return TemplatesEnum.BERSERK_CRYPTID_TARGET;
        }
    }

private boolean hasJoinButtonGreen(PointData center) {
        try {
            RawImageData rawImage = emuManager.captureScreen(EMULATOR_NUMBER);
            BufferedImage img = dev.frostguard.vision.convert.ImageConverter.toBufferedImage(rawImage);

            AreaData joinButton = new AreaData(
                    new PointData(center.getX() - 20, center.getY() - 10),
                    new PointData(center.getX() + 20, center.getY() + 10));
            int greenCount = PixelStats.count(img, joinButton,
                    PixelStats.near(JOIN_BUTTON_GREEN, JOIN_BUTTON_GREEN_TOLERANCE));

            if (greenCount >= MIN_JOIN_BUTTON_GREEN_PIXELS) {
                return true;
            }
            logInfo(routineLogManualRallyJoinLine("Green pixel count at join button: " + greenCount
                    + " (needs >= " + MIN_JOIN_BUTTON_GREEN_PIXELS + ")"));
            return false;
        } catch (Exception e) {
            logWarning(routineLogManualRallyJoinLine("Color check did not complete for join button at " + center + ": " + e.getMessage()
                    + ". Allowing tap anyway."));
            return true;

        }
    }
}
