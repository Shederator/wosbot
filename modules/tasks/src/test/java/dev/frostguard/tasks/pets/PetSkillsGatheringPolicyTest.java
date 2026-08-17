package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class PetSkillsGatheringPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 15, 0);

    @Test
    void suppressesAnotherBonusMarchDuringTheSameActiveWindow() {
        assertTrue(PetSkillsRoutine.isRecentGatheringDeployment(NOW.minusMinutes(14), NOW));
    }

    @Test
    void allowsRecoveryWhenTheDeploymentMarkerIsStaleOrInvalid() {
        assertFalse(PetSkillsRoutine.isRecentGatheringDeployment(NOW.minusMinutes(15), NOW));
        assertFalse(PetSkillsRoutine.isRecentGatheringDeployment(NOW.plusMinutes(1), NOW));
        assertFalse(PetSkillsRoutine.isRecentGatheringDeployment(null, NOW));
    }
}
