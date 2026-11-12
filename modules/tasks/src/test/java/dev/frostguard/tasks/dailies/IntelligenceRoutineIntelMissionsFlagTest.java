package dev.frostguard.tasks.dailies;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * INTEL_LAST_RUN_HAD_MISSIONS_BOOL must survive the profile reload between tasks and must be
 * correctable within the same pass.
 *
 * <p>{@code profile.setConfig(...)} only mutates the in-memory {@link AccountDescriptor};
 * {@code DelayedTask.run()} persists changed settings only when {@code shouldUpdateConfig} is set,
 * so every write must arm it or Gather never sees the value.</p>
 *
 * <p>When the in-pass re-scan finds the board drained, the flag set by the initial scan must be
 * overwritten, or Gather keeps deferring for an Intel pass with nothing left to do.</p>
 */
class IntelligenceRoutineIntelMissionsFlagTest {

    @BeforeAll
    static void createsRuntimeWorkspaceBeforeRoutineConstruction() throws IOException {
        Files.createDirectories(WorkspacePaths.current().root());
    }

    private AccountDescriptor profile() {
        return new AccountDescriptor(1L, "Test", "1", true, 1L, 30L);
    }

    private IntelligenceRoutine routine(AccountDescriptor profile) {
        return new IntelligenceRoutine(profile, TpDailyTaskEnum.INTEL);
    }

    @Test
    void writingTheFlagAlsoFlagsTheProfileForPersistence() {
        AccountDescriptor profile = profile();
        IntelligenceRoutine routine = routine(profile);

        routine.updateIntelMissionsAvailableFlag(true);

        assertTrue(profile.getConfig(ConfigurationKeyEnum.INTEL_LAST_RUN_HAD_MISSIONS_BOOL, Boolean.class));
        assertTrue(routine.isShouldUpdateConfig(),
                "the write must flag persistence, or DelayedTask.run() silently drops it on the next profile reload");
    }

    @Test
    void writingFalseAlsoFlagsTheProfileForPersistence() {
        AccountDescriptor profile = profile();
        IntelligenceRoutine routine = routine(profile);

        routine.updateIntelMissionsAvailableFlag(false);

        assertFalse(profile.getConfig(ConfigurationKeyEnum.INTEL_LAST_RUN_HAD_MISSIONS_BOOL, Boolean.class));
        assertTrue(routine.isShouldUpdateConfig());
    }

    @Test
    void secondCallWithADifferentValueOverwritesTheFirst() {
        // The initial scan writes true, then the in-pass re-scan (manageRescheduling) finds the
        // board drained and must correct it to false within the same task run.
        AccountDescriptor profile = profile();
        IntelligenceRoutine routine = routine(profile);

        routine.updateIntelMissionsAvailableFlag(true);
        assertTrue(profile.getConfig(ConfigurationKeyEnum.INTEL_LAST_RUN_HAD_MISSIONS_BOOL, Boolean.class));

        routine.updateIntelMissionsAvailableFlag(false);
        assertFalse(profile.getConfig(ConfigurationKeyEnum.INTEL_LAST_RUN_HAD_MISSIONS_BOOL, Boolean.class),
                "the in-pass correction must actually overwrite the stale initial-scan value");
        assertTrue(routine.isShouldUpdateConfig());
    }
}
