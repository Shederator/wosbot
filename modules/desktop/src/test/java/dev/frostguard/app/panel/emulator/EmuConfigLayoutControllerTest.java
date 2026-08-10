package dev.frostguard.app.panel.emulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EmuConfigLayoutControllerTest {

    @Test
    void acceptsPositiveIdleMinutes() {
        assertEquals("30", EmuConfigLayoutController.normalizePositiveMinutes("30", "15"));
        assertTrue(EmuConfigLayoutController.isPositiveMinutes("30"));
    }

    @Test
    void replacesNonPositiveIdleMinutesWithDefault() {
        assertEquals("15", EmuConfigLayoutController.normalizePositiveMinutes("0", "15"));
        assertEquals("15", EmuConfigLayoutController.normalizePositiveMinutes("-2", "15"));
        assertFalse(EmuConfigLayoutController.isPositiveMinutes("0"));
        assertFalse(EmuConfigLayoutController.isPositiveMinutes("-2"));
    }

    @Test
    void replacesMalformedIdleMinutesWithDefault() {
        assertEquals("15", EmuConfigLayoutController.normalizePositiveMinutes("never", "15"));
        assertEquals("15", EmuConfigLayoutController.normalizePositiveMinutes(null, "15"));
        assertFalse(EmuConfigLayoutController.isPositiveMinutes("never"));
        assertFalse(EmuConfigLayoutController.isPositiveMinutes(null));
    }
}
