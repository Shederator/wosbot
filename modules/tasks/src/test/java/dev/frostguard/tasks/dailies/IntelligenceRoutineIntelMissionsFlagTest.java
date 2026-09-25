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
 * INTEL_LAST_RUN_HAD_MISSIONS_BOOL carried two faults that this test pins down.
 *
 * <p>(1) {@code profile.setConfig(...)} alone only mutates the in-memory {@link AccountDescriptor};
 * {@code DelayedTask.run()} only persists changed settings to the DB when {@code
 * shouldUpdateConfig} is set, and the write site never set it -- so GatherRoutine's next profile
 * reload from DB silently lost the write, letting a stale "trust once" recur every pass.</p>
 *
 * <p>(2) The flag was written once from the initial board scan and never refreshed -- if that same
 * Intel pass then drained the board down to nothing, the flag stayed {@code true} from the stale
 * initial read, so GatherRoutine kept deferring/recalling for an Intel pass with nothing left to
 * do.</p>
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
        // Simulates the real sequence this bug involved: initial scan writes true, then the
        // in-pass re-scan (manageRescheduling) discovers the board drained and must be able to
        // correct it back to false within the same task run.
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
