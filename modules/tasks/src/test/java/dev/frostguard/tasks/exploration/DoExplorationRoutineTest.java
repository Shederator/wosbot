package dev.frostguard.tasks.exploration;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.runtime.WorkspacePaths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoExplorationRoutineTest {

    @BeforeAll
    static void createsRuntimeWorkspaceBeforeRoutineConstruction() throws IOException {
        Files.createDirectories(WorkspacePaths.current().root());
    }

    @Test
    void usesQuickDeployWhenEnabled() {
        TestableDoExplorationRoutine routine = routineWithQuickDeploy(true);

        routine.startBattle();

        assertEquals(List.of(
                new PointData(55, 1170),
                new PointData(390, 1170)), routine.tapTopLefts);
        assertEquals(List.of(300L), routine.sleepDurations);
    }

    @Test
    void keepsSavedFormationWhenQuickDeployIsDisabled() {
        TestableDoExplorationRoutine routine = routineWithQuickDeploy(false);

        routine.startBattle();

        assertEquals(List.of(new PointData(390, 1170)), routine.tapTopLefts);
        assertEquals(List.of(), routine.sleepDurations);
    }

    @Test
    void quickDeployRemainsEnabledByDefault() {
        AccountDescriptor profile = profile();

        assertTrue(profile.getConfig(ConfigurationKeyEnum.DO_EXPLORATION_QUICK_DEPLOY_BOOL, Boolean.class));
    }

    private TestableDoExplorationRoutine routineWithQuickDeploy(boolean enabled) {
        AccountDescriptor profile = profile();
        profile.setConfig(ConfigurationKeyEnum.DO_EXPLORATION_QUICK_DEPLOY_BOOL, enabled);
        return new TestableDoExplorationRoutine(profile);
    }

    private AccountDescriptor profile() {
        return new AccountDescriptor(1L, "Test", "1", true, 1L, 30L);
    }

    private static final class TestableDoExplorationRoutine extends DoExplorationRoutine {
        private final List<PointData> tapTopLefts = new ArrayList<>();
        private final List<Long> sleepDurations = new ArrayList<>();

        private TestableDoExplorationRoutine(AccountDescriptor profile) {
            super(profile, TpDailyTaskEnum.DO_EXPLORATION);
        }

        @Override
        public void tapInside(PointData topLeft, PointData bottomRight) {
            tapTopLefts.add(topLeft);
        }

        @Override
        protected void sleepTask(long millis) {
            sleepDurations.add(millis);
        }
    }
}
