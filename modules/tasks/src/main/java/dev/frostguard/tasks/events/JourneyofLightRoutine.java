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
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.tasks.diagnostics.TaskDiagnosticSnapshots;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class JourneyofLightRoutine extends DelayedTask {

    private static final PointData EVENT_STATUS_TOP_LEFT = new PointData(35, 275);
    private static final PointData EVENT_STATUS_BOTTOM_RIGHT = new PointData(685, 405);
    private static final PointData EVENT_QUEUES_TOP_LEFT = new PointData(200, 1025);
    private static final PointData EVENT_QUEUES_BOTTOM_RIGHT = new PointData(690, 1100);
    private static final int EVENT_STATUS_ATTEMPTS = 3;
    private static final long EVENT_STATUS_RETRY_DELAY_MS = 250L;
    private int consecutiveNavigationFailures;

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
            scheduleNavigationRetry("Deals button was not detected", "deals-button");
            return;
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
            scheduleNavigationRetry("event screen was not verified after 4 attempts", "event-navigation");
            return;
        }

        // Check if the event has ended
        JourneyOfLightEventStatus eventStatus = readEventStatus();
        if (eventStatus == JourneyOfLightEventStatus.UNKNOWN) {
            scheduleNavigationRetry("event status OCR remained unknown after retries", "event-status");
            return;
        }
        consecutiveNavigationFailures = 0;
        if (eventStatus == JourneyOfLightEventStatus.ENDED) {
            logInfo("Journey of Light event has ended. Rescheduling to next reset.");
            reschedule(GameTimeUtils.dailyResetTime());
            return;
        }

        // Do the actual JOL things
        tapInside(new PointData(50, 1150), new PointData(290, 1230), 5, 200);

        // fetch remaining time for all 4
        List<LocalDateTime> queueTimes = new ArrayList<>();

        PointData[][] queues = {
                { new PointData(62, 1036), new PointData(166, 1058) },
                { new PointData(234, 1036), new PointData(338, 1058) },
                { new PointData(397, 1036), new PointData(501, 1058) },
                { new PointData(560, 1036), new PointData(664, 1058) },
        };

        OcrSettingsData configs = OcrSettingsData.assembler()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)

                .charWhitelist("0123456789:")
                .build();

        for (int queueIndex = 0; queueIndex < queues.length; queueIndex++) {
            PointData[] queue = queues[queueIndex];
            LocalDateTime nextQueueTime = textHelper.attemptRecognition(
                    queue[0],
                    queue[1],
                    3,
                    200L,
                    configs,
                    GameTimeUtils::isAcceptedFormat,
                    text -> LocalDateTime.now().plus(GameTimeUtils.parseDuration(text)));

            if (nextQueueTime == null) {
                logDebug("Journey of Light queue " + (queueIndex + 1) + " timer was unreadable.");
                queueTimes.add(null);
                continue;
            }

            queueTimes.add(nextQueueTime);
            logInfo("Journey of Light queue " + (queueIndex + 1) + " completes in "
                    + GameTimeUtils.formatCountdown(nextQueueTime));
        }
        QueueSchedule queueSchedule = resolveQueueSchedule(LocalDateTime.now(), queueTimes);
        if (queueSchedule.incomplete()) {
            String snapshot = TaskDiagnosticSnapshots.capture(
                    emuManager, EMULATOR_NUMBER, "journeyoflight", "queue-timer");
            logWarning("Journey of Light queue scan was incomplete; retrying at "
                    + queueSchedule.nextCheck().format(DATETIME_FORMATTER) + "; " + snapshot + ".");
            reschedule(queueSchedule.nextCheck());
        } else {
            reschedule(queueSchedule.nextCheck());
        }

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

    private JourneyOfLightEventStatus readEventStatus() {
        String statusText = readNonBlankText(EVENT_STATUS_TOP_LEFT, EVENT_STATUS_BOTTOM_RIGHT);
        JourneyOfLightEventStatus status = JourneyOfLightEventStatus.classify(statusText, null);
        if (status == JourneyOfLightEventStatus.ENDED) {
            return status;
        }

        String queueText = readNonBlankText(EVENT_QUEUES_TOP_LEFT, EVENT_QUEUES_BOTTOM_RIGHT);
        return JourneyOfLightEventStatus.classify(statusText, queueText);
    }

    private String readNonBlankText(PointData topLeft, PointData bottomRight) {
        for (int attempt = 0; attempt < EVENT_STATUS_ATTEMPTS; attempt++) {
            String text = stringHelper.attemptRecognition(
                    topLeft,
                    bottomRight,
                    1,
                    EVENT_STATUS_RETRY_DELAY_MS,
                    null,
                    value -> value != null && !value.isBlank(),
                    String::trim);
            if (text != null && !text.isBlank()) {
                return text;
            }
            if (attempt + 1 < EVENT_STATUS_ATTEMPTS) {
                sleepTask(EVENT_STATUS_RETRY_DELAY_MS);
            }
        }
        return "";
    }

    private void scheduleNavigationRetry(String reason, String type) {
        consecutiveNavigationFailures++;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime retryAt = JourneyOfLightRetryPolicy.retryAt(
                now, GameTimeUtils.dailyResetTime(), consecutiveNavigationFailures);
        String snapshot = TaskDiagnosticSnapshots.capture(emuManager, EMULATOR_NUMBER, "journeyoflight", type);
        if (consecutiveNavigationFailures >= JourneyOfLightRetryPolicy.MAX_CONSECUTIVE_FAILURES) {
            logWarning("Journey of Light state is unknown after " + consecutiveNavigationFailures
                    + " consecutive failures: " + reason + "; retrying at next reset "
                    + retryAt.format(DATETIME_FORMATTER) + "; " + snapshot + ".");
            consecutiveNavigationFailures = 0;
        } else {
            logWarning("Journey of Light state is unknown after " + consecutiveNavigationFailures
                    + " consecutive failure(s): " + reason + "; retrying at "
                    + retryAt.format(DATETIME_FORMATTER) + "; " + snapshot + ".");
        }
        reschedule(retryAt);
    }

    static QueueSchedule resolveQueueSchedule(LocalDateTime now, List<LocalDateTime> queueTimes) {
        LocalDateTime earliest = null;
        boolean incomplete = queueTimes == null || queueTimes.isEmpty();
        if (queueTimes != null) {
            for (LocalDateTime queueTime : queueTimes) {
                if (queueTime == null) {
                    incomplete = true;
                } else if (earliest == null || queueTime.isBefore(earliest)) {
                    earliest = queueTime;
                }
            }
        }

        if (!incomplete && earliest != null) {
            return new QueueSchedule(earliest, false);
        }

        LocalDateTime retryAt = now.plusMinutes(5);
        if (earliest != null && earliest.isAfter(now) && earliest.isBefore(retryAt)) {
            retryAt = earliest;
        }
        return new QueueSchedule(retryAt, true);
    }

    record QueueSchedule(LocalDateTime nextCheck, boolean incomplete) {
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
