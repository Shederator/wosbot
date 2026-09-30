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
    /**
     * Reward sprites on the 2026-09-29 resource frames were still in flight
     * about 1.5s after the tap. A miss before this settle is the sprite
     * covering the card, not a completed claim.
     */
    static final long RESOURCE_CONFIRM_MIN_SETTLE_MS = 2500L;
    static final long RESOURCE_CONFIRM_WINDOW_MS = 4000L;
    private static final long RESOURCE_CONFIRM_POLL_MS = 400L;
    private static final long REFRESH_ACTION_SETTLE_MS = 2000L;
    /** Neighbor card centers on the 720-wide shop sit well beyond this radius. */
    static final int SAME_OFFER_RADIUS_PX = 24;
    private static final int OFFER_SEARCH_REACH_PX = 80;
    private static final int RESOURCE_GRID_LEFT_PX = 25;
    private static final int RESOURCE_GRID_RIGHT_PX = 690;
    /**
     * On the 720-wide 2026-09-30 frames, each card's merchandise icon sits in
     * the upper body and the resource or gem price sits on the bottom strip
     * (about y 640-680 on the first row, 930-970 on the second). Searching
     * the whole grid matches those price icons as free claims.
     */
    static final int RESOURCE_ICON_TOP_ROW_MIN_Y = 448;
    static final int RESOURCE_ICON_TOP_ROW_MAX_Y = 575;
    static final int RESOURCE_ICON_BOTTOM_ROW_MIN_Y = 738;
    static final int RESOURCE_ICON_BOTTOM_ROW_MAX_Y = 865;

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
                    ImageSearchResultData result = locateFreeResource(template);

                    if (result.isFound()) {
                        logInfo("Found resource: " + template.name() + ". Purchasing it.");
                        if (!tapInside(result)) {
                            logWarning("Could not dispatch resource tap for " + template.name()
                                    + ". Ending the resource scan to avoid repeating an unverified action.");
                            break;
                        }
                        if (!confirmResourceLeft(result.getPoint(), template)) {
                            retryUnverifiedAction(freeResourcesClaimedCount, vipPointsPurchasedCount,
                                    dailyRefreshUsedCount, "resource-claim",
                                    "Resource claim was not confirmed");
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

                    if (sameOffer(vipResult.getPoint(),
                            locateOffer(TemplatesEnum.NOMADIC_MERCHANT_VIP, vipResult.getPoint()))) {
                        retryUnverifiedAction(freeResourcesClaimedCount, vipPointsPurchasedCount,
                                dailyRefreshUsedCount, "vip-purchase",
                                "VIP purchase outcome is unverified; buying again only if the offer is still present");
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
                if (!tapInside(dailyRefreshResult)) {
                    retryUnverifiedAction(freeResourcesClaimedCount, vipPointsPurchasedCount,
                            dailyRefreshUsedCount, "daily-refresh",
                            "Could not dispatch the free refresh tap");
                    return;
                }
                if (!confirmRefreshTaken(dailyRefreshResult.getPoint())) {
                    ImageSearchResultData refreshStillAvailable = templateSearchHelper.locatePattern(
                            TemplatesEnum.MYSTERY_SHOP_DAILY_REFRESH,
                            SearchConfigConstants.DEFAULT_SINGLE);
                    if (refreshStillAvailable.isFound()) {
                        logInfo("Free refresh button is still present after the first tap. Tapping it once more.");
                        if (!tapInside(refreshStillAvailable)
                                || !confirmRefreshTaken(refreshStillAvailable.getPoint())) {
                            retryUnverifiedAction(freeResourcesClaimedCount, vipPointsPurchasedCount,
                                    dailyRefreshUsedCount, "daily-refresh",
                                    "Daily refresh outcome is unverified");
                            return;
                        }
                    }
                }
                dailyRefreshUsedCount++;
                logInfo("Free refresh action dispatched. Rescanning the full shop for replacement items.");
                // Continue the main loop to check every shop position again.
            } else {
                // PHASE 5: No refresh template is visible. This is the terminal state only
                // after the preceding full resource and VIP scans found no authorized action.
                logInfo("No free refresh detected after a full shop scan. All eligible Nomadic Merchant operations are complete.");
                continueOperations = false;
            }
        }

        boolean timedOut = System.currentTimeMillis() >= executionDeadlineMs;
        recordConfirmedResults(freeResourcesClaimedCount, vipPointsPurchasedCount, dailyRefreshUsedCount);
        String stats = resultSummary(freeResourcesClaimedCount, vipPointsPurchasedCount, dailyRefreshUsedCount);
        if (timedOut) {
            LocalDateTime retryAt = LocalDateTime.now().plusMinutes(5);
            String snapshot = TaskDiagnosticSnapshots.capture(
                    emuManager, EMULATOR_NUMBER, "nomadicmerchant", "execution-limit");
            logWarning("Nomadic Merchant task reached execution limit. Ending this cycle with partial results. "
                    + stats + "; retrying at " + retryAt.format(DATETIME_FORMATTER) + "; " + snapshot + ".");
            reschedule(retryAt);
            return;
        }

        logInfo(stats);
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

    /**
     * True for a match on the card artwork. Price-row icons on the 2026-09-30
     * frames sit outside these two bands (coal at ~660, wood cost at ~950).
     */
    static boolean isMerchandiseIcon(PointData point) {
        if (point == null) {
            return false;
        }
        int y = point.getY();
        return y >= RESOURCE_ICON_TOP_ROW_MIN_Y && y <= RESOURCE_ICON_TOP_ROW_MAX_Y
                || y >= RESOURCE_ICON_BOTTOM_ROW_MIN_Y && y <= RESOURCE_ICON_BOTTOM_ROW_MAX_Y;
    }

    private ImageSearchResultData locateFreeResource(TemplatesEnum template) {
        ImageSearchResultData top = locateInRow(template,
                RESOURCE_ICON_TOP_ROW_MIN_Y, RESOURCE_ICON_TOP_ROW_MAX_Y);
        if (top.isFound() && isMerchandiseIcon(top.getPoint())) {
            return top;
        }
        ImageSearchResultData bottom = locateInRow(template,
                RESOURCE_ICON_BOTTOM_ROW_MIN_Y, RESOURCE_ICON_BOTTOM_ROW_MAX_Y);
        if (bottom.isFound() && isMerchandiseIcon(bottom.getPoint())) {
            return bottom;
        }
        return new ImageSearchResultData(false, null, 0);
    }

    private ImageSearchResultData locateInRow(TemplatesEnum template, int minY, int maxY) {
        return templateSearchHelper.locatePattern(
                template,
                TemplateSearchHelper.SearchConfig.builder()
                        .withMaxAttempts(1)
                        .withThreshold(90)
                        .withDelay(300L)
                        .withCoordinates(
                                new PointData(RESOURCE_GRID_LEFT_PX, minY),
                                new PointData(RESOURCE_GRID_RIGHT_PX, maxY))
                        .build());
    }

    static boolean sameOffer(PointData tapped, ImageSearchResultData after) {
        if (tapped == null || after == null || !after.isFound() || after.getPoint() == null) {
            return false;
        }
        long dx = (long) tapped.getX() - after.getPoint().getX();
        long dy = (long) tapped.getY() - after.getPoint().getY();
        long radius = SAME_OFFER_RADIUS_PX;
        return dx * dx + dy * dy <= radius * radius;
    }

    /** A miss during the reward flyout is not a completed claim. */
    static boolean resourceClaimConfirmed(boolean offerStillPresent, long elapsedMs) {
        return !offerStillPresent && elapsedMs >= RESOURCE_CONFIRM_MIN_SETTLE_MS;
    }

    private boolean confirmResourceLeft(PointData tapped, TemplatesEnum template) {
        long started = System.currentTimeMillis();
        while (true) {
            long elapsed = System.currentTimeMillis() - started;
            long remainingToWindow = RESOURCE_CONFIRM_WINDOW_MS - elapsed;
            if (remainingToWindow <= 0) {
                return resourceClaimConfirmed(sameOffer(tapped, locateOffer(template, tapped)), elapsed);
            }
            long pause = RESOURCE_CONFIRM_POLL_MS;
            long remainingToSettle = RESOURCE_CONFIRM_MIN_SETTLE_MS - elapsed;
            if (remainingToSettle > 0) {
                pause = Math.max(pause, remainingToSettle);
            }
            sleepTask(Math.min(pause, remainingToWindow));
            elapsed = System.currentTimeMillis() - started;
            boolean stillPresent = sameOffer(tapped, locateOffer(template, tapped));
            if (resourceClaimConfirmed(stillPresent, elapsed)) {
                return true;
            }
            if (stillPresent && elapsed >= RESOURCE_CONFIRM_WINDOW_MS) {
                return false;
            }
        }
    }

    private boolean confirmRefreshTaken(PointData tapped) {
        sleepTask(REFRESH_ACTION_SETTLE_MS);
        ImageSearchResultData stillAvailable = templateSearchHelper.locatePattern(
                TemplatesEnum.MYSTERY_SHOP_DAILY_REFRESH,
                SearchConfigConstants.DEFAULT_SINGLE);
        return !sameOffer(tapped, stillAvailable);
    }

    private ImageSearchResultData locateOffer(TemplatesEnum template, PointData center) {
        int x = center.getX();
        int y = center.getY();
        PointData start = new PointData(
                Math.max(0, x - OFFER_SEARCH_REACH_PX),
                Math.max(0, y - OFFER_SEARCH_REACH_PX));
        PointData end = new PointData(x + OFFER_SEARCH_REACH_PX, y + OFFER_SEARCH_REACH_PX);
        return templateSearchHelper.locatePattern(
                template,
                TemplateSearchHelper.SearchConfig.builder()
                        .withMaxAttempts(1)
                        .withThreshold(90)
                        .withDelay(0)
                        .withCoordinates(start, end)
                        .build());
    }

    static LocalDateTime unverifiedPurchaseRetry(LocalDateTime now) {
        return now.plusMinutes(5);
    }

    private void retryUnverifiedAction(int freeResourcesClaimedCount, int vipPointsPurchasedCount,
            int dailyRefreshUsedCount, String snapshotType, String reason) {
        recordConfirmedResults(freeResourcesClaimedCount, vipPointsPurchasedCount, dailyRefreshUsedCount);
        LocalDateTime retryAt = unverifiedPurchaseRetry(LocalDateTime.now());
        String snapshot = TaskDiagnosticSnapshots.capture(
                emuManager, EMULATOR_NUMBER, "nomadicmerchant", snapshotType);
        logWarning(reason + "; confirmed results kept; retrying at "
                + retryAt.format(DATETIME_FORMATTER) + "; " + snapshot + ".");
        reschedule(retryAt);
    }

    private void recordConfirmedResults(int freeResourcesClaimedCount, int vipPointsPurchasedCount,
            int dailyRefreshUsedCount) {
        StatisticsService.obtain().addToCounter(profile, "Nomadic Merchant Free Resources Claimed",
                freeResourcesClaimedCount);
        StatisticsService.obtain().addToCounter(profile, "Nomadic Merchant VIP Points Purchased",
                vipPointsPurchasedCount);
        StatisticsService.obtain().addToCounter(profile, "Nomadic Merchant Daily Refresh Used",
                dailyRefreshUsedCount);
    }

    private static String resultSummary(int freeResourcesClaimedCount, int vipPointsPurchasedCount,
            int dailyRefreshUsedCount) {
        return "Nomadic Merchant stats - free resources claimed: " + freeResourcesClaimedCount
                + ", VIP points purchased: " + vipPointsPurchasedCount
                + ", daily refresh used: " + dailyRefreshUsedCount;
    }
}
