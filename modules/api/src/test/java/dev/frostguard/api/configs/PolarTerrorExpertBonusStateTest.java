package dev.frostguard.api.configs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PolarTerrorExpertBonusStateTest {

    @Test
    void acceptsPersistedDisplayNamesAndEnumNames() {
        assertEquals(PolarTerrorExpertBonusState.EXIST,
                PolarTerrorExpertBonusState.fromStoredValue("Exists"));
        assertEquals(PolarTerrorExpertBonusState.MISSING,
                PolarTerrorExpertBonusState.fromStoredValue("MISSING"));
    }

    @Test
    void treatsUnknownPersistedValuesAsNotDefined() {
        assertEquals(PolarTerrorExpertBonusState.NOT_DEFINED,
                PolarTerrorExpertBonusState.fromStoredValue("future-layout"));
    }

    @Test
    void defaultsProfilesToAutomaticLayoutDetection() {
        assertEquals(PolarTerrorExpertBonusState.NOT_DEFINED.getDisplayName(),
                ConfigurationKeyEnum.POLAR_TERROR_EXPERT_BONUS_LAYOUT_STRING.getDefaultValue());
    }
}
