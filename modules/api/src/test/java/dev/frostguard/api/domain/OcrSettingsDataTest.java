package dev.frostguard.api.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrSettingsDataTest {

    @Test
    void defaultConfigurationHasNoLanguageOrLineBreakPreservation() {
        OcrSettingsData defaults = OcrSettingsData.configurator().build();
        assertTrue(defaults.isDefaultConfiguration());
        assertEquals(null, defaults.language());
        assertFalse(defaults.preserveLineBreaks());
    }

    @Test
    void settingLanguageBreaksDefaultConfiguration() {
        OcrSettingsData cfg = OcrSettingsData.configurator().language("chi_sim").build();
        assertFalse(cfg.isDefaultConfiguration());
        assertEquals("chi_sim", cfg.language());
    }

    @Test
    void settingPreserveLineBreaksBreaksDefaultConfiguration() {
        OcrSettingsData cfg = OcrSettingsData.configurator().preserveLineBreaks(true).build();
        assertFalse(cfg.isDefaultConfiguration());
        assertTrue(cfg.preserveLineBreaks());
    }

    @Test
    void equalsAndHashCodeConsiderLanguageAndPreserveLineBreaks() {
        OcrSettingsData a = OcrSettingsData.configurator().language("eng").preserveLineBreaks(true).build();
        OcrSettingsData b = OcrSettingsData.configurator().language("eng").preserveLineBreaks(true).build();
        OcrSettingsData differentLanguage = OcrSettingsData.configurator().language("chi_sim").preserveLineBreaks(true).build();
        OcrSettingsData differentLineBreaks = OcrSettingsData.configurator().language("eng").preserveLineBreaks(false).build();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, differentLanguage);
        assertNotEquals(a, differentLineBreaks);
    }

    @Test
    void toConfiguratorRoundTripsLanguageAndPreserveLineBreaks() {
        OcrSettingsData original = OcrSettingsData.configurator()
                .language("eng+chi_sim")
                .preserveLineBreaks(true)
                .build();

        OcrSettingsData copy = original.toConfigurator().build();

        assertEquals(original, copy);
        assertEquals("eng+chi_sim", copy.language());
        assertTrue(copy.preserveLineBreaks());
    }

    @Test
    void toStringIncludesLanguageAndPreserveLineBreaks() {
        OcrSettingsData cfg = OcrSettingsData.configurator()
                .language("chi_sim")
                .preserveLineBreaks(true)
                .build();

        String text = cfg.toString();
        assertTrue(text.contains("language=chi_sim"));
        assertTrue(text.contains("preserveLineBreaks=true"));
    }

    @Test
    void presetsRemainUnaffectedByLanguageDefaults() {
        assertTrue(OcrSettingsData.forNumberRecognition().language() == null);
        assertTrue(OcrSettingsData.forTextBlock().language() == null);
        assertTrue(OcrSettingsData.forSingleWord().language() == null);
        assertTrue(OcrSettingsData.forWhiteTextOnDark().language() == null);
    }
}
