package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.frostguard.api.configs.PolarTerrorMode;
import org.junit.jupiter.api.Test;

class PolarTerrorModeTest {

    @Test
    void readsLegacyLimitedModeAsSpecialRewardsMode() {
        assertEquals(PolarTerrorMode.SPECIAL_REWARDS,
                PolarTerrorMode.fromStoredValue("Limited (10)"));
    }

    @Test
    void keepsUnlimitedModeUnlimited() {
        assertEquals(PolarTerrorMode.UNLIMITED,
                PolarTerrorMode.fromStoredValue("Unlimited"));
    }

    @Test
    void keepsUnknownStoredValuesBackwardCompatible() {
        assertEquals(PolarTerrorMode.UNLIMITED,
                PolarTerrorMode.fromStoredValue("unknown"));
    }
}
