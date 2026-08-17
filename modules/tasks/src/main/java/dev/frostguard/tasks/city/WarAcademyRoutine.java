package dev.frostguard.tasks.city;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.RegexNumberParser;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

public class WarAcademyRoutine extends DelayedTask {

private static final PointData LEFT_MENU_SWIPE_START_VALUE = new PointData(255, 477);

private static final PointData LEFT_MENU_SWIPE_END_VALUE = new PointData(255, 425);

private static final PointData BUILDING_TAP_CENTER_VALUE = new PointData(360, 790);

private static final PointData REDEEM_TAB_BUTTON_VALUE = new PointData(642, 164);

private static final PointData SHARDS_OCR_TOP_LEFT_VALUE = new PointData(466, 456);

private static final PointData SHARDS_OCR_BOTTOM_RIGHT_VALUE = new PointData(624, 484);

private static final PointData REDEEM_BUTTON_VALUE = new PointData(545, 520);

private static final PointData MAX_SHARDS_BUTTON_LIMIT = new PointData(614, 705);

private static final PointData CONFIRM_BUTTON_VALUE = new PointData(358, 828);

private static final int MIN_RESEARCH_CENTERS_REQUIRED_FLOOR = 2;

private static final int BUILDING_TAP_COUNT_VALUE = 5;

private static final int BUILDING_TAP_DELAY_MS = 100;

private static final int RETRY_DELAY_MINUTES_MS = 5;

private static final int ADDITIONAL_SHARDS_DELAY_HOURS_MS = 2;

private static final OcrSettingsData SHARDS_OCR_SETTINGS_VALUE = OcrSettingsData.assembler()
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)

            .charWhitelist("0123456789")
            .build();

public WarAcademyRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

@Override
    protected void execute() {

        if (!reachWarAcademy()) {
            logWarning(routineLogWarAcademyLine("Could not navigate to War Academy."));
            reschedule(LocalDateTime.now().plusMinutes(RETRY_DELAY_MINUTES_MS));
            return;
        }

        if (!openUpRedeemSection()) {
            logWarning(routineLogWarAcademyLine("Could not open Redeem section."));
            reschedule(LocalDateTime.now().plusMinutes(RETRY_DELAY_MINUTES_MS));
            return;
        }

        Integer remainingShards = scanRemainingShards();

        if (remainingShards == null) {
            logError(routineLogWarAcademyLine("Could not read remaining shards count."));
            reschedule(LocalDateTime.now().plusMinutes(RETRY_DELAY_MINUTES_MS));
            return;
        }

        if (remainingShards <= 0) {
            logInfo(routineLogWarAcademyLine("Zero shards available to redeem."));
            reschedule(GameTimeUtils.dailyResetTime());
            return;
        }

        logInfo(routineLogWarAcademyLine(String.format("Detected %d shards to redeem.", remainingShards)));

        if (!redeemMaximumShardsFlow()) {
            logWarning(routineLogWarAcademyLine("Could not redeem shards."));
            reschedule(LocalDateTime.now().plusMinutes(RETRY_DELAY_MINUTES_MS));
            return;
        }

        managePostRedemptionCheck();
    }

@Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

@Override
    public boolean provideDailyMissionProgress() {
        return false;
    }

private void managePostRedemptionCheck() {
        logInfo(routineLogWarAcademyLine("Inspecting for additional shards after redemption."));

        Integer finalShards = scanRemainingShards();

        if (finalShards == null) {
            logError(routineLogWarAcademyLine("Could not read final shards count."));
            reschedule(LocalDateTime.now().plusMinutes(RETRY_DELAY_MINUTES_MS));
            return;
        }

        if (finalShards > 0) {
            logInfo(routineLogWarAcademyLine(String.format("Additional shards detected: %d. Planning next run in %d hours.",
                    finalShards, ADDITIONAL_SHARDS_DELAY_HOURS_MS)));
            reschedule(LocalDateTime.now().plusHours(ADDITIONAL_SHARDS_DELAY_HOURS_MS));
        } else {
            logInfo(routineLogWarAcademyLine("Zero additional shards detected. Planning next run for game reset."));
            reschedule(GameTimeUtils.dailyResetTime());
        }
    }

private String routineLogWarAcademyLine(String note) {
        return "WarAcademyRoutine | " + note;
    }

private boolean redeemMaximumShardsFlow() {
        logInfo(routineLogWarAcademyLine("Redeeming maximum available shards."));


        logDebug(routineLogWarAcademyLine("Pressing Redeem button"));
        tapNear(REDEEM_BUTTON_VALUE);
        sleepTask(500);


        logDebug(routineLogWarAcademyLine("Selecting maximum shards"));
        tapNear(MAX_SHARDS_BUTTON_LIMIT);
        sleepTask(100);


        logDebug(routineLogWarAcademyLine("Confirming redemption"));
        tapNear(CONFIRM_BUTTON_VALUE);
        sleepTask(1000);


        logInfo(routineLogWarAcademyLine("Shards redeemed finished cleanly."));
        return true;
    }

private Integer scanRemainingShards() {
        logInfo(routineLogWarAcademyLine("Reading remaining shards count via OCR."));

        Integer shards = integerHelper.attemptRecognition(
                SHARDS_OCR_TOP_LEFT_VALUE,
                SHARDS_OCR_BOTTOM_RIGHT_VALUE,
                5,
                200L,
                SHARDS_OCR_SETTINGS_VALUE,
                text -> RegexNumberParser.conformsTo(text, Pattern.compile(".*?(\\d+).*")),
                text -> RegexNumberParser.extractByPattern(text, Pattern.compile(".*?(\\d+).*")));

        if (shards != null) {
            logInfo(routineLogWarAcademyLine("Successfully read shards count: " + shards));
        } else {
            logWarning(routineLogWarAcademyLine("Could not read shards count via OCR."));
        }

        return shards;
    }

private boolean openUpRedeemSection() {
        logDebug(routineLogWarAcademyLine("Entering Redeem section"));

        tapNear(REDEEM_TAB_BUTTON_VALUE);
        sleepTask(500);


        return true;
    }

private boolean reachWarAcademy() {
        logInfo(routineLogWarAcademyLine("Moving to War Academy."));

        marchHelper.openLeftMenuCitySection(true);


        logDebug(routineLogWarAcademyLine("Swiping to reveal Research Centers"));
        swipe(LEFT_MENU_SWIPE_START_VALUE, LEFT_MENU_SWIPE_END_VALUE);
        sleepTask(500);


        List<ImageSearchResultData> researchCenters = templateSearchHelper.locateAllPatterns(
                TemplatesEnum.GAME_HOME_SHORTCUTS_RESEARCH_CENTER,
                SearchConfigConstants.MULTIPLE_RESULTS);

        if (researchCenters.size() < MIN_RESEARCH_CENTERS_REQUIRED_FLOOR) {
            logError(routineLogWarAcademyLine(String.format("Only detected %d Research Centers, need at least %d.",
                    researchCenters.size(), MIN_RESEARCH_CENTERS_REQUIRED_FLOOR)));
            return false;
        }

        logInfo(routineLogWarAcademyLine(String.format("Detected %d Research Centers.", researchCenters.size())));


        ImageSearchResultData warAcademyCenter = researchCenters.stream()
                .max(Comparator.comparingInt(r -> r.getPoint().getY()))
                .orElseThrow(() -> new RuntimeException("No valid Research Center found"));

        logDebug(routineLogWarAcademyLine("Pressing War Academy (bottommost Research Center)"));
        tapInside(warAcademyCenter);
        sleepTask(1000);


        logDebug(routineLogWarAcademyLine("Pressing building center to enter"));
        tapInside(BUILDING_TAP_CENTER_VALUE, BUILDING_TAP_CENTER_VALUE, BUILDING_TAP_COUNT_VALUE, BUILDING_TAP_DELAY_MS);
        sleepTask(1000);


        ImageSearchResultData researchButton = templateSearchHelper.locatePattern(
                TemplatesEnum.BUILDING_BUTTON_RESEARCH,
                SearchConfigConstants.DEFAULT_SINGLE);

        if (!researchButton.isFound()) {
            logError(routineLogWarAcademyLine("Research button not detected."));
            return false;
        }

        logDebug(routineLogWarAcademyLine("Entering Research section"));
        tapInside(researchButton);
        sleepTask(500);


        ImageSearchResultData warAcademyUI = templateSearchHelper.locatePattern(
                TemplatesEnum.VALIDATION_WAR_ACADEMY_UI,
                SearchConfigConstants.DEFAULT_SINGLE);

        if (!warAcademyUI.isFound()) {
            logError(routineLogWarAcademyLine("War Academy UI validation did not complete."));
            return false;
        }

        logInfo(routineLogWarAcademyLine("Successfully navigated to War Academy."));
        return true;
    }
}
