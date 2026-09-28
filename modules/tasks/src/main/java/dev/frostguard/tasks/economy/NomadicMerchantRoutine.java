package dev.frostguard.tasks.economy;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.nav.ShopTab;
import dev.frostguard.engine.helper.TemplateSearchHelper;
import dev.frostguard.tasks.diagnostics.TaskDiagnosticSnapshots;

import java.time.LocalDateTime;

public class NomadicMerchantRoutine extends DelayedTask {

    private static final long MAX_TASK_EXECUTION_MS = 2 * 60 * 1000L;
    private static final long RESET_SETTLE_DELAY_MINUTES = 1L;
    private static final long RESOURCE_ACTION_SETTLE_MS = 500L;
    private static final long REFRESH_ACTION_SETTLE_MS = 2000L;

    private final TemplatesEnum[] TEMPLATES = { TemplatesEnum.NOMADIC_MERCHANT_COAL,
            TemplatesEnum.NOMADIC_MERCHANT_MEAT, TemplatesEnum.NOMADIC_MERCHANT_STONE,
            TemplatesEnum.NOMADIC_MERCHANT_WOOD };

    public NomadicMerchantRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected void execute() {

        if (!navigateToNomadicMerchantShop()) {
            LocalDateTime nextAttempt = LocalDateTime.now().plusMinutes(5);
            String snapshot = TaskDiagnosticSnapshots.capture(
                    emuManager, EMULATOR_NUMBER, "nomadicmerchant", "shop-navigation");
            logWarning("Nomadic Merchant shop navigation was not verified; retrying at "
                    + nextAttempt.format(DATETIME_FORMATTER) + "; " + snapshot + ".");
            this.reschedule(nextAttempt);
            return;
        }

        // STEP 2: Main loop to handle all nomadic merchant operations
        boolean continueOperations = true;
        int freeResourcesClaimedCount = 0;
        int vipPointsPurchasedCount = 0;
        int dailyRefreshUsedCount = 0;
        long executionDeadlineMs = System.currentTimeMillis() + MAX_TASK_EXECUTION_MS;

        while (continueOperations && System.currentTimeMillis() < executionDeadlineMs) {
            // PHASE 1: Search for resource templates until none are found
            boolean foundResourceTemplate = true;
            logInfo("Searching for free resources to claim.");

            while (foundResourceTemplate && System.currentTimeMillis() < executionDeadlineMs) {
                foundResourceTemplate = false;

                // Iterate through each resource template
                for (TemplatesEnum template : TEMPLATES) {
                    ImageSearchResultData result = templateSearchHelper.locatePattern(
                            template,
                            TemplateSearchHelper.SearchConfig.builder()
                                    .withMaxAttempts(1)
                                    .withThreshold(90)
                                    .withDelay(300L)
                                    .withCoordinates(new PointData(25, 412), new PointData(690, 1200))
                                    .build());

                    if (result.isFound()) {
                        logInfo("Found resource: " + template.name() + ". Purchasing it.");
                        if (!tapInside(result)) {
                            logWarning("Could not dispatch resource tap for " + template.name()
                                    + ". Ending the resource scan to avoid repeating an unverified action.");
                            break;
                        }
                        sleepTask(RESOURCE_ACTION_SETTLE_MS);
                        ImageSearchResultData stillAvailable = templateSearchHelper.locatePattern(
                                template,
                                TemplateSearchHelper.SearchConfig.builder()
                                        .withMaxAttempts(1)
                                        .withThreshold(90)
                                        .withCoordinates(new PointData(25, 412), new PointData(690, 1200))
                                        .build());
                        if (tappedPointStillPresent(result.getPoint(), stillAvailable)) {
                            LocalDateTime retryAt = unverifiedPurchaseRetry(LocalDateTime.now());
                            String snapshot = TaskDiagnosticSnapshots.capture(
                                    emuManager, EMULATOR_NUMBER, "nomadicmerchant", "resource-claim");
                            logWarning("Resource claim was not confirmed; retrying at "
                                    + retryAt.format(DATETIME_FORMATTER) + "; " + snapshot + ".");
                            reschedule(retryAt);
                            return;
                        }
                        freeResourcesClaimedCount++;
                        foundResourceTemplate = true;
                        logInfo("Resource action dispatched. Rescanning the full shop for replacement items.");
                        break; // Restart resource search from a fresh screen state
                    }
                }
            }

            if (System.currentTimeMillis() >= executionDeadlineMs) {
                continueOperations = false;
                break;
            }

            // PHASE 2: Check if VIP purchase is enabled and search for VIP templates
            boolean vipBuyEnabled = profile.getConfig(ConfigurationKeyEnum.BOOL_NOMADIC_MERCHANT_VIP_POINTS,
                    Boolean.class);
            boolean foundVipTemplate = false;

            if (vipBuyEnabled) {
                logInfo("VIP purchase is enabled. Searching for VIP points to buy.");

                // Search for VIP template in the entire screen
                ImageSearchResultData vipResult = templateSearchHelper.locatePattern(
                        TemplatesEnum.NOMADIC_MERCHANT_VIP,
                        SearchConfigConstants.DEFAULT_SINGLE);

                if (vipResult.isFound()) {
                    logInfo("Found VIP points. Purchasing with gems.");
                    // Tap slightly below the VIP template to access purchase options
                    tapNear(new PointData(vipResult.getPoint().getX(), vipResult.getPoint().getY() + 100));
                    sleepTask(1000);

                    // Tap buy with gems button
                    tapNear(new PointData(368, 830));
                    sleepTask(1000);

                    // Confirm purchase
                    tapNear(new PointData(355, 788));
                    sleepTask(1000);

                    ImageSearchResultData vipStillAvailable = templateSearchHelper.locatePattern(
                            TemplatesEnum.NOMADIC_MERCHANT_VIP,
                            SearchConfigConstants.DEFAULT_SINGLE);
                    if (vipStillAvailable.isFound()) {
                        LocalDateTime retryAt = unverifiedPurchaseRetry(LocalDateTime.now());
                        String snapshot = TaskDiagnosticSnapshots.capture(
                                emuManager, EMULATOR_NUMBER, "nomadicmerchant", "vip-purchase");
                        logWarning("VIP purchase outcome is unverified; retrying at "
                                + retryAt.format(DATETIME_FORMATTER)
                                + " and buying again only if the offer is still present; " + snapshot + ".");
                        reschedule(retryAt);
                        return;
                    }

                    vipPointsPurchasedCount++;
                    foundVipTemplate = true;
                    logInfo("VIP action dispatched. Rescanning the full shop for replacement items.");
                }
            }

            // PHASE 3: If VIP was purchased, recheck for new resource templates
            if (foundVipTemplate) {
                logInfo("VIP points purchased. Re-checking for new resource templates.");
                continue; // Go back to PHASE 1 to check for new resources
            }

            // PHASE 4: Check for daily refresh button if no resources or VIP were found
            logInfo("No more resources or VIP points found. Checking for daily refresh.");
            ImageSearchResultData dailyRefreshResult = templateSearchHelper.locatePattern(
                    TemplatesEnum.MYSTERY_SHOP_DAILY_REFRESH,
                    SearchConfigConstants.DEFAULT_SINGLE);

            if (dailyRefreshResult.isFound()) {
                logInfo("Daily refresh is available. Using it now.");
                if (tapInside(dailyRefreshResult)) {
                    sleepTask(REFRESH_ACTION_SETTLE_MS);
                    ImageSearchResultData refreshStillAvailable = templateSearchHelper.locatePattern(
                            TemplatesEnum.MYSTERY_SHOP_DAILY_REFRESH,
                            SearchConfigConstants.DEFAULT_SINGLE);
                    if (refreshStillAvailable.isFound()) {
                        LocalDateTime retryAt = LocalDateTime.now().plusMinutes(5);
                        String snapshot = TaskDiagnosticSnapshots.capture(
                                emuManager, EMULATOR_NUMBER, "nomadicmerchant", "daily-refresh");
                        logWarning("Daily refresh outcome is unverified; retrying at "
                                + retryAt.format(DATETIME_FORMATTER) + "; " + snapshot + ".");
                        reschedule(retryAt);
                        return;
                    }
                    dailyRefreshUsedCount++;
                    logInfo("Free refresh action dispatched. Rescanning the full shop for replacement items.");
                    // Continue the main loop to check every shop position again.
                } else {
                    logWarning("Could not dispatch the free refresh tap. Ending the cycle with refresh state unverified.");
                    continueOperations = false;
                }
            } else {
                // PHASE 5: No refresh template is visible. This is the terminal state only
                // after the preceding full resource and VIP scans found no authorized action.
                logInfo("No free refresh detected after a full shop scan. All eligible Nomadic Merchant operations are complete.");
                continueOperations = false;
            }
        }

        boolean timedOut = System.currentTimeMillis() >= executionDeadlineMs;
        if (timedOut) {
            LocalDateTime retryAt = LocalDateTime.now().plusMinutes(5);
            String snapshot = TaskDiagnosticSnapshots.capture(
                    emuManager, EMULATOR_NUMBER, "nomadicmerchant", "execution-limit");
            logWarning("Nomadic Merchant scan did not finish before its execution limit; retrying at "
                    + retryAt.format(DATETIME_FORMATTER) + "; " + snapshot + ".");
            reschedule(retryAt);
            return;
        }

        StatisticsService.obtain().addToCounter(profile, "Nomadic Merchant Free Resources Claimed", freeResourcesClaimedCount);
        StatisticsService.obtain().addToCounter(profile, "Nomadic Merchant VIP Points Purchased", vipPointsPurchasedCount);
        StatisticsService.obtain().addToCounter(profile, "Nomadic Merchant Daily Refresh Used", dailyRefreshUsedCount);

        String stats = "Nomadic Merchant stats - free resources claimed: " + freeResourcesClaimedCount
                + ", VIP points purchased: " + vipPointsPurchasedCount
                + ", daily refresh used: " + dailyRefreshUsedCount;
        // Wait briefly after reset so the shop has time to publish the new daily state.
        reschedule(GameTimeUtils.dailyResetTime().plusMinutes(RESET_SETTLE_DELAY_MINUTES));
    }

    boolean navigateToNomadicMerchantShop() {
        return navigationHelper.navigateToShop(ShopTab.NOMADIC_MERCHANT);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    static boolean tappedPointStillPresent(PointData tapped, ImageSearchResultData after) {
        if (tapped == null || after == null || !after.isFound() || after.getPoint() == null) {
            return false;
        }
        return tapped.getX() == after.getPoint().getX() && tapped.getY() == after.getPoint().getY();
    }

    static LocalDateTime unverifiedPurchaseRetry(LocalDateTime now) {
        return now.plusMinutes(5);
    }
}
