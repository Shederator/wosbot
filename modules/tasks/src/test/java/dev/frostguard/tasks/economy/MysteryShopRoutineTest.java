package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.schedule.LaunchPoint;

class MysteryShopRoutineTest {

    @BeforeAll
    static void createTestWorkspace() throws IOException {
        Files.createDirectories(WorkspacePaths.current().root());
    }

    @Test
    void retriesSharedNavigationThenReschedulesFromHome() {
        TestRoutine routine = new TestRoutine();
        LocalDateTime before = LocalDateTime.now().plusMinutes(59);

        routine.execute();

        assertEquals(5, routine.navigationAttempts);
        assertEquals(LaunchPoint.HOME, routine.requiredStartLocation());
        assertTrue(routine.scheduledTime().isAfter(before));
    }

    private static final class TestRoutine extends MysteryShopRoutine {
        private int navigationAttempts;

        private TestRoutine() {
            super(new AccountDescriptor(1L, "Test", "1", true, 1L, 30L),
                    TpDailyTaskEnum.SHOP_MYSTERY);
        }

        @Override
        boolean navigateToMysteryShop() {
            navigationAttempts++;
            return false;
        }

        @Override
        protected void sleepTask(long millis) {
            // Keep retry-policy verification deterministic and fast.
        }

        private LaunchPoint requiredStartLocation() {
            return getRequiredStartLocation();
        }

        private LocalDateTime scheduledTime() {
            return scheduledTime;
        }
    }
}
