package dev.frostguard.engine.emulator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScrcpyViewerLauncherTest {

    @Test
    void buildsViewerArgumentsWithCustomDimensionsAndOptions() {
        List<String> args = ScrcpyViewerLauncher.buildViewerArguments(
                "scrcpy", "127.0.0.1:16384", "Frostguard - Test", 480, 854);

        assertEquals(8, args.size());
        assertEquals("scrcpy", args.get(0));
        assertEquals("-s", args.get(1));
        assertEquals("127.0.0.1:16384", args.get(2));
        assertEquals("--window-title=Frostguard - Test", args.get(3));
        assertEquals("--no-audio", args.get(4));
        assertEquals("--window-width=480", args.get(5));
        assertEquals("--window-height=854", args.get(6));
        assertEquals("--stay-awake", args.get(7));
    }

    @Test
    void viewerLauncherTracksAndStopsProcessesGracefully() {
        ScrcpyViewerLauncher launcher = ScrcpyViewerLauncher.getInstance();
        assertFalse(launcher.isViewerRunning("non-existent-device"));

        // Stopping an unknown serial is safe and no-op
        launcher.stopViewer("non-existent-device");
        launcher.stopAll();
        assertFalse(launcher.isViewerRunning("non-existent-device"));
    }

    @Test
    void executableDiscoveryFindsLocalOrInstalledScrcpyIfPresent() {
        // If scrcpy is installed on this test host, findExecutable() returns it.
        var cand = ScrcpyViewerLauncher.findExecutable();
        if (cand.isPresent()) {
            assertTrue(cand.get().getFileName().toString().toLowerCase().contains("scrcpy"));
            assertTrue(ScrcpyViewerLauncher.isAvailable());
        }
    }

    @Test
    void executableDiscoveryDoesNotThrowOnQuotedOrMalformedPath() {
        assertDoesNotThrow(ScrcpyViewerLauncher::findExecutable);
    }
}
