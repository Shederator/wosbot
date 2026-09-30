package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;

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
    }

    @Test
    void retriesAnUnverifiedVipPurchaseInFiveMinutes() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 28, 10, 0);

        assertEquals(now.plusMinutes(5), NomadicMerchantRoutine.unverifiedPurchaseRetry(now));
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
    void treatsOnlyTheCardArtworkAsAFreeResource() {
        assertTrue(NomadicMerchantRoutine.isMerchandiseIcon(new PointData(360, 520)));
        assertTrue(NomadicMerchantRoutine.isMerchandiseIcon(new PointData(600, 800)));
        assertFalse(NomadicMerchantRoutine.isMerchandiseIcon(new PointData(600, 660)));
        assertFalse(NomadicMerchantRoutine.isMerchandiseIcon(new PointData(360, 950)));
        assertFalse(NomadicMerchantRoutine.isMerchandiseIcon(null));
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

        private TestRoutine() {
            super(new AccountDescriptor(1L, "Test", "1", true, 1L, 30L),
                    TpDailyTaskEnum.NOMADIC_MERCHANT);
        }

        @Override
        boolean navigateToNomadicMerchantShop() {
            navigationAttempted = true;
            return false;
        }

        private LocalDateTime scheduledTime() {
            return scheduledTime;
        }
    }
}
