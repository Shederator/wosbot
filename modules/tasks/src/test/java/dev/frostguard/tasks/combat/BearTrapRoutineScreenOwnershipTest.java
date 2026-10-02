package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
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
