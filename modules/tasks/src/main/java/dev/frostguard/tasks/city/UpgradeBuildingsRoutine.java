package dev.frostguard.tasks.city;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.*;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.helper.FurnacePanelDetector;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;
import dev.frostguard.tasks.city.CityUpgradeFlow.Attempt;
import dev.frostguard.tasks.city.CityUpgradeFlow.FailureReason;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.nav.SidebarSection;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.vision.convert.GameTimeUtils;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import static dev.frostguard.api.configs.TemplatesEnum.BUILDING_BUTTON_INFO;
import static dev.frostguard.api.configs.TemplatesEnum.BUILDING_BUTTON_RESEARCH;
import static dev.frostguard.api.configs.TemplatesEnum.BUILDING_BUTTON_SPEED;
import static dev.frostguard.api.configs.TemplatesEnum.BUILDING_BUTTON_TRAIN;
import static dev.frostguard.api.configs.TemplatesEnum.BUILDING_BUTTON_UPGRADE;
import static dev.frostguard.api.configs.TemplatesEnum.BUILDING_SURVIVOR_BUTTON_UPGRADE;
import static dev.frostguard.api.configs.TemplatesEnum.GAME_HOME_SHORTCUTS_HELP_REQUEST4;
import static dev.frostguard.api.configs.TemplatesEnum.GAME_HOME_SHORTCUTS_OBTAIN;
import static dev.frostguard.api.configs.TemplatesEnum.GAME_HOME_SHORTCUTS_UPGRADE_TEXT;
import static dev.frostguard.api.configs.TemplatesEnum.GAME_HOME_FURNACE;
import static dev.frostguard.api.configs.TemplatesEnum.REPLENISH_ALL_BUTTON;
import static dev.frostguard.engine.nav.LeftMenuTextSettings.*;

public class UpgradeBuildingsRoutine extends DelayedTask {

private static final AreaData QUEUE_AREA_1_VALUE = new AreaData(new PointData(95, 370), new PointData(358, 407));

private static final AreaData QUEUE_AREA_2_VALUE = new AreaData(new PointData(95, 443), new PointData(358, 480));

private static final AreaData BUILDING_ACTION_BUTTON_AREA_VALUE = new AreaData(new PointData(190, 1160), new PointData(530, 1250));

private static final AreaData BUILDING_CONFIRM_UPGRADE_SEARCH_AREA_VALUE =
        new AreaData(new PointData(350, 900), new PointData(700, 1255));

private static final AreaData BUILDING_NAME_AREA_VALUE = new AreaData(new PointData(260, 510), new PointData(510, 575));

private static final int BLOCKER_RELEASE_GRACE_MINUTES = 5;

private static final int COMPLETION_SETTLE_SECONDS = 2;

private static final int BUILDING_CONTROL_CHECKS = 3;

private static final int BUILDING_CONTROL_POLL_MS = 300;

private static final int UPGRADE_CONFIRMATION_THRESHOLD = 90;

private final CityUpgradeDiagnostics diagnostics = new CityUpgradeDiagnostics();

private static final int TEMPLATE_TAP_RADIUS = 8;

private static final int MAX_RESOURCE_REPLENISHMENTS = 4;

private static final PointData REPLENISH_CONFIRM_POINT = new PointData(511, 1056);

private static final SearchConfig REPLENISH_BUTTON_RECHECK = SearchConfig.builder()
        .withMaxAttempts(2)
        .withDelay(300)
        .withThreshold(90)
        .withCoordinates(new PointData(180, 1070), new PointData(535, 1195))
        .build();

private final List<AreaData> queues = new ArrayList<>(Arrays.asList(QUEUE_AREA_1_VALUE, QUEUE_AREA_2_VALUE));

public UpgradeBuildingsRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTaskEnum) {
        super(profile, tpDailyTaskEnum);
    }

@Override
    protected void execute() {
        CityUpgradeFlow.execute(this::executeQueues, new CityUpgradeFlow.Recovery() {
            @Override
            public void retainFailure(String type) {
                retainDiagnostic(type, false);
            }

            @Override
            public void recoverRoot() {
                recoverLocation(LaunchPoint.ANY);
            }

            @Override
            public void reportRecovery(boolean recovered, RuntimeException original, RuntimeException recoveryFailure) {
                logWarning(routineLogUpgradeBuildingsLine("Failure recovery: rootConfirmed=" + recovered
                        + "; original=" + original.getMessage()
                        + (recoveryFailure == null ? "; scheduler will retain the original failure and retry"
                                : "; recoveryFailure=" + recoveryFailure.getMessage())));
            }
        });
    }

private void executeQueues() {
        diagnostics.begin(0, 0);
        reachCityView();


        List<UpgradeBuildingsRoutine.QueueReadout> queueResults = inspectAllQueues();


        logQueueSummaryFlow(queueResults);
        clearConstructionReservationWhenStarted(queueResults);


        boolean hasIdleQueue = queueResults.stream()
                .anyMatch(result -> result.state.status == UpgradeBuildingsRoutine.QueueMood.IDLE ||
                        result.state.status == UpgradeBuildingsRoutine.QueueMood.IDLE_TEMP);

        if (hasIdleQueue) {


            List<ProductionBlocker> productionBlockers = new ArrayList<>();
            Set<Integer> attemptedQueues = new java.util.HashSet<>();


            for (UpgradeBuildingsRoutine.QueueReadout result : queueResults) {
                if (result.state.status == UpgradeBuildingsRoutine.QueueMood.IDLE ||
                        result.state.status == UpgradeBuildingsRoutine.QueueMood.IDLE_TEMP) {
                    logInfo(routineLogUpgradeBuildingsLine("Processing queue " + result.queueNumber + " (Status: " + result.state.status + ")"));
                    QueueHandlingResult handlingResult = handleQueue(result);

                    if (handlingResult.constructionAttempted()) {
                        attemptedQueues.add(result.queueNumber());
                    }
                    if (handlingResult.blocker() != null) {
                        productionBlockers.add(handlingResult.blocker());
                    }
                }
            }

            recoverLocation(LaunchPoint.HOME);

            logInfo(routineLogUpgradeBuildingsLine("Reanalyzing queues after processing idle queues..."));

            reachCityView();


            List<UpgradeBuildingsRoutine.QueueReadout> updatedResults = inspectAllQueues();


            logInfo(routineLogUpgradeBuildingsLine("=== Updated Queue Analysis After Processing ==="));
            logQueueSummaryFlow(updatedResults);
            clearConstructionReservationWhenStarted(updatedResults);


            if (!productionBlockers.isEmpty()) {
                LocalDateTime retryAt = productionBlockers.stream()
                        .map(ProductionBlocker::completionTime)
                        .min(LocalDateTime::compareTo)
                        .orElse(LocalDateTime.now().plusMinutes(5))
                        .plusSeconds(COMPLETION_SETTLE_SECONDS);
                logInfo(routineLogUpgradeBuildingsLine(
                        "Recommended building is blocked by production. Planning exact handoff retry for: " + retryAt));
                this.reschedule(retryAt);
                marchHelper.closeLeftMenu();
                return;
            }

            if (rescheduleForRetainedReservation(attemptedQueues, updatedResults)) {
                marchHelper.closeLeftMenu();
                return;
            }


            deferBasedOnBusyQueues(updatedResults);
            marchHelper.closeLeftMenu();
        } else {
            if (rescheduleForRetainedReservation(Set.of(), queueResults)) {
                marchHelper.closeLeftMenu();
                return;
            }
            deferBasedOnBusyQueues(queueResults);
        }
    }

@Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

enum QueueMood {
        IDLE,

        BUSY,

        NOT_PURCHASED,

        IDLE_TEMP,

        UNKNOWN

    }

private record QueueSnapshot(UpgradeBuildingsRoutine.QueueMood status, String timeRemaining) {
    }

private record QueueReadout(int queueNumber, AreaData queueArea, UpgradeBuildingsRoutine.QueueSnapshot state) {

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

private record ProductionBlocker(Set<ConstructionBlockerRegistry.Consumer> consumers,
        int constructionQueue, LocalDateTime completionTime) {
    }

private record QueueHandlingResult(boolean constructionAttempted, ProductionBlocker blocker) {
    }

private String routineLogUpgradeBuildingsLine(String note) {
        return "UpgradeBuildingsRoutine | " + note;
    }

private boolean refillResourcesIfNeededFlow() {
        var result = RepeatedResourceReplenishmentFlow.run(
                new RepeatedResourceReplenishmentFlow.Ui() {
                    @Override
                    public PointData findReplenishAll() {
                        return foundPoint(templateSearchHelper.locatePattern(
                                REPLENISH_ALL_BUTTON, REPLENISH_BUTTON_RECHECK));
                    }

                    @Override
                    public PointData findObtain() {
                        return foundPoint(templateSearchHelper.locatePattern(
                                GAME_HOME_SHORTCUTS_OBTAIN, SearchConfigConstants.DEFAULT_SINGLE));
                    }

                    @Override
                    public void openObtain(PointData point) {
                        tapNear(point);
                        sleepTask(500);
                    }

                    @Override
                    public void replenishAndConfirm(PointData point) {
                        logInfo(routineLogUpgradeBuildingsLine("Refilling one missing resource for the upgrade..."));
                        tapNear(point);
                        sleepTask(300);
                        tapNear(REPLENISH_CONFIRM_POINT);
                        sleepTask(1000);
                    }
                },
                MAX_RESOURCE_REPLENISHMENTS);

        if (result.ready()) {
            if (result.replenishedResources() > 0) {
                logInfo(routineLogUpgradeBuildingsLine(
                        "Replenished " + result.replenishedResources() + " missing resource(s)."));
            }
            return true;
        }

        logWarning(routineLogUpgradeBuildingsLine(
                "Resource replenishment did not finish (" + result.outcome()
                        + "). Skipping the building confirmation."));
        return false;
    }

private PointData foundPoint(ImageSearchResultData result) {
        return result != null && result.isFound() ? result.getPoint() : null;
    }

private FailureReason handleSurvivorBuilding() {
        logInfo(routineLogUpgradeBuildingsLine("Handling Survivor Building"));


        int limit = 100;
        ImageSearchResultData survivorUpgrade;

        while (!(survivorUpgrade = templateSearchHelper.locatePattern(BUILDING_SURVIVOR_BUTTON_UPGRADE,
                SearchConfigConstants.SINGLE_WITH_2_RETRIES)).isFound()) {
            tapInside(new PointData(560, 640), new PointData(650, 690), 1, 200);
            limit--;
            if (limit <= 0) {
                break;
            }
        }


        if (!survivorUpgrade.isFound()) {
            return FailureReason.CONTROL_NOT_RECOGNIZED;
        }
        tapInside(survivorUpgrade.getPoint(), survivorUpgrade.getPoint(), 1, 1000);


        if (!refillResourcesIfNeededFlow()) {
            return FailureReason.RESOURCES_NOT_OBTAINED;
        }


        tapInside(new PointData(450, 1190), new PointData(600, 1230), 1, 1000);


        if (tapAllianceHelp()) {
            sleepTask(500);
            tapInside(new PointData(540, 1200), new PointData(700, 1250), 1, 1000);
        }
        return null;
    }

private void deferBasedOnBusyQueues(List<QueueReadout> queueResults) {
        logInfo(routineLogUpgradeBuildingsLine("Inspecting construction queue outcomes to reschedule..."));


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
                logInfo(routineLogUpgradeBuildingsLine("Wait time exceeds 30 minutes (" + minutesToWait + " min). Planning next run for half time: " +
                        halfTime + " minutes from now"));
            } else if (minutesToWait < 5) {
                rescheduleTime = LocalDateTime.now().plusMinutes(minutesToWait);
                logInfo(routineLogUpgradeBuildingsLine("Wait time is less than 5 minutes. Keeping normal schedule: " +
                        minutesToWait + " minutes from now"));
            } else {
                rescheduleTime = LocalDateTime.now().plusMinutes(minutesToWait);
                logInfo(routineLogUpgradeBuildingsLine("Wait time is " + minutesToWait + " minutes. Using normal schedule"));
            }

            logInfo(routineLogUpgradeBuildingsLine("Shortest busy queue: Queue " + shortestBusyQueue.queueNumber +
                    " with " + shortestBusyQueue.state.timeRemaining + " remaining"));
            logInfo(routineLogUpgradeBuildingsLine("Planning next run task for: " + rescheduleTime));

            this.reschedule(rescheduleTime);
        } else {


            LocalDateTime rescheduleTime = LocalDateTime.now().plusHours(1);
            logWarning(routineLogUpgradeBuildingsLine("Zero BUSY queues with time information detected. Planning next run for 1 hour: " + rescheduleTime));
            this.reschedule(rescheduleTime);
        }
    }

private void logQueueSummaryFlow(List<UpgradeBuildingsRoutine.QueueReadout> queueResults) {
        logInfo(routineLogUpgradeBuildingsLine("=== Queue Analysis Summary ==="));
        for (UpgradeBuildingsRoutine.QueueReadout result : queueResults) {
            logInfo(routineLogUpgradeBuildingsLine(result.toString()));
        }
    }

private ProductionBlocker handleProductionBlocker(int constructionQueue) {
        checkPreemption();
        RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
        ImageSearchResultData train = emuManager.locatePattern(EMULATOR_NUMBER, frame,
                BUILDING_BUTTON_TRAIN, SearchConfigConstants.DEFAULT_SINGLE.getThreshold());
        ImageSearchResultData research = train.isFound() ? null : emuManager.locatePattern(EMULATOR_NUMBER, frame,
                BUILDING_BUTTON_RESEARCH, SearchConfigConstants.DEFAULT_SINGLE.getThreshold());
        diagnostics.decision(frame, "threshold=" + SearchConfigConstants.DEFAULT_SINGLE.getThreshold()
                + "; train=" + train + "; research=" + (research == null ? "not-searched" : research));

        if (!train.isFound() && (research == null || !research.isFound())) {
            return null;
        }

        Set<ConstructionBlockerRegistry.Consumer> consumers;
        if (train.isFound()) {
            String buildingName = readSelectedBuildingName();
            ConstructionBlockerRegistry.Consumer identified = identifyTrainingConsumer(buildingName);
            if (identified == null) {
                consumers = EnumSet.of(
                        ConstructionBlockerRegistry.Consumer.INFANTRY,
                        ConstructionBlockerRegistry.Consumer.LANCER,
                        ConstructionBlockerRegistry.Consumer.MARKSMAN);
                logWarning(routineLogUpgradeBuildingsLine(
                        "Training building name was unreadable. Reserving all training queues as a safe fallback."));
            } else {
                consumers = EnumSet.of(identified);
            }
        } else {
            consumers = EnumSet.of(ConstructionBlockerRegistry.Consumer.RESEARCH);
        }

        ImageSearchResultData speedupButton = templateSearchHelper.locatePattern(BUILDING_BUTTON_SPEED,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (!speedupButton.isFound()) {
            logWarning(routineLogUpgradeBuildingsLine(
                    "Production blocker detected, but its speedup button was not found. Retrying in 5 minutes."));
            LocalDateTime retryAt = LocalDateTime.now().plusMinutes(BLOCKER_RELEASE_GRACE_MINUTES);
            reserveConsumers(consumers, constructionQueue, retryAt);
            return new ProductionBlocker(consumers, constructionQueue, retryAt);
        }

        diagnostics.stage("production-timer");
        tapAround(speedupButton.getPoint(), TEMPLATE_TAP_RADIUS, 500);
        Duration remaining = durationHelper.attemptRecognition(
                new PointData(292, 284),
                new PointData(432, 314),
                5,
                300,
                null,
                GameTimeUtils::isAcceptedFormat,
                GameTimeUtils::parseDuration);

        if (remaining == null) {
            logWarning(routineLogUpgradeBuildingsLine(
                    "Could not read the production blocker timer. Retrying in 5 minutes."));
            LocalDateTime retryAt = LocalDateTime.now().plusMinutes(BLOCKER_RELEASE_GRACE_MINUTES);
            reserveConsumers(consumers, constructionQueue, retryAt);
            return new ProductionBlocker(consumers, constructionQueue, retryAt);
        }

        LocalDateTime completionTime = LocalDateTime.now().plus(remaining);
        reserveConsumers(consumers, constructionQueue, completionTime);
        logInfo(routineLogUpgradeBuildingsLine("Production blocker " + consumers
                + " completes at approximately " + completionTime
                + "; consumer remains reserved until construction start is verified"));
        return new ProductionBlocker(consumers, constructionQueue, completionTime);
    }

private String readSelectedBuildingName() {
        try {
            String text = emuManager.readText(
                    EMULATOR_NUMBER,
                    BUILDING_NAME_AREA_VALUE.topLeft(),
                    BUILDING_NAME_AREA_VALUE.bottomRight(),
                    WHITE_SETTINGS,
                    true).trim();
            logInfo(routineLogUpgradeBuildingsLine("Selected building name OCR: '" + text + "'"));
            return text;
        } catch (Exception e) {
            CityUpgradeFlow.rethrowControlSignal(e);
            logWarning(routineLogUpgradeBuildingsLine("Could not read selected building name: " + e.getMessage()));
            return "";
        }
    }

static ConstructionBlockerRegistry.Consumer identifyTrainingConsumer(String buildingName) {
        String normalized = buildingName == null
                ? ""
                : buildingName.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (normalized.contains("infantry")) {
            return ConstructionBlockerRegistry.Consumer.INFANTRY;
        }
        if (normalized.contains("lancer")) {
            return ConstructionBlockerRegistry.Consumer.LANCER;
        }
        if (normalized.contains("marksman")) {
            return ConstructionBlockerRegistry.Consumer.MARKSMAN;
        }
        return null;
    }

private void reserveConsumers(Set<ConstructionBlockerRegistry.Consumer> consumers, int constructionQueue,
        LocalDateTime retryAt) {
        ConstructionBlockerRegistry.reserve(profile, consumers, constructionQueue, retryAt);
}

private void clearConstructionReservationWhenStarted(List<QueueReadout> queueResults) {
        ConstructionBlockerRegistry.reservation(profile).ifPresent(reservation -> queueResults.stream()
                .filter(result -> result.queueNumber() == reservation.constructionQueue())
                .filter(result -> shouldReleaseReservation(result.state().status()))
                .findFirst()
                .ifPresent(result -> {
                    logInfo(routineLogUpgradeBuildingsLine(
                            "Reserved construction queue " + result.queueNumber()
                                    + " is BUSY; clearing production consumer lock "
                                    + reservation.consumers()));
                    ConstructionBlockerRegistry.clear(profile);
                }));
    }

static boolean shouldReleaseReservation(QueueMood reservedQueueState) {
        return reservedQueueState == QueueMood.BUSY;
}

static boolean shouldClearRetainedReservation(QueueMood reservedQueueState, boolean attempted, boolean expired) {
        return reservedQueueState == QueueMood.NOT_PURCHASED
                || attempted && (reservedQueueState == QueueMood.IDLE || reservedQueueState == QueueMood.IDLE_TEMP)
                || expired;
}

private boolean rescheduleForRetainedReservation(Set<Integer> attemptedQueues, List<QueueReadout> queueResults) {
        Optional<ConstructionBlockerRegistry.Reservation> retainedReservation =
                ConstructionBlockerRegistry.reservation(profile);
        if (retainedReservation.isEmpty()) {
            return false;
        }

        ConstructionBlockerRegistry.Reservation reservation = retainedReservation.get();
        LocalDateTime now = LocalDateTime.now();
        QueueMood reservedQueueState = queueResults.stream()
                .filter(result -> result.queueNumber() == reservation.constructionQueue())
                .map(result -> result.state().status())
                .findFirst()
                .orElse(QueueMood.UNKNOWN);

        boolean attempted = attemptedQueues.contains(reservation.constructionQueue());
        boolean expired = !reservation.retryAt().isAfter(now);
        if (shouldClearRetainedReservation(reservedQueueState, attempted, expired)) {
            String reason = reservedQueueState == QueueMood.NOT_PURCHASED
                    ? "reserved queue is not purchased"
                    : attempted && (reservedQueueState == QueueMood.IDLE || reservedQueueState == QueueMood.IDLE_TEMP)
                            ? "construction did not start on the reserved queue"
                            : "the reservation expired without construction start evidence";
            logWarning(routineLogUpgradeBuildingsLine(
                    "Clearing production consumer lock for queue " + reservation.constructionQueue()
                            + " because " + reason));
            ConstructionBlockerRegistry.clear(profile);
            return false;
        }
        String attemptEvidence = attempted
                ? "the construction attempt did not make the queue BUSY"
                : "the reserved queue did not provide start evidence";
        logWarning(routineLogUpgradeBuildingsLine(
                "Keeping production consumer lock because " + attemptEvidence
                        + ". Retrying construction at " + reservation.retryAt()));
        this.reschedule(reservation.retryAt());
        return true;
}

private void tapAround(PointData center, int radius, int delayMs) {
        tapInside(
                new PointData(center.getX() - radius, center.getY() - radius),
                new PointData(center.getX() + radius, center.getY() + radius),
                1,
                delayMs);
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
            CityUpgradeFlow.rethrowControlSignal(e);
            logError(routineLogUpgradeBuildingsLine("Error parsing time string '" + timeString + "': " + e.getMessage()));
            return 15;

        }
    }

private FailureReason startBuildingAction(String actionName, ImageSearchResultData upgrade) {
        diagnostics.stage("open-" + actionName + "-dialog");
        logInfo(routineLogUpgradeBuildingsLine("Starting building " + actionName + "; target=" + upgrade));
        if (upgrade != null) {
            if (!tapInside(upgrade)) {
                return FailureReason.CONTROL_NOT_RECOGNIZED;
            }
        } else {
            tapInside(BUILDING_ACTION_BUTTON_AREA_VALUE);
        }
        sleepTask(1000);

        diagnostics.stage("replenish-resources");
        if (!refillResourcesIfNeededFlow()) {
            return FailureReason.RESOURCES_NOT_OBTAINED;
        }

        diagnostics.stage("confirm-" + actionName);
        FailureReason failure = "upgrade".equals(actionName)
                ? confirmDetectedBuildingUpgrade()
                : confirmNewBuilding();
        if (failure != null) {
            return failure;
        }
        diagnostics.stage("alliance-help");
        tapAllianceHelp();
        return null;
    }

private FailureReason confirmDetectedBuildingUpgrade() {
        BuildingUpgradeConfirmationFlow.Outcome outcome = BuildingUpgradeConfirmationFlow.run(
                new BuildingUpgradeConfirmationFlow.Ui() {
                    @Override
                    public boolean tapDetectedUpgrade() {
                        ImageSearchResultData upgrade = locateConfirmationWithEvidence();
                        if (!upgrade.isFound()) {
                            return false;
                        }
                        logInfo(routineLogUpgradeBuildingsLine("Upgrade confirmation detected: " + upgrade));
                        diagnostics.stage("confirm-upgrade-transition");
                        return tapInside(upgrade);
                    }

                    @Override
                    public void waitForTransition() {
                        sleepTask(500);
                    }

                    @Override
                    public boolean isConfirmationPending() {
                        checkPreemption();
                        RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
                        ImageSearchResultData action = locateOnFrame(frame, GAME_HOME_SHORTCUTS_UPGRADE_TEXT,
                                BUILDING_CONFIRM_UPGRADE_SEARCH_AREA_VALUE, UPGRADE_CONFIRMATION_THRESHOLD);
                        ImageSearchResultData home = emuManager.locatePattern(EMULATOR_NUMBER, frame,
                                GAME_HOME_FURNACE, SearchConfigConstants.DEFAULT_SINGLE.getThreshold());
                        diagnostics.decision(frame, "confirmation=" + action + "; home=" + home);
                        return action.isFound() || !home.isFound();
                    }
                }, BUILDING_CONTROL_CHECKS);
        if (outcome == BuildingUpgradeConfirmationFlow.Outcome.CONFIRMED) {
            logInfo(routineLogUpgradeBuildingsLine("Building upgrade confirmed; Home anchor returned"));
            return null;
        }
        return outcome == BuildingUpgradeConfirmationFlow.Outcome.BUTTON_NOT_FOUND
                ? FailureReason.CONFIRMATION_NOT_FOUND
                : FailureReason.HOME_TRANSITION_NOT_CONFIRMED;
    }

private ImageSearchResultData locateConfirmationWithEvidence() {
        ImageSearchResultData result = ImageSearchResultData.miss();
        for (int attempt = 0; attempt < BUILDING_CONTROL_CHECKS; attempt++) {
            checkPreemption();
            RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
            result = locateOnFrame(frame, GAME_HOME_SHORTCUTS_UPGRADE_TEXT,
                    BUILDING_CONFIRM_UPGRADE_SEARCH_AREA_VALUE, UPGRADE_CONFIRMATION_THRESHOLD);
            diagnostics.decision(frame, "confirmation threshold=" + UPGRADE_CONFIRMATION_THRESHOLD + " " + result);
            if (result.isFound()) return result;
            if (attempt < BUILDING_CONTROL_CHECKS - 1) sleepTask(BUILDING_CONTROL_POLL_MS);
        }
        return result;
    }

private FailureReason confirmNewBuilding() {
        tapInside(new PointData(489, 1034), new PointData(500, 1050));
        sleepTask(500);
        checkPreemption();
        RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
        ImageSearchResultData home = emuManager.locatePattern(EMULATOR_NUMBER, frame,
                GAME_HOME_FURNACE, SearchConfigConstants.DEFAULT_SINGLE.getThreshold());
        diagnostics.decision(frame, "new-building home=" + home);
        return home.isFound() ? null : FailureReason.HOME_TRANSITION_NOT_CONFIRMED;
    }

private boolean isBuildButtonVisible() {
        try {
            emuManager.captureScreen(EMULATOR_NUMBER);
            String buttonText = emuManager.readText(
                    EMULATOR_NUMBER,
                    BUILDING_ACTION_BUTTON_AREA_VALUE.topLeft(),
                    BUILDING_ACTION_BUTTON_AREA_VALUE.bottomRight(),
                    WHITE_SETTINGS,
                    true);
            String normalized = buttonText == null ? "" : buttonText.toLowerCase().replaceAll("[^a-z]", "");
            diagnostics.observation("build OCR='" + normalized + "'");
            boolean detected = normalized.contains("build") || normalized.contains("bui");
            if (detected) {
                logInfo(routineLogUpgradeBuildingsLine("Build button detected via OCR: '" + buttonText + "'"));
            }
            return detected;
        } catch (Exception e) {
            CityUpgradeFlow.rethrowControlSignal(e);
            logWarning(routineLogUpgradeBuildingsLine("Build button OCR failed: " + e.getMessage()));
            return false;
        }
    }

private void logQueueStateFlow(int queueIndex, UpgradeBuildingsRoutine.QueueSnapshot state) {
        switch (state.status) {
            case IDLE:
                logInfo(routineLogUpgradeBuildingsLine("Queue " + queueIndex + " is IDLE - available for use"));
                break;
            case BUSY:
                logInfo(routineLogUpgradeBuildingsLine("Queue " + queueIndex + " is BUSY - Time remaining: " + state.timeRemaining));
                break;
            case NOT_PURCHASED:
                logInfo(routineLogUpgradeBuildingsLine("Queue " + queueIndex + " is NOT PURCHASED - needs to be acquired"));
                break;
            case IDLE_TEMP:
                logInfo(routineLogUpgradeBuildingsLine("Queue " + queueIndex + " is IDLE_TEMP - detected by orange color"));
                break;
            case UNKNOWN:
                logWarning(routineLogUpgradeBuildingsLine("Queue " + queueIndex + " state is UNKNOWN - OCR did not complete to detect state"));
                break;
        }
    }

private UpgradeBuildingsRoutine.QueueSnapshot inspectQueueState(AreaData queueArea) {
        try {
            ImageSearchResultData idle = templateSearchHelper.locatePattern(
                    TemplatesEnum.MARCH_QUEUE_STATUS_IDLE,
                    SearchConfig.builder()
                            .withArea(queueArea)
                            .withThreshold(88)
                            .withMaxAttempts(1)
                            .build());
            if (idle.isFound()) {
                return new UpgradeBuildingsRoutine.QueueSnapshot(UpgradeBuildingsRoutine.QueueMood.IDLE, null);
            }


            OcrSettingsData[] settingsToTry = {
                    WHITE_SETTINGS,
                    WHITE_NUMBERS,
                    RED_SETTINGS,
                    ORANGE_SETTINGS,
            };

            for (OcrSettingsData ocrPreset : settingsToTry) {
                String ocrText = emuManager.readText(
                        EMULATOR_NUMBER,
                        queueArea.topLeft(),
                        queueArea.bottomRight(),
                        ocrPreset,
                        true).trim();

                logDebug(routineLogUpgradeBuildingsLine("OCR result with ocrPreset " + ocrPreset.getClass().getSimpleName() + ": '" + ocrText + "'"));


                if (ocrText.toLowerCase().contains("idle")) {

                    if (ocrPreset == ORANGE_SETTINGS) {
                        logDebug(routineLogUpgradeBuildingsLine("Orange 'idle' text detected - IDLE_TEMP"));
                        return new UpgradeBuildingsRoutine.QueueSnapshot(UpgradeBuildingsRoutine.QueueMood.IDLE_TEMP, null);
                    } else {
                        return new UpgradeBuildingsRoutine.QueueSnapshot(UpgradeBuildingsRoutine.QueueMood.IDLE, null);
                    }
                }


                if (ocrText.toLowerCase().contains("purchase") ||
                        ocrText.toLowerCase().contains("queue")) {
                    return new UpgradeBuildingsRoutine.QueueSnapshot(UpgradeBuildingsRoutine.QueueMood.NOT_PURCHASED, null);
                }


                if (ocrText.matches(".*(\\d+d\\s*)?\\d{6}.*")) {


                    String cleanedTime = ocrText.replaceAll("[^0-9d]", "").trim();
                    if (!cleanedTime.isEmpty()) {
                        return new UpgradeBuildingsRoutine.QueueSnapshot(UpgradeBuildingsRoutine.QueueMood.BUSY, cleanedTime);
                    }
                }
            }


            return new UpgradeBuildingsRoutine.QueueSnapshot(UpgradeBuildingsRoutine.QueueMood.UNKNOWN, null);

        } catch (Exception e) {
            CityUpgradeFlow.rethrowControlSignal(e);
            logError(routineLogUpgradeBuildingsLine("Issue while OCR analysis: " + e.getMessage()));
            return new UpgradeBuildingsRoutine.QueueSnapshot(UpgradeBuildingsRoutine.QueueMood.UNKNOWN, null);
        }
    }

private List<UpgradeBuildingsRoutine.QueueReadout> inspectAllQueues() {
        List<UpgradeBuildingsRoutine.QueueReadout> results = new ArrayList<>();

        try {


            emuManager.captureScreen(EMULATOR_NUMBER);

            int queueIndex = 1;
            for (AreaData queueArea : queues) {
                logInfo(routineLogUpgradeBuildingsLine("Analyzing queue " + queueIndex));


                UpgradeBuildingsRoutine.QueueSnapshot state = inspectQueueState(queueArea);


                UpgradeBuildingsRoutine.QueueReadout result = new UpgradeBuildingsRoutine.QueueReadout(
                        queueIndex, queueArea, state);
                results.add(result);


                logQueueStateFlow(queueIndex, state);

                queueIndex++;
            }


            List<UpgradeBuildingsRoutine.QueueReadout> unknownResults = results.stream()
                    .filter(result -> result.state.status == UpgradeBuildingsRoutine.QueueMood.UNKNOWN)
                    .collect(Collectors.toList());

            if (!unknownResults.isEmpty()) {
                logInfo(routineLogUpgradeBuildingsLine("Detected " + unknownResults.size()
                        + " queue(s) with UNKNOWN status. Retrying with a new screenshot."));


                emuManager.captureScreen(EMULATOR_NUMBER);


                List<UpgradeBuildingsRoutine.QueueReadout> updatedResults = new ArrayList<>();


                for (UpgradeBuildingsRoutine.QueueReadout originalResult : results) {
                    if (originalResult.state.status == UpgradeBuildingsRoutine.QueueMood.UNKNOWN) {


                        logInfo(routineLogUpgradeBuildingsLine("Retrying analysis for queue " + originalResult.queueNumber));
                        UpgradeBuildingsRoutine.QueueSnapshot newState = inspectQueueState(originalResult.queueArea);


                        UpgradeBuildingsRoutine.QueueReadout newResult = new UpgradeBuildingsRoutine.QueueReadout(
                                originalResult.queueNumber, originalResult.queueArea, newState);


                        updatedResults.add(newResult);


                        logInfo(routineLogUpgradeBuildingsLine("Queue " + originalResult.queueNumber + " reanalyzed. New state: " + newState.status));
                        logQueueStateFlow(originalResult.queueNumber, newState);
                    } else {


                        updatedResults.add(originalResult);
                    }
                }


                results = updatedResults;
            }
        } catch (Exception e) {
            CityUpgradeFlow.rethrowControlSignal(e);
            logError(routineLogUpgradeBuildingsLine("Error analyzing construction queues: " + e.getMessage()));
        }

        return results;
    }

private boolean tapAllianceHelp() {
        ImageSearchResultData help = templateSearchHelper.locatePatternMultiScale(
                GAME_HOME_SHORTCUTS_HELP_REQUEST4, SearchConfigConstants.HIGH_SENSITIVITY);

        if (help == null || !help.isFound()) {
            logWarning(routineLogUpgradeBuildingsLine("Alliance help button not detected"));
            return false;
        }

        tapInside(help.getPoint(), help.getPoint(), 1, 500);
        return true;
    }

private void reachCityView() {
        if (!navigationHelper.openSidebarSection(SidebarSection.CITY)) {
            throw new IllegalStateException("Could not open the City sidebar section");
        }
    }

private QueueHandlingResult handleQueue(QueueReadout queueResult) {
        return CityUpgradeFlow.handleQueue(queueResult.queueNumber(), new CityUpgradeFlow.QueueUi<>() {
            @Override
            public Attempt<QueueHandlingResult> attempt(int number) {
                diagnostics.begin(queueResult.queueNumber(), number);
                checkPreemption();
                Attempt<QueueHandlingResult> result = handleQueueAttempt(queueResult);
                if (result.failure() == null) {
                    logInfo(routineLogUpgradeBuildingsLine("Recommended building handled; queue="
                            + queueResult.queueNumber() + "; attempt=" + number
                            + "; outcome=" + (result.result().blocker() == null ? "construction-attempted" : "production-blocked")));
                }
                return result;
            }

            @Override
            public void retainFailure(int number, FailureReason reason) {
                retainDiagnostic(reason.name().toLowerCase(Locale.ROOT), number == 1);
            }

            @Override
            public void recoverHome() {
                recoverLocation(LaunchPoint.HOME);
            }
        });
    }

private Attempt<QueueHandlingResult> handleQueueAttempt(QueueReadout queueResult) {
        reachCityView();
        sleepTask(500);
        diagnostics.stage("open-recommended-building");
        tapInside(queueResult.queueArea);
        sleepTask(500);

        diagnostics.stage("recognize-building-control");
        // Poll without tapping: claiming completed production may leave no building selected.
        for (int poll = 0; poll < BUILDING_CONTROL_CHECKS; poll++) {
            checkPreemption();
            RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
            FurnacePanelDetector.Evidence furnace = FurnacePanelDetector.inspect(
                    (template, area, threshold) -> locateOnFrame(frame, template, area, threshold));
            diagnostics.decision(frame, furnace.toString());
            if (furnace.actionable()) {
                logInfo(routineLogUpgradeBuildingsLine("Furnace entry recognized; " + furnace));
                return buildingAttempt(startBuildingAction("upgrade", furnace.upgrade()));
            }

            ImageSearchResultData info = emuManager.locatePattern(EMULATOR_NUMBER, frame,
                    BUILDING_BUTTON_INFO, SearchConfigConstants.RESILIENT.getThreshold());
            ImageSearchResultData upgrade = info.isFound() ? null : emuManager.locatePattern(EMULATOR_NUMBER, frame,
                    BUILDING_BUTTON_UPGRADE, SearchConfigConstants.RESILIENT.getThreshold());
            diagnostics.decision(frame, furnace + "; buildingControlThreshold="
                    + SearchConfigConstants.RESILIENT.getThreshold() + "; info=" + info + "; upgrade="
                    + (upgrade == null ? "not-searched" : upgrade));
            if (info.isFound()) {
                diagnostics.stage("survivor-building");
                tapNear(new PointData(info.getPoint().getX() + 100, info.getPoint().getY()));
                return buildingAttempt(handleSurvivorBuilding());
            }
            if (upgrade != null && upgrade.isFound()) {
                return buildingAttempt(startBuildingAction("upgrade", upgrade));
            }
            if (poll < BUILDING_CONTROL_CHECKS - 1) sleepTask(BUILDING_CONTROL_POLL_MS);
        }

        diagnostics.stage("recognize-build-control");
        if (isBuildButtonVisible()) {
            return buildingAttempt(startBuildingAction("build", null));
        }
        diagnostics.stage("production-blocker");
        ProductionBlocker blocker = handleProductionBlocker(queueResult.queueNumber());
        return blocker == null
                ? Attempt.unresolved(FailureReason.CONTROL_NOT_RECOGNIZED)
                : Attempt.completed(new QueueHandlingResult(false, blocker));
    }

private Attempt<QueueHandlingResult> buildingAttempt(FailureReason failure) {
        return failure == null ? Attempt.completed(new QueueHandlingResult(true, null))
                : Attempt.unresolved(failure);
    }

private ImageSearchResultData locateOnFrame(RawImageData frame, TemplatesEnum template, AreaData area, int threshold) {
        return emuManager.locatePattern(EMULATOR_NUMBER, frame, template,
                area.topLeft(), area.bottomRight(), threshold);
    }

private void recoverLocation(LaunchPoint target) {
        checkPreemption();
        logInfo(routineLogUpgradeBuildingsLine("Recovering screen; target=" + target));
        diagnostics.stage("recover-" + target.name().toLowerCase(Locale.ROOT));
        LaunchPoint reached = navigationHelper.ensureCorrectScreenLocation(target, this::checkPreemption);
        logInfo(routineLogUpgradeBuildingsLine("Recovery confirmed; target=" + target + "; reached=" + reached));
    }

private void retainDiagnostic(String type, boolean retryable) {
        checkPreemption();
        String snapshot = diagnostics.retain(DiagnosticSnapshotStore::forCurrentWorkspace,
                () -> emuManager.captureScreen(EMULATOR_NUMBER), type);
        String message = routineLogUpgradeBuildingsLine("City Upgrade diagnostic; reason=" + type
                + "; " + diagnostics.context() + "; " + snapshot);
        if (retryable) {
            logDebug(message);
        } else {
            logWarning(message);
        }
    }
}
