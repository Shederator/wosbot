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
import java.util.ArrayList;
import java.util.List;

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
     * On the 720-wide 2026-09-30 frames, the product icon sits in the upper
     * card body and the price sits on the bottom strip (about y 640-680 on
     * the first row, 930-970 on the second). A resource price (coal, meat,
     * stone, wood) is a take; a gem price is left for the VIP path. Tapping
     * the small price icon does not buy the card.
     */
    static final int RESOURCE_ICON_TOP_ROW_MIN_Y = 448;
    static final int RESOURCE_ICON_TOP_ROW_MAX_Y = 575;
    static final int RESOURCE_ICON_BOTTOM_ROW_MIN_Y = 738;
    static final int RESOURCE_ICON_BOTTOM_ROW_MAX_Y = 865;
    /** Open the gem purchase sheet from the VIP product icon. */
    static final int VIP_PURCHASE_OFFSET_Y = 100;
    /** Buy-with-gems control on the VIP sheet (no template captured yet). */
    static final PointData VIP_BUY_WITH_GEMS = new PointData(368, 830);
    /** Confirm control on the VIP sheet (no template captured yet). */
    static final PointData VIP_CONFIRM = new PointData(355, 788);

    private final TemplatesEnum[] TEMPLATES = { TemplatesEnum.NOMADIC_MERCHANT_COAL,
            TemplatesEnum.NOMADIC_MERCHANT_MEAT, TemplatesEnum.NOMADIC_MERCHANT_STONE,
            TemplatesEnum.NOMADIC_MERCHANT_WOOD };

    /**
     * Remembered across visits on this task instance. It is not a reason to
     * skip the next scan.
     */
    private NomadicMerchantProgress progress = NomadicMerchantProgress.READY;
    private NomadicMerchantPhase phase = NomadicMerchantPhase.OPENING;
    private String retrySnapshotType;
    private String retryReason;

    public NomadicMerchantRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    NomadicMerchantProgress progress() {
        return progress;
    }

    NomadicMerchantPhase phase() {
        return phase;
    }

    @Override
    protected void execute() {
        phase = NomadicMerchantPhase.OPENING;
        retrySnapshotType = null;
        retryReason = null;
        logInfo("Resuming Nomadic Merchant from " + progress + ".");

        int freeResourcesClaimedCount = 0;
        int vipPointsPurchasedCount = 0;
        int dailyRefreshUsedCount = 0;
        List<PointData> skippedResourceOffers = new ArrayList<>();
        long executionDeadlineMs = System.currentTimeMillis() + MAX_TASK_EXECUTION_MS;
        boolean unconfirmed = false;
        boolean complete = false;

        try {
            if (!navigateToNomadicMerchantShop()) {
                markUnconfirmed("shop-navigation", "Nomadic Merchant shop navigation was not verified");
                unconfirmed = true;
            } else {
                while (!unconfirmed && !complete
                        && System.currentTimeMillis() < executionDeadlineMs) {
                    int claimed = claimResourceOffers(skippedResourceOffers, executionDeadlineMs);
                    freeResourcesClaimedCount += claimed;
                    if (retrySnapshotType != null
                            || System.currentTimeMillis() >= executionDeadlineMs) {
                        break;
                    }

                    int vipBought = buyVipIfEnabled(executionDeadlineMs);
                    vipPointsPurchasedCount += Math.max(vipBought, 0);
                    if (retrySnapshotType != null) {
                        unconfirmed = true;
                        break;
                    }
                    if (vipBought > 0) {
                        logInfo("VIP points purchased. Re-checking for new resource templates.");
                    } else {
                        int refreshed = useFreeRefresh(skippedResourceOffers);
                        dailyRefreshUsedCount += Math.max(refreshed, 0);
                        if (retrySnapshotType != null) {
                            unconfirmed = true;
                            break;
                        }
                        if (refreshed <= 0) {
                            complete = true;
                            break;
                        }
                    }
                }
                if (!complete && retrySnapshotType == null
                        && System.currentTimeMillis() >= executionDeadlineMs) {
                    markUnconfirmed("execution-limit",
                            "Nomadic Merchant task reached execution limit. Ending current cycle with partial results");
                }
            }
        } finally {
            finishVisit(freeResourcesClaimedCount, vipPointsPurchasedCount, dailyRefreshUsedCount);
        }
    }

    private int claimResourceOffers(List<PointData> skippedResourceOffers, long executionDeadlineMs) {
        int claimed = 0;
        boolean foundResourceTemplate = true;
        phase = NomadicMerchantPhase.SEARCHING_RESOURCES;
        logInfo("Searching for free resources to claim.");

        while (foundResourceTemplate && System.currentTimeMillis() < executionDeadlineMs) {
            foundResourceTemplate = false;
            phase = NomadicMerchantPhase.SEARCHING_RESOURCES;
            for (TemplatesEnum template : TEMPLATES) {
                ImageSearchResultData result = locateFreeResource(template, skippedResourceOffers);
                if (!result.isFound()) {
                    continue;
                }
                phase = NomadicMerchantPhase.CLAIMING_RESOURCE;
                logInfo("Found resource: " + template.name() + ". Purchasing it.");
                boolean tapped = tapInside(result);
                phase = NomadicMerchantPhase.WAITING_CLAIM_ANIMATION;
                if (!tapped || !confirmResourceLeft(result.getPoint(), template)) {
                    skippedResourceOffers.add(result.getPoint());
                    logWarning("Resource claim was not confirmed. Skipping this offer and continuing the shop scan.");
                    foundResourceTemplate = true;
                    break;
                }
                claimed++;
                foundResourceTemplate = true;
                logInfo("Resource action dispatched. Rescanning the full shop for replacement items.");
                break;
            }
        }
        return claimed;
    }

    /** @return 1 when a VIP card was bought, 0 when none was present */
    private int buyVipIfEnabled(long executionDeadlineMs) {
        if (System.currentTimeMillis() >= executionDeadlineMs) {
            return 0;
        }
        phase = NomadicMerchantPhase.SEARCHING_VIP;
        boolean vipBuyEnabled = profile.getConfig(ConfigurationKeyEnum.BOOL_NOMADIC_MERCHANT_VIP_POINTS,
                Boolean.class);
        if (!vipBuyEnabled) {
            return 0;
        }
        logInfo("VIP purchase is enabled. Searching for VIP points to buy.");
        ImageSearchResultData vipResult = templateSearchHelper.locatePattern(
                TemplatesEnum.NOMADIC_MERCHANT_VIP,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (!vipResult.isFound()) {
            return 0;
        }

        phase = NomadicMerchantPhase.BUYING_VIP;
        logInfo("Found VIP points. Purchasing with gems.");
        tapNear(new PointData(vipResult.getPoint().getX(), vipResult.getPoint().getY() + VIP_PURCHASE_OFFSET_Y));
        sleepTask(1000);
        tapNear(VIP_BUY_WITH_GEMS);
        sleepTask(1000);
        tapNear(VIP_CONFIRM);
        sleepTask(1000);

        if (sameOffer(vipResult.getPoint(),
                locateOffer(TemplatesEnum.NOMADIC_MERCHANT_VIP, vipResult.getPoint()))) {
            markUnconfirmed("vip-purchase",
                    "VIP purchase outcome is unverified; buying again only if the offer is still present");
            return 0;
        }
        logInfo("VIP action dispatched. Rescanning the full shop for replacement items.");
        return 1;
    }

    /** @return 1 when Free Refresh was used, 0 when the shop is done or the tap is unverified */
    private int useFreeRefresh(List<PointData> skippedResourceOffers) {
        phase = NomadicMerchantPhase.SEARCHING_FREE_REFRESH;
        logInfo("No more resources or VIP points found. Checking for daily refresh.");
        ImageSearchResultData dailyRefreshResult = templateSearchHelper.locatePattern(
                TemplatesEnum.MYSTERY_SHOP_DAILY_REFRESH,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (!dailyRefreshResult.isFound()) {
            logInfo("No free refresh detected after a full shop scan. All eligible Nomadic Merchant operations are complete.");
            return 0;
        }

        phase = NomadicMerchantPhase.CLAIMING_FREE_REFRESH;
        logInfo("Daily refresh is available. Using it now.");
        if (!tapInside(dailyRefreshResult)) {
            markUnconfirmed("daily-refresh", "Could not dispatch the free refresh tap");
            return 0;
        }
        phase = NomadicMerchantPhase.WAITING_REFRESH;
        if (!confirmRefreshTaken(dailyRefreshResult.getPoint())) {
            ImageSearchResultData refreshStillAvailable = templateSearchHelper.locatePattern(
                    TemplatesEnum.MYSTERY_SHOP_DAILY_REFRESH,
                    SearchConfigConstants.DEFAULT_SINGLE);
            if (refreshStillAvailable.isFound()) {
                logInfo("Free refresh button is still present after the first tap. Tapping it once more.");
                phase = NomadicMerchantPhase.CLAIMING_FREE_REFRESH;
                if (!tapInside(refreshStillAvailable)) {
                    markUnconfirmed("daily-refresh", "Daily refresh outcome is unverified");
                    return 0;
                }
                phase = NomadicMerchantPhase.WAITING_REFRESH;
                if (!confirmRefreshTaken(refreshStillAvailable.getPoint())) {
                    markUnconfirmed("daily-refresh", "Daily refresh outcome is unverified");
                    return 0;
                }
            }
        }
        skippedResourceOffers.clear();
        logInfo("Free refresh action dispatched. Rescanning the full shop for replacement items.");
        return 1;
    }

    private void markUnconfirmed(String snapshotType, String reason) {
        retrySnapshotType = snapshotType;
        retryReason = reason;
    }

    private void finishVisit(int freeResourcesClaimedCount, int vipPointsPurchasedCount,
            int dailyRefreshUsedCount) {
        NomadicMerchantPhase failedDuring = phase;
        if (retrySnapshotType != null) {
            progress = NomadicMerchantProgress.UNCONFIRMED;
        } else if (failedDuring != NomadicMerchantPhase.OPENING) {
            progress = NomadicMerchantProgress.COMPLETE_UNTIL_RESET;
        }
        phase = NomadicMerchantPhase.FINISHED;
        recordConfirmedResults(freeResourcesClaimedCount, vipPointsPurchasedCount, dailyRefreshUsedCount);
        String stats = resultSummary(freeResourcesClaimedCount, vipPointsPurchasedCount, dailyRefreshUsedCount);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = progress == NomadicMerchantProgress.COMPLETE_UNTIL_RESET
                ? nextRun(progress, now, GameTimeUtils.dailyResetTime())
                : nextRun(progress, now, now);
        if (retrySnapshotType != null) {
            String snapshot = TaskDiagnosticSnapshots.capture(
                    emuManager, EMULATOR_NUMBER, "nomadicmerchant", retrySnapshotType);
            logWarning(retryReason + ". Progress " + progress + " after " + failedDuring
                    + "; confirmed results kept; " + stats
                    + "; next check at " + next.format(DATETIME_FORMATTER) + "; " + snapshot + ".");
        } else {
            logInfo(stats + ". Progress " + progress + " after " + failedDuring
                    + ". Next check at " + next.format(DATETIME_FORMATTER) + ".");
        }
        reschedule(next);
    }

    static LocalDateTime nextRun(NomadicMerchantProgress progress, LocalDateTime now,
            LocalDateTime dailyReset) {
        if (progress == NomadicMerchantProgress.COMPLETE_UNTIL_RESET) {
            return dailyReset.plusMinutes(RESET_SETTLE_DELAY_MINUTES);
        }
        return now.plusMinutes(5);
    }

    boolean navigateToNomadicMerchantShop() {
        return navigationHelper.navigateToShop(ShopTab.NOMADIC_MERCHANT);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    /**
     * True for a match on the card product. Price-row icons on the 2026-09-30
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

    static boolean isSkippedOffer(PointData candidate, List<PointData> skipped) {
        if (candidate == null || skipped == null || skipped.isEmpty()) {
            return false;
        }
        ImageSearchResultData hit = new ImageSearchResultData(true, candidate, 100);
        for (PointData skip : skipped) {
            if (sameOffer(skip, hit)) {
                return true;
            }
        }
        return false;
    }

    private ImageSearchResultData locateFreeResource(TemplatesEnum template, List<PointData> skipped) {
        ImageSearchResultData top = locateInRow(template,
                RESOURCE_ICON_TOP_ROW_MIN_Y, RESOURCE_ICON_TOP_ROW_MAX_Y);
        if (usableResourceMatch(top, skipped)) {
            return top;
        }
        ImageSearchResultData bottom = locateInRow(template,
                RESOURCE_ICON_BOTTOM_ROW_MIN_Y, RESOURCE_ICON_BOTTOM_ROW_MAX_Y);
        if (usableResourceMatch(bottom, skipped)) {
            return bottom;
        }
        return new ImageSearchResultData(false, null, 0);
    }

    private static boolean usableResourceMatch(ImageSearchResultData result, List<PointData> skipped) {
        return result != null && result.isFound() && isMerchandiseIcon(result.getPoint())
                && !isSkippedOffer(result.getPoint(), skipped);
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
        return nextRun(NomadicMerchantProgress.UNCONFIRMED, now, now);
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
