package dev.frostguard.tasks.city;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import static dev.frostguard.api.configs.TemplatesEnum.GAME_HOME_FURNACE;
import static dev.frostguard.api.configs.TemplatesEnum.GAME_HOME_SHORTCUTS_GO;
import static dev.frostguard.api.configs.TemplatesEnum.GAME_HOME_SHORTCUTS_OBTAIN;
import static dev.frostguard.api.configs.TemplatesEnum.GAME_HOME_SHORTCUTS_UPGRADE_TEXT;
import static dev.frostguard.api.configs.TemplatesEnum.GAME_HOME_SHORTCUTS_UPGRADE;
import static dev.frostguard.api.configs.TemplatesEnum.GAME_HOME_WORLD;
import static dev.frostguard.engine.nav.LeftMenuTextSettings.*;

public class PrioritiseFurnaceRoutine extends DelayedTask {

private static final AreaData QUEUE_AREA_1_VALUE = new AreaData(new PointData(95, 377), new PointData(358, 398));

private static final AreaData QUEUE_AREA_2_VALUE = new AreaData(new PointData(95, 450), new PointData(358, 474));

private final List<AreaData> queues = new ArrayList<>(Arrays.asList(QUEUE_AREA_1_VALUE, QUEUE_AREA_2_VALUE));

private int retryCount = 0;

public PrioritiseFurnaceRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTaskEnum) {
        super(profile, tpDailyTaskEnum);
    }

@Override
    protected void execute() {

        ImageSearchResultData worldResult = templateSearchHelper.locatePattern(GAME_HOME_WORLD,
                SearchConfigConstants.DEFAULT_SINGLE);
        ImageSearchResultData cityResult = templateSearchHelper.locatePattern(GAME_HOME_FURNACE,
                SearchConfigConstants.DEFAULT_SINGLE);

        if (worldResult.isFound()) {
            logInfo(routineLogPrioritiseFurnaceLine("World image detected (Not on correct screen). Pressing to navigate."));
            tapInside(worldResult);
        } else if (cityResult.isFound()) {
            logInfo(routineLogPrioritiseFurnaceLine("City image detected. Screen state validated."));
        } else {
            logInfo(routineLogPrioritiseFurnaceLine("Neither World nor City image detected."));
        }


        tapNear(new PointData(15, 552));
        sleepTask(200);
        for (int i = 0; i < 4; i++) {
            tapNear(new PointData(120, 270));
            sleepTask(20);
        }
        sleepTask(200);


        List<QueueReadout> queueResults = inspectAllQueues();
        logQueueSummaryFlow(queueResults);

        boolean hasIdleQueue = queueResults.stream()
                .anyMatch(result -> result.state.status == QueueMood.IDLE ||
                        result.state.status == QueueMood.IDLE_TEMP);

        if (!hasIdleQueue) {
            deferBasedOnBusyQueues(queueResults);
            return;
        }


        tapNear(new PointData(300, 20));
        sleepTask(500);
        tapNear(new PointData(80, 649));
        sleepTask(500);

        ImageSearchResultData goResult = templateSearchHelper.locatePattern(GAME_HOME_SHORTCUTS_GO,
                SearchConfigConstants.DEFAULT_SINGLE);

        if (goResult.isFound()) {
            tapInside(goResult);
            sleepTask(500);
            tapNear(new PointData(7, 440));
            sleepTask(500);
            tapNear(new PointData(136, 658));
            sleepTask(500);

            ImageSearchResultData upgradeTextResult = templateSearchHelper.locatePattern(
                    GAME_HOME_SHORTCUTS_UPGRADE_TEXT,
                    SearchConfigConstants.DEFAULT_SINGLE);

            if (upgradeTextResult.isFound()) {
                logInfo(routineLogPrioritiseFurnaceLine("upgradetext.png detected. Pressing coordinates " + upgradeTextResult.getPoint()));
                tapInside(upgradeTextResult);
                sleepTask(200);


                for (int n = 0; n < 2; n++) {
                    ImageSearchResultData upgradeTextLoopResult = templateSearchHelper.locatePattern(
                            GAME_HOME_SHORTCUTS_UPGRADE_TEXT,
                            SearchConfigConstants.DEFAULT_SINGLE);
                    ImageSearchResultData obtainResult = templateSearchHelper.locatePattern(
                            GAME_HOME_SHORTCUTS_OBTAIN,
                            SearchConfigConstants.DEFAULT_SINGLE);

                    if (upgradeTextLoopResult.isFound()) {
                        logInfo(routineLogPrioritiseFurnaceLine("upgradetext.png detected again. Pressing it."));


                        long minutesToWait = 30;

                        try {
                            emuManager.captureScreen(EMULATOR_NUMBER);

                            TesseractSettingsData[] settingsToTry = {
                                    WHITE_SETTINGS,
                                    WHITE_NUMBERS,
                                    RED_SETTINGS,
                                    ORANGE_SETTINGS,
                            };

                            AreaData timeArea = new AreaData(new PointData(490, 1209), new PointData(605, 1239));
                            boolean timeFound = false;

                            for (TesseractSettingsData ocrPreset : settingsToTry) {
                                String ocrText = emuManager.readText(
                                        EMULATOR_NUMBER,
                                        timeArea.topLeft(),
                                        timeArea.bottomRight(),
                                        ocrPreset).trim();

                                logDebug(routineLogPrioritiseFurnaceLine("OCR result with ocrPreset " + ocrPreset.getClass().getSimpleName() + ": '"
                                        + ocrText + "'"));

                                if (ocrText.matches(".*(\\d+d\\s*)?\\d{6}.*")) {


                                    String cleanedTime = ocrText.replaceAll("[^0-9d]", "").trim();
                                    if (!cleanedTime.isEmpty()) {
                                        minutesToWait = decodeTimeToMinutes(cleanedTime);
                                        timeFound = true;
                                        logInfo(routineLogPrioritiseFurnaceLine("OCR finished cleanly. Time detected: " + cleanedTime + " (" + minutesToWait
                                                + " minutes)"));
                                        break;
                                    }
                                }
                            }
                            if (!timeFound) {
                                logWarning(routineLogPrioritiseFurnaceLine("Could not parse time from OCR. Using default 30 minutes."));
                            }

                        } catch (Exception e) {
                            logError(routineLogPrioritiseFurnaceLine("Issue while OCR time extraction: " + e.getMessage()));
                        }

                        tapInside(upgradeTextLoopResult);
                        sleepTask(1000);
                        if (obtainResult.isFound()) {
                            tapNear(new PointData(362, 1138));
                            sleepTask(200);
                            tapNear(new PointData(519, 1040));
                            sleepTask(500);
                            tapInside(upgradeTextLoopResult);
                        }
                        tapNear(new PointData(133, 545));


                        LocalDateTime rescheduleTime;
                        if (minutesToWait > 30) {
                            long halfTime = minutesToWait / 2;
                            rescheduleTime = LocalDateTime.now().plusMinutes(halfTime);
                            logInfo(routineLogPrioritiseFurnaceLine("Wait time exceeds 30 minutes (" + minutesToWait
                                    + " min). Planning next run for half time: " +
                                    halfTime + " minutes from now"));
                        } else if (minutesToWait < 5) {
                            rescheduleTime = LocalDateTime.now().plusMinutes(minutesToWait);
                            logInfo(routineLogPrioritiseFurnaceLine("Wait time is less than 5 minutes. Keeping normal schedule: " +
                                    minutesToWait + " minutes from now"));
                        } else {
                            rescheduleTime = LocalDateTime.now().plusMinutes(minutesToWait);
                            logInfo(routineLogPrioritiseFurnaceLine("Wait time is " + minutesToWait + " minutes. Using normal schedule"));
                        }

                        this.retryCount = 0;

                        this.reschedule(rescheduleTime);
                        return;
                    } else {
                        ImageSearchResultData goLoopResult = templateSearchHelper.locatePattern(
                                GAME_HOME_SHORTCUTS_GO,
                                SearchConfigConstants.DEFAULT_SINGLE);

                        if (goLoopResult.isFound()) {
                            logInfo(routineLogPrioritiseFurnaceLine("go.png detected. Pressing it."));
                            tapInside(goLoopResult);
                            sleepTask(500);


                            for (int j = 0; j < 4; j++) {
                                logInfo(routineLogPrioritiseFurnaceLine("Upgrade search iteration: " + (j + 1)));
                                tapNear(new PointData(350, 667));
                                sleepTask(200);

                                ImageSearchResultData upgradeResult = templateSearchHelper.locatePattern(
                                        GAME_HOME_SHORTCUTS_UPGRADE,
                                        SearchConfigConstants.DEFAULT_SINGLE);

                                if (upgradeResult.isFound()) {
                                    logInfo(routineLogPrioritiseFurnaceLine("upgrade.png detected. Pressing it."));
                                    tapInside(upgradeResult);
                                    break;

                                }
                            }
                        }
                    }
                }

            }

        }

        sleepTask(200);


        for (int k = 0; k < 3; k++) {
            logInfo(routineLogPrioritiseFurnaceLine("Step 4 Loop iteration: " + (k + 1)));

            ImageSearchResultData upgradeTextResult = templateSearchHelper.locatePattern(
                    GAME_HOME_SHORTCUTS_UPGRADE_TEXT,
                    SearchConfigConstants.DEFAULT_SINGLE);

            if (upgradeTextResult.isFound()) {
                logInfo(routineLogPrioritiseFurnaceLine("upgradetext.png detected in Step 4. Pressing it."));


                long minutesToWait = 60;

                try {
                    emuManager.captureScreen(EMULATOR_NUMBER);

                    TesseractSettingsData[] settingsToTry = {
                            WHITE_SETTINGS,
                            WHITE_NUMBERS,
                            RED_SETTINGS,
                            ORANGE_SETTINGS,
                    };

                    AreaData timeArea = new AreaData(new PointData(480, 1045), new PointData(596, 1070));
                    boolean timeFound = false;

                    for (TesseractSettingsData ocrPreset : settingsToTry) {
                        String ocrText = emuManager.readText(
                                EMULATOR_NUMBER,
                                timeArea.topLeft(),
                                timeArea.bottomRight(),
                                ocrPreset).trim();

                        logDebug(routineLogPrioritiseFurnaceLine("OCR result with ocrPreset " + ocrPreset.getClass().getSimpleName() + ": '" + ocrText
                                + "'"));

                        if (ocrText.matches(".*(\\d+d\\s*)?\\d{6}.*")) {


                            String cleanedTime = ocrText.replaceAll("[^0-9d]", "").trim();
                            if (!cleanedTime.isEmpty()) {
                                minutesToWait = decodeTimeToMinutes(cleanedTime);
                                timeFound = true;
                                logInfo(routineLogPrioritiseFurnaceLine("OCR finished cleanly. Time detected: " + cleanedTime + " (" + minutesToWait + " minutes)"));
                                break;
                            }
                        }
                    }
                    if (!timeFound) {
                        logWarning(routineLogPrioritiseFurnaceLine("Could not parse time from OCR. Using default 60 minutes."));
                    }

                } catch (Exception e) {
                    logError(routineLogPrioritiseFurnaceLine("Issue while OCR time extraction: " + e.getMessage()));
                }

                tapInside(upgradeTextResult);
                sleepTask(1000);

                tapNear(new PointData(362, 581));


                LocalDateTime rescheduleTime;
                if (minutesToWait > 30) {
                    long halfTime = minutesToWait / 2;
                    rescheduleTime = LocalDateTime.now().plusMinutes(halfTime);
                    logInfo(routineLogPrioritiseFurnaceLine("Wait time exceeds 30 minutes (" + minutesToWait + " min). Planning next run for half time: " +
                            halfTime + " minutes from now"));
                } else if (minutesToWait < 5) {
                    rescheduleTime = LocalDateTime.now().plusMinutes(minutesToWait);
                    logInfo(routineLogPrioritiseFurnaceLine("Wait time is less than 5 minutes. Keeping normal schedule: " +
                            minutesToWait + " minutes from now"));
                } else {
                    rescheduleTime = LocalDateTime.now().plusMinutes(minutesToWait);
                    logInfo(routineLogPrioritiseFurnaceLine("Wait time is " + minutesToWait + " minutes. Using normal schedule"));
                }

                this.retryCount = 0;

                this.reschedule(rescheduleTime);
                return;
            } else {


                for (int m = 0; m < 3; m++) {
                    logInfo(routineLogPrioritiseFurnaceLine("Obtain loop iteration: " + (m + 1)));
                    ImageSearchResultData obtainResult = templateSearchHelper.locatePattern(
                            GAME_HOME_SHORTCUTS_OBTAIN,
                            SearchConfigConstants.DEFAULT_SINGLE);

                    if (obtainResult.isFound()) {
                        logInfo(routineLogPrioritiseFurnaceLine("obtain.png detected. Executing obtain sequence."));
                        tapInside(obtainResult);
                        sleepTask(200);
                        tapNear(new PointData(362, 1138));
                        sleepTask(200);
                        tapNear(new PointData(519, 1040));
                        sleepTask(500);
                    } else {
                        break;
                    }
                }
            }
        }


        manageTaskFailure();
    }

private enum QueueMood {
        IDLE,

        BUSY,

        NOT_PURCHASED,

        IDLE_TEMP,

        UNKNOWN

    }

private record QueueSnapshot(QueueMood status, String timeRemaining) {
    }

private record QueueReadout(int queueNumber, AreaData queueArea, QueueSnapshot state) {

        @Override
        public @NotNull String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Queue ").append(queueNumber).append(": ");
            sb.append(state.status);
            if (state.timeRemaining != null) {
                sb.append(" (").append(state.timeRemaining).append(")");
            }
            return sb.toString();
        }
    }

@Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

private String routineLogPrioritiseFurnaceLine(String note) {
        return "PrioritiseFurnaceRoutine | " + note;
    }

private void deferBasedOnBusyQueues(List<QueueReadout> queueResults) {
        logInfo(routineLogPrioritiseFurnaceLine("Zero IDLE queues available. Inspecting BUSY queues to reschedule..."));
        this.retryCount = 0;


        QueueReadout shortestBusyQueue = queueResults.stream()
                .filter(result -> result.state.status == QueueMood.BUSY && result.state.timeRemaining != null)
                .min((q1, q2) -> {
                    long time1 = decodeTimeToMinutes(q1.state.timeRemaining);
                    long time2 = decodeTimeToMinutes(q2.state.timeRemaining);
                    return Long.compare(time1, time2);
                })
                .orElse(null);

        if (shortestBusyQueue != null) {
            long minutesToWait = decodeTimeToMinutes(shortestBusyQueue.state.timeRemaining);
            LocalDateTime rescheduleTime;

            if (minutesToWait > 30) {

                long halfTime = minutesToWait / 2;
                rescheduleTime = LocalDateTime.now().plusMinutes(halfTime);
                logInfo(routineLogPrioritiseFurnaceLine("Wait time exceeds 30 minutes (" + minutesToWait + " min). Planning next run for half time: " +
                        halfTime + " minutes from now"));
            } else if (minutesToWait < 5) {
                rescheduleTime = LocalDateTime.now().plusMinutes(minutesToWait);
                logInfo(routineLogPrioritiseFurnaceLine("Wait time is less than 5 minutes. Keeping normal schedule: " +
                        minutesToWait + " minutes from now"));
            } else {
                rescheduleTime = LocalDateTime.now().plusMinutes(minutesToWait);
                logInfo(routineLogPrioritiseFurnaceLine("Wait time is " + minutesToWait + " minutes. Using normal schedule"));
            }

            logInfo(routineLogPrioritiseFurnaceLine("Shortest busy queue: Queue " + shortestBusyQueue.queueNumber +
                    " with " + shortestBusyQueue.state.timeRemaining + " remaining"));
            logInfo(routineLogPrioritiseFurnaceLine("Planning next run task for: " + rescheduleTime));

            this.reschedule(rescheduleTime);
        } else {


            LocalDateTime rescheduleTime = LocalDateTime.now().plusHours(1);
            logWarning(routineLogPrioritiseFurnaceLine("Zero BUSY queues with time information detected. Planning next run for 1 hour: " + rescheduleTime));
            this.reschedule(rescheduleTime);
        }
    }

private void logQueueSummaryFlow(List<QueueReadout> queueResults) {
        logInfo(routineLogPrioritiseFurnaceLine("=== Queue Analysis Summary ==="));
        for (QueueReadout result : queueResults) {
            logInfo(routineLogPrioritiseFurnaceLine(result.toString()));
        }
    }

private long decodeTimeToMinutes(String timeString) {
        if (timeString == null || timeString.isEmpty()) {
            return 0;
        }

        try {
            long totalMinutes = 0;
            String timePart = timeString.trim();


            if (timePart.toLowerCase().contains("d")) {
                String[] daysPart = timePart.toLowerCase().split("d");
                if (daysPart.length > 0) {
                    String daysStr = daysPart[0].replaceAll("[^0-9]", "");
                    if (!daysStr.isEmpty()) {
                        int days = Integer.parseInt(daysStr);
                        totalMinutes += (long) days * 24 * 60;

                    }
                }


                if (daysPart.length > 1) {
                    timePart = daysPart[1].trim();
                } else {
                    return totalMinutes;
                }
            }


            timePart = timePart.replaceAll("[^0-9:]", "");

            if (timePart.isEmpty()) {
                return totalMinutes;
            }


            if (timePart.contains(":")) {


                String[] timeParts = timePart.split(":");
                if (timeParts.length >= 2) {


                    if (!timeParts[0].isEmpty()) {
                        int hours = Integer.parseInt(timeParts[0]);
                        totalMinutes += hours * 60L;
                    }


                    if (!timeParts[1].isEmpty()) {
                        int minutes = Integer.parseInt(timeParts[1]);
                        totalMinutes += minutes;
                    }
                }
            } else {


                if (timePart.length() >= 4) {


                    String hoursStr = timePart.substring(0, 2);
                    int hours = Integer.parseInt(hoursStr);
                    totalMinutes += hours * 60L;


                    String minutesStr = timePart.substring(2, 4);
                    int minutes = Integer.parseInt(minutesStr);
                    totalMinutes += minutes;
                }
            }

            return totalMinutes;

        } catch (Exception e) {
            logError(routineLogPrioritiseFurnaceLine("Error parsing time string '" + timeString + "': " + e.getMessage()));
            return 15;

        }
    }

private void manageTaskFailure() {
        retryCount++;
        if (retryCount < 3) {
            logWarning(routineLogPrioritiseFurnaceLine("Task iteration did not complete to find expected elements. Retrying (" + retryCount + "/3)."));


            this.reschedule(LocalDateTime.now().plusSeconds(10));
        } else {
            logError(routineLogPrioritiseFurnaceLine("Routine pass did not complete 3 consecutive times. Planning next run for 30 minutes to avoid loop."));
            retryCount = 0;
            this.reschedule(LocalDateTime.now().plusMinutes(30));
        }
    }

private void logQueueStateFlow(int queueIndex, QueueSnapshot state) {
        switch (state.status) {
            case IDLE:
                logInfo(routineLogPrioritiseFurnaceLine("Queue " + queueIndex + " is IDLE - available for use"));
                break;
            case BUSY:
                logInfo(routineLogPrioritiseFurnaceLine("Queue " + queueIndex + " is BUSY - Time remaining: " + state.timeRemaining));
                break;
            case NOT_PURCHASED:
                logInfo(routineLogPrioritiseFurnaceLine("Queue " + queueIndex + " is NOT PURCHASED - needs to be acquired"));
                break;
            case IDLE_TEMP:
                logInfo(routineLogPrioritiseFurnaceLine("Queue " + queueIndex + " is IDLE_TEMP - detected by orange color"));
                break;
            case UNKNOWN:
                logWarning(routineLogPrioritiseFurnaceLine("Queue " + queueIndex + " state is UNKNOWN - OCR did not complete to detect state"));
                break;
        }
    }

private QueueSnapshot inspectQueueState(AreaData queueArea) {
        try {


            TesseractSettingsData[] settingsToTry = {
                    WHITE_SETTINGS,
                    WHITE_NUMBERS,
                    RED_SETTINGS,
                    ORANGE_SETTINGS,
            };

            for (TesseractSettingsData ocrPreset : settingsToTry) {
                String ocrText = emuManager.readText(
                        EMULATOR_NUMBER,
                        queueArea.topLeft(),
                        queueArea.bottomRight(),
                        ocrPreset).trim();

                logDebug(routineLogPrioritiseFurnaceLine("OCR result with ocrPreset " + ocrPreset.getClass().getSimpleName() + ": '" + ocrText + "'"));


                if (ocrText.toLowerCase().contains("idle")) {

                    if (ocrPreset == ORANGE_SETTINGS) {
                        logDebug(routineLogPrioritiseFurnaceLine("Orange 'idle' text detected - IDLE_TEMP"));
                        return new QueueSnapshot(QueueMood.IDLE_TEMP, null);
                    } else {
                        return new QueueSnapshot(QueueMood.IDLE, null);
                    }
                }


                if (ocrText.toLowerCase().contains("purchase") ||
                        ocrText.toLowerCase().contains("queue")) {
                    return new QueueSnapshot(QueueMood.NOT_PURCHASED, null);
                }


                if (ocrText.matches(".*(\\d+d\\s*)?\\d{6}.*")) {


                    String cleanedTime = ocrText.replaceAll("[^0-9d]", "").trim();
                    if (!cleanedTime.isEmpty()) {
                        return new QueueSnapshot(QueueMood.BUSY, cleanedTime);
                    }
                }
            }


            return new QueueSnapshot(QueueMood.UNKNOWN, null);

        } catch (Exception e) {
            logError(routineLogPrioritiseFurnaceLine("Issue while OCR analysis: " + e.getMessage()));
            return new QueueSnapshot(QueueMood.UNKNOWN, null);
        }
    }

private List<QueueReadout> inspectAllQueues() {
        List<QueueReadout> results = new ArrayList<>();

        try {


            emuManager.captureScreen(EMULATOR_NUMBER);

            int queueIndex = 1;
            for (AreaData queueArea : queues) {
                logInfo(routineLogPrioritiseFurnaceLine("Analyzing queue " + queueIndex));


                QueueSnapshot state = inspectQueueState(queueArea);


                QueueReadout result = new QueueReadout(
                        queueIndex, queueArea, state);
                results.add(result);


                logQueueStateFlow(queueIndex, state);

                queueIndex++;
            }


            List<QueueReadout> unknownResults = results.stream()
                    .filter(result -> result.state.status == QueueMood.UNKNOWN)
                    .collect(Collectors.toList());

            if (!unknownResults.isEmpty()) {
                logInfo(routineLogPrioritiseFurnaceLine("Detected " + unknownResults.size()
                        + " queue(s) with UNKNOWN status. Retrying with a new screenshot."));


                emuManager.captureScreen(EMULATOR_NUMBER);


                List<QueueReadout> updatedResults = new ArrayList<>();


                for (QueueReadout originalResult : results) {
                    if (originalResult.state.status == QueueMood.UNKNOWN) {


                        logInfo(routineLogPrioritiseFurnaceLine("Retrying analysis for queue " + originalResult.queueNumber));
                        QueueSnapshot newState = inspectQueueState(originalResult.queueArea);


                        QueueReadout newResult = new QueueReadout(
                                originalResult.queueNumber, originalResult.queueArea, newState);


                        updatedResults.add(newResult);


                        logInfo(routineLogPrioritiseFurnaceLine("Queue " + originalResult.queueNumber + " reanalyzed. New state: " + newState.status));
                        logQueueStateFlow(originalResult.queueNumber, newState);
                    } else {


                        updatedResults.add(originalResult);
                    }
                }


                results = updatedResults;
            }
        } catch (Exception e) {
            logError(routineLogPrioritiseFurnaceLine("Error analyzing construction queues: " + e.getMessage()));
        }

        return results;
    }
}
