package dev.frostguard.tasks.city;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.vision.convert.GameTimeUtils;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.*;
import static dev.frostguard.api.configs.TemplatesEnum.*;

public class CrystalLaboratoryRoutine extends DelayedTask {

private static final int MAX_CONSECUTIVE_FAILED_CLAIMS_LIMIT = 3;

private static final int MAX_OCR_RETRIES_LIMIT = 5;

private static final int NAVIGATION_RETRY_MINUTES_VALUE = 5;

private static final int INSUFFICIENT_FC_RETRY_HOURS_VALUE = 2;

private static final int RFC_COST_TIER_1_VALUE = 20;

private static final int RFC_COST_TIER_2_VALUE = 50;

private static final int RFC_COST_TIER_3_VALUE = 100;

private static final int RFC_COST_TIER_4_VALUE = 130;

private static final int RFC_COST_TIER_5_VALUE = 160;

private static final int RFC_TIER_1_MAX_VALUE = 20;

private static final int RFC_TIER_2_MAX_VALUE = 40;

private static final int RFC_TIER_3_MAX_VALUE = 60;

private static final int RFC_TIER_4_MAX_VALUE = 80;

private static final int RFC_TIER_5_MAX_VALUE = 100;

private static final PointData CURRENT_FC_TOP_LEFT_VALUE = new PointData(590, 21);

private static final PointData CURRENT_FC_BOTTOM_RIGHT_VALUE = new PointData(700, 60);

private static final PointData CURRENT_RFC_TOP_LEFT_VALUE = new PointData(170, 1078);

private static final PointData CURRENT_RFC_BOTTOM_RIGHT_VALUE = new PointData(512, 1106);

private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d{1,3}(?:[.,]\\d{3})*|\\d+)");

private boolean useDiscountedDailyRFC;

private int weeklyRFCTarget;

public CrystalLaboratoryRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

protected void loadConfiguration() {
        this.useDiscountedDailyRFC = profile.getConfig(
                BOOL_CRYSTAL_LAB_DAILY_DISCOUNTED_RFC,
                Boolean.class);

        this.weeklyRFCTarget = profile.getConfig(INT_WEEKLY_RFC, Integer.class);

        logInfo(routineLogCrystalLaboratoryLine(String.format("Configuration loaded - Discounted RFC: %s, Weekly target: %d",
                useDiscountedDailyRFC, weeklyRFCTarget)));
    }

@Override
    protected void execute() {

        loadConfiguration();

        if (!reachCrystalLaboratory()) {
            reschedule(LocalDateTime.now().plusMinutes(NAVIGATION_RETRY_MINUTES_VALUE));
            return;
        }

        redeemAllCrystals();

        if (useDiscountedDailyRFC) {
            purchaseDiscountedRFCFlow();

            if (hasMonday()) {
                WeeklyRFCResultShape result = handleWeeklyRFC();

                if (result == WeeklyRFCResultShape.INSUFFICIENT_FC) {


                    reschedule(LocalDateTime.now().plusHours(INSUFFICIENT_FC_RETRY_HOURS_VALUE));
                    return;
                }
            }
        }


        reschedule(GameTimeUtils.dailyResetTime());
    }

@Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

private enum WeeklyRFCResultShape {
        REFINEMENTS_DONE,
        TARGET_REACHED,
        INSUFFICIENT_FC,
        OCR_FAILED
    }

private String routineLogCrystalLaboratoryLine(String note) {
        return "CrystalLaboratoryRoutine | " + note;
    }

private void performDiscountedRFCPurchase() {
        ImageSearchResultData refineResult = templateSearchHelper.locatePattern(
                CRYSTAL_LAB_RFC_REFINE_BUTTON,
                SearchConfig.builder().build());

        if (refineResult.isFound()) {
            tapInside(refineResult);
            sleepTask(500);

            logInfo(routineLogCrystalLaboratoryLine("Discounted RFC purchased finished cleanly."));
        } else {
            logWarning(routineLogCrystalLaboratoryLine("Could not find RFC refine button for discounted purchase."));
        }
    }

private void performBulkRefinementsFlow(int currentRFC) {
        int refinesToDo = weeklyRFCTarget - currentRFC;

        logInfo(routineLogCrystalLaboratoryLine(String.format("Sufficient FC available. Performing %d refinements.", refinesToDo)));

        ImageSearchResultData refineResult = templateSearchHelper.locatePattern(
                CRYSTAL_LAB_RFC_REFINE_BUTTON,
                SearchConfig.builder().build());

        if (refineResult.isFound()) {
            tapInside(refineResult.getPoint(), refineResult.getPoint(), refinesToDo, 500);
            logInfo(routineLogCrystalLaboratoryLine("Bulk refinements completed."));
        } else {
            logWarning(routineLogCrystalLaboratoryLine("Could not find RFC refine button for bulk refinements."));
        }
    }

private void purchaseDiscountedRFCFlow() {
        ImageSearchResultData discountedResult = templateSearchHelper.locatePattern(
                CRYSTAL_LAB_DAILY_DISCOUNTED_RFC,
                SearchConfig.builder().build());

        if (!discountedResult.isFound()) {
            logInfo(routineLogCrystalLaboratoryLine("Zero discounted RFC available today."));
            return;
        }

        logInfo(routineLogCrystalLaboratoryLine("50% discounted RFC available. Attempting to purchase."));
        performDiscountedRFCPurchase();
    }

private boolean openUpCrystalLabInterface() {
        tapInside(new PointData(637, 903), new PointData(692, 914), 1, 500);

        ImageSearchResultData validationResult = templateSearchHelper.locatePattern(
                VALIDATION_CRYSTAL_LAB_UI,
                SearchConfig.builder().build());

        if (!validationResult.isFound()) {
            logWarning(routineLogCrystalLaboratoryLine("Crystal Lab UI not detected. Retrying in 5min."));
            return false;
        }
        logInfo(routineLogCrystalLaboratoryLine("Successfully navigated to Crystal Laboratory"));
        return true;
    }

private boolean locateAndTapTroopsButton() {
        ImageSearchResultData troopsResult = templateSearchHelper.locatePattern(
                GAME_HOME_SHORTCUTS_LANCER,
                SearchConfig.builder().build());

        if (!troopsResult.isFound()) {
            logWarning(routineLogCrystalLaboratoryLine("Could not locate troops button. Navigation did not complete."));
            return false;
        }

        tapInside(troopsResult);
        sleepTask(1000);

        return true;
    }

private int extractNumberWithOCRFlow(PointData topLeft, PointData bottomRight, String description) {
        for (int attempt = 1; attempt <= MAX_OCR_RETRIES_LIMIT; attempt++) {
            logDebug(routineLogCrystalLaboratoryLine("Extracting " + description + " via OCR (attempt " +
                    attempt + "/" + MAX_OCR_RETRIES_LIMIT + ")"));

            try {
                String ocrResult = stringHelper.attemptRecognition(
                        topLeft,
                        bottomRight,
                        1,
                        300L,
                        null,
                        s -> !s.isEmpty(),
                        s -> s);
                Integer number = decodeNumberFromOCR(ocrResult);

                if (number != null) {
                    logInfo(routineLogCrystalLaboratoryLine(description + ": " + number));
                    return number;
                }

            } catch (Exception e) {
                logWarning(routineLogCrystalLaboratoryLine("OCR attempt " + attempt + " threw exception: " + e.getMessage()));
            }

            if (attempt < MAX_OCR_RETRIES_LIMIT) {
                sleepTask(1000);

            }
        }

        logWarning(routineLogCrystalLaboratoryLine("Could not extract " + description + " after " + MAX_OCR_RETRIES_LIMIT + " attempts"));
        return -1;
    }

private void performCrystalClaimLoop(ImageSearchResultData initialClaimResult) {
        ImageSearchResultData claimResult = initialClaimResult;
        int consecutiveFailures = 0;

        while (claimResult.isFound() && consecutiveFailures < MAX_CONSECUTIVE_FAILED_CLAIMS_LIMIT) {
            logDebug(routineLogCrystalLaboratoryLine("Collecting crystal..."));

            tapInside(claimResult.getPoint(), claimResult.getPoint());
            sleepTask(100);


            claimResult = templateSearchHelper.locatePattern(
                    CRYSTAL_LAB_REFINE_BUTTON,
                    SearchConfig.builder().build());

            if (!claimResult.isFound()) {
                consecutiveFailures++;
                logDebug(routineLogCrystalLaboratoryLine("Claim button not detected. Consecutive failures: " +
                        consecutiveFailures + "/" + MAX_CONSECUTIVE_FAILED_CLAIMS_LIMIT));
            } else {
                consecutiveFailures = 0;

            }
        }

        logClaimLoopResultFlow(consecutiveFailures);
    }

private boolean reachCrystalLaboratory() {
        logInfo(routineLogCrystalLaboratoryLine("Moving to Crystal Laboratory"));

        marchHelper.openLeftMenuCitySection(true);

        if (!locateAndTapTroopsButton()) {
            return false;
        }

        return openUpCrystalLabInterface();
    }

private void logClaimLoopResultFlow(int consecutiveFailures) {
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILED_CLAIMS_LIMIT) {
            logInfo(routineLogCrystalLaboratoryLine("Crystal collecting completed - no more claims available."));
        } else {
            logInfo(routineLogCrystalLaboratoryLine("Crystal collecting process finished finished cleanly."));
        }
    }

private int resolveRefinementCost(int refineLevel) {
        if (refineLevel <= RFC_TIER_1_MAX_VALUE) {
            return RFC_COST_TIER_1_VALUE;
        } else if (refineLevel <= RFC_TIER_2_MAX_VALUE) {
            return RFC_COST_TIER_2_VALUE;
        } else if (refineLevel <= RFC_TIER_3_MAX_VALUE) {
            return RFC_COST_TIER_3_VALUE;
        } else if (refineLevel <= RFC_TIER_4_MAX_VALUE) {
            return RFC_COST_TIER_4_VALUE;
        } else if (refineLevel <= RFC_TIER_5_MAX_VALUE) {
            return RFC_COST_TIER_5_VALUE;
        }


        return RFC_COST_TIER_5_VALUE;
    }

private Integer extractCurrentRFCFlow() {
        int rfc = extractNumberWithOCRFlow(
                CURRENT_RFC_TOP_LEFT_VALUE,
                CURRENT_RFC_BOTTOM_RIGHT_VALUE,
                "current refined FC");

        return rfc == -1 ? null : rfc;
    }

private WeeklyRFCResultShape handleRFCRefinements(int currentFC, int currentRFC) {
        if (currentRFC >= weeklyRFCTarget) {
            logInfo(routineLogCrystalLaboratoryLine(String.format("Weekly target (%d) already reached. Current: %d",
                    weeklyRFCTarget, currentRFC)));
            return WeeklyRFCResultShape.TARGET_REACHED;
        }

        int neededFC = computeFCNeeded(currentRFC, weeklyRFCTarget);

        logInfo(routineLogCrystalLaboratoryLine(String.format("FC Analysis - Available: %d, Current RFC: %d, Target: %d, Needed: %d",
                currentFC, currentRFC, weeklyRFCTarget, neededFC)));

        if (neededFC > currentFC) {
            logInfo(routineLogCrystalLaboratoryLine(String.format("Insufficient FC. Need %d more FC to reach target.",
                    neededFC - currentFC)));
            return WeeklyRFCResultShape.INSUFFICIENT_FC;
        }

        performBulkRefinementsFlow(currentRFC);
        return WeeklyRFCResultShape.REFINEMENTS_DONE;
    }

private Integer extractCurrentFCFlow() {
        int fc = extractNumberWithOCRFlow(
                CURRENT_FC_TOP_LEFT_VALUE,
                CURRENT_FC_BOTTOM_RIGHT_VALUE,
                "current FC");

        return fc == -1 ? null : fc;
    }

private Integer decodeNumberFromOCR(String ocrText) {
        Matcher matcher = NUMBER_PATTERN.matcher(ocrText);

        if (!matcher.find()) {
            return null;
        }

        try {
            String normalized = matcher.group(1).replaceAll("[.,]", "");
            return Integer.parseInt(normalized);
        } catch (NumberFormatException e) {
            logWarning(routineLogCrystalLaboratoryLine("Could not parse number from: " + ocrText));
            return null;
        }
    }

private void redeemAllCrystals() {
        ImageSearchResultData claimResult = templateSearchHelper.locatePattern(
                CRYSTAL_LAB_REFINE_BUTTON,
                SearchConfig.builder().build());

        if (!claimResult.isFound()) {
            logInfo(routineLogCrystalLaboratoryLine("Zero crystals available to claim."));
            return;
        }

        logInfo(routineLogCrystalLaboratoryLine("Initiating crystal collecting process"));
        performCrystalClaimLoop(claimResult);
    }

private WeeklyRFCResultShape handleWeeklyRFC() {
        logInfo(routineLogCrystalLaboratoryLine("Processing weekly RFC refinements (Monday check)"));

        Integer currentFC = extractCurrentFCFlow();
        if (currentFC == null) {
            return WeeklyRFCResultShape.OCR_FAILED;
        }

        Integer currentRFC = extractCurrentRFCFlow();
        if (currentRFC == null) {
            return WeeklyRFCResultShape.OCR_FAILED;
        }

        return handleRFCRefinements(currentFC, currentRFC);
    }

private int computeFCNeeded(int currentLevel, int targetLevel) {
        int totalFC = 0;

        for (int refine = currentLevel + 1; refine <= targetLevel; refine++) {
            totalFC += resolveRefinementCost(refine);
        }

        return totalFC;
    }

private boolean hasMonday() {
        return LocalDateTime.now(Clock.systemUTC()).getDayOfWeek() == DayOfWeek.MONDAY;
    }
}
