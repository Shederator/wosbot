package dev.frostguard.tasks.dailies;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;

class IntelPatternPreferenceTest {

    @Test
    void startsNormalFirstAndKeepsFireCrystalAsFallback() {
        IntelPatternPreference preference = new IntelPatternPreference();

        assertArrayEquals(new TemplatesEnum[] {
                TemplatesEnum.INTEL_BEAST_GRAYSCALE,
                TemplatesEnum.INTEL_BEAST_GRAYSCALE_FC,
                TemplatesEnum.INTEL_BEAST_GRAYSCALE_FC1
        }, preference.order(TemplatesEnum.INTEL_BEAST_GRAYSCALE,
                TemplatesEnum.INTEL_BEAST_GRAYSCALE_FC,
                TemplatesEnum.INTEL_BEAST_GRAYSCALE_FC1));
        assertFalse(preference.recordMatch(TemplatesEnum.INTEL_BEAST_GRAYSCALE));
        assertFalse(preference.isFireCrystalFirst());
    }

    @Test
    void fireCrystalMatchSwitchesEveryCategoryToFireCrystalFirst() {
        IntelPatternPreference preference = new IntelPatternPreference();

        assertTrue(preference.recordMatch(TemplatesEnum.INTEL_SURVIVOR_GRAYSCALE_FC));
        assertArrayEquals(new TemplatesEnum[] {
                TemplatesEnum.INTEL_JOURNEY_GRAYSCALE_FC,
                TemplatesEnum.INTEL_JOURNEY_GRAYSCALE
        }, preference.order(TemplatesEnum.INTEL_JOURNEY_GRAYSCALE,
                TemplatesEnum.INTEL_JOURNEY_GRAYSCALE_FC));
        assertArrayEquals(new TemplatesEnum[] {
                TemplatesEnum.INTEL_BEAST_GRAYSCALE_FC,
                TemplatesEnum.INTEL_BEAST_GRAYSCALE_FC1,
                TemplatesEnum.INTEL_BEAST_GRAYSCALE
        }, preference.order(TemplatesEnum.INTEL_BEAST_GRAYSCALE,
                TemplatesEnum.INTEL_BEAST_GRAYSCALE_FC,
                TemplatesEnum.INTEL_BEAST_GRAYSCALE_FC1));
    }

    @Test
    void resetRestoresNormalFirstForTheNextRun() {
        IntelPatternPreference preference = new IntelPatternPreference();
        preference.recordMatch(TemplatesEnum.INTEL_JOURNEY_GRAYSCALE_FC);

        preference.reset();

        assertFalse(preference.isFireCrystalFirst());
        assertArrayEquals(new TemplatesEnum[] {
                TemplatesEnum.INTEL_JOURNEY_GRAYSCALE,
                TemplatesEnum.INTEL_JOURNEY_GRAYSCALE_FC
        }, preference.order(TemplatesEnum.INTEL_JOURNEY_GRAYSCALE,
                TemplatesEnum.INTEL_JOURNEY_GRAYSCALE_FC));
    }

    @Test
    void fireBeastMatchDoesNotSwitchEraPreference() {
        IntelPatternPreference preference = new IntelPatternPreference();

        assertFalse(preference.recordMatch(TemplatesEnum.INTEL_FIRE_BEAST));
        assertFalse(preference.isFireCrystalFirst());
    }
}
