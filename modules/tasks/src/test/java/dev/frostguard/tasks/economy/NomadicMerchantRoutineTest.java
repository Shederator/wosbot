package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.TemplateSearchHelper;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.error.ADBConnectionException;
import dev.frostguard.vision.convert.GameTimeUtils;

class NomadicMerchantRoutineTest {

    @BeforeAll
    static void createTestWorkspace() throws IOException {
        Files.createDirectories(WorkspacePaths.current().root());
    }

    @Test
    void stopsAndReschedulesWhenSharedShopNavigationFails() {
        TestRoutine routine = new TestRoutine();
        LocalDateTime before = LocalDateTime.now().plusMinutes(4);

        routine.execute();

        assertTrue(routine.navigationAttempted);
        assertTrue(routine.scheduledTime().isAfter(before));
        assertEquals(NomadicMerchantProgress.TIMEOUT_RETRY, routine.progress());
        assertEquals(NomadicMerchantPhase.FINISHED, routine.phase());
    }

    @Test
    void fourthNavigationFailureDefersUntilResetWithoutSkippingTheScan() {
        TestRoutine routine = new TestRoutine();

        routine.execute();
        assertEquals(NomadicMerchantProgress.TIMEOUT_RETRY, routine.progress());
        assertEquals(NomadicMerchantRescheduleReason.NAVIGATION_ERROR, routine.rescheduleReason());

        routine.execute();
        assertEquals(NomadicMerchantProgress.TIMEOUT_RETRY, routine.progress());

        routine.execute();
        assertEquals(NomadicMerchantProgress.TIMEOUT_RETRY, routine.progress());

        routine.execute();
        assertEquals(NomadicMerchantProgress.FAILED_RESCHEDULED, routine.progress());
        assertEquals(NomadicMerchantRescheduleReason.NAVIGATION_ERROR, routine.rescheduleReason());
        assertEquals(GameTimeUtils.dailyResetTime().plusMinutes(1), routine.scheduledTime());
        assertEquals(4, routine.navigationAttempts);
        assertEquals(NomadicMerchantPhase.FINISHED, routine.phase());
    }

    @Test
    void retriesAfterResourceScanFailureWithoutRecordingCompletion() {
        ADBConnectionException expectedFailure = new ADBConnectionException("synthetic capture failure");
        FailingResourceScanRoutine routine = new FailingResourceScanRoutine(expectedFailure);
        LocalDateTime before = LocalDateTime.now();

        ADBConnectionException failure = assertThrows(ADBConnectionException.class, routine::execute);

        assertSame(expectedFailure, failure);
        assertEquals(NomadicMerchantProgress.TIMEOUT_RETRY, routine.progress());
        assertEquals(NomadicMerchantRescheduleReason.ADB_ERROR, routine.rescheduleReason());
        assertEquals(NomadicMerchantPhase.FINISHED, routine.phase());
        assertTrue(routine.scheduledTime().isAfter(before.plusMinutes(4)));
        assertTrue(routine.scheduledTime().isBefore(LocalDateTime.now().plusMinutes(6)));
        assertEquals(1, routine.visitResults().freeResourcesClaimedCount());
    }

    @Test
    void retriesNullScanOnceThenSchedulesErrorWithoutCompletion() {
        NullScanRoutine routine = new NullScanRoutine();
        LocalDateTime before = LocalDateTime.now();

        assertThrows(IllegalStateException.class, routine::execute);

        assertEquals(2, routine.scanAttempts);
        assertEquals(NomadicMerchantProgress.TIMEOUT_RETRY, routine.progress());
        assertEquals(NomadicMerchantRescheduleReason.NO_COLLECT_ERROR, routine.rescheduleReason());
        assertTrue(routine.scheduledTime().isAfter(before.plusMinutes(4)));
        assertTrue(routine.scheduledTime().isBefore(LocalDateTime.now().plusMinutes(6)));
    }

    @Test
    void fourthTimeoutWithoutCalledCollectionsDefersUntilReset() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 6, 10, 0);
        LocalDateTime reset = now.plusHours(16);
        assertEquals(NomadicMerchantProgress.TIMEOUT_RETRY,
                NomadicMerchantRoutine.timeoutProgress(0, 1));
        assertEquals(NomadicMerchantProgress.TIMEOUT_RETRY,
                NomadicMerchantRoutine.timeoutProgress(0, 2));
        assertEquals(NomadicMerchantProgress.TIMEOUT_RETRY,
                NomadicMerchantRoutine.timeoutProgress(0, 3));
        assertEquals(NomadicMerchantProgress.FAILED_RESCHEDULED,
                NomadicMerchantRoutine.timeoutProgress(0, 4));
        assertEquals(now.plusMinutes(5), NomadicMerchantRoutine.nextRun(
                NomadicMerchantRoutine.timeoutProgress(0, 3), now, reset));
        assertEquals(reset.plusMinutes(1), NomadicMerchantRoutine.nextRun(
                NomadicMerchantRoutine.timeoutProgress(0, 4), now, reset));
    }

    @Test
    void continuesResourceScanAfterUnknownOfferAndCountsConfirmedOffer() {
        ScanningResourceRoutine routine = new ScanningResourceRoutine();
        NomadicMerchantRoutine.VisitResults results = new NomadicMerchantRoutine.VisitResults();

        int firstSlot = routine.claimResourceOffer(0, results);
        int secondSlot = routine.claimResourceOffer(firstSlot + 1, results);

        assertEquals(0, firstSlot);
        assertEquals(1, secondSlot);
        assertEquals(List.of(0, 1), routine.attemptedSlots);
        assertEquals(1, results.freeResourcesClaimedCount());
        assertEquals(1, results.unconfirmedResourceClaimsCount());
        assertEquals(1, results.failedActivityActionsCount());
    }

    @Test
    void fourthTimeoutWithCalledCollectionIsPartial() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 6, 10, 0);
        LocalDateTime reset = now.plusHours(16);
        assertEquals(NomadicMerchantProgress.TIMEOUT_RETRY,
                NomadicMerchantRoutine.timeoutProgress(1, 1));
        assertEquals(reset.plusMinutes(1), NomadicMerchantRoutine.nextRun(
                NomadicMerchantRoutine.timeoutProgress(1, 4), now, reset));
    }

    @Test
    void completionStateUsesDispatchedActionsRatherThanConfirmation() {
        assertEquals(NomadicMerchantProgress.COMPLETED_UNVERIFIED,
                NomadicMerchantRoutine.completionProgress(0, 0));
        assertEquals(NomadicMerchantProgress.COMPLETED_SUCCESS_RESCHEDULED,
                NomadicMerchantRoutine.completionProgress(1, 0));
        assertEquals(NomadicMerchantProgress.COMPLETED_SUCCESS_RESCHEDULED,
                NomadicMerchantRoutine.completionProgress(0, 1));
    }

    @Test
    void fourVisitsThatTimeOutWithoutClaimsScheduleTheFourthAfterReset() {
        TimedOutRoutine routine = new TimedOutRoutine(false);
        for (int visit = 1; visit <= 4; visit++) {
            LocalDateTime before = LocalDateTime.now();
            LocalDateTime reset = GameTimeUtils.dailyResetTime();

            routine.execute();

            assertEquals(visit < 4 ? NomadicMerchantProgress.TIMEOUT_RETRY
                    : NomadicMerchantProgress.FAILED_RESCHEDULED, routine.progress());
            if (visit < 4) {
                assertTrue(routine.scheduledTime().isAfter(before.plusMinutes(4)));
                assertTrue(routine.scheduledTime().isBefore(LocalDateTime.now().plusMinutes(6)));
            } else {
                assertEquals(reset.plusMinutes(1), routine.scheduledTime());
            }
        }
        assertEquals(4, routine.scans);
    }

    @Test
    void partialRescheduleKeepsTimeoutReasonAfterProgressThenStall() {
        TimedOutRoutine routine = new TimedOutRoutine(true);
        LocalDateTime reset = GameTimeUtils.dailyResetTime();

        routine.execute();
        assertEquals(NomadicMerchantProgress.TIMEOUT_RETRY, routine.progress());
        routine.execute();
        assertEquals(NomadicMerchantProgress.TIMEOUT_RETRY, routine.progress());
        routine.execute();
        assertEquals(NomadicMerchantProgress.TIMEOUT_RETRY, routine.progress());
        routine.execute();

        assertEquals(NomadicMerchantProgress.PARTIAL_RESCHEDULED, routine.progress());
        assertEquals(NomadicMerchantRescheduleReason.TIMEOUT, routine.rescheduleReason());
        assertEquals(reset.plusMinutes(1), routine.scheduledTime());
        assertEquals(4, routine.scans);
    }

    @Test
    void partialRescheduleKeepsNavigationReasonWhenProgressedVisitIsFollowedByNavigationFailures() {
        ProgressThenNavigationFailureRoutine routine = new ProgressThenNavigationFailureRoutine();

        routine.execute();
        assertEquals(NomadicMerchantProgress.TIMEOUT_RETRY, routine.progress());
        assertEquals(NomadicMerchantRescheduleReason.TIMEOUT, routine.rescheduleReason());

        routine.execute();
        assertEquals(NomadicMerchantProgress.TIMEOUT_RETRY, routine.progress());
        assertEquals(NomadicMerchantRescheduleReason.NAVIGATION_ERROR, routine.rescheduleReason());

        routine.execute();
        assertEquals(NomadicMerchantProgress.TIMEOUT_RETRY, routine.progress());
        assertEquals(NomadicMerchantRescheduleReason.NAVIGATION_ERROR, routine.rescheduleReason());

        routine.execute();

        assertEquals(NomadicMerchantProgress.PARTIAL_RESCHEDULED, routine.progress());
        assertEquals(NomadicMerchantRescheduleReason.NAVIGATION_ERROR, routine.rescheduleReason());
        assertEquals(GameTimeUtils.dailyResetTime().plusMinutes(1), routine.scheduledTime());
        assertEquals(1, routine.scans);
    }

    @Test
    void schedulesFromRememberedProgress() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 30, 10, 0);
        LocalDateTime reset = LocalDateTime.of(2026, 10, 1, 2, 0);

        assertEquals(now.plusMinutes(5), NomadicMerchantRoutine.nextRun(
                NomadicMerchantProgress.READY, now, reset));
        assertEquals(now.plusMinutes(5), NomadicMerchantRoutine.nextRun(
                NomadicMerchantProgress.TIMEOUT_RETRY, now, reset));
        assertEquals(reset.plusMinutes(1), NomadicMerchantRoutine.nextRun(
                NomadicMerchantProgress.COMPLETED_SUCCESS_RESCHEDULED, now, reset));
        assertEquals(reset.plusMinutes(1), NomadicMerchantRoutine.nextRun(
                NomadicMerchantProgress.COMPLETED_UNVERIFIED, now, reset));
        assertEquals(reset.plusMinutes(1), NomadicMerchantRoutine.nextRun(
                NomadicMerchantProgress.PARTIAL_RESCHEDULED, now, reset));
        assertEquals(reset.plusMinutes(1), NomadicMerchantRoutine.nextRun(
                NomadicMerchantProgress.FAILED_RESCHEDULED, now, reset));
    }

    @Test
    void namesTheVipPurchaseTaps() {
        assertEquals(100, NomadicMerchantRoutine.VIP_PURCHASE_OFFSET_Y);
        assertEquals(368, NomadicMerchantRoutine.VIP_BUY_WITH_GEMS.getX());
        assertEquals(830, NomadicMerchantRoutine.VIP_BUY_WITH_GEMS.getY());
        assertEquals(355, NomadicMerchantRoutine.VIP_CONFIRM.getX());
        assertEquals(788, NomadicMerchantRoutine.VIP_CONFIRM.getY());
    }

    @Test
    void keepsASlightlyShiftedIconAsTheSameOffer() {
        PointData tapped = new PointData(100, 500);
        ImageSearchResultData samePoint = new ImageSearchResultData(true, new PointData(100, 500), 95);
        ImageSearchResultData shifted = new ImageSearchResultData(true, new PointData(112, 508), 95);
        ImageSearchResultData otherCard = new ImageSearchResultData(true, new PointData(400, 700), 95);

        assertTrue(NomadicMerchantRoutine.sameOffer(tapped, samePoint));
        assertTrue(NomadicMerchantRoutine.sameOffer(tapped, shifted));
        assertFalse(NomadicMerchantRoutine.sameOffer(tapped, otherCard));
        assertFalse(NomadicMerchantRoutine.sameOffer(tapped, new ImageSearchResultData(false, null, 0)));
    }

    @Test
    void tapsThePriceStripRatherThanTheProductIcon() {
        var topTap = NomadicMerchantDecisions.priceTap(0);
        var topPrice = NomadicMerchantDecisions.priceTopLeft(0);
        var bottomTap = NomadicMerchantDecisions.priceTap(5);
        var bottomPrice = NomadicMerchantDecisions.priceTopLeft(5);

        assertTrue(topTap.getY() >= topPrice.getY());
        assertTrue(bottomTap.getY() >= bottomPrice.getY());
        assertTrue(topTap.getY() < NomadicMerchantDecisions.TOP_SLOT_BOTTOM);
        assertTrue(bottomTap.getY() < NomadicMerchantDecisions.BOTTOM_SLOT_BOTTOM);
        assertTrue(topTap.getY() > 625);
        assertTrue(bottomTap.getY() > 720);
    }

    @Test
    void waitsOutTheRewardFlyoutBeforeTreatingAMissAsClaimed() {
        assertFalse(NomadicMerchantRoutine.resourceClaimConfirmed(
                false, NomadicMerchantRoutine.RESOURCE_CONFIRM_MIN_SETTLE_MS - 1));
        assertFalse(NomadicMerchantRoutine.resourceClaimConfirmed(
                true, NomadicMerchantRoutine.RESOURCE_CONFIRM_WINDOW_MS));
        assertTrue(NomadicMerchantRoutine.resourceClaimConfirmed(
                false, NomadicMerchantRoutine.RESOURCE_CONFIRM_MIN_SETTLE_MS));
    }

    @Test
    void dispatchesOneTapPerRefreshSearchAndRescansResourcesBetweenTaps() {
        RefreshLoopRoutine routine = new RefreshLoopRoutine(false);

        routine.execute();

        assertEquals(List.of("resource-scan", "refresh-search", "tap", "wait-500",
                "resource-scan", "resource-claimed", "resource-scan", "refresh-search", "tap", "wait-500",
                "resource-scan", "refresh-search"), routine.events);
        assertEquals(2, routine.refreshTaps);
        assertEquals(List.of(500L, 500L), routine.waits);
        assertEquals(NomadicMerchantProgress.COMPLETED_SUCCESS_RESCHEDULED, routine.progress());
        assertEquals(2, routine.visitResults.freeRefreshTapsDispatchedCount());
        assertEquals(1, routine.visitResults.freeResourcesClaimedCount());
    }

    @Test
    void retriesVisibleVipUntilItIsNoLongerDetected() {
        RepeatedVipRoutine routine = new RepeatedVipRoutine();

        routine.execute();

        assertEquals(2, routine.vipPurchaseAttempts);
        assertEquals(3, routine.vipSearches);
        assertEquals(3, routine.resourceScans);
        assertEquals(NomadicMerchantProgress.COMPLETED_SUCCESS_RESCHEDULED, routine.progress());
    }

    @Test
    void unconfirmedResourceOfferDoesNotPreventFreeRefreshOrCompletion() {
        UnknownResourceRefreshRoutine routine = new UnknownResourceRefreshRoutine(false);

        routine.execute();

        assertEquals(1, routine.resourceAttempts);
        assertEquals(2, routine.refreshSearches);
        assertEquals(1, routine.refreshTaps);
        assertEquals(NomadicMerchantProgress.COMPLETED_SUCCESS_RESCHEDULED, routine.progress());
        assertEquals(1, routine.visitResults.unconfirmedResourceClaimsCount());
        assertEquals(1, routine.visitResults.freeRefreshTapsDispatchedCount());
    }

    @Test
    void collectionConfirmationDoesNotChangeTheScanAndRefreshLoop() {
        UnknownResourceRefreshRoutine unconfirmed = new UnknownResourceRefreshRoutine(false);
        UnknownResourceRefreshRoutine confirmed = new UnknownResourceRefreshRoutine(true);

        unconfirmed.execute();
        confirmed.execute();

        assertEquals(unconfirmed.loopEvents, confirmed.loopEvents);
        assertEquals(1, unconfirmed.resourceAttempts);
        assertEquals(1, confirmed.resourceAttempts);
        assertEquals(1, unconfirmed.visitResults.unconfirmedResourceClaimsCount());
        assertEquals(0, unconfirmed.visitResults.freeResourcesClaimedCount());
        assertEquals(0, confirmed.visitResults.unconfirmedResourceClaimsCount());
        assertEquals(1, confirmed.visitResults.freeResourcesClaimedCount());
        assertEquals(NomadicMerchantProgress.COMPLETED_SUCCESS_RESCHEDULED, unconfirmed.progress());
        assertEquals(NomadicMerchantProgress.COMPLETED_SUCCESS_RESCHEDULED, confirmed.progress());
        assertEquals(2, unconfirmed.refreshSearches);
        assertEquals(2, confirmed.refreshSearches);
        assertEquals(1, unconfirmed.refreshTaps);
        assertEquals(1, confirmed.refreshTaps);
    }

    @Test
    void doesNotCountRefreshTapWhenAdbThrowsDuringDispatch() {
        ADBConnectionException expectedFailure = new ADBConnectionException("synthetic tap failure");
        RefreshLoopRoutine routine = new RefreshLoopRoutine(true);
        routine.tapFailure = expectedFailure;
        LocalDateTime before = LocalDateTime.now();

        ADBConnectionException failure = assertThrows(ADBConnectionException.class, routine::execute);

        assertSame(expectedFailure, failure);
        assertEquals(List.of("resource-scan", "refresh-search", "tap"), routine.events);
        assertEquals(NomadicMerchantProgress.TIMEOUT_RETRY, routine.progress());
        assertTrue(routine.scheduledTime().isAfter(before.plusMinutes(4)));
        assertTrue(routine.scheduledTime().isBefore(LocalDateTime.now().plusMinutes(6)));
        assertEquals(0, routine.visitResults.freeRefreshTapsDispatchedCount());
    }

    @Test
    void retriesWithoutCompletionWhenRefreshTapCannotBeSent() {
        RefreshLoopRoutine routine = new RefreshLoopRoutine(true);
        routine.tapReturnsFalse = true;
        LocalDateTime before = LocalDateTime.now();

        routine.execute();

        assertEquals(List.of("resource-scan", "refresh-search", "tap", "tap"), routine.events);
        assertEquals(NomadicMerchantProgress.TIMEOUT_RETRY, routine.progress());
        assertTrue(routine.scheduledTime().isAfter(before.plusMinutes(4)));
        assertTrue(routine.scheduledTime().isBefore(LocalDateTime.now().plusMinutes(6)));
        assertEquals(0, routine.visitResults.freeRefreshTapsDispatchedCount());
    }

    @Test
    void countsRefreshTapWhenTheFollowingSettleWaitFails() {
        RefreshLoopRoutine routine = new RefreshLoopRoutine(true);
        routine.waitFailure = new ADBConnectionException("synthetic settle interruption");

        ADBConnectionException failure = assertThrows(ADBConnectionException.class, routine::execute);

        assertSame(routine.waitFailure, failure);
        assertEquals(List.of("resource-scan", "refresh-search", "tap", "wait-500"), routine.events);
        assertEquals(NomadicMerchantProgress.TIMEOUT_RETRY, routine.progress());
        assertEquals(1, routine.visitResults.freeRefreshTapsDispatchedCount());
    }

    private static final class TestRoutine extends NomadicMerchantRoutine {
        private boolean navigationAttempted;
        private int navigationAttempts;

        private TestRoutine() {
            super(new AccountDescriptor(1L, "Test", "1", true, 1L, 30L),
                    TpDailyTaskEnum.NOMADIC_MERCHANT);
        }

        @Override
        boolean navigateToNomadicMerchantShop() {
            navigationAttempted = true;
            navigationAttempts++;
            return false;
        }

        private LocalDateTime scheduledTime() {
            return scheduledTime;
        }
    }

    private static final class FailingResourceScanRoutine extends NomadicMerchantRoutine {
        private final ADBConnectionException failure;
        private VisitResults visitResults;

        private FailingResourceScanRoutine(ADBConnectionException failure) {
            super(new AccountDescriptor(1L, "Test", "1", true, 1L, 30L),
                    TpDailyTaskEnum.NOMADIC_MERCHANT);
            this.failure = failure;
        }

        @Override
        boolean navigateToNomadicMerchantShop() {
            return true;
        }

        @Override
        int claimResourceOffer(int nextResourceSlot, VisitResults visitResults) {
            this.visitResults = visitResults;
            visitResults.recordFreeResourceClaim();
            throw failure;
        }

        private LocalDateTime scheduledTime() {
            return scheduledTime;
        }

        private VisitResults visitResults() {
            return visitResults;
        }
    }

    private static final class NullScanRoutine extends NomadicMerchantRoutine {
        private int scanAttempts;

        private NullScanRoutine() {
            super(new AccountDescriptor(1L, "NullScanTest", "1", true, 1L, 30L),
                    TpDailyTaskEnum.NOMADIC_MERCHANT);
            templateSearchHelper = new TemplateSearchHelper(null, "1", profile) {
                @Override
                public ImageSearchResultData locatePattern(TemplatesEnum template, SearchConfig config) {
                    scanAttempts++;
                    return null;
                }
            };
        }

        @Override
        boolean navigateToNomadicMerchantShop() {
            return true;
        }

        private LocalDateTime scheduledTime() {
            return scheduledTime;
        }
    }

    private static final class ScanningResourceRoutine extends NomadicMerchantRoutine {
        private final java.util.ArrayList<Integer> attemptedSlots = new java.util.ArrayList<>();

        private ScanningResourceRoutine() {
            super(new AccountDescriptor(5L, "ScanTest", "5", true, 1L, 30L),
                    TpDailyTaskEnum.NOMADIC_MERCHANT);
            templateSearchHelper = new TemplateSearchHelper(null, "5", profile) {
                @Override
                public ImageSearchResultData locatePattern(TemplatesEnum template, SearchConfig config) {
                    int slotLeft = config.getStartPoint().getX();
                    boolean gemPriced = config.getStartPoint().getY()
                            >= NomadicMerchantDecisions.priceTopLeft(3).getY()
                            || slotLeft >= NomadicMerchantDecisions.slotLeft(2)
                            || (slotLeft == NomadicMerchantDecisions.slotLeft(1)
                                    && attemptedSlots.contains(1));
                    return new ImageSearchResultData(gemPriced, null, gemPriced ? 90 : 0);
                }
            };
        }

        @Override
        boolean collectOffer(int slot) {
            attemptedSlots.add(slot);
            return slot == 1;
        }
    }

    private static final class RefreshLoopRoutine extends NomadicMerchantRoutine {
        private final java.util.ArrayList<String> events = new java.util.ArrayList<>();
        private final java.util.ArrayList<Long> waits = new java.util.ArrayList<>();
        private final boolean failOnFirstTap;
        private RuntimeException tapFailure;
        private RuntimeException waitFailure;
        private boolean tapReturnsFalse;
        private int refreshTaps;
        private int searches;
        private VisitResults visitResults;

        private RefreshLoopRoutine(boolean failOnFirstTap) {
            super(new AccountDescriptor(3L, "RefreshTest", "3", true, 1L, 30L),
                    TpDailyTaskEnum.NOMADIC_MERCHANT);
            this.failOnFirstTap = failOnFirstTap;
            this.templateSearchHelper = new TemplateSearchHelper(null, "3", profile) {
                @Override
                public ImageSearchResultData locatePattern(TemplatesEnum template, SearchConfig config) {
                    if (template != TemplatesEnum.MYSTERY_SHOP_DAILY_REFRESH) {
                        return new ImageSearchResultData(false, null, 0);
                    }
                    events.add("refresh-search");
                    searches++;
                    return searches <= (failOnFirstTap ? 1 : 2)
                            ? new ImageSearchResultData(true, new PointData(400, 900), 95)
                            : new ImageSearchResultData(false, null, 0);
                }
            };
        }

        @Override
        boolean navigateToNomadicMerchantShop() {
            return true;
        }

        @Override
        int claimResourceOffer(int nextResourceSlot, VisitResults visitResults) {
            this.visitResults = visitResults;
            events.add("resource-scan");
            if (events.stream().filter("resource-scan"::equals).count() == 2) {
                events.add("resource-claimed");
                visitResults.recordFreeResourceClaim();
                return 1;
            }
            return -1;
        }

        @Override
        public boolean tapInside(ImageSearchResultData result) {
            events.add("tap");
            if (failOnFirstTap && tapFailure != null) {
                throw tapFailure;
            }
            if (tapReturnsFalse) {
                return false;
            }
            refreshTaps++;
            return true;
        }

        @Override
        protected void sleepTask(long millis) {
            events.add("wait-" + millis);
            waits.add(millis);
            if (millis == REFRESH_ACTION_SETTLE_MS && waitFailure != null) {
                throw waitFailure;
            }
        }

        private LocalDateTime scheduledTime() {
            return scheduledTime;
        }

    }

    private static final class RepeatedVipRoutine extends NomadicMerchantRoutine {
        private int resourceScans;
        private int vipSearches;
        private int vipConfirmationSearches;
        private int vipPurchaseAttempts;

        private RepeatedVipRoutine() {
            super(new AccountDescriptor(6L, "VipTest", "6", true, 1L, 30L),
                    TpDailyTaskEnum.NOMADIC_MERCHANT);
            profile.setConfig(ConfigurationKeyEnum.BOOL_NOMADIC_MERCHANT_VIP_POINTS, true);
            templateSearchHelper = new TemplateSearchHelper(null, "6", profile) {
                @Override
                public ImageSearchResultData locatePattern(TemplatesEnum template, SearchConfig config) {
                    if (template == TemplatesEnum.NOMADIC_MERCHANT_VIP) {
                        if (config.hasCoordinates()) {
                            vipConfirmationSearches++;
                            boolean stillPresent = vipConfirmationSearches == 1;
                            return new ImageSearchResultData(stillPresent,
                                    stillPresent ? new PointData(300, 500) : null, 95);
                        }
                        vipSearches++;
                        boolean found = vipSearches <= 2;
                        return new ImageSearchResultData(found,
                                found ? new PointData(300, 500) : null, 95);
                    }
                    return new ImageSearchResultData(false, null, 0);
                }
            };
        }

        @Override
        boolean navigateToNomadicMerchantShop() {
            return true;
        }

        @Override
        int claimResourceOffer(int nextResourceSlot, VisitResults visitResults) {
            resourceScans++;
            return -1;
        }

        @Override
        public void tapNear(PointData point) {
            if (point.equals(VIP_CONFIRM)) {
                vipPurchaseAttempts++;
            }
        }

        @Override
        protected void sleepTask(long millis) {
        }

    }

    private static final class TimedOutRoutine extends NomadicMerchantRoutine {
        private final boolean confirmClaim;
        private int scans;

        private TimedOutRoutine(boolean confirmClaim) {
            super(new AccountDescriptor(confirmClaim ? 7L : 8L, "TimeoutTest", "7", true, 1L, 30L),
                    TpDailyTaskEnum.NOMADIC_MERCHANT);
            this.confirmClaim = confirmClaim;
        }

        @Override
        boolean navigateToNomadicMerchantShop() {
            return true;
        }

        @Override
        long executionDeadlineMs() {
            return System.currentTimeMillis() + 200;
        }

        @Override
        int claimResourceOffer(int nextResourceSlot, VisitResults visitResults) {
            scans++;
            try {
                Thread.sleep(250);
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interruption);
            }
            if (confirmClaim) {
                visitResults.recordFreeResourceClaim();
                visitResults.recordOfferActionDispatched();
            }
            return 0;
        }

        private LocalDateTime scheduledTime() {
            return scheduledTime;
        }
    }

    private static final class ProgressThenNavigationFailureRoutine extends NomadicMerchantRoutine {
        private int visits;
        private int scans;

        private ProgressThenNavigationFailureRoutine() {
            super(new AccountDescriptor(9L, "PartialNavigationTest", "9", true, 1L, 30L),
                    TpDailyTaskEnum.NOMADIC_MERCHANT);
        }

        @Override
        boolean navigateToNomadicMerchantShop() {
            return ++visits == 1;
        }

        @Override
        long executionDeadlineMs() {
            return System.currentTimeMillis() + 200;
        }

        @Override
        int claimResourceOffer(int nextResourceSlot, VisitResults visitResults) {
            scans++;
            visitResults.recordOfferActionDispatched();
            try {
                Thread.sleep(250);
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interruption);
            }
            return 0;
        }

        private LocalDateTime scheduledTime() {
            return scheduledTime;
        }
    }

    private static final class UnknownResourceRefreshRoutine extends NomadicMerchantRoutine {
        private final boolean confirmClaim;
        private final java.util.ArrayList<String> loopEvents = new java.util.ArrayList<>();
        private VisitResults visitResults;
        private int resourceAttempts;
        private int refreshSearches;
        private int refreshTaps;

        private UnknownResourceRefreshRoutine(boolean confirmClaim) {
            super(new AccountDescriptor(3L, "UnknownRefreshTest", "3", true, 1L, 30L),
                    TpDailyTaskEnum.NOMADIC_MERCHANT);
            this.confirmClaim = confirmClaim;
            profile.setConfig(ConfigurationKeyEnum.BOOL_NOMADIC_MERCHANT_VIP_POINTS, false);
            templateSearchHelper = new TemplateSearchHelper(null, "3", profile) {
                @Override
                public ImageSearchResultData locatePattern(TemplatesEnum template, SearchConfig config) {
                    if (template == TemplatesEnum.NOMADIC_MERCHANT_GEM_PRICE) {
                        boolean selectable = resourceAttempts == 0
                                && config.getStartPoint().getX() == NomadicMerchantDecisions.slotLeft(0)
                                && config.getStartPoint().getY() == NomadicMerchantDecisions.priceTopLeft(0).getY();
                        return new ImageSearchResultData(!selectable, null, 90);
                    }
                    if (template == TemplatesEnum.MYSTERY_SHOP_DAILY_REFRESH) {
                        refreshSearches++;
                        loopEvents.add("refresh-search");
                        boolean available = refreshSearches == 1;
                        return new ImageSearchResultData(available,
                                available ? new PointData(400, 900) : null, 95);
                    }
                    return new ImageSearchResultData(false, null, 0);
                }
            };
        }

        @Override
        boolean navigateToNomadicMerchantShop() {
            return true;
        }

        @Override
        int claimResourceOffer(int nextResourceSlot, VisitResults visitResults) {
            this.visitResults = visitResults;
            loopEvents.add("resource-scan");
            return super.claimResourceOffer(nextResourceSlot, visitResults);
        }

        @Override
        boolean collectOffer(int slot) {
            resourceAttempts++;
            return confirmClaim;
        }

        @Override
        public boolean tapInside(ImageSearchResultData result) {
            loopEvents.add("refresh-tap");
            refreshTaps++;
            return true;
        }

        @Override
        protected void sleepTask(long millis) {
            loopEvents.add("wait-" + millis);
        }

    }
}
