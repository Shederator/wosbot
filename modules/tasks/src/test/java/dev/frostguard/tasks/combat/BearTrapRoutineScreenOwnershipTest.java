package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.engine.error.BearSessionExecutionException;
import dev.frostguard.engine.helper.NavigationHelper;
import dev.frostguard.engine.schedule.LaunchPoint;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BearTrapRoutineScreenOwnershipTest {

    @BeforeAll
    static void initializeTestWorkspace() {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
    }

    @Test
    void bearEntersAndLeavesWithoutLegacyNavigation() {
        RecordingBear bear = new RecordingBear(true);

        bear.enterStartScreen(true);
        bear.leaveScreen();

        assertEquals(List.of(), bear.navigation, "Bear owns every screen transition itself");
    }

    @Test
    void missingGameProcessIsAProtectedRestartNotAnInitializeHandoff() {
        RecordingBear bear = new RecordingBear(false);

        BearSessionExecutionException failure = assertThrows(
                BearSessionExecutionException.class, () -> bear.enterStartScreen(false));

        assertEquals(BearSessionExecutionException.RecoveryDirective.RESTART_APP,
                failure.recoveryDirective());
        assertEquals(List.of(), bear.navigation);
    }

    @Test
    void observeOnlyRefusesEveryDeviceInput() {
        RecordingBear bear = new RecordingBear(true);
        bear.getProfile().setConfig(ConfigurationKeyEnum.BEAR_TRAP_OBSERVE_ONLY_BOOL, true);

        List<Runnable> inputs = List.of(
                () -> bear.tapInside(new PointData(10, 10), new PointData(10, 10)),
                () -> bear.tapInside(new AreaData(new PointData(1, 1), new PointData(2, 2))),
                () -> bear.tapInside(new ImageSearchResultData(true, new PointData(5, 5), 99.0)),
                () -> bear.swipe(new PointData(1, 1), new PointData(1, 200)),
                () -> bear.swipe(new PointData(1, 1), new PointData(1, 200), 300),
                () -> bear.tapInside(new PointData(10, 10), new PointData(10, 10), 2, 50),
                () -> bear.tapInside(new AreaData(new PointData(1, 1), new PointData(2, 2)), 2, 50),
                () -> bear.tapInside(new ImageSearchResultData(true, new PointData(5, 5), 99.0), 2, 50),
                () -> bear.tapNear(new PointData(5, 5)),
                () -> bear.tapNear(new PointData(5, 5), 3),
                () -> bear.tapNear(new PointData(5, 5), 3, 2, 50),
                bear::pressBack);
        for (Runnable input : inputs) {
            IllegalStateException refused = assertThrows(IllegalStateException.class, input::run);
            assertTrue(refused.getMessage().contains("observe-only"),
                    "input must be refused by observe-only, not fail later: " + refused.getMessage());
        }
    }

    private static final class RecordingBear extends BearTrapRoutine {

        private final boolean gameRunning;
        private final List<LaunchPoint> navigation = new ArrayList<>();

        private RecordingBear(boolean gameRunning) {
            super(profile(), TpDailyTaskEnum.BEAR_TRAP);
            this.gameRunning = gameRunning;
            this.navigationHelper = new NavigationHelper(emuManager, "0", profile()) {
                @Override
                public void ensureCorrectScreenLocation(LaunchPoint target) {
                    navigation.add(target);
                }
            };
        }

        @Override
        protected boolean gameProcessRunning() {
            return gameRunning;
        }

        private static AccountDescriptor profile() {
            return new AccountDescriptor(990_002L, "Bear screen ownership", "0", true, 100L, 30L);
        }
    }
}
