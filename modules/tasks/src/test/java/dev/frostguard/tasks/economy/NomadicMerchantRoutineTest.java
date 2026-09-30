package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.runtime.WorkspacePaths;

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
    void tapsTheProductIconRatherThanThePriceIcon() {
        assertTrue(NomadicMerchantRoutine.isMerchandiseIcon(new PointData(360, 520)));
        assertTrue(NomadicMerchantRoutine.isMerchandiseIcon(new PointData(600, 800)));
        assertFalse(NomadicMerchantRoutine.isMerchandiseIcon(new PointData(600, 660)));
        assertFalse(NomadicMerchantRoutine.isMerchandiseIcon(new PointData(360, 950)));
        assertFalse(NomadicMerchantRoutine.isMerchandiseIcon(null));
    }

    @Test
    void skipsAnUnconfirmedOfferAndKeepsScanning() {
        PointData failedWood = new PointData(600, 520);
        List<PointData> skipped = new ArrayList<>();
        skipped.add(failedWood);

        assertTrue(NomadicMerchantRoutine.isSkippedOffer(failedWood, skipped));
        assertTrue(NomadicMerchantRoutine.isSkippedOffer(new PointData(608, 524), skipped));
        assertFalse(NomadicMerchantRoutine.isSkippedOffer(new PointData(120, 800), skipped));
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
}
