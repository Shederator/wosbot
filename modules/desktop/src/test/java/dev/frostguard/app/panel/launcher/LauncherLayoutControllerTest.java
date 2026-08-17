package dev.frostguard.app.panel.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void addsProfileContextToApplicationTitle() {
        assertEquals("Frostguard Development · fix/arena-refresh-budget · v3.0.0-dev - Main [Stamina: 77]",
                LauncherLayoutController.formatWindowTitle(
                        "Frostguard Development · fix/arena-refresh-budget · v3.0.0-dev", "Main", 77));
    }
}
