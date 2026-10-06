package dev.frostguard.tasks.economy;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
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
    private static final int MAX_FAILED_TIMEOUTS = 3;
    /**
     * Reward sprites on the 2026-09-29 resource frames were still in flight
     * about 1.5s after the tap. A miss before this settle is the sprite
     * covering the card, not a completed claim.
     */
    static final long RESOURCE_CONFIRM_MIN_SETTLE_MS = 2500L;
    static final long RESOURCE_CONFIRM_WINDOW_MS = 4000L;
    private static final long RESOURCE_CONFIRM_POLL_MS = 400L;
    static final long REFRESH_ACTION_SETTLE_MS = 500L;
    /** Neighbor card centers on the 720-wide shop sit well beyond this radius. */
    static final int SAME_OFFER_RADIUS_PX = 24;
    private static final int OFFER_SEARCH_REACH_PX = 80;
    static final int GEM_PRICE_THRESHOLD = 80;
    /** Open the gem purchase sheet from the VIP product icon. */
    static final int VIP_PURCHASE_OFFSET_Y = 100;
    /** Buy-with-gems control on the VIP sheet (no template captured yet). */
    static final PointData VIP_BUY_WITH_GEMS = new PointData(368, 830);
    /** Confirm control on the VIP sheet (no template captured yet). */
    static final PointData VIP_CONFIRM = new PointData(355, 788);

    /** Daily progress stays on this task instance and is never persisted. */
    private NomadicMerchantProgress progress = NomadicMerchantProgress.READY;
    private NomadicMerchantPhase phase = NomadicMerchantPhase.OPENING;
    private String retrySnapshotType;
    private String retryReason;
    private int failedTimeoutsThisCycle;
    private LocalDateTime retryDeferredUntil;
    private int confirmedOffersThisCycle;

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
        if (retryDeferredUntil != null && !LocalDateTime.now().isBefore(retryDeferredUntil)) {
            failedTimeoutsThisCycle = 0;
            confirmedOffersThisCycle = 0;
            retryDeferredUntil = null;
        }
        phase = NomadicMerchantPhase.OPENING;
        retrySnapshotType = null;
        retryReason = null;
        logInfo("Resuming Nomadic Merchant from " + progress + ".");

        int vipPointsPurchasedCount = 0;
        int unconfirmedVipPurchasesCount = 0;
        int nextResourceSlot = 0;
        VisitResults visitResults = new VisitResults();
        long executionDeadlineMs = executionDeadlineMs();
        boolean unconfirmed = false;
        boolean complete = false;
        RuntimeException visitFailure = null;

        try {
            if (!navigateToNomadicMerchantShop()) {
                markUnconfirmed("shop-navigation", "Nomadic Merchant shop navigation was not verified");
                unconfirmed = true;
            } else {
                while (!unconfirmed && !complete
                        && System.currentTimeMillis() < executionDeadlineMs) {
                    phase = NomadicMerchantPhase.SEARCHING_RESOURCES;
                    int attemptedSlot = claimResourceOffer(nextResourceSlot, visitResults);
                    if (attemptedSlot >= 0) {
                        nextResourceSlot = (attemptedSlot + 1) % NomadicMerchantDecisions.SLOT_COUNT;
                        continue;
                    }
                    if (System.currentTimeMillis() >= executionDeadlineMs) {
                        break;
                    }

                    int vipBought = buyVipIfEnabled(executionDeadlineMs);
                    vipPointsPurchasedCount += Math.max(vipBought, 0);
                    if (vipBought < 0) {
                        unconfirmedVipPurchasesCount++;
                        visitResults.recordFailedActivityAction();
                    }
                    if (vipBought != 0) {
                        logInfo("VIP purchase attempt dispatched. Rescanning the shop.");
                        continue;
                    }
                    if (System.currentTimeMillis() >= executionDeadlineMs) {
                        break;
                    }

                    int refreshed = useFreeRefresh(visitResults);
                    if (retrySnapshotType != null) {
                        visitResults.recordFailedActivityAction();
                        unconfirmed = true;
                        break;
                    }
                    if (refreshed <= 0) {
                        complete = true;
                    } else {
                        nextResourceSlot = 0;
                    }
                }
                if (!complete && retrySnapshotType == null
                        && System.currentTimeMillis() >= executionDeadlineMs) {
                    markUnconfirmed("execution-limit",
                            "Nomadic Merchant task reached execution limit. Ending current cycle with partial results");
                }
            }
        } catch (RuntimeException failure) {
            visitFailure = failure;
            throw failure;
        } finally {
            if (visitFailure != null && isActionPhase(phase)) {
                visitResults.recordFailedActivityAction();
            }
            finishVisit(visitResults.freeResourcesClaimedCount(), visitResults.unconfirmedResourceClaimsCount(),
                    vipPointsPurchasedCount, unconfirmedVipPurchasesCount,
                    visitResults.freeRefreshTapsDispatchedCount(),
                    visitResults.failedActivityActionsCount(), complete, visitFailure);
        }
    }

    long executionDeadlineMs() {
        return System.currentTimeMillis() + MAX_TASK_EXECUTION_MS;
    }

    int claimResourceOffer(int nextResourceSlot, VisitResults visitResults) {
        logDebug("Scanning Nomadic Merchant cards for resource-priced offers.");
        for (int offset = 0; offset < NomadicMerchantDecisions.SLOT_COUNT; offset++) {
            int slot = (nextResourceSlot + offset) % NomadicMerchantDecisions.SLOT_COUNT;
            boolean gemOnPrice = foundInSlot(TemplatesEnum.NOMADIC_MERCHANT_GEM_PRICE,
                    NomadicMerchantDecisions.priceTopLeft(slot),
                    NomadicMerchantDecisions.priceBottomRight(slot),
                    GEM_PRICE_THRESHOLD);
            if (NomadicMerchantDecisions.takeIfNotGemPriced(gemOnPrice)) {
                phase = NomadicMerchantPhase.CLAIMING_RESOURCE;
                logInfo("Found a resource-priced offer in slot " + slot + ". Purchasing it.");
                if (collectOffer(slot)) {
                    visitResults.recordFreeResourceClaim();
                    logInfo("Resource claim confirmed. Rescanning the full shop for replacement items.");
                } else {
                    visitResults.recordUnconfirmedResourceClaim();
                    visitResults.recordFailedActivityAction();
                    logWarning("Resource claim in slot " + slot
                            + " was not confirmed. Rescanning without assuming the offer changed.");
                }
                return slot;
            }
        }
        return -1;
    }

    boolean collectOffer(int slot) {
        RawImageData before = emuManager.captureScreen(EMULATOR_NUMBER);
        tapNear(NomadicMerchantDecisions.priceTap(slot));
        phase = NomadicMerchantPhase.WAITING_CLAIM_ANIMATION;
        return confirmSlotChanged(slot, before);
    }

    static final class VisitResults {
        private int freeResourcesClaimedCount;
        private int unconfirmedResourceClaimsCount;
        private int failedActivityActionsCount;
        private int freeRefreshTapsDispatchedCount;

        void recordFreeResourceClaim() {
            freeResourcesClaimedCount++;
        }

        int freeResourcesClaimedCount() {
            return freeResourcesClaimedCount;
        }

        void recordUnconfirmedResourceClaim() {
            unconfirmedResourceClaimsCount++;
        }

        int unconfirmedResourceClaimsCount() {
            return unconfirmedResourceClaimsCount;
        }

        void recordFailedActivityAction() {
            failedActivityActionsCount++;
        }

        int failedActivityActionsCount() {
            return failedActivityActionsCount;
        }

        void recordFreeRefreshTapDispatched() {
            freeRefreshTapsDispatchedCount++;
        }

        int freeRefreshTapsDispatchedCount() {
            return freeRefreshTapsDispatchedCount;
        }
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
        logDebug("VIP purchase is enabled. Searching for VIP points to buy.");
        ImageSearchResultData vipResult = locatePatternChecked(
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
            return -1;
        }
        logInfo("VIP action dispatched. Rescanning the full shop for replacement items.");
        return 1;
    }

    /** @return 1 when a detected Free Refresh tap was dispatched, 0 when none was available */
    int useFreeRefresh(VisitResults visitResults) {
        phase = NomadicMerchantPhase.SEARCHING_FREE_REFRESH;
        logDebug("No selectable offer found. Checking for Free Refresh.");
        ImageSearchResultData dailyRefreshResult = locatePatternChecked(
                TemplatesEnum.MYSTERY_SHOP_DAILY_REFRESH,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (!dailyRefreshResult.isFound()) {
            logInfo("No free refresh detected after a full shop scan. All eligible Nomadic Merchant operations are complete.");
            return 0;
        }

        phase = NomadicMerchantPhase.CLAIMING_FREE_REFRESH;
        if (visitResults.freeRefreshTapsDispatchedCount() == 0) {
            logInfo("Free Refresh detected; starting the refresh scan cycle.");
        }
        if (!tapInside(dailyRefreshResult)) {
            logWarning("Could not dispatch the first Free Refresh tap. Retrying once.");
            if (!tapInside(dailyRefreshResult)) {
                markUnconfirmed("daily-refresh", "Could not dispatch the free refresh tap after two attempts");
                return 0;
            }
        }
        visitResults.recordFreeRefreshTapDispatched();
        phase = NomadicMerchantPhase.WAITING_REFRESH;
        sleepTask(REFRESH_ACTION_SETTLE_MS);
        logDebug("Free Refresh tap dispatched. Rescanning the full shop.");
        return 1;
    }

    private void markUnconfirmed(String snapshotType, String reason) {
        retrySnapshotType = snapshotType;
        retryReason = reason;
    }

    private void finishVisit(int freeResourcesClaimedCount, int unconfirmedResourceClaimsCount,
            int vipPointsPurchasedCount, int unconfirmedVipPurchasesCount,
            int freeRefreshTapsDispatchedCount, int failedActivityActionsCount,
            boolean complete, RuntimeException visitFailure) {
        NomadicMerchantPhase failedDuring = phase;
        confirmedOffersThisCycle += freeResourcesClaimedCount + vipPointsPurchasedCount;
        phase = NomadicMerchantPhase.FINISHED;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next;
        String scheduleReason;
        int shortRetriesScheduledThisVisit = 0;
        if (complete && retrySnapshotType == null && visitFailure == null) {
            progress = NomadicMerchantProgress.COMPLETED;
            failedTimeoutsThisCycle = 0;
            next = nextRun(progress, now, GameTimeUtils.dailyResetTime());
            retryDeferredUntil = next;
            scheduleReason = "shop exhausted";
        } else if (visitFailure != null || retrySnapshotType != null
                && !"execution-limit".equals(retrySnapshotType)) {
            progress = NomadicMerchantProgress.ERROR_RETRY;
            failedTimeoutsThisCycle = 0;
            next = nextRun(progress, now, now);
            scheduleReason = "scan or action error; retry soon";
        } else {
            failedTimeoutsThisCycle++;
            progress = timeoutProgress(confirmedOffersThisCycle, failedTimeoutsThisCycle);
            if (progress == NomadicMerchantProgress.FAILED_RETRY) {
                shortRetriesScheduledThisVisit = 1;
                next = nextRun(progress, now, now);
                scheduleReason = "timeout retry " + failedTimeoutsThisCycle + "/" + MAX_FAILED_TIMEOUTS;
            } else {
                retryDeferredUntil = GameTimeUtils.dailyResetTime().plusMinutes(RESET_SETTLE_DELAY_MINUTES);
                next = retryDeferredUntil;
                scheduleReason = progress == NomadicMerchantProgress.PARTIAL_RESCHEDULED
                        ? "timeout after confirmed collections; deferred until daily reset"
                        : "third timeout without confirmed collections; deferred until daily reset";
            }
        }
        reschedule(next);
        recordVisitResults(freeResourcesClaimedCount, unconfirmedResourceClaimsCount, vipPointsPurchasedCount,
                unconfirmedVipPurchasesCount, freeRefreshTapsDispatchedCount, failedActivityActionsCount,
                shortRetriesScheduledThisVisit);
        String stats = resultSummary(freeResourcesClaimedCount, unconfirmedResourceClaimsCount,
                vipPointsPurchasedCount, unconfirmedVipPurchasesCount, freeRefreshTapsDispatchedCount,
                failedActivityActionsCount, shortRetriesScheduledThisVisit);
        if (visitFailure != null) {
            logWarning("Nomadic Merchant visit interrupted by " + visitFailure.getClass().getSimpleName()
                    + " during " + failedDuring + ". Progress " + progress
                    + "; confirmed results kept; " + stats
                    + "; " + scheduleReason + "; next check at " + next.format(DATETIME_FORMATTER) + ".");
        } else if (retrySnapshotType != null) {
            String snapshot = TaskDiagnosticSnapshots.capture(
                    emuManager, EMULATOR_NUMBER, "nomadicmerchant", retrySnapshotType);
            logWarning(retryReason + ". Progress " + progress + " after " + failedDuring
                    + "; confirmed results kept; " + stats
                    + "; " + scheduleReason + "; next check at " + next.format(DATETIME_FORMATTER)
                    + "; " + snapshot + ".");
        } else {
            logInfo(stats + ". Progress " + progress + " after " + failedDuring
                    + ". Next check at " + next.format(DATETIME_FORMATTER) + ".");
        }
    }

    static LocalDateTime nextRun(NomadicMerchantProgress progress, LocalDateTime now,
            LocalDateTime dailyReset) {
        if (progress == NomadicMerchantProgress.COMPLETED
                || progress == NomadicMerchantProgress.PARTIAL_RESCHEDULED
                || progress == NomadicMerchantProgress.FAILED_RESCHEDULED) {
            return dailyReset.plusMinutes(RESET_SETTLE_DELAY_MINUTES);
        }
        return now.plusMinutes(5);
    }

    static NomadicMerchantProgress timeoutProgress(int confirmedOffersThisCycle, int failedTimeoutsThisCycle) {
        if (confirmedOffersThisCycle > 0) {
            return NomadicMerchantProgress.PARTIAL_RESCHEDULED;
        }
        return failedTimeoutsThisCycle >= MAX_FAILED_TIMEOUTS
                ? NomadicMerchantProgress.FAILED_RESCHEDULED
                : NomadicMerchantProgress.FAILED_RETRY;
    }

    boolean navigateToNomadicMerchantShop() {
        return navigationHelper.navigateToShop(ShopTab.NOMADIC_MERCHANT);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    private boolean foundInSlot(TemplatesEnum template, PointData topLeft, PointData bottomRight,
            int threshold) {
        ImageSearchResultData hit = locatePatternChecked(template,
                TemplateSearchHelper.SearchConfig.builder()
                        .withMaxAttempts(1)
                        .withThreshold(threshold)
                        .withDelay(0)
                        .withCoordinates(topLeft, bottomRight)
                        .build());
        return hit.isFound();
    }

    private ImageSearchResultData locatePatternChecked(TemplatesEnum template,
            TemplateSearchHelper.SearchConfig config) {
        ImageSearchResultData hit = templateSearchHelper.locatePattern(template, config);
        if (hit == null) {
            logWarning("Nomadic Merchant scan returned no result for " + template + ". Retrying once.");
            hit = templateSearchHelper.locatePattern(template, config);
        }
        if (hit == null) {
            throw new IllegalStateException("Nomadic Merchant scan returned no result for " + template);
        }
        return hit;
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

    private boolean confirmSlotChanged(int slot, RawImageData before) {
        long started = System.currentTimeMillis();
        while (true) {
            long elapsed = System.currentTimeMillis() - started;
            long remainingToWindow = RESOURCE_CONFIRM_WINDOW_MS - elapsed;
            if (remainingToWindow <= 0) {
                return slotReplaced(slot, before);
            }
            long pause = RESOURCE_CONFIRM_POLL_MS;
            long remainingToSettle = RESOURCE_CONFIRM_MIN_SETTLE_MS - elapsed;
            if (remainingToSettle > 0) {
                pause = Math.max(pause, remainingToSettle);
            }
            sleepTask(Math.min(pause, remainingToWindow));
            elapsed = System.currentTimeMillis() - started;
            boolean replaced = slotReplaced(slot, before);
            if (resourceClaimConfirmed(!replaced, elapsed)) {
                return true;
            }
            if (!replaced && elapsed >= RESOURCE_CONFIRM_WINDOW_MS) {
                return false;
            }
        }
    }

    private boolean slotReplaced(int slot, RawImageData before) {
        RawImageData after = emuManager.captureScreen(EMULATOR_NUMBER);
        double delta = NomadicMerchantDecisions.meanChannelDelta(
                before, after,
                NomadicMerchantDecisions.productTopLeft(slot),
                NomadicMerchantDecisions.productBottomRight(slot),
                8);
        return NomadicMerchantDecisions.slotChanged(delta);
    }

    private ImageSearchResultData locateOffer(TemplatesEnum template, PointData center) {
        int x = center.getX();
        int y = center.getY();
        PointData start = new PointData(
                Math.max(0, x - OFFER_SEARCH_REACH_PX),
                Math.max(0, y - OFFER_SEARCH_REACH_PX));
        PointData end = new PointData(x + OFFER_SEARCH_REACH_PX, y + OFFER_SEARCH_REACH_PX);
        return locatePatternChecked(
                template,
                TemplateSearchHelper.SearchConfig.builder()
                        .withMaxAttempts(1)
                        .withThreshold(90)
                        .withDelay(0)
                        .withCoordinates(start, end)
                        .build());
    }

    private void recordVisitResults(int freeResourcesClaimedCount, int unconfirmedResourceClaimsCount,
            int vipPointsPurchasedCount, int unconfirmedVipPurchasesCount,
            int freeRefreshTapsDispatchedCount, int failedActivityActionsCount,
            int shortRetriesScheduledThisVisit) {
        StatisticsService.obtain().addToCounter(profile, "Nomadic Merchant Free Resources Claimed",
                freeResourcesClaimedCount);
        StatisticsService.obtain().addToCounter(profile, "Nomadic Merchant Unconfirmed Resource Claims",
                unconfirmedResourceClaimsCount);
        StatisticsService.obtain().addToCounter(profile, "Nomadic Merchant VIP Points Purchased",
                vipPointsPurchasedCount);
        StatisticsService.obtain().addToCounter(profile, "Nomadic Merchant Unconfirmed VIP Purchases",
                unconfirmedVipPurchasesCount);
        StatisticsService.obtain().addToCounter(profile, "Nomadic Merchant Free Refresh Taps Dispatched",
                freeRefreshTapsDispatchedCount);
        StatisticsService.obtain().addToCounter(profile, "Nomadic Merchant Failed Activity Actions",
                failedActivityActionsCount);
        StatisticsService.obtain().addToCounter(profile, "Nomadic Merchant Retries Scheduled",
                shortRetriesScheduledThisVisit);
    }

    private static boolean isActionPhase(NomadicMerchantPhase phase) {
        return phase == NomadicMerchantPhase.CLAIMING_RESOURCE
                || phase == NomadicMerchantPhase.WAITING_CLAIM_ANIMATION
                || phase == NomadicMerchantPhase.BUYING_VIP
                || phase == NomadicMerchantPhase.CLAIMING_FREE_REFRESH
                || phase == NomadicMerchantPhase.WAITING_REFRESH;
    }

    private static String resultSummary(int freeResourcesClaimedCount, int unconfirmedResourceClaimsCount,
            int vipPointsPurchasedCount, int unconfirmedVipPurchasesCount,
            int freeRefreshTapsDispatchedCount, int failedActivityActionsCount,
            int shortRetriesScheduledThisVisit) {
        return "Nomadic Merchant stats - free resources claimed: " + freeResourcesClaimedCount
                + ", unconfirmed resource claims: " + unconfirmedResourceClaimsCount
                + ", VIP points purchased: " + vipPointsPurchasedCount
                + ", unconfirmed VIP purchases: " + unconfirmedVipPurchasesCount
                + ", Free Refresh taps dispatched: " + freeRefreshTapsDispatchedCount
                + ", failed activity actions: " + failedActivityActionsCount
                + ", short retries scheduled this visit: " + shortRetriesScheduledThisVisit;
    }
}
