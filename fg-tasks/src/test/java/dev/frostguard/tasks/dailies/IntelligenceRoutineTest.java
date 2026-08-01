package dev.frostguard.tasks.dailies;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
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

    @Test
    void defersOtherTasksWhenIntelIsStaminaStarved() {
        assertTrue(IntelligenceRoutine.shouldDeferTaskToIntel(
                true,
                true,
                LocalDateTime.now().plusMinutes(10),
                20,
                IntelligenceRoutine.MIN_STAMINA_REQUIRED_FLOOR));
    }

    @Test
    void doesNotDeferOtherTasksWhenIntelHasEnoughStamina() {
        assertFalse(IntelligenceRoutine.shouldDeferTaskToIntel(
                true,
                true,
                LocalDateTime.now().plusMinutes(10),
                50,
                IntelligenceRoutine.MIN_STAMINA_REQUIRED_FLOOR));
    }

    @Test
    void defersOtherTasksWhenIntelIsDueSoon() {
        assertTrue(IntelligenceRoutine.shouldDeferTaskToIntel(
                true,
                true,
                LocalDateTime.now().plusMinutes(2),
                50,
                IntelligenceRoutine.MIN_STAMINA_REQUIRED_FLOOR));
    }
}
