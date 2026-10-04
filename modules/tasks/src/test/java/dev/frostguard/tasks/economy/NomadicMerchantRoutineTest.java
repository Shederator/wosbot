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

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.error.ADBConnectionException;
import dev.frostguard.engine.service.StatisticsService;

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
        assertEquals(NomadicMerchantProgress.UNCONFIRMED, routine.progress());
        assertEquals(NomadicMerchantPhase.FINISHED, routine.phase());
    }

    @Test
    void remembersUnconfirmedProgressAcrossVisitsWithoutSkippingTheScan() {
        TestRoutine routine = new TestRoutine();

        routine.execute();
        assertEquals(NomadicMerchantProgress.UNCONFIRMED, routine.progress());

        routine.execute();
        assertEquals(NomadicMerchantProgress.UNCONFIRMED, routine.progress());
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
        assertEquals(NomadicMerchantProgress.UNCONFIRMED, routine.progress());
        assertEquals(NomadicMerchantPhase.FINISHED, routine.phase());
        assertTrue(routine.scheduledTime().isAfter(before.plusMinutes(4)));
        assertTrue(routine.scheduledTime().isBefore(LocalDateTime.now().plusMinutes(6)));
        assertEquals(1, routine.visitResults().freeResourcesClaimedCount());
    }

    @Test
    void keepsSkippedResourceOffersUnconfirmedAndScansThemAgainNextVisit() {
        SkippedOfferRoutine routine = new SkippedOfferRoutine();
        LocalDateTime beforeFirstVisit = LocalDateTime.now();

        routine.execute();

        assertEquals(NomadicMerchantProgress.UNCONFIRMED, routine.progress());
        assertEquals(NomadicMerchantPhase.FINISHED, routine.phase());
        assertTrue(routine.scheduledTime().isAfter(beforeFirstVisit.plusMinutes(4)));
        assertTrue(routine.scheduledTime().isBefore(beforeFirstVisit.plusMinutes(6)));
        assertEquals(1, routine.scans);
        assertEquals(List.of(List.of()), routine.skippedOffersAtScan);
        assertEquals(0, routine.recordedCount("Nomadic Merchant Free Resources Claimed"));
        assertEquals(0, routine.recordedCount("Nomadic Merchant Daily Refresh Used"));

        LocalDateTime beforeSecondVisit = LocalDateTime.now();
        routine.execute();

        assertEquals(NomadicMerchantProgress.UNCONFIRMED, routine.progress());
        assertEquals(NomadicMerchantPhase.FINISHED, routine.phase());
        assertTrue(routine.scheduledTime().isAfter(beforeSecondVisit.plusMinutes(4)));
        assertTrue(routine.scheduledTime().isBefore(beforeSecondVisit.plusMinutes(6)));
        assertEquals(2, routine.scans);
        assertEquals(List.of(List.of(), List.of()), routine.skippedOffersAtScan);
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
                NomadicMerchantProgress.UNCONFIRMED, now, reset));
        assertEquals(reset.plusMinutes(1), NomadicMerchantRoutine.nextRun(
                NomadicMerchantProgress.COMPLETE_UNTIL_RESET, now, reset));
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
}
