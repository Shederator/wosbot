package dev.frostguard.tasks.city;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.ResearchCategoryEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.PriorityItemData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.ResearchBadgeData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.engine.config.PriorityConfigResolver;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.tasks.city.ResearchDialogClassifier.ResearchDialogState;
import dev.frostguard.tasks.city.ResearchNodeSelectionPolicy.ResearchNode;
import dev.frostguard.tasks.city.ResearchNodeSelectionPolicy.ResearchRow;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.ocr.ResearchBadgeReader;
import dev.frostguard.vision.ocr.OcrEngine;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import dev.frostguard.vision.ocr.OcrException;

public class ResearchRoutine extends DelayedTask {

private static final int HAND_CLICK_OFFSET_X_VALUE = -73;

private static final int HAND_CLICK_OFFSET_Y_VALUE = 88;

private static final int MAX_SCROLL_ATTEMPTS_LIMIT = 10;

private static final int RESEARCH_SCROLL_SETTLE_MILLIS = 1000;

private static final int RESEARCH_DRAG_DURATION_MILLIS = 800;

private static final int RESEARCH_ENTRY_ATTEMPTS = 2;

private static final int RESEARCH_ENTRY_RETRY_MINUTES = 5;

private static final int RESEARCH_SAFE_TAP_Y = 180;

private static final PointData RESEARCH_TITLE_TOP_LEFT = new PointData(80, 0);

private static final PointData RESEARCH_TITLE_BOTTOM_RIGHT = new PointData(360, 80);

private static final PointData RESEARCH_TREE_TOP_LEFT = new PointData(0, 150);

private static final PointData RESEARCH_TREE_BOTTOM_RIGHT = new PointData(720, 1180);

private static final PointData RESEARCH_REQUIREMENTS_TOP_LEFT = new PointData(70, 810);

private static final PointData RESEARCH_REQUIREMENTS_BOTTOM_RIGHT = new PointData(500, 1035);

private static final PointData RESEARCH_GO_TOP_LEFT = new PointData(480, 800);

private static final PointData RESEARCH_GO_BOTTOM_RIGHT = new PointData(670, 1040);

private static final PointData RESEARCH_ACTION_TOP_LEFT = new PointData(500, 1135);

private static final PointData RESEARCH_ACTION_BOTTOM_RIGHT = new PointData(710, 1250);

private static final SearchConfig RESEARCH_ACTION_RESILIENT = SearchConfig.builder()
        .withMaxAttempts(5)
        .withDelay(300)
        .withThreshold(90)
        .withCoordinates(RESEARCH_ACTION_TOP_LEFT, RESEARCH_ACTION_BOTTOM_RIGHT)
        .build();

private static final SearchConfig RESEARCH_ACTION_RECHECK = SearchConfig.builder()
        .withMaxAttempts(2)
        .withDelay(300)
        .withThreshold(90)
        .withCoordinates(RESEARCH_ACTION_TOP_LEFT, RESEARCH_ACTION_BOTTOM_RIGHT)
        .build();

private static final SearchConfig REPLENISH_BUTTON_RECHECK = SearchConfig.builder()
        .withMaxAttempts(2)
        .withDelay(300)
        .withThreshold(90)
        .withCoordinates(new PointData(180, 1070), new PointData(535, 1195))
        .build();

private static final OcrSettingsData RESEARCH_TITLE_OCR = OcrSettingsData.assembler()
        .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)

        .build();

private static final PointData REPLENISH_CONFIRM_POINT = new PointData(511, 1056);

private static final int COMPLETED_RESEARCH_RETRY_SECONDS = 10;

private static final int RESEARCH_TIMER_RETRY_MINUTES = 1;

private static final int INSUFFICIENT_RESOURCES_RETRY_MINUTES = 60;

public ResearchRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

@Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    protected boolean acceptsInjections() {
        // A queued home-screen action can steal the transition after the Research Center tap.
        return false;
    }

    @Override
    protected void execute() {

        var constructionReservation = ConstructionBlockerRegistry.reservationFor(
                profile, ConstructionBlockerRegistry.Consumer.RESEARCH);
        if (constructionReservation.isPresent()) {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime retryAt = constructionReservation.get().retryAt();
            if (!retryAt.isAfter(now)) {
                retryAt = now.plusMinutes(5);
            }
            logInfo(routineLogResearchLine(
                    "Research Center remains reserved until construction start is verified. Next check at "
                            + retryAt + "; training camps remain independent."));
            this.reschedule(retryAt);
            return;
        }


        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);


        marchHelper.openLeftMenuCitySection(true);


        logDebug(routineLogResearchLine("Inspecting research queue status via OCR..."));
        try {
            String queueStatus = emuManager.readText(
                    EMULATOR_NUMBER,
                    new PointData(164, 811),
                    new PointData(303, 841)).trim();

            logInfo(routineLogResearchLine("Research queue OCR status: '" + queueStatus + "'"));

            if (!queueStatus.toLowerCase().contains("idle")) {


                logInfo(routineLogResearchLine("Research queue is busy. Attempting to read remaining time..."));
                Duration busyTime = durationHelper.attemptRecognition(
                        new PointData(164, 811),
                        new PointData(303, 841),
                        5,
                        300,
                        null,
                        GameTimeUtils::isAcceptedFormat,
                        GameTimeUtils::parseDuration);

                if (busyTime != null) {
                    Duration recheckDelay = ResearchTimerPolicy.recheckDelay(busyTime);
                    LocalDateTime rescheduleTime = LocalDateTime.now().plus(recheckDelay);
                    logInfo(routineLogResearchLine("Research busy for "
                            + formatDuration(busyTime)
                            + ". Planning next run at half time in "
                            + formatDuration(recheckDelay) + "."));
                    this.reschedule(rescheduleTime);
                } else {
                    logWarning(routineLogResearchLine("Could not read research queue time. Planning next run in 1 hour."));
                    this.reschedule(LocalDateTime.now().plusHours(1));
                }
                return;
            }
        } catch (IOException | OcrException | RuntimeException e) {
            logError(routineLogResearchLine("Issue while research status OCR: " + e.getMessage()));
            this.reschedule(LocalDateTime.now().plusHours(1));
            return;
        }

        logInfo(routineLogResearchLine("Research queue is Idle. Proceeding..."));


        if (!openResearchTree()) {
            logWarning(routineLogResearchLine("Research tree did not open after "
                    + RESEARCH_ENTRY_ATTEMPTS + " attempts. Planning next run in "
                    + RESEARCH_ENTRY_RETRY_MINUTES + " minutes."));
            this.reschedule(LocalDateTime.now().plusMinutes(RESEARCH_ENTRY_RETRY_MINUTES));
            return;
        }


        ImageSearchResultData researchTextResult = findResearchInPriorityCategories();
        if (researchTextResult == null) {
            logWarning(routineLogResearchLine("No enabled category had an available research. Planning next run in 1 hour."));
            this.reschedule(LocalDateTime.now().plusHours(1));
            return;
        }

        logInfo(routineLogResearchLine("Research text template detected."));


                tapInside(researchTextResult);
                sleepTask(500);


                if (!replenishResourcesAndRetryStart(researchTextResult.getPoint())) {
                    return;
                }


                if (!ensureResearchIsRunningAfterHelp()) {
                    return;
                }


                logInfo(routineLogResearchLine("Reading research time via OCR..."));
                Duration researchTime = durationHelper.attemptRecognition(
                        new PointData(226, 1194),
                        new PointData(422, 1234),
                        5,
                        300,
                        null,
                        GameTimeUtils::isAcceptedFormat,
                        GameTimeUtils::parseDuration);


                if (researchTime != null) {
                    Duration recheckDelay = ResearchTimerPolicy.recheckDelay(researchTime);
                    LocalDateTime rescheduleTime = LocalDateTime.now().plus(recheckDelay);

                    logInfo(routineLogResearchLine("Research time is "
                            + formatDuration(researchTime)
                            + ". Planning next run at half time in "
                            + formatDuration(recheckDelay) + "."));
                    logInfo(routineLogResearchLine("Research task completed. Planning next run for: " + rescheduleTime));
                    this.reschedule(rescheduleTime);
                    return;
                } else {
                    logWarning(routineLogResearchLine("Could not OCR running research time. Retrying in "
                            + RESEARCH_TIMER_RETRY_MINUTES + " minute."));
                }

        this.reschedule(LocalDateTime.now().plusMinutes(RESEARCH_TIMER_RETRY_MINUTES));
    }

private boolean replenishResourcesAndRetryStart(PointData researchButton) {
        ImageSearchResultData replenishAllButton = templateSearchHelper.locatePattern(
                TemplatesEnum.REPLENISH_ALL_BUTTON, REPLENISH_BUTTON_RECHECK);
        if (!isFound(replenishAllButton)) {
            return true;
        }

        // An unaffordable research opens the shared "Obtain more" screen instead
        // of Help/Speedup. Use owned resource items, confirm, then retry Research.
        logInfo(routineLogResearchLine("Insufficient resources detected. Replenishing and retrying start."));
        tapInside(replenishAllButton);
        sleepTask(500);
        tapNear(REPLENISH_CONFIRM_POINT);
        sleepTask(1000);
        tapNear(researchButton);
        sleepTask(800);

        replenishAllButton = templateSearchHelper.locatePattern(
                TemplatesEnum.REPLENISH_ALL_BUTTON, REPLENISH_BUTTON_RECHECK);
        if (!isFound(replenishAllButton)) {
            return true;
        }

        logWarning(routineLogResearchLine("Still insufficient after replenish. Rechecking in "
                + INSUFFICIENT_RESOURCES_RETRY_MINUTES + " min."));
        pressBack();
        this.reschedule(LocalDateTime.now().plusMinutes(INSUFFICIENT_RESOURCES_RETRY_MINUTES));
        return false;
    }

private boolean ensureResearchIsRunningAfterHelp() {
        ImageSearchResultData helpButton = templateSearchHelper.locatePattern(
                TemplatesEnum.RESEARCH_HELP_BUTTON, RESEARCH_ACTION_RESILIENT);

        if (isFound(helpButton)) {
            logInfo(routineLogResearchLine("Research Help button detected. Pressing it."));
            tapInside(helpButton);
            sleepTask(300);
        } else {
            ImageSearchResultData speedupButton = templateSearchHelper.locatePattern(
                    TemplatesEnum.RESEARCH_SPEEDUP_BUTTON, RESEARCH_ACTION_RECHECK);
            if (isFound(speedupButton)) {
                logInfo(routineLogResearchLine("Research is already running; Speedup button detected."));
                return true;
            }

            logWarning(routineLogResearchLine(
                    "Neither Help nor Speedup button appeared after starting research. Retrying shortly."));
            rescheduleShortResearchRetry();
            return false;
        }

        ImageSearchResultData speedupButton = templateSearchHelper.locatePattern(
                TemplatesEnum.RESEARCH_SPEEDUP_BUTTON, RESEARCH_ACTION_RESILIENT);
        if (isFound(speedupButton)) {
            logInfo(routineLogResearchLine("Speedup button detected after Help; research is still running."));
            return true;
        }

        // A delayed or missed Help tap leaves the same button visible. Retry once before
        // treating the vanished bottom panel as an alliance-help instant completion.
        ImageSearchResultData remainingHelpButton = templateSearchHelper.locatePattern(
                TemplatesEnum.RESEARCH_HELP_BUTTON, RESEARCH_ACTION_RECHECK);
        if (isFound(remainingHelpButton)) {
            logInfo(routineLogResearchLine("Help button remained visible. Retrying the tap once."));
            tapInside(remainingHelpButton);
            sleepTask(300);

            speedupButton = templateSearchHelper.locatePattern(
                    TemplatesEnum.RESEARCH_SPEEDUP_BUTTON, RESEARCH_ACTION_RESILIENT);
            if (isFound(speedupButton)) {
                logInfo(routineLogResearchLine("Speedup button detected after Help retry; research is still running."));
                return true;
            }

            remainingHelpButton = templateSearchHelper.locatePattern(
                    TemplatesEnum.RESEARCH_HELP_BUTTON, RESEARCH_ACTION_RECHECK);
            if (isFound(remainingHelpButton)) {
                logWarning(routineLogResearchLine("Help button still visible after retry. Retrying research shortly."));
                rescheduleShortResearchRetry();
                return false;
            }
        }

        logInfo(routineLogResearchLine(
                "Neither Help nor Speedup remains; alliance help completed the research instantly."));
        rescheduleShortResearchRetry();
        return false;
    }

private boolean isFound(ImageSearchResultData result) {
        return result != null && result.isFound();
    }

private void rescheduleShortResearchRetry() {
        LocalDateTime retryAt = LocalDateTime.now().plusSeconds(COMPLETED_RESEARCH_RETRY_SECONDS);
        logInfo(routineLogResearchLine("Planning next research check for: " + retryAt));
        this.reschedule(retryAt);
    }

private static String formatDuration(Duration duration) {
        return String.format("%02d:%02d:%02d",
                duration.toHours(), duration.toMinutesPart(), duration.toSecondsPart());
    }

private boolean openResearchTree() {
        for (int attempt = 1; attempt <= RESEARCH_ENTRY_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                pressBack();
                sleepTask(500);
                navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
            }

            if (!navigationHelper.navigateToSidebarDestination(SidebarDestination.RESEARCH_CENTER)) {
                logWarning(routineLogResearchLine("Research Center destination was not reached during entry attempt "
                        + attempt + "/" + RESEARCH_ENTRY_ATTEMPTS + "."));
                continue;
            }

            ImageSearchResultData researchButton = templateSearchHelper.locatePattern(
                    TemplatesEnum.BUILDING_BUTTON_RESEARCH,
                    SearchConfigConstants.DEFAULT_SINGLE);
            if (isFound(researchButton)) {
                logInfo(routineLogResearchLine("Research building button detected. Entering Research tree."));
                tapInside(researchButton);
            } else {
                PointData handTarget = findResearchEntryHandTarget();
                if (handTarget == null) {
                    logWarning(routineLogResearchLine("Neither Research building button nor entry hand was detected "
                            + "on attempt " + attempt + "/" + RESEARCH_ENTRY_ATTEMPTS + "."));
                    continue;
                }

                logInfo(routineLogResearchLine(
                        "Research entry hand detected over the button. Entering at its relative target."));
                tapNear(handTarget);
            }
            sleepTask(500);

            if (isResearchTreeVisible()) {
                logInfo(routineLogResearchLine("Research tree screen verified."));
                return true;
            }

            logWarning(routineLogResearchLine("Research tree title was not detected on attempt "
                    + attempt + "/" + RESEARCH_ENTRY_ATTEMPTS + "."));
        }
        return false;
    }

private boolean isResearchTreeVisible() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String title = emuManager.readText(
                        EMULATOR_NUMBER,
                        RESEARCH_TITLE_TOP_LEFT,
                        RESEARCH_TITLE_BOTTOM_RIGHT,
                        RESEARCH_TITLE_OCR).trim();
                logDebug(routineLogResearchLine("Research tree title OCR: '" + title + "'."));
                if (title.toLowerCase().contains("research")) {
                    return true;
                }
            } catch (IOException | OcrException | RuntimeException e) {
                logDebug(routineLogResearchLine("Research tree title OCR attempt "
                        + attempt + " failed: " + e.getMessage()));
            }
            if (attempt < 3) {
                sleepTask(300);
            }
        }
        return false;
    }

private PointData findResearchEntryHandTarget() {
        RawImageData screenshot = emuManager.captureScreen(EMULATOR_NUMBER);
        if (screenshot == null) {
            return null;
        }

        ImageSearchResultData hand = emuManager.locatePattern(EMULATOR_NUMBER, screenshot,
                TemplatesEnum.SKIP_TUTORIAL_HAND, 80.0);
        if (isFound(hand)) {
            PointData point = hand.getPoint();
            return new PointData(point.getX() + HAND_CLICK_OFFSET_X_VALUE,
                    point.getY() + HAND_CLICK_OFFSET_Y_VALUE);
        }

        ImageSearchResultData mirror = emuManager.locatePattern(EMULATOR_NUMBER, screenshot,
                TemplatesEnum.SKIP_TUTORIAL_HAND_MIRROR, 80.0);
        if (isFound(mirror)) {
            PointData point = mirror.getPoint();
            return new PointData(point.getX() - HAND_CLICK_OFFSET_X_VALUE,
                    point.getY() + HAND_CLICK_OFFSET_Y_VALUE);
        }
        return null;
    }

private ImageSearchResultData findResearchInPriorityCategories() {
        List<PriorityItemData> priorities = PriorityConfigResolver.activeRankings(
                profile, ConfigurationKeyEnum.RESEARCH_PRIORITIES_STRING);
        if (priorities.isEmpty()) {
            logWarning(routineLogResearchLine("No research categories enabled."));
            return null;
        }

        for (PriorityItemData priority : priorities) {
            ResearchCategoryEnum category = ResearchCategoryEnum.fromKey(priority.getIdentifier());
            if (category == null) {
                logWarning(routineLogResearchLine("Ignoring unknown research category priority: " + priority.getIdentifier()));
                continue;
            }

            logInfo(routineLogResearchLine("Trying category '" + category.label()
                    + "' (priority " + priority.getPriority() + ")."));
            tapCategoryTab(category);
            sleepTask(500);

            ImageSearchResultData candidate = findStartableResearchNode();
            if (!isFound(candidate)) {
                logInfo(routineLogResearchLine("No available tech node in '" + category.label()
                        + "'. Trying next category."));
                continue;
            }

            logInfo(routineLogResearchLine("'" + category.label() + "' has an available research."));
            return candidate;
        }
        return null;
    }

private void tapCategoryTab(ResearchCategoryEnum category) {
        switch (category) {
            case GROWTH -> tapInside(new PointData(58, 88), new PointData(211, 137));
            case ECONOMY -> tapInside(new PointData(274, 84), new PointData(445, 142));
            case BATTLE -> tapInside(new PointData(499, 99), new PointData(671, 139));
        }
    }

private ImageSearchResultData findStartableResearchNode() {
        logDebug(routineLogResearchLine("Normalizing research menu with swipes..."));
        for (int i = 0; i < 3; i++) {
            swipe(new PointData(489, 500), new PointData(489, 850), RESEARCH_DRAG_DURATION_MILLIS);
            sleepTask(500);
        }

        boolean topRowRepositioned = false;
        for (int scrollAttempt = 0; scrollAttempt < MAX_SCROLL_ATTEMPTS_LIMIT; scrollAttempt++) {
            checkPreemption();
            sleepTask(RESEARCH_SCROLL_SETTLE_MILLIS);

            RawImageData researchScreenshot = emuManager.captureScreen(EMULATOR_NUMBER);
            if (researchScreenshot == null) {
                logWarning(routineLogResearchLine("Could not capture screenshot for research template search."));
                continue;
            }

            List<ResearchRow> rows = ResearchNodeSelectionPolicy.rows(
                    detectResearchNodes(researchScreenshot));
            if (!rows.isEmpty()) {
                logInfo(routineLogResearchLine("Detected " + rows.size()
                        + " visible incomplete research row(s)."));
            }
            if (!rows.isEmpty() && rows.get(0).candidates().get(0).tapTarget().getY() < RESEARCH_SAFE_TAP_Y) {
                if (topRowRepositioned) {
                    logWarning(routineLogResearchLine(
                            "Top incomplete row remains hidden behind the category header; refusing an unsafe tap."));
                    return null;
                }
                logInfo(routineLogResearchLine(
                        "Top incomplete row is partially hidden; repositioning it below the category header."));
                swipe(new PointData(489, 430), new PointData(489, 650), RESEARCH_DRAG_DURATION_MILLIS);
                topRowRepositioned = true;
                continue;
            }
            topRowRepositioned = false;

            for (ResearchRow row : rows) {
                boolean centerCapped = false;
                for (ResearchNode node : row.candidates()) {
                    PointData tap = node.tapTarget();
                    logInfo(routineLogResearchLine("Trying row candidate " + node.currentLevel() + "/"
                            + node.maximumLevel() + " at badge (" + node.badgePoint().getX() + ", "
                            + node.badgePoint().getY() + "), tap (" + tap.getX() + ", " + tap.getY() + ")."));
                    tapNear(tap);
                    sleepTask(1000);

                    ResearchDialogInspection inspection = inspectResearchDialog();
                    logInfo(routineLogResearchLine("Candidate result: " + inspection.state()
                            + ", goButtons=" + inspection.goButtonCount()
                            + ", requirements='" + inspection.requirementText() + "'."));
                    if (inspection.state() == ResearchDialogState.STARTABLE) {
                        return inspection.researchButton();
                    }

                    pressBack();
                    sleepTask(500);

                    if (inspection.state() == ResearchDialogState.CENTER_CAPPED) {
                        centerCapped = true;
                        break;
                    }
                    if (inspection.state() == ResearchDialogState.UNKNOWN) {
                        logWarning(routineLogResearchLine(
                                "Research detail state was unknown; refusing to infer availability below it."));
                        return null;
                    }
                }

                if (!centerCapped) {
                    logInfo(routineLogResearchLine(
                            "No candidate in the current row had satisfied prerequisites; not skipping the row."));
                    return null;
                }
                if (row.minimumLevel() == 0) {
                    logInfo(routineLogResearchLine(
                            "The 0/x frontier is Research-Center-capped; deeper rows cannot be available."));
                    return null;
                }
            }

            logDebug(routineLogResearchLine("No startable research in the visible frontier, scrolling down (attempt "
                    + (scrollAttempt + 1) + "/" + MAX_SCROLL_ATTEMPTS_LIMIT + ")"));
            swipe(new PointData(489, 760), new PointData(489, 500), RESEARCH_DRAG_DURATION_MILLIS);
        }
        return null;
    }

private List<ResearchNode> detectResearchNodes(RawImageData screenshot) {
        List<ResearchNode> nodes = new ArrayList<>();
        try {
            List<ResearchBadgeData> badges = ResearchBadgeReader.read(
                    screenshot, RESEARCH_TREE_TOP_LEFT, RESEARCH_TREE_BOTTOM_RIGHT);
            for (ResearchBadgeData badge : badges) {
                PointData point = badge.center();
                nodes.add(new ResearchNode(badge.currentLevel(), badge.maximumLevel(), point));
                logInfo(routineLogResearchLine("Pattern detected research badge "
                        + badge.currentLevel() + "/" + badge.maximumLevel() + " at ("
                        + point.getX() + ", " + point.getY() + "), confidence="
                        + String.format("%.1f", badge.confidence()) + "."));
            }
        } catch (RuntimeException exception) {
            logWarning(routineLogResearchLine(
                    "Research badge pattern classification failed: " + exception.getMessage()));
        }
        return nodes;
    }

private ResearchDialogInspection inspectResearchDialog() {
        RawImageData screenshot = emuManager.captureScreen(EMULATOR_NUMBER);
        if (screenshot == null) {
            return new ResearchDialogInspection(ResearchDialogState.UNKNOWN, null, 0, "");
        }

        ImageSearchResultData researchButton = emuManager.locatePattern(
                EMULATOR_NUMBER, screenshot, TemplatesEnum.RESEARCH_TEXT, 80.0);
        int goButtonCount = emuManager.locateAllPatterns(
                EMULATOR_NUMBER, screenshot, TemplatesEnum.GAME_HOME_SHORTCUTS_GO,
                RESEARCH_GO_TOP_LEFT, RESEARCH_GO_BOTTOM_RIGHT, 85.0, 4).size();
        String requirements = "";
        try {
            requirements = OcrEngine.recognizeText(
                    screenshot, RESEARCH_REQUIREMENTS_TOP_LEFT, RESEARCH_REQUIREMENTS_BOTTOM_RIGHT,
                    OcrSettingsData.forTextBlock());
        } catch (OcrException | RuntimeException exception) {
            logWarning(routineLogResearchLine("Research requirement OCR failed: " + exception.getMessage()));
        }
        ResearchDialogState state = ResearchDialogClassifier.classify(
                isFound(researchButton), goButtonCount, requirements);
        return new ResearchDialogInspection(state, researchButton, goButtonCount, requirements);
    }

private record ResearchDialogInspection(ResearchDialogState state,
                                        ImageSearchResultData researchButton,
                                        int goButtonCount,
                                        String requirementText) {}

private String routineLogResearchLine(String note) {
        return "ResearchRoutine | " + note;
    }
}
