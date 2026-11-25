package dev.frostguard.engine.emulator.capture;

import dev.frostguard.api.domain.RawImageData;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class CaptureContractTest {

    @Test
    void validatesCaptureResultConstruction() {
        RawImageData frame = RawImageData.capture(new byte[16], 2, 2, 32);
        CaptureResult result = new CaptureResult(frame, "mumu_ipc", 1, 1000L, Duration.ofMillis(5));

        assertSame(frame, result.image());
        assertEquals("mumu_ipc", result.backend());
        assertEquals(1, result.sequence());
        assertEquals(1000L, result.timestampNanos());
        assertEquals(Duration.ofMillis(5), result.latency());
        assertEquals(0L, result.sessionGeneration());
        assertTrue(result.fresh());

        CaptureResult custom = new CaptureResult(frame, "adb", 5, 2000L, Duration.ofMillis(10), 3L, false);
        assertEquals(3L, custom.sessionGeneration());
        assertFalse(custom.fresh());

        assertThrows(NullPointerException.class, () -> new CaptureResult(null, "backend", 1, 0, Duration.ZERO));
        assertThrows(NullPointerException.class, () -> new CaptureResult(frame, null, 1, 0, Duration.ZERO));
        assertThrows(NullPointerException.class, () -> new CaptureResult(frame, "backend", 1, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new CaptureResult(frame, "backend", -1, 0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new CaptureResult(frame, "backend", 1, 0, Duration.ZERO, -1L, true));
    }

    @Test
    void adbScreenCaptureProducesMonotonicSequences() throws Exception {
        RawImageData frame = RawImageData.capture(new byte[16], 2, 2, 32);
        AdbScreenCapture adb = new AdbScreenCapture(instance -> frame);

        assertEquals("adb", adb.backendName());
        assertTrue(adb.isAvailable("0"));

        CaptureResult r1 = adb.capture("0");
        CaptureResult r2 = adb.capture("0");

        assertEquals(1, r1.sequence());
        assertEquals(2, r2.sequence());
        assertEquals("adb", r1.backend());
        assertSame(frame, r1.image());
        assertEquals(1L, r1.sessionGeneration());
        assertTrue(r1.fresh());
    }

    @Test
    void adbScreenCapturePropagatesInterruptionBeforeAcquisition() {
        RawImageData frame = RawImageData.capture(new byte[16], 2, 2, 32);
        AdbScreenCapture adb = new AdbScreenCapture(instance -> frame);

        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> adb.capture("0"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted(); // clear status
        }
    }

    @Test
    void adbScreenCapturePropagatesInterruptionDuringAcquisition() {
        AdbScreenCapture adb = new AdbScreenCapture(instance -> {
            Thread.currentThread().interrupt();
            return RawImageData.capture(new byte[16], 2, 2, 32);
        });

        try {
            assertThrows(InterruptedException.class, () -> adb.capture("0"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted(); // clear status
        }
    }

    @Test
    void adbScreenCaptureFailsOnNullOrInvalidImage() {
        AdbScreenCapture nullAdb = new AdbScreenCapture(instance -> null);
        assertThrows(ScreenCaptureException.class, () -> nullAdb.capture("0"));

        RawImageData invalid = RawImageData.capture(new byte[4], 2, 2, 32); // requires 16 bytes, only has 4
        assertFalse(invalid.isValid());
        AdbScreenCapture invalidAdb = new AdbScreenCapture(instance -> invalid);
        assertThrows(ScreenCaptureException.class, () -> invalidAdb.capture("0"));
    }
}
