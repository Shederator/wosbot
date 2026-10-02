package dev.frostguard.tasks.analytics;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.engine.emulator.DeviceReleaseGuard;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GameAnalyticsBearProtectionTest {

    @BeforeAll
    static void initializeTestWorkspace() {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
    }

    @Test
    void powerAnalyticsRefusesToForceStopAGameThatABearEventOwns() {
        BooleanSupplier bearOwnsTheEmulator = () -> true;
        DeviceReleaseGuard.register("analytics-guard", bearOwnsTheEmulator);
        try {
            GameAnalyticsRoutine analytics = new GameAnalyticsRoutine(
                    new AccountDescriptor(990_004L, "Analytics guard", "analytics-guard", true, 100L, 30L),
                    TpDailyTaskEnum.GAME_ANALYTICS_POWER);

            IllegalStateException refused = assertThrows(IllegalStateException.class, analytics::run);

            assertTrue(refused.getMessage().contains("active Bear event"),
                    "the capture must stop before any force-stop: " + refused.getMessage());
        } finally {
            DeviceReleaseGuard.unregister("analytics-guard", bearOwnsTheEmulator);
        }
    }
}
