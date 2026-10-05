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

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.TemplateSearchHelper;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.error.ADBConnectionException;
import dev.frostguard.engine.service.StatisticsService;
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
        assertEquals(NomadicMerchantProgress.FAILED_RETRY, routine.progress());
        assertEquals(NomadicMerchantPhase.FINISHED, routine.phase());
    }

    @Test
    void remembersUnconfirmedProgressAcrossVisitsWithoutSkippingTheScan() {
        TestRoutine routine = new TestRoutine();

        routine.execute();
        assertEquals(NomadicMerchantProgress.FAILED_RETRY, routine.progress());

        routine.execute();
        assertEquals(NomadicMerchantProgress.FAILED_RETRY, routine.progress());
        assertEquals(2, routine.navigationAttempts);
        assertEquals(NomadicMerchantPhase.FINISHED, routine.phase());
    }

    @Test
    void retriesAfterResourceScanFailureWithoutRecordingCompletion() {
        ADBConnectionException expectedFailure = new ADBConnectionException("synthetic capture failure");
        FailingResourceScanRoutine routine = new FailingResourceScanRoutine(expectedFailure);
        LocalDateTime before = LocalDateTime.now();

        ADBConnectionException failure = assertThrows(ADBConnectionException.class, routine::execute);

        assertSame(expectedFailure, failure);
        assertEquals(NomadicMerchantProgress.FAILED_RETRY, routine.progress());
        assertEquals(NomadicMerchantPhase.FINISHED, routine.phase());
        assertTrue(routine.scheduledTime().isAfter(before.plusMinutes(4)));
        assertTrue(routine.scheduledTime().isBefore(LocalDateTime.now().plusMinutes(6)));
        assertEquals(1, routine.visitResults().freeResourcesClaimedCount());
    }

    @Test
    void capsUnconfirmedResourceRetriesBeforeDailyResetWhileRescanningEachVisit() {
        SkippedOfferRoutine routine = new SkippedOfferRoutine();
        int retriesScheduledBefore = routine.recordedCount("Nomadic Merchant Retries Scheduled");
        for (int visit = 0; visit < 4; visit++) {
            LocalDateTime beforeVisit = LocalDateTime.now();
            LocalDateTime dailyReset = GameTimeUtils.dailyResetTime();

            routine.execute();

            assertEquals(visit < 3 ? NomadicMerchantProgress.FAILED_RETRY
                    : NomadicMerchantProgress.FAILED_RESCHEDULED,
                    routine.progress(), "visit " + (visit + 1));
            assertEquals(NomadicMerchantPhase.FINISHED, routine.phase(), "visit " + (visit + 1));
            if (visit < 3) {
                assertTrue(routine.scheduledTime().isAfter(beforeVisit.plusMinutes(4)), "visit " + (visit + 1));
                assertTrue(routine.scheduledTime().isBefore(beforeVisit.plusMinutes(6)), "visit " + (visit + 1));
            } else {
                assertEquals(dailyReset.plusMinutes(1), routine.scheduledTime());
            }
            assertEquals(visit + 1, routine.scans);
            assertEquals(retriesScheduledBefore + Math.min(visit + 1, 3),
                    routine.recordedCount("Nomadic Merchant Retries Scheduled"));
            assertEquals(List.of(), routine.skippedOffersAtScan.get(visit),
                    "each visit must start a fresh resource scan");
            assertEquals(0, routine.recordedCount("Nomadic Merchant Free Resources Claimed"));
            assertEquals(0, routine.recordedCount("Nomadic Merchant VIP Points Purchased"));
            assertEquals(0, routine.recordedCount("Nomadic Merchant Free Refresh Taps Dispatched"));
        }

        assertEquals(List.of(List.of(), List.of(), List.of(), List.of()), routine.skippedOffersAtScan);
    }

    @Test
    void marksRetryExhaustionPartialAfterConfirmedResourceClaims() {
        PartialVisitRoutine routine = new PartialVisitRoutine();
        LocalDateTime reset = GameTimeUtils.dailyResetTime();

        for (int visit = 0; visit < 4; visit++) {
            routine.execute();
        }

        assertEquals(NomadicMerchantProgress.PARTIAL_RESCHEDULED, routine.progress());
        assertEquals(reset.plusMinutes(1), routine.scheduledTime());
        assertEquals(4, routine.scans);
    }

    @Test
    void retriesAnUnverifiedVipPurchaseInFiveMinutes() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 28, 10, 0);

        assertEquals(now.plusMinutes(5), NomadicMerchantRoutine.unverifiedPurchaseRetry(now));
    }

    @Test
    void schedulesFromRememberedProgress() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 30, 10, 0);
        LocalDateTime reset = LocalDateTime.of(2026, 10, 1, 2, 0);

        assertEquals(now.plusMinutes(5), NomadicMerchantRoutine.nextRun(
                NomadicMerchantProgress.READY, now, reset));
        assertEquals(now.plusMinutes(5), NomadicMerchantRoutine.nextRun(
                NomadicMerchantProgress.FAILED_RETRY, now, reset));
        assertEquals(reset.plusMinutes(1), NomadicMerchantRoutine.nextRun(
                NomadicMerchantProgress.COMPLETED_SUCCESS, now, reset));
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
        int dispatchedBefore = routine.recordedCount("Nomadic Merchant Free Refresh Taps Dispatched");
        int resourcesBefore = routine.recordedCount("Nomadic Merchant Free Resources Claimed");

        routine.execute();

        assertEquals(List.of("resource-scan", "refresh-search", "tap", "wait-500",
                "resource-scan", "resource-claimed", "refresh-search", "tap", "wait-500",
                "resource-scan", "refresh-search"), routine.events);
        assertEquals(2, routine.refreshTaps);
        assertEquals(List.of(500L, 500L), routine.waits);
        assertEquals(NomadicMerchantProgress.COMPLETED_SUCCESS, routine.progress());
        assertEquals(dispatchedBefore + 2,
                routine.recordedCount("Nomadic Merchant Free Refresh Taps Dispatched"));
        assertEquals(resourcesBefore + 1,
                routine.recordedCount("Nomadic Merchant Free Resources Claimed"));
    }

    @Test
    void doesNotCountRefreshTapWhenAdbThrowsDuringDispatch() {
        ADBConnectionException expectedFailure = new ADBConnectionException("synthetic tap failure");
        RefreshLoopRoutine routine = new RefreshLoopRoutine(true);
        routine.tapFailure = expectedFailure;
        int dispatchedBefore = routine.recordedCount("Nomadic Merchant Free Refresh Taps Dispatched");
        LocalDateTime before = LocalDateTime.now();

        ADBConnectionException failure = assertThrows(ADBConnectionException.class, routine::execute);

        assertSame(expectedFailure, failure);
        assertEquals(List.of("resource-scan", "refresh-search", "tap"), routine.events);
        assertEquals(NomadicMerchantProgress.FAILED_RETRY, routine.progress());
        assertTrue(routine.scheduledTime().isAfter(before.plusMinutes(4)));
        assertTrue(routine.scheduledTime().isBefore(LocalDateTime.now().plusMinutes(6)));
        assertEquals(dispatchedBefore,
                routine.recordedCount("Nomadic Merchant Free Refresh Taps Dispatched"));
    }

    @Test
    void retriesWithoutCompletionWhenRefreshTapCannotBeSent() {
        RefreshLoopRoutine routine = new RefreshLoopRoutine(true);
        routine.tapReturnsFalse = true;
        int dispatchedBefore = routine.recordedCount("Nomadic Merchant Free Refresh Taps Dispatched");
        LocalDateTime before = LocalDateTime.now();

        routine.execute();

        assertEquals(List.of("resource-scan", "refresh-search", "tap"), routine.events);
        assertEquals(NomadicMerchantProgress.FAILED_RETRY, routine.progress());
        assertTrue(routine.scheduledTime().isAfter(before.plusMinutes(4)));
        assertTrue(routine.scheduledTime().isBefore(LocalDateTime.now().plusMinutes(6)));
        assertEquals(dispatchedBefore,
                routine.recordedCount("Nomadic Merchant Free Refresh Taps Dispatched"));
    }

    @Test
    void countsRefreshTapWhenTheFollowingSettleWaitFails() {
        RefreshLoopRoutine routine = new RefreshLoopRoutine(true);
        routine.waitFailure = new ADBConnectionException("synthetic settle interruption");
        int dispatchedBefore = routine.recordedCount("Nomadic Merchant Free Refresh Taps Dispatched");

        ADBConnectionException failure = assertThrows(ADBConnectionException.class, routine::execute);

        assertSame(routine.waitFailure, failure);
        assertEquals(List.of("resource-scan", "refresh-search", "tap", "wait-500"), routine.events);
        assertEquals(NomadicMerchantProgress.FAILED_RETRY, routine.progress());
        assertEquals(dispatchedBefore + 1,
                routine.recordedCount("Nomadic Merchant Free Refresh Taps Dispatched"));
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
        void claimResourceOffers(List<Integer> skippedResourceOffers, long executionDeadlineMs,
                VisitResults visitResults) {
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

    private static final class SkippedOfferRoutine extends NomadicMerchantRoutine {
        private int scans;
        private final java.util.ArrayList<List<Integer>> skippedOffersAtScan = new java.util.ArrayList<>();

        private SkippedOfferRoutine() {
            super(new AccountDescriptor(1L, "Test", "1", true, 1L, 30L),
                    TpDailyTaskEnum.NOMADIC_MERCHANT);
        }

        @Override
        boolean navigateToNomadicMerchantShop() {
            return true;
        }

        @Override
        void claimResourceOffers(List<Integer> skippedResourceOffers, long executionDeadlineMs,
                VisitResults visitResults) {
            scans++;
            skippedOffersAtScan.add(List.copyOf(skippedResourceOffers));
            skippedResourceOffers.add(2);
        }

        private LocalDateTime scheduledTime() {
            return scheduledTime;
        }

        private int recordedCount(String name) {
            return StatisticsService.obtain().loadMetrics(profile).getCustomCounters().getOrDefault(name, 0);
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
        void claimResourceOffers(List<Integer> skippedResourceOffers, long executionDeadlineMs,
                VisitResults visitResults) {
            events.add("resource-scan");
            if (events.stream().filter("resource-scan"::equals).count() == 2) {
                events.add("resource-claimed");
                visitResults.recordFreeResourceClaim();
            }
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

        private int recordedCount(String name) {
            return StatisticsService.obtain().loadMetrics(profile).getCustomCounters().getOrDefault(name, 0);
        }
    }

    private static final class PartialVisitRoutine extends NomadicMerchantRoutine {
        private int scans;

        private PartialVisitRoutine() {
            super(new AccountDescriptor(4L, "PartialTest", "4", true, 1L, 30L),
                    TpDailyTaskEnum.NOMADIC_MERCHANT);
        }

        @Override
        boolean navigateToNomadicMerchantShop() {
            return true;
        }

        @Override
        void claimResourceOffers(List<Integer> skippedResourceOffers, long executionDeadlineMs,
                VisitResults visitResults) {
            scans++;
            visitResults.recordFreeResourceClaim();
            skippedResourceOffers.add(1);
        }

        private LocalDateTime scheduledTime() {
            return scheduledTime;
        }
    }
}
