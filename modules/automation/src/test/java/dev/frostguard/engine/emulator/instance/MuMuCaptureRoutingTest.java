package dev.frostguard.engine.emulator.instance;

import dev.frostguard.api.configs.CaptureMethodEnum;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.emulator.capture.AdbScreenCapture;
import dev.frostguard.engine.emulator.capture.CaptureResult;
import dev.frostguard.engine.emulator.capture.ScreenCapture;
import dev.frostguard.engine.emulator.capture.ScreenCaptureException;
import dev.frostguard.engine.emulator.capture.mumu.MuMuNemuScreenCapture;
import dev.frostguard.engine.error.ADBConnectionException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class MuMuCaptureRoutingTest {
    private static final RawImageData FRAME = RawImageData.capture(new byte[16], 2, 2, 32);

    @Test
    void defaultConstructorUsesAdbWithoutCreatingNativeBackend() {
        TestMuMu backend = new TestMuMu();
        assertSame(FRAME, backend.captureScreenshot("0"));
        assertEquals(List.of("0"), backend.adbCalls);
        assertEquals(0, backend.factoryCalls);
    }

    @Test
    void explicitNativePreferenceReusesOneBackendForEachInstance() {
        TestMuMu backend = new TestMuMu(CaptureMethodEnum.MUMU_NEMU_IPC);
        backend.factory = instance -> successful("mumu_ipc");
        assertSame(FRAME, backend.captureScreenshot("0"));
        assertSame(FRAME, backend.captureScreenshot("0"));
        assertEquals(1, backend.factoryCalls);
        assertSame(FRAME, backend.captureScreenshot("1"));
        assertEquals(2, backend.factoryCalls);
        assertTrue(backend.adbCalls.isEmpty());
        backend.closeCaptureSessions();
    }

    @Test
    void unavailableNativeUsesActualCompositeFallbackForTheRequestedInstance() {
        TestMuMu backend = new TestMuMu(CaptureMethodEnum.MUMU_NEMU_IPC);
        backend.factory = instance -> new MuMuNemuScreenCapture(java.nio.file.Path.of("missing-mumu-install"),
                instance, new AdbScreenCapture(backend::captureAdbScreenshot));
        assertSame(FRAME, backend.captureScreenshot("1"));
        assertSame(FRAME, backend.captureScreenshot("1"));
        assertEquals(List.of("1", "1"), backend.adbCalls);
        assertEquals(1, backend.factoryCalls);
        backend.closeCaptureSessions();
    }

    @Test
    void failedCompositePropagatesWithoutRepeatingAdbCapture() {
        TestMuMu backend = new TestMuMu(CaptureMethodEnum.MUMU_NEMU_IPC);
        backend.factory = instance -> new ScreenCapture() {
            @Override
            public CaptureResult capture(String target) throws ScreenCaptureException {
                throw new ScreenCaptureException("adb", "Both sources failed");
            }
            @Override public String backendName() { return "failed"; }
            @Override public boolean isAvailable(String target) { return true; }
        };
        ADBConnectionException failure = assertThrows(ADBConnectionException.class,
                () -> backend.captureScreenshot("0"));
        assertInstanceOf(ScreenCaptureException.class, failure.getCause());
        assertTrue(backend.adbCalls.isEmpty());
        backend.closeCaptureSessions();
    }

    @Test
    void cancellationPreservesInterruptWithoutRetryingAdb() {
        TestMuMu backend = new TestMuMu(CaptureMethodEnum.MUMU_NEMU_IPC);
        backend.factory = instance -> new ScreenCapture() {
            @Override
            public CaptureResult capture(String target) throws InterruptedException {
                throw new InterruptedException("Synthetic cancellation");
            }
            @Override public String backendName() { return "cancelled"; }
            @Override public boolean isAvailable(String target) { return true; }
        };
        try {
            assertThrows(ADBConnectionException.class, () -> backend.captureScreenshot("0"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(backend.adbCalls.isEmpty());
        } finally {
            Thread.interrupted();
            backend.closeCaptureSessions();
        }
    }

    @Test
    void controllerTeardownClosesAllWorkersAndAllowsFreshSessions() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        TestMuMu backend = new TestMuMu(CaptureMethodEnum.MUMU_NEMU_IPC);
        backend.factory = instance -> new ScreenCapture() {
            @Override public CaptureResult capture(String target) { return observation("mumu_ipc"); }
            @Override public String backendName() { return "mumu_ipc"; }
            @Override public boolean isAvailable(String target) { return true; }
            @Override public void close() { closed.incrementAndGet(); }
        };
        backend.captureScreenshot("0");
        backend.captureScreenshot("1");
        var constructor = EmulatorController.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        EmulatorController controller = constructor.newInstance();
        var field = EmulatorController.class.getDeclaredField("backend");
        field.setAccessible(true);
        field.set(controller, backend);
        controller.closeCaptureSessions();
        assertEquals(2, closed.get());
        controller.closeCaptureSessions();
        assertEquals(2, closed.get());
        backend.captureScreenshot("0");
        assertEquals(3, backend.factoryCalls);
        controller.closeCaptureSessions();
        assertEquals(3, closed.get());
    }

    @Test
    void failedCleanupStillClosesOtherWorkersAndRetainsFailedSessionForRetry() {
        TestMuMu backend = new TestMuMu(CaptureMethodEnum.MUMU_NEMU_IPC);
        AtomicInteger failedAttempts = new AtomicInteger();
        AtomicInteger successfulCloses = new AtomicInteger();
        backend.factory = instance -> new ScreenCapture() {
            @Override public CaptureResult capture(String target) { return observation("mumu_ipc"); }
            @Override public String backendName() { return "mumu_ipc"; }
            @Override public boolean isAvailable(String target) { return true; }
            @Override public void close() {
                if (instance.equals("0") && failedAttempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("Synthetic cleanup failure");
                }
                successfulCloses.incrementAndGet();
            }
        };
        backend.captureScreenshot("0");
        backend.captureScreenshot("1");
        IllegalStateException failure = assertThrows(IllegalStateException.class, backend::closeCaptureSessions);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(1, successfulCloses.get());
        backend.closeCaptureSessions();
        assertEquals(2, failedAttempts.get());
        assertEquals(2, successfulCloses.get());
        backend.closeCaptureSessions();
        assertEquals(2, successfulCloses.get());
    }

    private static CaptureResult observation(String backend) {
        return new CaptureResult(FRAME, backend, 1, System.nanoTime(), Duration.ZERO);
    }

    private static ScreenCapture successful(String name) {
        return new ScreenCapture() {
            @Override public CaptureResult capture(String target) { return observation(name); }
            @Override public String backendName() { return name; }
            @Override public boolean isAvailable(String target) { return true; }
        };
    }

    private static class TestMuMu extends MuMuEmulatorInstance {
        final List<String> adbCalls = new ArrayList<>();
        int factoryCalls;
        Function<String, ScreenCapture> factory = instance -> { throw new AssertionError("Unexpected native setup"); };

        TestMuMu() { super("unused-manager"); }
        TestMuMu(CaptureMethodEnum method) { super("unused-manager", method); }
        @Override protected void initBridge() { }
        @Override protected RawImageData captureAdbScreenshot(String instance) { adbCalls.add(instance); return FRAME; }
        @Override protected ScreenCapture createCaptureBackend(String instance) { factoryCalls++; return factory.apply(instance); }
    }
}
