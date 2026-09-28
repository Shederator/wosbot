package dev.frostguard.tasks.city;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.vision.convert.GameTimeUtils;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.*;
import static dev.frostguard.api.configs.TemplatesEnum.*;

public class CrystalLaboratoryRoutine extends DelayedTask {

private static final int MAX_CONSECUTIVE_FAILED_CLAIMS_LIMIT = 3;

private static final int MAX_OCR_RETRIES_LIMIT = 5;

private static final int MAX_SCREEN_VALIDATION_ATTEMPTS = 3;

private static final int MAX_NAVIGATION_ATTEMPTS = 2;

private static final int INSUFFICIENT_FC_RETRY_HOURS_VALUE = 2;

private static final int OCR_RETRY_HOURS_VALUE = 1;

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

boolean useDiscountedDailyRFC;

int weeklyRFCTarget;

private int consecutiveNavigationFailures;

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
            scheduleNavigationRetry();
            return;
        }
        consecutiveNavigationFailures = 0;

        redeemAllCrystals();

        if (useDiscountedDailyRFC) {
            purchaseDiscountedRFCFlow();
        }

        if (hasMonday() && weeklyRFCTarget > 0) {
            WeeklyRFCResultShape result = handleWeeklyRFC();

            if (result == WeeklyRFCResultShape.INSUFFICIENT_FC
                    || result == WeeklyRFCResultShape.OCR_FAILED
                    || result == WeeklyRFCResultShape.ACTION_FAILED) {
                int retryHours = result == WeeklyRFCResultShape.INSUFFICIENT_FC
                        ? INSUFFICIENT_FC_RETRY_HOURS_VALUE : OCR_RETRY_HOURS_VALUE;
                LocalDateTime retryAt = earlierOf(
                        currentTime().plusHours(retryHours), dailyResetTime());
                logInfo(routineLogCrystalLaboratoryLine("Weekly RFC retry scheduled at "
                        + retryAt.format(DATETIME_FORMATTER) + "; reason=" + result + "."));
                reschedule(retryAt);
                return;
            }
        }


        reschedule(dailyResetTime());
    }

@Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

enum WeeklyRFCResultShape {
        REFINEMENTS_DONE,
        TARGET_REACHED,
        INSUFFICIENT_FC,
        OCR_FAILED,
        ACTION_FAILED
    }

private String routineLogCrystalLaboratoryLine(String note) {
        return "CrystalLaboratoryRoutine | " + note;
    }

void performDiscountedRFCPurchase() {
        ImageSearchResultData refineResult = locateRfcRefineButton();

        if (refineResult.isFound()) {
            if (tapDiscountedRfc(refineResult)) {
                logInfo(routineLogCrystalLaboratoryLine(
                        "Discounted RFC tap sent; purchase outcome was not independently confirmed."));
            } else {
                logWarning(routineLogCrystalLaboratoryLine(
                        "Discounted RFC tap was not sent; "
                                + retainDiagnosticSnapshot("discounted-rfc-tap-failed")));
            }
        } else {
            logWarning(routineLogCrystalLaboratoryLine("Discounted offer was detected, but its refine button "
                    + "was not detected; " + retainDiagnosticSnapshot("discounted-rfc-button-missing")));
        }
    }

ImageSearchResultData locateRfcRefineButton() {
        return templateSearchHelper.locatePattern(
                CRYSTAL_LAB_RFC_REFINE_BUTTON,
                SearchConfig.builder().build());
    }

boolean tapDiscountedRfc(ImageSearchResultData refineResult) {
        boolean tapped = tapInside(refineResult);
        sleepTask(500);
        return tapped;
    }

boolean performBulkRefinementsFlow(int currentRFC) {
        int refinesToDo = weeklyRFCTarget - currentRFC;

        logInfo(routineLogCrystalLaboratoryLine(String.format("Sufficient FC available. Performing %d refinements.", refinesToDo)));

        ImageSearchResultData refineResult = templateSearchHelper.locatePattern(
                CRYSTAL_LAB_RFC_REFINE_BUTTON,
                SearchConfig.builder().build());

        if (refineResult.isFound()) {
            return tapInside(refineResult, refinesToDo, 500);
        } else {
            logWarning(routineLogCrystalLaboratoryLine("Could not detect RFC refine button for weekly "
                    + "refinements; " + retainDiagnosticSnapshot("weekly-rfc-button-missing")));
            return false;
        }
    }

void purchaseDiscountedRFCFlow() {
        ImageSearchResultData discountedResult = locateDailyDiscountedRfc();

        if (!discountedResult.isFound()) {
            logInfo(routineLogCrystalLaboratoryLine(
                    "No discounted RFC offer was detected; skipping today's discounted purchase."));
            return;
        }

        logInfo(routineLogCrystalLaboratoryLine("50% discounted RFC available. Attempting to purchase."));
        performDiscountedRFCPurchase();
    }

ImageSearchResultData locateDailyDiscountedRfc() {
        return templateSearchHelper.locatePattern(
                CRYSTAL_LAB_DAILY_DISCOUNTED_RFC,
                SearchConfig.builder().build());
    }

private boolean validateCrystalLabInterface() {
        for (int attempt = 1; attempt <= MAX_SCREEN_VALIDATION_ATTEMPTS; attempt++) {
            if (isCrystalLabInterfaceVisible()) {
                logInfo(routineLogCrystalLaboratoryLine("Successfully navigated to Crystal Laboratory"));
                return true;
            }

            if (attempt < MAX_SCREEN_VALIDATION_ATTEMPTS) {
                sleepTask(500);
            }
        }

        logWarning(routineLogCrystalLaboratoryLine("Crystal Lab UI not detected after "
                + MAX_SCREEN_VALIDATION_ATTEMPTS + " checks; "
                + retainDiagnosticSnapshot("screen-validation-failed")));
        return false;
    }

boolean isCrystalLabInterfaceVisible() {
        ImageSearchResultData validationResult = templateSearchHelper.locatePattern(
                VALIDATION_CRYSTAL_LAB_UI,
                SearchConfig.builder().build());
        return validationResult.isFound();
    }

int extractNumberWithOCRFlow(PointData topLeft, PointData bottomRight, String description) {
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
                        s -> s != null && !s.isBlank(),
                        s -> s);
                Integer number = decodeNumberFromOCR(ocrResult);

                if (number != null) {
                    logInfo(routineLogCrystalLaboratoryLine(description + ": " + number));
                    return number;
                }

            } catch (Exception e) {
                CityUpgradeFlow.rethrowControlSignal(e);
                logDebug(routineLogCrystalLaboratoryLine(
                        "OCR attempt " + attempt + " failed: " + e.getClass().getSimpleName()));
            }

            if (attempt < MAX_OCR_RETRIES_LIMIT) {
                sleepTask(1000);

            }
        }

        logWarning(routineLogCrystalLaboratoryLine("Could not extract " + description + " after "
                + MAX_OCR_RETRIES_LIMIT + " attempts; "
                + retainDiagnosticSnapshot("ocr-failed-" + description.replace(' ', '-'))));
        return -1;
    }

CrystalClaimLoop.Result performCrystalClaimLoop() {
        return CrystalClaimLoop.collect(
                this::locateAndClaimCrystal,
                () -> sleepTask(250),
                MAX_CONSECUTIVE_FAILED_CLAIMS_LIMIT);
    }

boolean reachCrystalLaboratory() {
        logInfo(routineLogCrystalLaboratoryLine("Moving to Crystal Laboratory"));

        for (int attempt = 1; attempt <= MAX_NAVIGATION_ATTEMPTS; attempt++) {
            if (navigateToCrystalLaboratoryViaSidebar()
                    && openCrystalLaboratoryFromCity()
                    && validateCrystalLabInterface()) {
                return true;
            }

            if (attempt < MAX_NAVIGATION_ATTEMPTS) {
                logWarning(routineLogCrystalLaboratoryLine(
                        "Crystal Laboratory navigation was not confirmed. Recovering and retrying."));
                recoverCrystalLaboratoryNavigation();
                sleepTask(500);
            }
        }

        logWarning(routineLogCrystalLaboratoryLine(
                "Crystal Laboratory row, its Go action, or the resulting screen could not be confirmed."));
        return false;
    }

boolean navigateToCrystalLaboratoryViaSidebar() {
        return navigationHelper.navigateToSidebarDestination(SidebarDestination.CRYSTAL_LABORATORY);
    }

    boolean openCrystalLaboratoryFromCity() {
        ImageSearchResultData entryResult = templateSearchHelper.locatePattern(
                CRYSTAL_LAB_BUILDING_MARKER,
                SearchConfig.builder()
                        .withMaxAttempts(3)
                        .withDelay(500)
                        .build());
        if (!entryResult.isFound()) {
            logWarning(routineLogCrystalLaboratoryLine("Crystal Laboratory building marker was not detected "
                    + "after sidebar navigation; " + retainDiagnosticSnapshot("building-marker-missing")));
            return false;
        }

        tapInside(entryResult);
        sleepTask(1000);
        return true;
    }

void recoverCrystalLaboratoryNavigation() {
        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
    }

private boolean locateAndClaimCrystal() {
        ImageSearchResultData claimResult = templateSearchHelper.locatePattern(
                CRYSTAL_LAB_REFINE_BUTTON,
                SearchConfig.builder().build());

        if (!claimResult.isFound()) {
            return false;
        }

        logDebug(routineLogCrystalLaboratoryLine("Collecting crystal..."));
        tapInside(claimResult.getPoint(), claimResult.getPoint());
        return true;
    }

private void scheduleNavigationRetry() {
        consecutiveNavigationFailures++;
        LocalDateTime now = currentTime();
        LocalDateTime retryAt = CrystalLaboratoryRetryPolicy.retryAt(
                now, dailyResetTime(), consecutiveNavigationFailures);
        logWarning(routineLogCrystalLaboratoryLine(
                "Navigation failed " + consecutiveNavigationFailures + " time(s). Next attempt at "
                        + retryAt.format(DATETIME_FORMATTER) + "."));
        reschedule(retryAt);
    }

LocalDateTime currentTime() {
        return LocalDateTime.now();
    }

LocalDateTime dailyResetTime() {
        return GameTimeUtils.dailyResetTime();
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

Integer extractCurrentRFCFlow() {
        int rfc = extractNumberWithOCRFlow(
                CURRENT_RFC_TOP_LEFT_VALUE,
                CURRENT_RFC_BOTTOM_RIGHT_VALUE,
                "current refined FC");

        return rfc == -1 ? null : rfc;
    }

WeeklyRFCResultShape handleRFCRefinements(int currentFC, int currentRFC) {
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

        if (!performBulkRefinementsFlow(currentRFC)) {
            logWarning(routineLogCrystalLaboratoryLine("Weekly RFC refinement taps could not be sent; "
                    + retainDiagnosticSnapshot("weekly-rfc-tap-failed")));
            return WeeklyRFCResultShape.ACTION_FAILED;
        }

        sleepTask(500);
        Integer updatedRFC = extractCurrentRFCFlow();
        if (updatedRFC == null) {
            return WeeklyRFCResultShape.OCR_FAILED;
        }
        if (updatedRFC < weeklyRFCTarget) {
            logWarning(routineLogCrystalLaboratoryLine(String.format(
                    "Weekly RFC progress was not confirmed: current=%d, target=%d; %s.",
                    updatedRFC, weeklyRFCTarget,
                    retainDiagnosticSnapshot("weekly-rfc-progress-unconfirmed"))));
            return WeeklyRFCResultShape.ACTION_FAILED;
        }
        logInfo(routineLogCrystalLaboratoryLine(String.format(
                "Weekly RFC target confirmed after refinement: %d/%d.", updatedRFC, weeklyRFCTarget)));
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
        if (ocrText == null || ocrText.isBlank()) {
            return null;
        }

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

void redeemAllCrystals() {
        logInfo(routineLogCrystalLaboratoryLine("Initiating crystal collecting process"));
        CrystalClaimLoop.Result result = performCrystalClaimLoop();

        if (result.attemptLimitReached()) {
            logWarning(routineLogCrystalLaboratoryLine(
                    "Crystal collecting stopped at the safety limit after " + result.claims() + " claim(s)."));
        } else if (result.claims() == 0) {
            logInfo(routineLogCrystalLaboratoryLine(
                    "No crystal claim control detected after " + result.consecutiveMisses()
                            + " checks; no crystals were claimed."));
        } else {
            logInfo(routineLogCrystalLaboratoryLine(
                    "Collected " + result.claims() + " crystal(s); no further claim control detected after "
                            + result.consecutiveMisses() + " final checks."));
        }
    }

WeeklyRFCResultShape handleWeeklyRFC() {
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

boolean hasMonday() {
        return LocalDateTime.now(Clock.systemUTC()).getDayOfWeek() == DayOfWeek.MONDAY;
    }

    private LocalDateTime earlierOf(LocalDateTime first, LocalDateTime second) {
        return first.isBefore(second) ? first : second;
    }

    String retainDiagnosticSnapshot(String type) {
        try {
            var frame = emuManager.captureScreen(EMULATOR_NUMBER);
            if (frame == null || !frame.isValid()) {
                return "snapshot=unavailable; reason=capture-returned-no-valid-frame";
            }
            var saved = DiagnosticSnapshotStore.forCurrentWorkspace().write(
                    frame, "crystallaboratory", type, Instant.now());
            return saved.map(path -> "snapshot=" + path)
                    .orElse("snapshot=unavailable; reason=write-failed");
        } catch (RuntimeException failure) {
            CityUpgradeFlow.rethrowControlSignal(failure);
            logDebug(routineLogCrystalLaboratoryLine(
                    "Diagnostic snapshot unavailable; reason=" + failure.getClass().getSimpleName()));
            return "snapshot=unavailable";
        }
    }
}
