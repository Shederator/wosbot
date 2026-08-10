package dev.frostguard.tasks.events;

import dev.frostguard.engine.schedule.LaunchPoint;


import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.nav.SearchConfigConstants;

import java.time.LocalDateTime;

public class JourneyofLightRoutine extends DelayedTask {

    private ResilientOcrExecutor<LocalDateTime> textHelper;

    public JourneyofLightRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    protected void execute() {

        this.textHelper = new ResilientOcrExecutor<>(provider);

        ImageSearchResultData dealsResult = templateSearchHelper.locatePattern(
                TemplatesEnum.HOME_DEALS_BUTTON, SearchConfigConstants.DEFAULT_SINGLE);

        if (!dealsResult.isFound()) {
            logWarning("The 'Deals' button was not found. Retrying in 5 minutes. ");
            reschedule(LocalDateTime.now().plusMinutes(5));
        }

        tapInside(dealsResult);
        sleepTask(1500);

        // Try to navigate to the event screen, retrying up to 3 times if necessary
        boolean navigated = navigateToEventScreen();
        for (int i = 0; i < 3 && !navigated; i++) {
            logDebug("Retrying navigation to the Journey of Light event screen. Attempt " + (i + 1) + " of 3.");
            sleepTask(1000);
            navigated = navigateToEventScreen();
        }

        if (!navigated) {
            logWarning(
                    "Failed to navigate to the Journey of Light event screen after 3 attempts. Rescheduling to next reset.");
            reschedule(GameTimeUtils.dailyResetTime());
            return;
        }

        // Check if the event has ended
        if (eventHasEnded()) {
            logInfo("Journey of Light event has ended. Rescheduling to next reset.");
            reschedule(GameTimeUtils.dailyResetTime());
            return;
        }

        // Do the actual JOL things
        tapInside(new PointData(50, 1150), new PointData(290, 1230), 5, 200);

        // fetch remaining time for all 4
        LocalDateTime nextScheduleTime = LocalDateTime.now().plusHours(1000);

        PointData[][] queues = {
                { new PointData(62, 1036), new PointData(166, 1058) },
                { new PointData(234, 1036), new PointData(338, 1058) },
                { new PointData(397, 1036), new PointData(501, 1058) },
                { new PointData(560, 1036), new PointData(664, 1058) },
        };

        TesseractSettingsData configs = TesseractSettingsData.assembler()
                .pageAnalysis(TesseractSettingsData.PageAnalysis.SINGLE_LINE)
                .recognitionEngine(TesseractSettingsData.RecognitionEngine.LSTM_ONLY)
                .charWhitelist("0123456789:")
                .build();

        for (PointData[] queue : queues) {
            LocalDateTime nextQueueTime = textHelper.attemptRecognition(
                    queue[0],
                    queue[1],
                    3,
                    200L,
                    configs,
                    GameTimeUtils::isAcceptedFormat,
                    text -> LocalDateTime.now().plus(GameTimeUtils.parseDuration(text)));

            if (nextQueueTime == null) {
                logWarning("Failed to fetch next queue time for queue " + queue[0]);
                continue;
            }

            if (nextQueueTime.isBefore(nextScheduleTime)) {
                nextScheduleTime = nextQueueTime;
            }
            logInfo("Next queue time for queue " + profile.getName() + ": "
                    + GameTimeUtils.formatCountdown(nextQueueTime));
        }
;
        reschedule(nextScheduleTime);

        sleepTask(200);
        checkAndClaimFreeWatches();
        for (int i = 0; i < 3; i++) {
            sleepTask(500);
            pressBack();
        }
    }

    private boolean navigateToEventScreen() {
        // Close any windows that may be open
        tapInside(new PointData(529, 27), new PointData(635, 63), 5, 300);

        // Search for the Journey of Light menu within deals
        ImageSearchResultData result1 = templateSearchHelper.locatePattern(
                TemplatesEnum.JOURNEY_OF_LIGHT_TAB, SearchConfigConstants.DEFAULT_SINGLE);
        ImageSearchResultData result2 = templateSearchHelper.locatePattern(
                TemplatesEnum.JOURNEY_OF_LIGHT_UNSELECTED_TAB, SearchConfigConstants.DEFAULT_SINGLE);

        if (result1.isFound() || result2.isFound()) {
            logInfo("Successfully navigated to the Journey of Light event.");
            sleepTask(500);
            tapInside(result1.isFound() ? result1 : result2);
            sleepTask(1000);

            // Tap "Journey of Light" tab to make sure "My Treasures" tab is not active
            tapInside(new PointData(50, 220), new PointData(350, 260));
            sleepTask(500);

            return true;
        }

        return false;
    }

    private boolean eventHasEnded() {
        String result = stringHelper.attemptRecognition(
                new PointData(50, 300),
                new PointData(400, 400),
                1,
                300L,
                null,
                s -> !s.isEmpty(),
                s -> s);
        if (result == null)
            return false;
        return result.contains("collect");
    }

    private void checkAndClaimFreeWatches() {
        ImageSearchResultData result = templateSearchHelper.locatePattern(
                TemplatesEnum.JOURNEY_OF_LIGHT_FREE_WATCHES, SearchConfigConstants.DEFAULT_SINGLE);

        if (!result.isFound()) {
            logInfo("No free watches found, skipping claim.");
            return;
        }

        tapInside(result);
        sleepTask(500);

        ImageSearchResultData freeWatch = templateSearchHelper.locatePattern(
                TemplatesEnum.JOURNEY_OF_LIGHT_CLAIM_WATCHES, SearchConfigConstants.DEFAULT_SINGLE);

        if (!freeWatch.isFound()) {
            logInfo("No free watches found, skipping claim.");
            return;
        }

        tapInside(freeWatch);
    }
}
