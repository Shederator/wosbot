package dev.frostguard.tasks.combat;

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

class ArenaRoutineQuickDeployTest {

    @BeforeAll
    static void createsRuntimeWorkspaceBeforeRoutineConstruction() throws IOException {
        Files.createDirectories(WorkspacePaths.current().root());
    }

    @Test
    void usesQuickDeployForAttacksWhenEnabled() {
        TestableArenaRoutine routine = routineWithAttackQuickDeploy(true);

        routine.executeBattleSequence();

        assertEquals(List.of(
                new PointData(180, 1200),
                new PointData(530, 1200),
                new PointData(60, 962),
                new PointData(252, 635)), routine.taps);
        assertEquals(List.of(500L, 3000L, 500L, 1000L), routine.sleepDurations);
    }

    @Test
    void keepsSavedAttackFormationWhenQuickDeployIsDisabled() {
        TestableArenaRoutine routine = routineWithAttackQuickDeploy(false);

        routine.executeBattleSequence();

        assertEquals(List.of(
                new PointData(530, 1200),
                new PointData(60, 962),
                new PointData(252, 635)), routine.taps);
        assertEquals(List.of(3000L, 500L, 1000L), routine.sleepDurations);
    }

    @Test
    void attackQuickDeployRemainsEnabledByDefault() {
        assertTrue(profile().getConfig(ConfigurationKeyEnum.ARENA_TASK_ATTACK_QUICK_DEPLOY_BOOL, Boolean.class));
    }

    private TestableArenaRoutine routineWithAttackQuickDeploy(boolean enabled) {
        AccountDescriptor profile = profile();
        profile.setConfig(ConfigurationKeyEnum.ARENA_TASK_ATTACK_QUICK_DEPLOY_BOOL, enabled);
        return new TestableArenaRoutine(profile);
    }

    private AccountDescriptor profile() {
        return new AccountDescriptor(1L, "Test", "1", true, 1L, 30L);
    }

    private static final class TestableArenaRoutine extends ArenaRoutine {
        private final List<PointData> taps = new ArrayList<>();
        private final List<Long> sleepDurations = new ArrayList<>();

        private TestableArenaRoutine(AccountDescriptor profile) {
            super(profile, TpDailyTaskEnum.ARENA);
        }

        @Override
        public void tapNear(PointData point) {
            taps.add(point);
        }

        @Override
        protected void sleepTask(long millis) {
            sleepDurations.add(millis);
        }
    }
}
