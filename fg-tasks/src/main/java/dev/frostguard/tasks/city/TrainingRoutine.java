package dev.frostguard.tasks.city;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.*;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.TaskManagementService;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.RegexNumberParser;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;
import java.awt.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.*;
import static dev.frostguard.api.configs.TemplatesEnum.*;
import static dev.frostguard.engine.nav.LeftMenuTextSettings.*;

public class TrainingRoutine extends DelayedTask {

private static final AreaData INFANTRY_AREA_VALUE = new AreaData(
            new PointData(161, 563),
            new PointData(289, 588));

private static final AreaData LANCER_AREA_VALUE = new AreaData(
            new PointData(161, 636),
            new PointData(289, 664));

private static final AreaData MARKSMAN_AREA_VALUE = new AreaData(
            new PointData(161, 708),
            new PointData(289, 739));

private static final PointData TRAINING_CAMP_TAP_MIN_VALUE = new PointData(310, 650);

private static final PointData TRAINING_CAMP_TAP_MAX_VALUE = new PointData(450, 730);

private static final PointData MINISTRY_CONFIRM_MIN_VALUE = new PointData(440, 770);

private static final PointData MINISTRY_CONFIRM_MAX_VALUE = new PointData(580, 800);

private static final PointData MINISTRY_DETAILS_MIN_VALUE = new PointData(534, 692);

private static final PointData MINISTRY_DETAILS_MAX_VALUE = new PointData(633, 720);

private static final PointData MINISTRY_MORE_DETAILS_MIN_VALUE = new PointData(532, 1057);

private static final PointData MINISTRY_MORE_DETAILS_MAX_VALUE = new PointData(617, 1152);

private static final PointData TROOP_COUNT_INPUT_MIN_VALUE = new PointData(470, 1038);

private static final PointData TROOP_COUNT_INPUT_MAX_VALUE = new PointData(615, 1085);

private static final PointData TRAIN_TIME_TOP_LEFT_MS = new PointData(427, 1202);

private static final PointData TRAIN_TIME_BOTTOM_RIGHT_MS = new PointData(654, 1237);

private static final PointData TRAIN_TIME_STARTED_TOP_LEFT_MS = new PointData(434, 998);

private static final PointData TRAIN_TIME_STARTED_BOTTOM_RIGHT_MS = new PointData(584, 1028);

private static final PointData PROMOTION_TIME_TOP_LEFT_MS = new PointData(398, 897);

private static final PointData PROMOTION_TIME_BOTTOM_RIGHT_MS = new PointData(642, 935);

private static final PointData MINISTRY_TIME_TOP_LEFT_MS = new PointData(397, 1069);

private static final PointData MINISTRY_TIME_BOTTOM_RIGHT_MS = new PointData(596, 1094);

private static final PointData POPUP_DISMISS_MIN_VALUE = new PointData(1, 0);

private static final PointData POPUP_DISMISS_MAX_VALUE = new PointData(720, 0);

private static final PointData TAB_SWIPE_RIGHT_START_VALUE = new PointData(610, 140);

private static final PointData TAB_SWIPE_RIGHT_END_VALUE = new PointData(130, 140);

private static final PointData TAB_SWIPE_LEFT_START_VALUE = new PointData(500, 128);

private static final PointData TAB_SWIPE_LEFT_END_VALUE = new PointData(630, 143);

private static final PointData TROOP_LIST_LEFT_VALUE = new PointData(73, 785);

private static final PointData TROOP_LIST_RIGHT_VALUE = new PointData(690, 785);

private static final PointData TROOP_SCROLL_START_VALUE = new PointData(530, 773);

private static final PointData TROOP_SCROLL_END_VALUE = new PointData(490, 773);

private static final PointData PROMOTION_CONFIRM_POINT_VALUE = new PointData(523, 900);

private static final int MAX_QUEUE_STATUS_RETRIES_LIMIT = 3;

private static final int MAX_TEMPLATE_SEARCH_ATTEMPTS_LIMIT = 3;

private static final int SOON_READY_THRESHOLD_MINUTES_VALUE = 3;

private static final int UPGRADING_RESCHEDULE_MINUTES_VALUE = 10;

private static final int TRAINING_BUTTON_RETRY_MINUTES_VALUE = 5;

private static final int MINISTRY_PROTECTION_WINDOW_MINUTES_VALUE = 30;

private static final int MAX_SUNFIRE_TAB_SWIPES_LIMIT = 3;

private static final int TAB_RESET_SWIPES_VALUE = 2;

private List<AreaData> queuesToCheck;

private List<TroopTypeShape> enabledTroopTypes;

private boolean trainInfantry;

private boolean trainLancer;

private boolean trainMarksman;

private boolean prioritizePromotion;

private boolean ministryAppointmentEnabled;

private TroopTypeShape troopTypeBeingTrained;

private boolean isPromotionTraining;

private LocalDateTime promotionCompletionTime;

private LocalDateTime appointmentTime;

private final ResilientOcrExecutor<LocalDateTime> trainingTimeHelper;

public TrainingRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
        this.trainingTimeHelper = new ResilientOcrExecutor<>(provider);
    }

protected void loadConfiguration() {
        this.trainInfantry = profile.getConfig(TRAIN_INFANTRY_BOOL, Boolean.class);
        this.trainLancer = profile.getConfig(TRAIN_LANCER_BOOL, Boolean.class);
        this.trainMarksman = profile.getConfig(TRAIN_MARKSMAN_BOOL, Boolean.class);
        this.prioritizePromotion = profile.getConfig(TRAIN_PRIORITIZE_PROMOTION_BOOL, Boolean.class);
        this.ministryAppointmentEnabled = profile.getConfig(TRAIN_MINISTRY_APPOINTMENT_BOOL, Boolean.class);

        Long appointmentTimestamp = profile.getConfig(TRAIN_MINISTRY_APPOINTMENT_TIME_LONG, Long.class);
        if (appointmentTimestamp != null && appointmentTimestamp > 0) {
            this.appointmentTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(appointmentTimestamp),
                    ZoneId.systemDefault());
        } else {
            this.appointmentTime = LocalDateTime.MIN;
        }

        logInfo(routineLogTrainingLine(String.format(
                "Configuration loaded - Infantry: %s, Lancer: %s, Marksman: %s, Promotion Priority: %s, Ministry: %s",
                trainInfantry, trainLancer, trainMarksman, prioritizePromotion, ministryAppointmentEnabled)));
    }

@Override
    protected void execute() {

        loadConfiguration();
        buildQueuesListFlow();

        if (queuesToCheck.isEmpty()) {
            manageNoQueuesSelected();
            return;
        }

        List<QueueSlot> analyzedQueues = inspectAllQueues();

        enabledTroopTypes.stream()
                .map(type -> Map.entry(type, reservationUntil(type)))
                .filter(entry -> entry.getValue().isPresent())
                .forEach(entry -> logInfo(routineLogTrainingLine(
                        "Skipping " + entry.getKey() + " training while reserved for construction; next check at "
                                + entry.getValue().orElseThrow().format(DATETIME_FORMATTER))));

        if (manageSoonReadyQueue(analyzedQueues))
            return;

        List<QueueSlot> readyQueues = filterReadyQueuesFlow(analyzedQueues);

        if (readyQueues.isEmpty()) {
            if (manageAllTrainingQueues(analyzedQueues))
                return;
            if (manageUpgradingQueues(analyzedQueues))
                return;
            manageNoReadyQueues();
            return;
        }

        refreshMinistryAppointmentIfNeeded();


        List<LocalDateTime> allCompletionTimes = new ArrayList<>();


        allCompletionTimes.addAll(extractExistingCompletionTimesFlow(analyzedQueues));


        List<LocalDateTime> newCompletionTimes = trainAllReadyQueuesFlow(readyQueues);

        logInfo(routineLogTrainingLine("Verifying final queue states via sidebar."));
        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
        marchHelper.openLeftMenuCitySection(true);
        List<QueueSlot> verifiedQueues = inspectAllQueues();
        allCompletionTimes.clear();
        allCompletionTimes.addAll(extractExistingCompletionTimesFlow(verifiedQueues));

        deferToEarliestCompletion(allCompletionTimes);
    }

@Override
    public boolean provideDailyMissionProgress() {
        return true;
    }

@Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

private enum TroopTypeShape {
        INFANTRY,
        LANCER,
        MARKSMAN
    }

private enum QueueMood {
        IDLE,
        TRAINING,
        COMPLETE,
        UPGRADING,
        UNKNOWN
    }

private record QueueSlot(TroopTypeShape type, QueueMood status, LocalDateTime readyAt) {
    }

private boolean shouldLimitTrainingForAppointmentFlow() {
        if (!ministryAppointmentEnabled || appointmentTime == null) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime appointmentEnd = appointmentTime.plusMinutes(MINISTRY_PROTECTION_WINDOW_MINUTES_VALUE);

        if (now.isBefore(appointmentTime)) {
            Duration timeUntilAppointment = Duration.between(now, appointmentTime);
            logInfo(routineLogTrainingLine(String.format("BEFORE appointment window. Time until appointment: %02d:%02d:%02d",
                    timeUntilAppointment.toHours(),
                    timeUntilAppointment.toMinutesPart(),
                    timeUntilAppointment.toSecondsPart())));
            return true;
        } else if (now.isAfter(appointmentTime) && now.isBefore(appointmentEnd)) {
            long minutesIntoAppointment = Duration.between(appointmentTime, now).toMinutes();
            logInfo(routineLogTrainingLine(String.format("INSIDE appointment window (%d/%d minutes). Training maximum.",
                    minutesIntoAppointment, MINISTRY_PROTECTION_WINDOW_MINUTES_VALUE)));
            return false;
        } else {
            logInfo(routineLogTrainingLine("AFTER appointment window. Training maximum."));
            return false;
        }
    }

private boolean performLimitedTrainingForAppointment(QueueSlot queue) {
        LocalDateTime now = LocalDateTime.now();


        Duration neededTime = Duration.between(now, appointmentTime);

        logInfo(routineLogTrainingLine(String.format("Calculating limited training. Time until appointment: %02d:%02d:%02d",
                neededTime.toHours(),
                neededTime.toMinutesPart(),
                neededTime.toSecondsPart())));


        if (selectHighestUnlockedTroopLevel(troopTypeBeingTrained) == -1) {
            logWarning(routineLogTrainingLine("No unlocked troop level confirmed. Skipping limited training."));
            return false;
        }

        emuManager.captureScreen(EMULATOR_NUMBER);

        Duration trainTime = extractMaxTrainingTimeFlow();
        Integer maxTroops = extractMaxTroopCountFlow();

        if (trainTime == null || maxTroops == null) {
            logWarning(routineLogTrainingLine("Could not read training data. Training maximum as fallback."));


            pressTrainButton();
            isPromotionTraining = true;
            return true;
        }

        if (maxTroops == 0) {
            logWarning(routineLogTrainingLine("Max troops is zero. Zero training possible."));
            return false;
        }

        trainOptimalTroopCountFlow(trainTime, maxTroops, neededTime);
        return true;
    }

private void refreshMinistryAppointmentIfNeeded() {
        if (!ministryAppointmentEnabled || appointmentTime == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        long minutesSinceAppointment = ChronoUnit.MINUTES.between(appointmentTime, now);

        if (minutesSinceAppointment < MINISTRY_PROTECTION_WINDOW_MINUTES_VALUE) {
            logInfo(routineLogTrainingLine(String.format("Ministry appointment protected. %d minutes remaining.",
                    MINISTRY_PROTECTION_WINDOW_MINUTES_VALUE - minutesSinceAppointment)));
            return;
        }

        logInfo(routineLogTrainingLine("Ministry appointment protection expired. Inspecting for reappointment."));

        if (!reachSunfireCastleTab()) {
            logWarning(routineLogTrainingLine("Could not navigate to Sunfire Castle. Skipping ministry appointment update."));
            return;
        }

        applyForMinistryAppointmentFlow();
        scanAndUpdateAppointmentTime();

        persistAppointmentTimeFlow();


        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
    }

private List<LocalDateTime> extractExistingCompletionTimesFlow(List<QueueSlot> queues) {
        return queues.stream()
                .filter(q -> q.status() == QueueMood.TRAINING)
                .filter(q -> !isReservedForConstruction(q.type()))
                .map(QueueSlot::readyAt)
                .filter(Objects::nonNull)
                .toList();
    }

private void capturePromotionCompletionTimeFlow() {
        try {
            Duration promotionDuration = durationHelper.attemptRecognition(
                    PROMOTION_TIME_TOP_LEFT_MS,
                    PROMOTION_TIME_BOTTOM_RIGHT_MS,
                    3,
                    200L,
                    TesseractSettingsData.assembler()
                            .charWhitelist("0123456789")
                            .build(),
                    GameTimeUtils::isAcceptedFormat,
                    GameTimeUtils::parseDuration);

            if (promotionDuration != null) {
                promotionCompletionTime = LocalDateTime.now().plus(promotionDuration);
                logInfo(routineLogTrainingLine("Promotion will complete at: " + promotionCompletionTime.format(DATETIME_FORMATTER)));
                return;
            }

            logWarning(routineLogTrainingLine("Could not read promotion completion time."));
        } catch (Exception e) {
            logError(routineLogTrainingLine("Error extracting promotion completion time: " + e.getMessage()));
        }
    }

private LocalDateTime extractTrainingCompletionTimeFlow() {
        try {
            if (isPromotionTraining) {
                isPromotionTraining = false;
                if (promotionCompletionTime != null) {
                    LocalDateTime completion = promotionCompletionTime;
                    promotionCompletionTime = null;
                    return completion;
                }
                logWarning(routineLogTrainingLine("Promotion completion time unavailable, falling back to normal timer."));
            }
            Duration trainingDuration = durationHelper.attemptRecognition(
                    TRAIN_TIME_STARTED_TOP_LEFT_MS,
                    TRAIN_TIME_STARTED_BOTTOM_RIGHT_MS,
                    3,
                    200L,
                    TesseractSettingsData.assembler()
                            .charWhitelist("0123456789")
                            .build(),
                    GameTimeUtils::isAcceptedFormat,
                    GameTimeUtils::parseDuration);

            if (trainingDuration != null) {
                LocalDateTime completionTime = LocalDateTime.now().plus(trainingDuration);
                logInfo(routineLogTrainingLine("Training will complete at: " + completionTime.format(DATETIME_FORMATTER)));
                return completionTime;
            }
        } catch (Exception e) {
            logError(routineLogTrainingLine("Error extracting training completion time: " + e.getMessage()));
        }

        return null;
    }

private boolean performPromotionPriorityTraining(QueueSlot queue) {
        logInfo(routineLogTrainingLine("Executing promotion-priority training for " + queue.type().name()));

        int maxLevel = selectHighestUnlockedTroopLevel(queue.type());

        if (maxLevel == -1) {
            logWarning(routineLogTrainingLine("No unlocked troop level confirmed. Skipping training."));
            return false;
        }

        logInfo(routineLogTrainingLine("Maximum unlocked troop level: " + maxLevel));
        resetTroopListToStartFlow();

        boolean promotionExecuted = attemptTroopPromotionsFlow(queue.type(), maxLevel);

        if (promotionExecuted) {
            logInfo(routineLogTrainingLine("Promotion executed finished cleanly."));
            return true;
        } else {
            logInfo(routineLogTrainingLine("Zero promotable troops detected. Reselecting highest unlocked level for normal training."));
            int selectedLevel = selectHighestUnlockedTroopLevel(queue.type());
            if (selectedLevel == -1) {
                logWarning(routineLogTrainingLine("Could not reselect an unlocked troop level. Skipping normal training."));
                return false;
            }
            logInfo(routineLogTrainingLine("Reselected level " + selectedLevel + " for normal training."));
            pressTrainButton();
            return true;
        }
    }

private int extractLevelFromTemplateNameFlow(String templateName) {
        try {
            String levelStr = templateName.replaceAll("[^0-9]", "");

            if (levelStr.isEmpty()) {
                return -1;
            }

            return Integer.parseInt(levelStr);
        } catch (NumberFormatException e) {
            logError(routineLogTrainingLine("Could not extract level from: " + templateName), e);
            return -1;
        }
    }

private boolean attemptSingleTroopPromotionFlow(TemplatesEnum template) {
        logDebug(routineLogTrainingLine("Attempting promotion for: " + template.name()));

        ImageSearchResultData troop = templateSearchHelper.locatePattern(
                template,
                SearchConfigConstants.DEFAULT_SINGLE);

        if (!troop.isFound()) {
            // The next tier can be just beyond the current five-card viewport. Advancing the list without
            // retrying that same tier skipped the first promotable card on the newly visible page.
            logDebug(routineLogTrainingLine("Template not visible before scroll: " + template.name()
                    + ". Advancing troop list and retrying the same level."));
            scrollToNextTroopTypeFlow();
            troop = templateSearchHelper.locatePattern(
                    template,
                    SearchConfigConstants.STRICT_MATCHING);
        }

        if (troop.isFound()) {
            tapInside(troop);
            sleepTask(300);


            emuManager.captureScreen(EMULATOR_NUMBER);
            ImageSearchResultData promoteButton = templateSearchHelper.locatePattern(
                    TRAINING_TROOP_PROMOTE,
                    SearchConfigConstants.DEFAULT_SINGLE);

            if (promoteButton.isFound()) {
                return performPromotion(template, promoteButton);
            } else {
                logDebug(routineLogTrainingLine("Promotion not available for: " + template.name()));
            }
        } else {
            logDebug(routineLogTrainingLine("Template not detected after scroll retry: " + template.name()));
        }

        return false;
    }

private void applyForMinistryAppointmentFlow() {
        tapInside(MINISTRY_DETAILS_MIN_VALUE, MINISTRY_DETAILS_MAX_VALUE, 1, 300);
        tapInside(MINISTRY_MORE_DETAILS_MIN_VALUE, MINISTRY_MORE_DETAILS_MAX_VALUE, 1, 300);

        ImageSearchResultData applyButton = templateSearchHelper.locatePattern(
                SUNFIRE_MINISTRY_APPLY_BUTTON,
                SearchConfigConstants.DEFAULT_SINGLE);

        if (applyButton.isFound()) {
            logInfo(routineLogTrainingLine("Applying for ministry appointment"));
            tapInside(applyButton.getPoint(), applyButton.getPoint(), 1, 1000);
            tapInside(MINISTRY_CONFIRM_MIN_VALUE, MINISTRY_CONFIRM_MAX_VALUE, 1, 2500);
        } else {
            logInfo(routineLogTrainingLine("Apply button not detected. May already have active appointment."));
        }
    }

private String normalizeMinistryTimeTextFlow(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.trim().toLowerCase().replace(" ", "");
        if (normalized.startsWith(":")) {
            normalized = normalized.substring(1);
        }
        return normalized.replace("d:", "d");
    }

private void scanAndUpdateAppointmentTime() {
        Duration activeAppointmentTime = durationHelper.attemptRecognition(
                MINISTRY_TIME_TOP_LEFT_MS,
                MINISTRY_TIME_BOTTOM_RIGHT_MS,
                5,
                200L,
                TesseractSettingsData.assembler()
                        .stripBackground(true)
                        .setTextColor(new Color(121, 136, 155))
                        .charWhitelist("0123456789:d")
                        .build(),
                text -> GameTimeUtils.isAcceptedFormat(normalizeMinistryTimeTextFlow(text)),
                text -> GameTimeUtils.parseDuration(normalizeMinistryTimeTextFlow(text)));

        if (activeAppointmentTime != null) {
            appointmentTime = LocalDateTime.now().plusSeconds(activeAppointmentTime.getSeconds());
            logInfo(routineLogTrainingLine("Ministry appointment time: " + appointmentTime.format(DATETIME_FORMATTER)));
        } else {
            logInfo(routineLogTrainingLine("Could not read appointment time. Applying to now for normal training."));
            appointmentTime = LocalDateTime.now();
        }
    }

private LocalDateTime manageTrainingButtonNotFound(QueueSlot queue) {
        logWarning(routineLogTrainingLine("Training button not detected for " + queue.type().name() +
                ". Will retry in " + TRAINING_BUTTON_RETRY_MINUTES_VALUE + " minutes."));


        return null;
    }

private void trainOptimalTroopCountFlow(Duration trainTime, int maxTroops, Duration neededTime) {
        String trainTimeStr = formatDurationFlow(trainTime);
        String neededTimeStr = formatDurationFlow(neededTime);

        logInfo(routineLogTrainingLine(String.format("Max troops: %d | Train time for max: %s | Time available: %s",
                maxTroops, trainTimeStr, neededTimeStr)));

        if (trainTime.compareTo(neededTime) <= 0) {
            trainMaximumTroopsFlow(trainTimeStr, neededTimeStr, maxTroops);
        } else {
            trainCalculatedTroopsFlow(trainTime, maxTroops, neededTime, trainTimeStr, neededTimeStr);
        }
    }

private boolean seekSunfireCastleWithSwipe() {
        logInfo(routineLogTrainingLine("Sunfire Castle not immediately visible. Swiping to locate."));

        resetTabPositionFlow();

        for (int attempt = 0; attempt < MAX_SUNFIRE_TAB_SWIPES_LIMIT; attempt++) {
            ImageSearchResultData sunfireCastle = templateSearchHelper.locatePattern(
                    EVENTS_SUNFIRE_TAB,
                    SearchConfigConstants.DEFAULT_SINGLE);

            if (sunfireCastle.isFound()) {
                tapInside(sunfireCastle.getPoint(), sunfireCastle.getPoint(), 1, 500);
                return true;
            }

            logDebug(routineLogTrainingLine("Sunfire Castle not detected. Swiping left (attempt " +
                    (attempt + 1) + "/" + MAX_SUNFIRE_TAB_SWIPES_LIMIT + ")"));

            swipe(TAB_SWIPE_LEFT_START_VALUE, TAB_SWIPE_LEFT_END_VALUE);
            sleepTask(300);

        }

        logWarning(routineLogTrainingLine("Sunfire Castle tab not detected after all swipe attempts"));
        return false;
    }

private List<Integer> retryUnknownQueuesOnceFlow(
            List<QueueSlot> results,
            List<Integer> unknownIndices) {

        List<Integer> stillUnknown = new ArrayList<>();

        for (int queueIndex : unknownIndices) {
            TroopTypeShape troopType = enabledTroopTypes.get(queueIndex);
            AreaData queueArea = queuesToCheck.get(queueIndex);

            logDebug(routineLogTrainingLine("Retrying queue: " + troopType.name()));

            QueueSlot newInfo = inspectQueueState(queueArea, troopType);

            if (newInfo.status() != QueueMood.UNKNOWN) {
                logInfo(routineLogTrainingLine("Queue " + troopType.name() + " resolved to: " + newInfo.status()));
                results.set(queueIndex, newInfo);
            } else {
                stillUnknown.add(queueIndex);
            }
        }

        return stillUnknown;
    }

private void persistAppointmentTimeFlow() {
        long timestamp = appointmentTime.atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();

        ConfigService.obtain().writeAccountSetting(
                profile,
                TRAIN_MINISTRY_APPOINTMENT_TIME_LONG,
                String.valueOf(timestamp));
    }

private Duration extractMaxTrainingTimeFlow() {
        return durationHelper.attemptRecognition(
                TRAIN_TIME_TOP_LEFT_MS,
                TRAIN_TIME_BOTTOM_RIGHT_MS,
                3,
                200L,
                TesseractSettingsData.assembler()
                        .charWhitelist("0123456789")
                        .stripBackground(true)
                        .setTextColor(new Color(254, 254, 254))
                        .build(),
                GameTimeUtils::isAcceptedFormat,
                GameTimeUtils::parseDuration);
    }

private boolean attemptTroopPromotionsFlow(TroopTypeShape troopType, int maxLevel) {
        List<TemplatesEnum> templates = resolveTroopsTemplates(troopType);
        logInfo(routineLogTrainingLine("Scanning for promotable troops (levels < " + maxLevel + ")"));

        for (int i = templates.size() - 1; i >= 0; i--) {
            TemplatesEnum template = templates.get(i);
            int templateLevel = extractLevelFromTemplateNameFlow(template.name());

            if (templateLevel > 0 && templateLevel < maxLevel) {
                if (attemptSingleTroopPromotionFlow(template)) {
                    return true;
                }
            }
        }

        logInfo(routineLogTrainingLine("Zero promotable troops detected."));
        return false;
    }

private void trainCalculatedTroopsFlow(
            Duration trainTime,
            int maxTroops,
            Duration neededTime,
            String trainTimeStr,
            String neededTimeStr) {

        long secondsPerTroop = trainTime.getSeconds() / maxTroops;
        int troopsToTrain = (int) (neededTime.getSeconds() / secondsPerTroop);

        if (troopsToTrain > maxTroops) {
            troopsToTrain = maxTroops;
        }

        if (troopsToTrain <= 0) {
            logWarning(routineLogTrainingLine("Calculated troops is zero or negative. Skipping training."));
            return;
        }

        Duration calculatedTrainTime = Duration.ofSeconds(secondsPerTroop * troopsToTrain);
        String calculatedTimeStr = formatDurationFlow(calculatedTrainTime);

        logInfo(routineLogTrainingLine(String.format("Train time (%s) exceeds available time (%s).",
                trainTimeStr, neededTimeStr)));
        logInfo(routineLogTrainingLine(String.format("Calculated: %d troops (%.1f sec/troop) = ~%s training time",
                troopsToTrain, (double) secondsPerTroop, calculatedTimeStr)));

        inputTroopCountFlow(troopsToTrain);
        pressTrainButton();
    }

private void scrollToNextTroopTypeFlow() {
        swipe(TROOP_SCROLL_START_VALUE, TROOP_SCROLL_END_VALUE);
        sleepTask(200);

        emuManager.captureScreen(EMULATOR_NUMBER);
    }

private void resetTroopListToStartFlow() {
        logDebug(routineLogTrainingLine("Resetting troop list to the start."));
        swipe(TROOP_LIST_LEFT_VALUE, TROOP_LIST_RIGHT_VALUE);
        swipe(TROOP_LIST_LEFT_VALUE, TROOP_LIST_RIGHT_VALUE);
        sleepTask(200);

    }

private List<Integer> locateUnknownQueueIndices(List<QueueSlot> results) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).status() == QueueMood.UNKNOWN) {
                indices.add(i);
            }
        }
        return indices;
    }

private void deferToEarliestCompletion(List<LocalDateTime> completionTimes) {
        List<LocalDateTime> candidates = new ArrayList<>(completionTimes);
        earliestReservationCheck().ifPresent(candidates::add);

        if (candidates.isEmpty()) {
            logInfo(routineLogTrainingLine("Zero completion times extracted. Planning next run retry soon."));
            reschedule(LocalDateTime.now().plusMinutes(TRAINING_BUTTON_RETRY_MINUTES_VALUE));
            return;
        }

        LocalDateTime earliest = candidates.stream()
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(LocalDateTime.now().plusMinutes(TRAINING_BUTTON_RETRY_MINUTES_VALUE));


        if (ministryAppointmentEnabled && appointmentTime != null) {
            if (earliest.isBefore(appointmentTime)) {
                long minutesUntilAppointment = ChronoUnit.MINUTES.between(earliest, appointmentTime);


                if (minutesUntilAppointment <= SOON_READY_THRESHOLD_MINUTES_VALUE) {
                    logInfo(routineLogTrainingLine(String.format(
                            "Training completes %d min before appointment. Waiting for appointment at %s to maximize bonus.",
                            minutesUntilAppointment,
                            appointmentTime.format(DATETIME_FORMATTER))));
                    reschedule(appointmentTime);
                    return;
                }


                logInfo(routineLogTrainingLine(String.format(
                        "Training completes at %s (%d min before appointment at %s). Will train more limited troops.",
                        earliest.format(DATETIME_FORMATTER),
                        minutesUntilAppointment,
                        appointmentTime.format(DATETIME_FORMATTER))));
            }
        }

        logInfo(routineLogTrainingLine("Planning next run to earliest completion: " + earliest.format(DATETIME_FORMATTER)));
        reschedule(earliest);
    }

private Integer extractMaxTroopCountFlow() {
        return integerHelper.attemptRecognition(
                TROOP_COUNT_INPUT_MIN_VALUE,
                TROOP_COUNT_INPUT_MAX_VALUE,
                5,
                200L,
                TesseractSettingsData.assembler()
                        .charWhitelist("0123456789")
                        .stripBackground(true)
                        .setTextColor(new Color(254, 254, 254))
                        .build(),
                text -> RegexNumberParser.conformsTo(text, Pattern.compile(".*?(\\d+).*")),
                text -> RegexNumberParser.extractByPattern(text, Pattern.compile(".*?(\\d+).*")));
    }

    private List<LocalDateTime> trainAllReadyQueuesFlow(List<QueueSlot> readyQueues) {
        List<LocalDateTime> completionTimes = new ArrayList<>();
        if (readyQueues.isEmpty()) return completionTimes;

        boolean inTrainingInterface = false;

        for (QueueSlot queue : readyQueues) {
            troopTypeBeingTrained = queue.type();
            boolean trainingStartedSuccessfully = false;

            if (!inTrainingInterface) {
                navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
                marchHelper.openLeftMenuCitySection(true);
                AreaData areaToTap = resolvePipelineArea(queue.type());
                tapInside(areaToTap.topLeft(), areaToTap.bottomRight(), 1, 500);
                tapInside(TRAINING_CAMP_TAP_MIN_VALUE, TRAINING_CAMP_TAP_MAX_VALUE, 10, 100);
                if (openUpTrainingInterface()) {
                    inTrainingInterface = true;
                }
            } else {
                logInfo(routineLogTrainingLine("Switching to tab: " + queue.type().name()));
                switchTrainingTab(queue.type());
                sleepTask(500); // UI transition delay
            }

            if (inTrainingInterface) {
                promotionCompletionTime = null;
                isPromotionTraining = false;

                if (performTrainingForQueue(queue)) {
                    trainingStartedSuccessfully = true;
                    LocalDateTime completionTime = extractTrainingCompletionTimeFlow();
                    if (completionTime != null) {
                        completionTimes.add(completionTime);
                    }
                }
            }

            if (!trainingStartedSuccessfully) {
                logWarning(routineLogTrainingLine("Multi-tab flow failed for " + queue.type().name() + ". Falling back to single queue flow."));
                
                // Reset state by forcing a menu close (which acts as a generic back/reset in many cases)
                marchHelper.closeLeftMenu();
                for (int i = 0; i < 3; i++) {
                    dismissPopupsFlow();
                }
                
                inTrainingInterface = false;

                LocalDateTime fallbackTime = trainSingleQueueFlow(queue);
                if (fallbackTime != null) {
                    completionTimes.add(fallbackTime);
                }
            }
        }

        return completionTimes;
    }

    private void switchTrainingTab(TroopTypeShape type) {
        switch (type) {
            case INFANTRY -> tapNear(new dev.frostguard.api.domain.PointData(120, 1213));
            case LANCER -> tapNear(new dev.frostguard.api.domain.PointData(360, 1213));
            case MARKSMAN -> tapNear(new dev.frostguard.api.domain.PointData(600, 1213));
        }
    }

    private boolean performTrainingForQueue(QueueSlot queue) {
        if (shouldLimitTrainingForAppointmentFlow()) {
            return performLimitedTrainingForAppointment(queue);
        } else {
            return performMaximumTraining(queue);
        }
    }

private void resetTroopListToEndFlow() {
        logDebug(routineLogTrainingLine("Resetting troop list to the end."));
        swipe(TROOP_LIST_RIGHT_VALUE, TROOP_LIST_LEFT_VALUE);
        swipe(TROOP_LIST_RIGHT_VALUE, TROOP_LIST_LEFT_VALUE);
        sleepTask(200);

    }

private boolean openUpTrainingInterface() {
        ImageSearchResultData trainingButton = templateSearchHelper.locatePattern(
                BUILDING_BUTTON_TRAIN,
                SearchConfigConstants.DEFAULT_SINGLE);

        if (!trainingButton.isFound()) {
            return false;
        }

        tapInside(trainingButton.getPoint(), trainingButton.getPoint(), 1, 1000);
        return true;
    }

private boolean manageAllTrainingQueues(List<QueueSlot> queues) {
        boolean allTraining = queues.stream()
                .allMatch(q -> q.status() == QueueMood.TRAINING || isReservedForConstruction(q.type()));

        if (!allTraining) {
            return false;
        }

        Optional<LocalDateTime> nextReadyTime = queues.stream()
                .filter(q -> !isReservedForConstruction(q.type()))
                .map(QueueSlot::readyAt)
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo);

        Optional<LocalDateTime> reservationRelease = earliestReservationCheck();
        if (reservationRelease.isPresent()
                && (nextReadyTime.isEmpty() || reservationRelease.get().isBefore(nextReadyTime.get()))) {
            nextReadyTime = reservationRelease;
        }

        if (nextReadyTime.isPresent()) {
            logInfo(routineLogTrainingLine("All queues TRAINING. Planning next run to earliest completion: " +
                    nextReadyTime.get().format(DATETIME_FORMATTER)));

            reschedule(nextReadyTime.get());
            marchHelper.closeLeftMenu();
            return true;
        } else {
            logWarning(routineLogTrainingLine("All queues TRAINING but couldn't determine next ready time. Continuing."));
            return false;
        }
    }

private void logUnresolvedQueuesFlow(List<Integer> unknownIndices) {
        if (!unknownIndices.isEmpty()) {
            logWarning(routineLogTrainingLine("After " + MAX_QUEUE_STATUS_RETRIES_LIMIT + " retries, " +
                    unknownIndices.size() + " queues still have UNKNOWN status."));

            for (int index : unknownIndices) {
                logWarning(routineLogTrainingLine("Queue " + enabledTroopTypes.get(index).name() + " remains UNKNOWN"));
            }
        } else {
            logInfo(routineLogTrainingLine("All queues finished cleanly identified after retries."));
        }
    }

private QueueSlot inspectForStateKeywords(
            AreaData queueArea,
            TroopTypeShape troopType,
            TesseractSettingsData[] settingsToTry) {

        for (TesseractSettingsData ocrPreset : settingsToTry) {
            try {
                String text = stringHelper.attemptRecognition(
                        queueArea.topLeft(),
                        queueArea.bottomRight(),
                        1,
                        300L,
                        ocrPreset,
                        s -> !s.isEmpty(),
                        s -> s);

                if (text != null && !text.trim().isEmpty()) {
                    String lowerText = text.trim().toLowerCase();

                    if (lowerText.contains("idle")) {
                        logInfo(routineLogTrainingLine(troopType + " queue is IDLE"));
                        return new QueueSlot(troopType, QueueMood.IDLE, null);
                    }

                    if (lowerText.contains("upgrading") || lowerText.contains("upgrade")) {
                        logInfo(routineLogTrainingLine(troopType + " queue is UPGRADING"));
                        return new QueueSlot(troopType, QueueMood.UPGRADING, null);
                    }

                    if (lowerText.contains("complete")) {
                        logInfo(routineLogTrainingLine(troopType + " queue is COMPLETE"));
                        return new QueueSlot(troopType, QueueMood.COMPLETE, null);
                    }
                }
            } catch (Exception e) {
                logWarning(routineLogTrainingLine("Error extracting queue state text: " + e.getMessage()));
            }
        }

        return null;
    }

private int selectHighestUnlockedTroopLevel(TroopTypeShape troopType) {
        List<TemplatesEnum> templates = resolveTroopsTemplates(troopType);
        resetTroopListToEndFlow();
        emuManager.captureScreen(EMULATOR_NUMBER);
        Optional<TemplatesEnum> selected = TrainingTierSelector.findHighestUnlocked(
                templates,
                this::probeTroopTier);

        if (selected.isPresent()) {
            TemplatesEnum template = selected.get();
            int level = extractLevelFromTemplateNameFlow(template.name());
            logInfo(routineLogTrainingLine("Selected highest unlocked troop level: " + level + " (" + template.name() + ")"));
            return level;
        }

        logWarning(routineLogTrainingLine("Could not select an unlocked troop level."));
        return -1;
    }

private TrainingTierSelector.TierState probeTroopTier(TemplatesEnum template) {
        // Locked grayscale cards retain enough armour detail to match their tier template, so identity
        // alone cannot prove that a tier is trainable. Confirm the selected card's lock state separately.
        ImageSearchResultData troop = templateSearchHelper.locatePattern(
                template,
                SearchConfigConstants.STRICT_MATCHING);

        if (!troop.isFound()) {
            swipe(TROOP_SCROLL_END_VALUE, TROOP_SCROLL_START_VALUE);
            sleepTask(200);
            emuManager.captureScreen(EMULATOR_NUMBER);
            return TrainingTierSelector.TierState.NOT_VISIBLE;
        }

        tapInside(troop);
        sleepTask(250);
        emuManager.captureScreen(EMULATOR_NUMBER);
        ImageSearchResultData lockedIndicator = templateSearchHelper.locatePattern(
                TRAINING_TROOP_LOCKED,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (lockedIndicator.isFound()) {
            logInfo(routineLogTrainingLine("Troop level locked: " + template.name() + ". Continuing search."));
            return TrainingTierSelector.TierState.LOCKED;
        }

        return TrainingTierSelector.TierState.UNLOCKED;
    }

private void trainMaximumTroopsFlow(String trainTimeStr, String neededTimeStr, int maxTroops) {
        logInfo(routineLogTrainingLine(String.format("Train time (%s) fits within available time (%s). Training MAX: %d",
                trainTimeStr, neededTimeStr, maxTroops)));

        pressTrainButton();
    }

private String routineLogTrainingLine(String note) {
        return "TrainingRoutine | " + note;
    }

private List<QueueSlot> retryUnknownQueuesFlow(List<QueueSlot> initialResults) {
        List<Integer> unknownIndices = locateUnknownQueueIndices(initialResults);

        if (unknownIndices.isEmpty()) {
            return initialResults;
        }

        logInfo(routineLogTrainingLine("Detected " + unknownIndices.size() + " queues with UNKNOWN status. Performing retries."));

        for (int attempt = 1; attempt <= MAX_QUEUE_STATUS_RETRIES_LIMIT; attempt++) {
            if (unknownIndices.isEmpty())
                break;

            logInfo(routineLogTrainingLine("Retry attempt " + attempt + "/" + MAX_QUEUE_STATUS_RETRIES_LIMIT));

            marchHelper.openLeftMenuCitySection(true);
            emuManager.captureScreen(EMULATOR_NUMBER);

            unknownIndices = retryUnknownQueuesOnceFlow(initialResults, unknownIndices);
        }

        logUnresolvedQueuesFlow(unknownIndices);
        return initialResults;
    }

private List<TemplatesEnum> resolveTroopsTemplates(TroopTypeShape type) {
        List<TemplatesEnum> templates = new ArrayList<>();

        return switch (type) {
            case INFANTRY -> {
                templates.add(TRAINING_INFANTRY_T11);
                templates.add(TRAINING_INFANTRY_T10);
                templates.add(TRAINING_INFANTRY_T9);
                templates.add(TRAINING_INFANTRY_T8);
                templates.add(TRAINING_INFANTRY_T7);
                templates.add(TRAINING_INFANTRY_T6);
                templates.add(TRAINING_INFANTRY_T5);
                templates.add(TRAINING_INFANTRY_T4);
                templates.add(TRAINING_INFANTRY_T3);
                templates.add(TRAINING_INFANTRY_T2);
                templates.add(TRAINING_INFANTRY_T1);
                yield templates;
            }
            case LANCER -> {
                templates.add(TRAINING_LANCER_T11);
                templates.add(TRAINING_LANCER_T10);
                templates.add(TRAINING_LANCER_T9);
                templates.add(TRAINING_LANCER_T8);
                templates.add(TRAINING_LANCER_T7);
                templates.add(TRAINING_LANCER_T6);
                templates.add(TRAINING_LANCER_T5);
                templates.add(TRAINING_LANCER_T4);
                templates.add(TRAINING_LANCER_T3);
                templates.add(TRAINING_LANCER_T2);
                templates.add(TRAINING_LANCER_T1);
                yield templates;
            }
            case MARKSMAN -> {
                templates.add(TRAINING_MARKSMAN_T11);
                templates.add(TRAINING_MARKSMAN_T10);
                templates.add(TRAINING_MARKSMAN_T9);
                templates.add(TRAINING_MARKSMAN_T8);
                templates.add(TRAINING_MARKSMAN_T7);
                templates.add(TRAINING_MARKSMAN_T6);
                templates.add(TRAINING_MARKSMAN_T5);
                templates.add(TRAINING_MARKSMAN_T4);
                templates.add(TRAINING_MARKSMAN_T3);
                templates.add(TRAINING_MARKSMAN_T2);
                templates.add(TRAINING_MARKSMAN_T1);
                yield templates;
            }
        };
    }

    private boolean performMaximumTraining(QueueSlot queue) {
        logInfo(routineLogTrainingLine("Training maximum troops (no appointment constraints)."));

        if (!ministryAppointmentEnabled && prioritizePromotion) {
            return performPromotionPriorityTraining(queue);
        } else {
            if (selectHighestUnlockedTroopLevel(troopTypeBeingTrained) != -1) {
                pressTrainButton();
                return true;
            }
        }
        return false;
    }



private void manageNoQueuesSelected() {
        logInfo(routineLogTrainingLine("Zero troop types selected for training. Disabling task."));
        setRecurring(false);
    }

private QueueSlot inspectForTrainingTime(
            AreaData queueArea,
            TroopTypeShape troopType,
            TesseractSettingsData[] settingsToTry) {

        for (TesseractSettingsData ocrPreset : settingsToTry) {
            try {
                LocalDateTime readyAt = trainingTimeHelper.attemptRecognition(
                        queueArea,
                        3,
                        10,
                        ocrPreset,
                        GameTimeUtils::isAcceptedFormat,
                        text -> LocalDateTime.now().plus(GameTimeUtils.parseDuration(text)));

                if (readyAt != null) {
                    logInfo(routineLogTrainingLine(troopType + " training ready at: " + readyAt.format(DATETIME_FORMATTER)));
                    return new QueueSlot(troopType, QueueMood.TRAINING, readyAt);
                }
            } catch (Exception e) {
                logWarning(routineLogTrainingLine("Error extracting training time: " + e.getMessage()));
            }
        }

        logWarning(routineLogTrainingLine("Could not determine state for " + troopType.name() + " queue"));
        return new QueueSlot(troopType, QueueMood.UNKNOWN, null);
    }

private boolean reachSunfireCastleTab() {
        logInfo(routineLogTrainingLine("Moving to Sunfire Castle tab"));

        if (!pressEventsButton()) {
            return false;
        }

        dismissPopupsFlow();

        ImageSearchResultData sunfireCastle = templateSearchHelper.locatePattern(
                EVENTS_SUNFIRE_TAB,
                SearchConfigConstants.DEFAULT_SINGLE);

        if (sunfireCastle.isFound()) {
            tapInside(sunfireCastle.getPoint(), sunfireCastle.getPoint(), 1, 500);
            return true;
        }

        return seekSunfireCastleWithSwipe();
    }

private boolean manageUpgradingQueues(List<QueueSlot> queues) {
        boolean anyUpgrading = queues.stream()
                .anyMatch(q -> q.status() == QueueMood.UPGRADING);

        if (anyUpgrading) {
            LocalDateTime now = LocalDateTime.now();
            List<LocalDateTime> trainingCompletions = queues.stream()
                    .filter(q -> q.status() == QueueMood.TRAINING)
                    .map(QueueSlot::readyAt)
                    .filter(Objects::nonNull)
                    .toList();
            LocalDateTime cityBuildRun = nextCityBuildRun().orElse(null);
            LocalDateTime constructionCheck = earliestReservationCheck().orElse(null);
            LocalDateTime nextCheck = selectUpgradingWakeup(
                    now, trainingCompletions, cityBuildRun, constructionCheck);

            logInfo(routineLogTrainingLine(
                    "At least one queue UPGRADING. Planning next run for: "
                            + nextCheck.format(DATETIME_FORMATTER)
                            + " (earliest known training/construction event; 10-minute fallback only if unknown)."));

            reschedule(nextCheck);
            return true;
        }

        return false;
    }

private Optional<LocalDateTime> nextCityBuildRun() {
        if (profile == null || profile.getId() == null) {
            return Optional.empty();
        }

        TaskStateData cityBuildState = TaskManagementService.shared().lookupTaskState(
                profile.getId(), TpDailyTaskEnum.CITY_UPGRADE_FURNACE.getId());
        if (cityBuildState == null || !cityBuildState.isScheduled()) {
            return Optional.empty();
        }
        return Optional.ofNullable(cityBuildState.getNextExecutionTime());
    }

static LocalDateTime selectUpgradingWakeup(
        LocalDateTime now,
        Collection<LocalDateTime> trainingCompletions,
        LocalDateTime cityBuildRun,
        LocalDateTime constructionCheck) {
        LocalDateTime fallback = now.plusMinutes(UPGRADING_RESCHEDULE_MINUTES_VALUE);
        return Stream.concat(
                        trainingCompletions == null ? Stream.empty() : trainingCompletions.stream(),
                        Stream.of(cityBuildRun, constructionCheck))
                .filter(Objects::nonNull)
                .filter(candidate -> candidate.isAfter(now))
                .min(LocalDateTime::compareTo)
                .orElse(fallback);
    }

private List<QueueSlot> filterReadyQueuesFlow(List<QueueSlot> queues) {
        return queues.stream()
                .filter(q -> q.status() == QueueMood.COMPLETE || q.status() == QueueMood.IDLE)
                .filter(q -> !isReservedForConstruction(q.type()))
                .toList();
    }

private LocalDateTime trainSingleQueueFlow(QueueSlot queue) {
        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
        marchHelper.openLeftMenuCitySection(true);

        promotionCompletionTime = null;
        isPromotionTraining = false;

        AreaData areaToTap = resolvePipelineArea(queue.type());

        logInfo(routineLogTrainingLine("Preparing to train " + queue.type().name()));
        tapInside(areaToTap.topLeft(), areaToTap.bottomRight(), 1, 500);

        tapInside(TRAINING_CAMP_TAP_MIN_VALUE, TRAINING_CAMP_TAP_MAX_VALUE, 10, 100);

        if (!openUpTrainingInterface()) {
            return manageTrainingButtonNotFound(queue);
        }


        performTrainingForQueue(queue);

        return extractTrainingCompletionTimeFlow();
    }

private void resetTabPositionFlow() {
        for (int i = 0; i < TAB_RESET_SWIPES_VALUE; i++) {
            swipe(TAB_SWIPE_RIGHT_START_VALUE, TAB_SWIPE_RIGHT_END_VALUE);
            sleepTask(100);

        }
        sleepTask(300);

    }

private boolean manageSoonReadyQueue(List<QueueSlot> queues) {
        Optional<QueueSlot> soonReady = queues.stream()
                .filter(q -> q.status() == QueueMood.TRAINING && q.readyAt() != null)
                .filter(q -> !isReservedForConstruction(q.type()))
                .filter(q -> Duration.between(LocalDateTime.now(), q.readyAt())
                        .toMinutes() <= SOON_READY_THRESHOLD_MINUTES_VALUE)
                .findFirst();

        if (soonReady.isPresent()) {
            QueueSlot queue = soonReady.get();
            logInfo(routineLogTrainingLine(String.format("Queue %s ready in <%d minutes. Planning next run to %s",
                    queue.type().name(),
                    SOON_READY_THRESHOLD_MINUTES_VALUE,
                    queue.readyAt().format(DATETIME_FORMATTER))));

            reschedule(queue.readyAt());
            return true;
        }

        return false;
    }

private boolean isReservedForConstruction(TroopTypeShape type) {
        return reservationUntil(type).isPresent();
    }

private Optional<LocalDateTime> reservationUntil(TroopTypeShape type) {
        ConstructionBlockerRegistry.Consumer consumer = switch (type) {
            case INFANTRY -> ConstructionBlockerRegistry.Consumer.INFANTRY;
            case LANCER -> ConstructionBlockerRegistry.Consumer.LANCER;
            case MARKSMAN -> ConstructionBlockerRegistry.Consumer.MARKSMAN;
        };
        return ConstructionBlockerRegistry.reservationFor(profile, consumer)
                .map(ConstructionBlockerRegistry.Reservation::retryAt)
                .map(this::normalizeConstructionRetry);
    }

private LocalDateTime normalizeConstructionRetry(LocalDateTime retryAt) {
        LocalDateTime minimumRetry = LocalDateTime.now().plusMinutes(TRAINING_BUTTON_RETRY_MINUTES_VALUE);
        return retryAt.isAfter(minimumRetry) ? retryAt : minimumRetry;
}

private Optional<LocalDateTime> earliestReservationCheck() {
        return enabledTroopTypes.stream()
                .map(this::reservationUntil)
                .flatMap(Optional::stream)
                .min(LocalDateTime::compareTo);
    }

private AreaData resolvePipelineArea(TroopTypeShape type) {
        return switch (type) {
            case INFANTRY -> INFANTRY_AREA_VALUE;
            case LANCER -> LANCER_AREA_VALUE;
            case MARKSMAN -> MARKSMAN_AREA_VALUE;
        };
    }

private void manageNoReadyQueues() {
        logInfo(routineLogTrainingLine("Zero queues are ready for training. Planning next run check shortly."));
        reschedule(LocalDateTime.now().plusMinutes(TRAINING_BUTTON_RETRY_MINUTES_VALUE));
    }

private void inputTroopCountFlow(int count) {
        tapInside(TROOP_COUNT_INPUT_MIN_VALUE, TROOP_COUNT_INPUT_MAX_VALUE, 1, 100);
        emuManager.clearText(EMULATOR_NUMBER, 6);
        emuManager.writeText(EMULATOR_NUMBER, count + "\n");
        sleepTask(1000);

    }

private void buildQueuesListFlow() {
        queuesToCheck = new ArrayList<>();
        enabledTroopTypes = new ArrayList<>();

        if (trainInfantry) {
            queuesToCheck.add(INFANTRY_AREA_VALUE);
            enabledTroopTypes.add(TroopTypeShape.INFANTRY);
        }
        if (trainLancer) {
            queuesToCheck.add(LANCER_AREA_VALUE);
            enabledTroopTypes.add(TroopTypeShape.LANCER);
        }
        if (trainMarksman) {
            queuesToCheck.add(MARKSMAN_AREA_VALUE);
            enabledTroopTypes.add(TroopTypeShape.MARKSMAN);
        }
    }

private String formatDurationFlow(Duration duration) {
        return String.format("%02d:%02d:%02d",
                duration.toHours(),
                duration.toMinutesPart(),
                duration.toSecondsPart());
    }

private boolean pressEventsButton() {
        ImageSearchResultData eventsButton = templateSearchHelper.locatePattern(
                HOME_EVENTS_BUTTON,
                SearchConfigConstants.DEFAULT_SINGLE);

        if (!eventsButton.isFound()) {
            logWarning(routineLogTrainingLine("Events button not detected"));
            return false;
        }

        tapInside(eventsButton);
        sleepTask(1000);

        return true;
    }

private QueueSlot inspectQueueState(AreaData queueArea, TroopTypeShape troopType) {
        TesseractSettingsData[] settingsToTry = {
                WHITE_SETTINGS,
                WHITE_NUMBERS,
                ORANGE_SETTINGS,
                GREEN_TEXT_SETTINGS
        };

        QueueSlot stateInfo = inspectForStateKeywords(queueArea, troopType, settingsToTry);
        if (stateInfo != null) {
            return stateInfo;
        }

        return inspectForTrainingTime(queueArea, troopType, settingsToTry);
    }

private void dismissPopupsFlow() {
        tapInside(POPUP_DISMISS_MIN_VALUE, POPUP_DISMISS_MAX_VALUE, 5, 200);
    }

private void pressTrainButton() {
        ImageSearchResultData trainButton = templateSearchHelper.locatePattern(
                TRAINING_TRAIN_BUTTON,
                SearchConfigConstants.DEFAULT_SINGLE);

        if (trainButton.isFound()) {
            tapInside(trainButton.getPoint(), trainButton.getPoint(), 1, 500);

            emuManager.captureScreen(EMULATOR_NUMBER);
            ImageSearchResultData replenishAll = templateSearchHelper.locatePattern(
                    REPLENISH_ALL_BUTTON,
                    SearchConfigConstants.DEFAULT_SINGLE);

            if (replenishAll.isFound()) {
                logInfo(routineLogTrainingLine("Filling resources."));
                tapInside(replenishAll.getPoint(), replenishAll.getPoint(), 1, 300);
                sleepTask(300);
                PointData replenishConfirm = new PointData(442, 1064);
                tapInside(replenishConfirm, replenishConfirm, 3, 100);
                tapInside(trainButton.getPoint(), trainButton.getPoint(), 1, 500);
            }
        } else {
            logWarning(routineLogTrainingLine("Train button not detected."));
        }
    }

private boolean performPromotion(TemplatesEnum template, ImageSearchResultData promoteButton) {
        logInfo(routineLogTrainingLine("Executing promotion for: " + template.name()));
        isPromotionTraining = true;

        tapInside(promoteButton.getPoint(), promoteButton.getPoint());
        sleepTask(300);


        capturePromotionCompletionTimeFlow();

        tapNear(PROMOTION_CONFIRM_POINT_VALUE);
        sleepTask(300);


        logInfo(routineLogTrainingLine("Promotion confirmed for: " + template.name()));
        return true;
    }

private List<QueueSlot> inspectAllQueues() {
        marchHelper.openLeftMenuCitySection(true);
        List<QueueSlot> result = new ArrayList<>();

        emuManager.captureScreen(EMULATOR_NUMBER);

        for (int i = 0; i < queuesToCheck.size(); i++) {
            AreaData queueArea = queuesToCheck.get(i);
            TroopTypeShape troopType = enabledTroopTypes.get(i);

            logInfo(routineLogTrainingLine("Analyzing queue for " + troopType.name()));
            QueueSlot queueInfo = inspectQueueState(queueArea, troopType);
            result.add(queueInfo);
        }

        result = retryUnknownQueuesFlow(result);
        marchHelper.closeLeftMenu();
        return result;
    }
}
