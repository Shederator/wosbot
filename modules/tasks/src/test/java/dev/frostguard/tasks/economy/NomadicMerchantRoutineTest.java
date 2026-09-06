package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;

class NomadicMerchantRoutineTest {

    @Test
    void stopsAndReschedulesWhenSharedShopNavigationFails() {
        TestRoutine routine = new TestRoutine();
        LocalDateTime before = LocalDateTime.now().plusMinutes(59);

        routine.execute();

        assertTrue(routine.navigationAttempted);
        assertTrue(routine.scheduledTime().isAfter(before));
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
