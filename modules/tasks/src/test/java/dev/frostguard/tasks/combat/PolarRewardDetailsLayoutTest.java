package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.configs.PolarTerrorExpertBonusState;
import org.junit.jupiter.api.Test;

class PolarRewardDetailsLayoutTest {

    @Test
    void persistsConfirmedExpertBonusAndUsesItsMapping() {
        PolarRewardDetailsLayout.Resolution resolution = PolarRewardDetailsLayout.resolve(
                PolarTerrorExpertBonusState.NOT_DEFINED, true);

        assertEquals(PolarTerrorExpertBonusState.EXIST, resolution.effectiveState());
        assertEquals(PolarRewardDetailsLayout.Mapping.CONFIRMED_EXPERT_BONUS, resolution.mapping());
        assertTrue(resolution.persistExistence());
    }

    @Test
    void keepsInconclusiveProbeTransientAndUsesLegacyMapping() {
        PolarRewardDetailsLayout.Resolution resolution = PolarRewardDetailsLayout.resolve(
                PolarTerrorExpertBonusState.NOT_DEFINED, false);

        assertEquals(PolarTerrorExpertBonusState.NOT_CONFIRMED, resolution.effectiveState());
        assertEquals(PolarRewardDetailsLayout.Mapping.NO_EXPERT_BONUS, resolution.mapping());
        assertFalse(resolution.persistExistence());
    }

    @Test
    void respectsManualOverridesWithoutProbingOrPersisting() {
        PolarRewardDetailsLayout.Resolution exists = PolarRewardDetailsLayout.resolve(
                PolarTerrorExpertBonusState.EXIST, false);
        PolarRewardDetailsLayout.Resolution missing = PolarRewardDetailsLayout.resolve(
                PolarTerrorExpertBonusState.MISSING, true);

        assertEquals(PolarRewardDetailsLayout.Mapping.CONFIRMED_EXPERT_BONUS, exists.mapping());
        assertEquals(PolarRewardDetailsLayout.Mapping.NO_EXPERT_BONUS, missing.mapping());
        assertFalse(exists.persistExistence());
        assertFalse(missing.persistExistence());
    }
}
