package dev.frostguard.app.panel.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class LauncherLayoutControllerTest {

    @Test
    void formatsUptimeFromElapsedSeconds() {
        assertEquals("00:00:00", LauncherLayoutController.formatUptime(0));
        assertEquals("01:01:01", LauncherLayoutController.formatUptime(3_661));
    }

    @Test
    void keepsHoursBeyondOneDay() {
        assertEquals("25:00:00", LauncherLayoutController.formatUptime(90_000));
    }

    @Test
    void formatsMultipleProfileStaminaValuesDeterministically() {
        String segment = LauncherTitleFormatter.formatProfileSegment(List.of(
                new LauncherTitleFormatter.ProfileEntry(2L, "Zulu", 81),
                new LauncherTitleFormatter.ProfileEntry(1L, "alpha", 120)));

        assertEquals("alpha [Stamina: 120] | Zulu [Stamina: 81]", segment);
    }

    @Test
    void fallsBackToProfileIdWhenQueueNameIsUnavailable() {
        String segment = LauncherTitleFormatter.formatProfileSegment(List.of(
                new LauncherTitleFormatter.ProfileEntry(42L, " ", 0)));

        assertEquals("42 [Stamina: 0]", segment);
    }

    @Test
    void formatsEmptyProfileListAsEmptySegment() {
        assertEquals("", LauncherTitleFormatter.formatProfileSegment(List.of()));
    }
}
