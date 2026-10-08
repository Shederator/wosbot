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
import dev.frostguard.engine.error.ADBConnectionException;
import dev.frostguard.tasks.diagnostics.TaskDiagnosticSnapshots;

import java.time.LocalDateTime;

public class NomadicMerchantRoutine extends DelayedTask {

    private static final long MAX_TASK_EXECUTION_MS = 2 * 60 * 1000L;
    private static final long RESET_SETTLE_DELAY_MINUTES = 1L;
    private static final int MAX_FAILED_TIMEOUTS = 4;
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
    private NomadicMerchantRescheduleReason rescheduleReason;
    private int failedTimeoutsThisCycle;
    private LocalDateTime retryDeferredUntil;
    private int offerActionsThisCycle;
    private int freeRefreshActionsThisCycle;

    public NomadicMerchantRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    NomadicMerchantProgress progress() {
        return progress;
    }

    NomadicMerchantPhase phase() {
        return phase;
    }

    NomadicMerchantRescheduleReason rescheduleReason() {
        return rescheduleReason;
    }

    @Override
    protected void execute() {
        if (retryDeferredUntil != null && !LocalDateTime.now().isBefore(retryDeferredUntil)) {
            failedTimeoutsThisCycle = 0;
            offerActionsThisCycle = 0;
            freeRefreshActionsThisCycle = 0;
            retryDeferredUntil = null;
        }
        phase = NomadicMerchantPhase.OPENING;
        retrySnapshotType = null;
        retryReason = null;
        rescheduleReason = null;
        logInfo("Resuming Nomadic Merchant from " + progress + ".");

        int vipPointsPurchasedCount = 0;
        int unconfirmedVipPurchasesCount = 0;
        int nextResourceSlot = 0;
        VisitResults visitResults = new VisitResults();
        long executionDeadlineMs = executionDeadlineMs();
        boolean complete = false;
        boolean timedOut = false;
        RuntimeException visitFailure = null;

        try {
            if (!navigateToNomadicMerchantShop()) {
                markUnconfirmed("shop-navigation", "Nomadic Merchant shop navigation was not verified");
            } else {
                while (!complete) {
                    phase = NomadicMerchantPhase.SEARCHING_RESOURCES;
                    int resourceSlot = claimResourceOffer(nextResourceSlot, visitResults);
                    if (resourceSlot >= 0) {
                        if (isTimedOut(executionDeadlineMs)) {
                            timedOut = true;
                            break;
                        }
                        nextResourceSlot = (resourceSlot + 1) % NomadicMerchantDecisions.SLOT_COUNT;
                        continue;
                    }

                    ImageSearchResultData vipOffer = findVipOffer();
                    if (vipOffer.isFound()) {
                        if (isTimedOut(executionDeadlineMs)) {
                            timedOut = true;
                            break;
                        }
                        int vipBought = buyVip(vipOffer);
                        visitResults.recordOfferActionDispatched();
                        vipPointsPurchasedCount += Math.max(vipBought, 0);
                        if (vipBought < 0) {
                            unconfirmedVipPurchasesCount++;
                            visitResults.recordFailedActivityAction();
                        }
                        logInfo("VIP purchase attempt dispatched. Rescanning the shop.");
                        continue;
                    }

                    ImageSearchResultData freeRefresh = findFreeRefresh();
                    if (isTimedOut(executionDeadlineMs)) {
                        timedOut = true;
                        break;
                    }
                    if (!freeRefresh.isFound()) {
                        complete = true;
                        continue;
                    }
                    int refreshed = useFreeRefresh(freeRefresh, visitResults);
                    if (retrySnapshotType != null) {
                        visitResults.recordFailedActivityAction();
                        break;
                    }
                    if (refreshed > 0) {
                        nextResourceSlot = 0;
                    }
                }
                if (timedOut) {
                    markUnconfirmed("execution-limit",
                            "Nomadic Merchant task reached execution limit after the latest shop scan");
                }
            }
        } catch (RuntimeException failure) {
            visitFailure = failure;
            throw failure;
        } finally {
            if (visitFailure != null && isActionPhase(phase)) {
                visitResults.recordFailedActivityAction();
            }
            finishVisit(visitResults,
                    vipPointsPurchasedCount, unconfirmedVipPurchasesCount,
                    complete, visitFailure);
        }
    }

    long executionDeadlineMs() {
        return System.currentTimeMillis() + MAX_TASK_EXECUTION_MS;
    }

    int findResourceOffer(int nextResourceSlot) {
        logDebug("Scanning Nomadic Merchant cards for resource-priced offers.");
        for (int offset = 0; offset < NomadicMerchantDecisions.SLOT_COUNT; offset++) {
            int slot = (nextResourceSlot + offset) % NomadicMerchantDecisions.SLOT_COUNT;
            boolean gemOnPrice = foundInSlot(TemplatesEnum.NOMADIC_MERCHANT_GEM_PRICE,
                    NomadicMerchantDecisions.priceTopLeft(slot),
                    NomadicMerchantDecisions.priceBottomRight(slot),
                    GEM_PRICE_THRESHOLD);
            if (NomadicMerchantDecisions.takeIfNotGemPriced(gemOnPrice)) {
                return slot;
            }
        }
        return -1;
    }

    int claimResourceOffer(int nextResourceSlot, VisitResults visitResults) {
        int slot = findResourceOffer(nextResourceSlot);
        if (slot >= 0) {
            collectResourceOffer(slot, visitResults);
        }
        return slot;
    }

    private void collectResourceOffer(int slot, VisitResults visitResults) {
        phase = NomadicMerchantPhase.CLAIMING_RESOURCE;
        logInfo("Found a resource-priced offer in slot " + slot + ". Purchasing it.");
        boolean confirmed = collectOffer(slot);
        visitResults.recordOfferActionDispatched();
        if (confirmed) {
            visitResults.recordFreeResourceClaim();
            logInfo("Resource claim confirmed. Rescanning the full shop for replacement items.");
        } else {
            visitResults.recordUnconfirmedResourceClaim();
            visitResults.recordFailedActivityAction();
            logWarning("Resource claim in slot " + slot
                    + " was not confirmed. Rescanning without assuming the offer changed.");
        }
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
        private int offerActionsDispatchedCount;

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

        void recordOfferActionDispatched() {
            offerActionsDispatchedCount++;
        }

        int offerActionsDispatchedCount() {
            return offerActionsDispatchedCount;
        }
    }

    private ImageSearchResultData findVipOffer() {
        phase = NomadicMerchantPhase.SEARCHING_VIP;
        boolean vipBuyEnabled = profile.getConfig(ConfigurationKeyEnum.BOOL_NOMADIC_MERCHANT_VIP_POINTS,
                Boolean.class);
        if (!vipBuyEnabled) {
            return new ImageSearchResultData(false, null, 0);
        }
        logDebug("VIP purchase is enabled. Searching for VIP points to buy.");
        ImageSearchResultData vipResult = locatePatternChecked(
                TemplatesEnum.NOMADIC_MERCHANT_VIP,
                SearchConfigConstants.DEFAULT_SINGLE);
        return vipResult;
    }

    /** @return 1 when a VIP purchase is confirmed, -1 when it is unconfirmed */
    private int buyVip(ImageSearchResultData vipResult) {
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

    private ImageSearchResultData findFreeRefresh() {
        phase = NomadicMerchantPhase.SEARCHING_FREE_REFRESH;
        logDebug("No selectable offer found. Checking for Free Refresh.");
        return locatePatternChecked(
                TemplatesEnum.MYSTERY_SHOP_DAILY_REFRESH,
                SearchConfigConstants.DEFAULT_SINGLE);
    }

    /** @return 1 when the detected Free Refresh tap was dispatched */
    int useFreeRefresh(ImageSearchResultData dailyRefreshResult, VisitResults visitResults) {
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

    private void finishVisit(VisitResults visitResults, int vipPointsPurchasedCount,
            int unconfirmedVipPurchasesCount,
            boolean complete, RuntimeException visitFailure) {
        NomadicMerchantPhase failedDuring = phase;
        offerActionsThisCycle += visitResults.offerActionsDispatchedCount();
        freeRefreshActionsThisCycle += visitResults.freeRefreshTapsDispatchedCount();
        phase = NomadicMerchantPhase.FINISHED;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next;
        String scheduleReason;
        int shortRetriesScheduledThisVisit = 0;
        if (complete && retrySnapshotType == null && visitFailure == null) {
            progress = completionProgress(offerActionsThisCycle, freeRefreshActionsThisCycle);
            failedTimeoutsThisCycle = 0;
            next = nextRun(progress, now, GameTimeUtils.dailyResetTime());
            retryDeferredUntil = next;
            scheduleReason = "shop exhausted";
        } else if (visitFailure != null || retrySnapshotType != null
                && !"execution-limit".equals(retrySnapshotType)) {
            rescheduleReason = failureReason(visitFailure, retrySnapshotType);
            failedTimeoutsThisCycle++;
            progress = timeoutProgress(offerActionsThisCycle, failedTimeoutsThisCycle);
            if (progress == NomadicMerchantProgress.TIMEOUT_RETRY) {
                shortRetriesScheduledThisVisit = 1;
                next = nextRun(progress, now, now);
                scheduleReason = "navigation, scan, or action error; retry soon";
            } else {
                retryDeferredUntil = GameTimeUtils.dailyResetTime().plusMinutes(RESET_SETTLE_DELAY_MINUTES);
                next = retryDeferredUntil;
                scheduleReason = progress == NomadicMerchantProgress.PARTIAL_RESCHEDULED
                        ? "fourth failed visit after dispatched collections; deferred until daily reset"
                        : "fourth failed visit without dispatched collections; deferred until daily reset";
            }
        } else {
            rescheduleReason = NomadicMerchantRescheduleReason.TIMEOUT;
            failedTimeoutsThisCycle++;
            progress = timeoutProgress(offerActionsThisCycle, failedTimeoutsThisCycle);
            if (progress == NomadicMerchantProgress.TIMEOUT_RETRY) {
                shortRetriesScheduledThisVisit = 1;
                next = nextRun(progress, now, now);
                scheduleReason = "timeout retry " + failedTimeoutsThisCycle + "/" + MAX_FAILED_TIMEOUTS;
            } else {
                retryDeferredUntil = GameTimeUtils.dailyResetTime().plusMinutes(RESET_SETTLE_DELAY_MINUTES);
                next = retryDeferredUntil;
                scheduleReason = progress == NomadicMerchantProgress.PARTIAL_RESCHEDULED
                        ? "fourth timeout after dispatched collections; deferred until daily reset"
                        : "fourth timeout without dispatched collections; deferred until daily reset";
            }
        }
        String snapshot = captureTerminalFailureSnapshot(progress, rescheduleReason);
        reschedule(next);
        recordVisitResults(visitResults.freeResourcesClaimedCount(), visitResults.unconfirmedResourceClaimsCount(), vipPointsPurchasedCount,
                unconfirmedVipPurchasesCount, visitResults.freeRefreshTapsDispatchedCount(), visitResults.failedActivityActionsCount(),
                shortRetriesScheduledThisVisit);
        String stats = resultSummary(visitResults.freeResourcesClaimedCount(), visitResults.unconfirmedResourceClaimsCount(),
                vipPointsPurchasedCount, unconfirmedVipPurchasesCount, visitResults.freeRefreshTapsDispatchedCount(),
                visitResults.failedActivityActionsCount(), shortRetriesScheduledThisVisit);
        if (visitFailure != null) {
            logWarning("Nomadic Merchant visit interrupted by " + visitFailure.getClass().getSimpleName()
                    + " during " + failedDuring + ". Progress " + progress
                    + "; reschedule reason " + rescheduleReason.code() + "; confirmed results kept; " + stats
                    + "; " + scheduleReason + "; next check at " + next.format(DATETIME_FORMATTER)
                    + snapshotSuffix(snapshot) + ".");
        } else if (retrySnapshotType != null) {
            logWarning(retryReason + ". Progress " + progress + " after " + failedDuring
                    + "; reschedule reason " + rescheduleReason.code() + "; confirmed results kept; " + stats
                    + "; " + scheduleReason + "; next check at " + next.format(DATETIME_FORMATTER)
                    + snapshotSuffix(snapshot) + ".");
        } else {
            logInfo(stats + ". Progress " + progress + " after " + failedDuring
                    + ". Next check at " + next.format(DATETIME_FORMATTER) + ".");
        }
    }

    static LocalDateTime nextRun(NomadicMerchantProgress progress, LocalDateTime now,
            LocalDateTime dailyReset) {
        if (progress == NomadicMerchantProgress.COMPLETED_SUCCESS_RESCHEDULED
                || progress == NomadicMerchantProgress.COMPLETED_UNVERIFIED
                || progress == NomadicMerchantProgress.PARTIAL_RESCHEDULED
                || progress == NomadicMerchantProgress.FAILED_RESCHEDULED) {
            return dailyReset.plusMinutes(RESET_SETTLE_DELAY_MINUTES);
        }
        return now.plusMinutes(5);
    }

    static NomadicMerchantProgress timeoutProgress(int offerActionsThisCycle, int failedTimeoutsThisCycle) {
        if (failedTimeoutsThisCycle < MAX_FAILED_TIMEOUTS) {
            return NomadicMerchantProgress.TIMEOUT_RETRY;
        }
        return offerActionsThisCycle > 0
                ? NomadicMerchantProgress.PARTIAL_RESCHEDULED
                : NomadicMerchantProgress.FAILED_RESCHEDULED;
    }

    static NomadicMerchantProgress completionProgress(int offerActionsThisCycle, int freeRefreshActionsThisCycle) {
        return offerActionsThisCycle > 0 || freeRefreshActionsThisCycle > 0
                ? NomadicMerchantProgress.COMPLETED_SUCCESS_RESCHEDULED
                : NomadicMerchantProgress.COMPLETED_UNVERIFIED;
    }

    static boolean shouldCaptureTerminalFailureSnapshot(NomadicMerchantProgress progress,
            NomadicMerchantRescheduleReason reason) {
        return (progress == NomadicMerchantProgress.PARTIAL_RESCHEDULED
                || progress == NomadicMerchantProgress.FAILED_RESCHEDULED)
                && (reason == NomadicMerchantRescheduleReason.NAVIGATION_ERROR
                || reason == NomadicMerchantRescheduleReason.NO_COLLECT_ERROR);
    }

    private String captureTerminalFailureSnapshot(NomadicMerchantProgress progress,
            NomadicMerchantRescheduleReason reason) {
        if (reason == NomadicMerchantRescheduleReason.ADB_ERROR
                && (progress == NomadicMerchantProgress.PARTIAL_RESCHEDULED
                || progress == NomadicMerchantProgress.FAILED_RESCHEDULED)) {
            return "snapshot=unavailable; reason=adb_error";
        }
        if (!shouldCaptureTerminalFailureSnapshot(progress, reason)) {
            return null;
        }
        return TaskDiagnosticSnapshots.capture(
                emuManager, EMULATOR_NUMBER, "nomadicmerchant", "terminal-" + reason.code());
    }

    private static String snapshotSuffix(String snapshot) {
        return snapshot == null ? "" : "; " + snapshot;
    }

    private static NomadicMerchantRescheduleReason failureReason(RuntimeException visitFailure,
            String retrySnapshotType) {
        if (visitFailure instanceof ADBConnectionException) {
            return NomadicMerchantRescheduleReason.ADB_ERROR;
        }
        if ("shop-navigation".equals(retrySnapshotType)) {
            return NomadicMerchantRescheduleReason.NAVIGATION_ERROR;
        }
        return NomadicMerchantRescheduleReason.NO_COLLECT_ERROR;
    }

    private static boolean isTimedOut(long executionDeadlineMs) {
        return System.currentTimeMillis() >= executionDeadlineMs;
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
