package dev.frostguard.engine.emulator;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScrcpyStreamCaptureTest {

    private static final String OWNED = "/data/local/tmp/frostguard-video-00000000-0000-0000-0000-000000000000";

    @Test
    void bundledServerIsPinnedAndModifiedBytesAreRefused() throws Exception {
        try (var resource = getClass().getResourceAsStream("/android/scrcpy-server-v4.1")) {
            assertNotNull(resource);
            byte[] binary = resource.readAllBytes();
            ScrcpyStreamCapture.verifyServer(binary);
            binary[0] ^= 1;
            assertThrows(IOException.class, () -> ScrcpyStreamCapture.verifyServer(binary));
        }
    }

    @Test
    void launchScriptIncludesRequiredVideoOptionsAndProcessTracking() {
        String script = ScrcpyStreamCapture.launchScript(OWNED, "12345678", 1280, 30, 4_000_000);
        assertFalse(script.contains("\r"));
        assertTrue(script.contains("video=true"));
        assertTrue(script.contains("audio=false"));
        assertTrue(script.contains("control=false"));
        assertTrue(script.contains("send_dummy_byte=true"));
        assertTrue(script.contains("tunnel_forward=true"));
        assertTrue(script.contains("max_size=1280"));
        assertTrue(script.contains("max_fps=30"));
        assertTrue(script.contains("video_bit_rate=4000000"));
        assertTrue(script.contains("/proc/$$/stat"));
        assertTrue(script.contains("|| exit 1"));
    }

    @Test
    void launchAndCleanupScriptsRejectUnownedPathsOrMalformedSocketId() {
        assertThrows(IllegalArgumentException.class, () ->
                ScrcpyStreamCapture.launchScript("/data/local/tmp/unowned", "12345678", 1280, 30, 4_000_000));
        assertThrows(IllegalArgumentException.class, () ->
                ScrcpyStreamCapture.launchScript(OWNED, "invalid;scid", 1280, 30, 4_000_000));
        assertThrows(IllegalArgumentException.class, () ->
                ScrcpyStreamCapture.cleanupScript("/data/local/tmp/unowned"));
    }

    @Test
    void parsesForwardedPortEvenWithAdbDaemonMessages() {
        assertEquals(50493, ScrcpyStreamCapture.parseForwardedPort("50493\n"));
        String noisyOutput = "* daemon not running; starting now at tcp:5037\n* daemon started successfully\n59424\n";
        assertEquals(59424, ScrcpyStreamCapture.parseForwardedPort(noisyOutput));
        assertThrows(IllegalArgumentException.class, () -> ScrcpyStreamCapture.parseForwardedPort(""));
        assertThrows(IllegalArgumentException.class, () -> ScrcpyStreamCapture.parseForwardedPort("not-a-port\n"));
        assertThrows(IllegalArgumentException.class, () -> ScrcpyStreamCapture.parseForwardedPort("999999\n"));
    }

    @Test
    void parsesScrcpy16ByteHeaderCorrectly() {
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.put("h264".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(0x80000000);
        buf.putInt(720);
        buf.putInt(1280);

        ScrcpyStreamCapture.VideoStreamMeta meta = ScrcpyStreamCapture.parseHeader(buf.array());
        assertEquals("h264", meta.codec());
        assertEquals(0x80000000, meta.flags());
        assertEquals(720, meta.width());
        assertEquals(1280, meta.height());

        assertThrows(IllegalArgumentException.class, () -> ScrcpyStreamCapture.parseHeader(new byte[15]));
        assertThrows(IllegalArgumentException.class, () -> ScrcpyStreamCapture.parseHeader(null));
    }

    @Test
    void readExactReadsExpectedBytesOrThrowsEof() throws Exception {
        byte[] data = new byte[]{1, 2, 3, 4, 5};
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        byte[] dest = new byte[5];
        ScrcpyStreamCapture.readExact(in, dest);
        assertArrayEquals(data, dest);

        ByteArrayInputStream shortIn = new ByteArrayInputStream(new byte[]{1, 2});
        assertThrows(EOFException.class, () -> ScrcpyStreamCapture.readExact(shortIn, new byte[5]));
    }

    @Test
    void interruptedThreadRefusesStartup() {
        Thread.currentThread().interrupt();
        try {
            ScrcpyStreamCapture capture = new ScrcpyStreamCapture("must-not-be-executed", "test-serial");
            assertThrows(RuntimeException.class, capture::start);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void stopHandlesInterruptedThreadWithoutThrowing() {
        Thread.currentThread().interrupt();
        try {
            ScrcpyStreamCapture capture = new ScrcpyStreamCapture("must-not-be-executed", "test-serial");
            capture.stop();
            assertFalse(capture.isRunning());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
