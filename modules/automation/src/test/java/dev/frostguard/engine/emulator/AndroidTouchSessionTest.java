package dev.frostguard.engine.emulator;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AndroidTouchSessionTest {
    private static final String OWNED = "/data/local/tmp/frostguard-touch-00000000-0000-0000-0000-000000000000";

    @Test void unknownForwardOutcomeCanRecoverOnlyTheExactOwnedSocketOnTheExactSerial() {
        String socket = "localabstract:scrcpy_00000001";
        String listing = "one tcp:12345 " + socket + "\nother tcp:23456 " + socket
                + "\none tcp:34567 localabstract:scrcpy_00000002\none tcp:99999 " + socket
                + "\none tcp:12345 " + socket + "\none tcp:12x " + socket;
        assertEquals(java.util.List.of(12345), AndroidTouchSession.ownedForwardPorts(listing, "one", socket));
    }

    @Test void bundledServerIsPinnedAndModifiedBytesAreRefused() throws Exception {
        try (var resource = getClass().getResourceAsStream("/android/scrcpy-server-v4.1")) {
            assertNotNull(resource);
            byte[] binary = resource.readAllBytes();
            AndroidTouchSession.verifyServer(binary);
            binary[0] ^= 1;
            assertThrows(IOException.class, () -> AndroidTouchSession.verifyServer(binary));
        }
        assertNotNull(getClass().getResource("/android/scrcpy-LICENSE"));
    }

    @Test void cancelledOpenCannotLaunchAdbOrUploadAnything() {
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedException.class, () -> AndroidTouchSession.open("must-not-be-executed", "one-serial")); }
        finally { Thread.interrupted(); }
    }

    @Test void launchDisablesUnneededServicesAndCleanupRejectsUnownedPaths() {
        String launch = AndroidTouchSession.launchScript(OWNED, "00000001");
        assertFalse(launch.contains("\r"));
        for (String setting : new String[]{"video=false", "audio=false", "clipboard_autosync=false",
                "power_on=false", "stay_awake=false", "send_device_meta=false"}) assertTrue(launch.contains(setting));
        assertTrue(launch.contains("/proc/$$/stat")); assertTrue(launch.contains("|| exit 1"));
        String cleanup = AndroidTouchSession.cleanupScript(OWNED);
        assertTrue(cleanup.contains("/proc/$pid/stat")); assertTrue(cleanup.contains("/proc/$pid/comm"));
        assertTrue(cleanup.contains("app_process|app_process32|app_process64"));
        assertFalse(cleanup.contains("killall")); assertFalse(cleanup.contains("pkill"));
        assertThrows(IllegalArgumentException.class, () -> AndroidTouchSession.cleanupScript("/data/local/tmp"));
        assertThrows(IllegalArgumentException.class, () -> AndroidTouchSession.launchScript(OWNED, "other;bad"));
    }
}
