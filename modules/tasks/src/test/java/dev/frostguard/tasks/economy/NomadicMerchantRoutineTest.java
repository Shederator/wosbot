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
    void treatsADifferentRemainingIconAsANewOfferRatherThanAFailedTap() {
        PointData tapped = new PointData(100, 500);
        ImageSearchResultData samePoint = new ImageSearchResultData(true, new PointData(100, 500), 95);
        ImageSearchResultData otherPoint = new ImageSearchResultData(true, new PointData(400, 700), 95);

        assertTrue(NomadicMerchantRoutine.tappedPointStillPresent(tapped, samePoint));
        assertFalse(NomadicMerchantRoutine.tappedPointStillPresent(tapped, otherPoint));
        assertFalse(NomadicMerchantRoutine.tappedPointStillPresent(tapped,
                new ImageSearchResultData(false, null, 0)));
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
