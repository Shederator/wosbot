package dev.frostguard.engine.emulator;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AndroidRealtimeTransportTest {
    @Test
    void videoIsPinnedToOneSerialWithoutDesktopFocusCommands() {
        assertEquals(List.of("adb", "-s", "test-serial", "exec-out", "screenrecord", "--output-format=h264",
                "--size", "720x1280", "--bit-rate", "4000000", "--time-limit", "120", "-"),
                AndroidFrameStream.command("adb", "test-serial"));
    }

    @Test
    void refusesMissingSerialAndRepeatedStreamStarts() {
        assertThrows(IllegalArgumentException.class, () -> new AndroidFrameStream("adb", ""));
    }

    @Test
    void closedStreamCannotLaunchRecording() {
        var stream = new AndroidFrameStream("must-not-be-executed", "test-serial");
        stream.close();
        assertThrows(IllegalStateException.class, stream::start);
        stream.close();
    }

    @Test
    void cancelledStreamDoesNotLoadDecoderOrLaunchRecording() {
        var stream = new AndroidFrameStream("must-not-be-executed", "test-serial");
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class, stream::start);
        } finally {
            Thread.interrupted();
            stream.close();
        }
    }

    @Test
    void binaryRecorderUsesRawExecAndUploadedScriptNotInlineQuoting() {
        String owned = "/data/local/tmp/frostguard-video-00000000-0000-0000-0000-000000000000.pid";
        var command = AndroidFrameStream.ownedCommand("adb", "one-serial", owned);
        assertEquals(List.of("adb", "-s", "one-serial", "exec-out", "sh",
                owned.replace(".pid", ".sh")), command);
        String script = AndroidFrameStream.recordingScript(owned);
        assertTrue(script.startsWith("echo \"$$ "));
        assertTrue(script.endsWith(" -\n"));
        assertTrue(script.contains("/proc/$$/stat"));
        assertFalse(script.contains("\r"));
        assertTrue(script.contains("|| exit 1"));
    }

    @Test
    void recordingCleanupIsSerialPinnedAndRejectsUnownedPaths() {
        String owned = "/data/local/tmp/frostguard-video-00000000-0000-0000-0000-000000000000.pid";
        var command = AndroidFrameStream.cleanupCommand("adb", "one-serial", owned);
        assertEquals(List.of("adb", "-s", "one-serial", "shell", "-T", "sh"), command);
        String script = AndroidFrameStream.cleanupScript(owned);
        assertTrue(script.contains("/proc/$pid/stat"));
        assertTrue(script.contains("/proc/$pid/comm"));
        assertFalse(script.contains("killall"));
        assertThrows(IllegalArgumentException.class,
                () -> AndroidFrameStream.cleanupCommand("adb", "one-serial", "/data/local/tmp/other.pid"));
    }
}
