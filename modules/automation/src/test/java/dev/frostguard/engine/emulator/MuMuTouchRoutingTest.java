package dev.frostguard.engine.emulator;

import dev.frostguard.api.configs.CaptureMethodEnum;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.emulator.capture.CaptureResult;
import dev.frostguard.engine.emulator.capture.ScreenCapture;
import dev.frostguard.engine.emulator.capture.ScreenCaptureException;
import dev.frostguard.engine.emulator.capture.mumu.NemuTouchInput;
import dev.frostguard.engine.emulator.instance.MuMuEmulatorInstance;
import dev.frostguard.engine.error.ADBConnectionException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MuMuTouchRoutingTest {
    private static final PointData POINT = new PointData(100, 200);

    @Test
    void defaultStaysAdbWithoutCreatingNativeSession() {
        assertEquals("false", ConfigurationKeyEnum.MUMU_IPC_TOUCH_BOOL.getDefaultValue());
        TestMuMu backend = new TestMuMu(false);
        ((EmulatorInstance) backend).touchArea("0", POINT, POINT);
        backend.swipe("1", POINT, new PointData(200, 300));
        assertEquals(List.of("tap:0:100:200", "swipe:1:null"), backend.adb);
        assertEquals(0, backend.created);
    }

    @Test
    void nativeTouchIsIndependentOfAdbCaptureAndReusesEachInstanceSession() {
        TestMuMu backend = new TestMuMu(true);
        ((EmulatorInstance) backend).touchArea("0", POINT, POINT);
        backend.swipe("0", POINT, new PointData(200, 300), 600);
        backend.swipe("1", POINT, new PointData(200, 300));
        assertEquals(List.of("0:100:200:100:200:40", "0:100:200:200:300:600", "1:100:200:200:300:300"), backend.nativeCalls);
        assertTrue(backend.adb.isEmpty());
        assertEquals(2, backend.created);
        backend.closeCaptureSessions();
    }

    @Test
    void unavailableBeforeDispatchFallsBackExactlyOnce() {
        TestMuMu backend = new TestMuMu(true);
        backend.available = false;
        ((EmulatorInstance) backend).touchArea("1", POINT, POINT);
        assertEquals(List.of("tap:1:100:200"), backend.adb);
        assertEquals(1, backend.nativeCalls.size());
        backend.closeCaptureSessions();
    }

    @Test
    void unknownNativeOutcomeNeverReplaysThroughAdb() {
        TestMuMu backend = new TestMuMu(true);
        backend.fail = true;
        assertThrows(ADBConnectionException.class, () -> ((EmulatorInstance) backend).touchArea("0", POINT, POINT));
        assertTrue(backend.adb.isEmpty());
        assertEquals(1, backend.nativeCalls.size());
        backend.closeCaptureSessions();
    }

    @Test
    void cancellationNeverFallsBackAndPreservesFlag() {
        TestMuMu backend = new TestMuMu(true);
        backend.cancel = true;
        try {
            assertThrows(ADBConnectionException.class, () -> backend.swipe("0", POINT, POINT, 100));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(backend.adb.isEmpty());
        } finally {
            Thread.interrupted();
            backend.closeCaptureSessions();
        }
    }

    private static final class TestMuMu extends MuMuEmulatorInstance {
        final List<String> adb = new ArrayList<>();
        final List<String> nativeCalls = new ArrayList<>();
        int created;
        boolean available = true;
        boolean fail;
        boolean cancel;

        TestMuMu(boolean enabled) { super("unused-manager", CaptureMethodEnum.ADB, enabled); }
        @Override protected void initBridge() { }
        @Override protected void sendAdbTap(String idx, PointData point) { adb.add("tap:" + idx + ":" + point.getX() + ":" + point.getY()); }
        @Override protected void sendAdbSwipe(String idx, PointData from, PointData to, Integer duration) { adb.add("swipe:" + idx + ":" + duration); }
        @Override protected ScreenCapture createCaptureBackend(String instance) { created++; return new FakeTouch(); }

        private final class FakeTouch implements ScreenCapture, NemuTouchInput {
            @Override public boolean tryTouch(String instance, int x, int y, int endX, int endY, int duration)
                    throws ScreenCaptureException, InterruptedException {
                nativeCalls.add(instance + ":" + x + ":" + y + ":" + endX + ":" + endY + ":" + duration);
                if (cancel) throw new InterruptedException("Synthetic cancellation");
                if (fail) throw new ScreenCaptureException("mumu_ipc", "Unknown outcome");
                return available;
            }
            @Override public CaptureResult capture(String instance) { throw new AssertionError("Unexpected capture"); }
            @Override public String backendName() { return "mumu_ipc"; }
            @Override public boolean isAvailable(String instance) { return available; }
        }
    }
}
