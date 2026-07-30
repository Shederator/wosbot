package dev.frostguard.tasks.dailies;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IntelligenceRoutineTest {

    @Test
    void abortsDeploymentWhenFlagModeEnabledAndFlagSelectionFails() {
        assertTrue(IntelligenceRoutine.shouldAbortBeastDeployForLockedFlag(true, false));
    }

    @Test
    void doesNotAbortDeploymentWhenFlagModeDisabled() {
        assertFalse(IntelligenceRoutine.shouldAbortBeastDeployForLockedFlag(false, false));
    }

    @Test
    void doesNotAbortDeploymentWhenFlagWasSelectedSuccessfully() {
        assertFalse(IntelligenceRoutine.shouldAbortBeastDeployForLockedFlag(true, true));
    }
}
