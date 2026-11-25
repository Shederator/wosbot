package dev.frostguard.engine.emulator.capture.mumu;

import com.sun.jna.ptr.IntByReference;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NemuTouchGestureTest {
    @Test
    void preservesFramebufferCoordinatesBecauseNativeSdkAlreadyHandlesRotation() throws Exception {
        FakeNative fake = new FakeNative();
        assertEquals(0, new NemuTouchGesture(fake.library(), 7, 720, 1280).tap(100, 200));
        assertEquals(List.of("down:7:100:200", "up:7"), fake.events);
    }

    @Test
    void swipeReachesEndpointAndReleasesContact() throws Exception {
        FakeNative fake = new FakeNative();
        assertEquals(0, new NemuTouchGesture(fake.library(), 8, 720, 1280).swipe(10, 20, 30, 40, 16));
        assertEquals("down:8:10:20", fake.events.getFirst());
        assertEquals("down:8:30:40", fake.events.get(fake.events.size() - 2));
        assertEquals("up:8", fake.events.getLast());
    }

    @Test
    void avatarAndLastPixelStayInsideTheMeasuredNativeInputAxes() throws Exception {
        FakeNative fake = new FakeNative();
        NemuTouchGesture gesture = new NemuTouchGesture(fake.library(), 7, 720, 1280);
        assertEquals(0, gesture.tap(50, 53));
        assertEquals(0, gesture.tap(719, 1279));
        assertEquals(List.of("down:7:50:53", "up:7", "down:7:719:1279", "up:7"), fake.events);
    }

    @Test
    void rejectsInvalidCoordinatesAndChangedGeometryBeforeTouching() throws Exception {
        FakeNative fake = new FakeNative();
        NemuTouchGesture gesture = new NemuTouchGesture(fake.library(), 7, 720, 1280);
        assertThrows(IllegalArgumentException.class, () -> gesture.tap(720, 0));
        assertThrows(IllegalArgumentException.class, () -> gesture.swipe(0, 0, 10, 10, 0));
        fake.height = 720;
        assertNotEquals(0, gesture.tap(10, 10));
        assertTrue(fake.events.isEmpty());
    }

    @Test
    void failedDownStillAttemptsReleaseAndReportsFailure() throws Exception {
        FakeNative fake = new FakeNative();
        fake.downResult = 4;
        assertEquals(4, new NemuTouchGesture(fake.library(), 7, 720, 1280).tap(100, 200));
        assertEquals("up:7", fake.events.getLast());
    }

    @Test
    void cancellationDuringContactStillReleasesWithoutAnotherDown() {
        FakeNative fake = new FakeNative();
        fake.interruptDown = true;
        try {
            assertThrows(InterruptedException.class,
                    () -> new NemuTouchGesture(fake.library(), 7, 720, 1280).swipe(10, 20, 30, 40, 16));
            assertEquals(List.of("down:7:10:20", "up:7"), fake.events);
        } finally {
            Thread.interrupted();
        }
    }

    private static final class FakeNative {
        final List<String> events = new ArrayList<>();
        int height = 1280;
        int downResult;
        boolean interruptDown;

        NemuIpcLibrary library() {
            return (NemuIpcLibrary) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{NemuIpcLibrary.class}, (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "nemu_capture_display" -> {
                                ((IntByReference) args[3]).setValue(720);
                                ((IntByReference) args[4]).setValue(height);
                                yield 0;
                            }
                            case "nemu_input_event_touch_down" -> {
                                events.add("down:" + args[0] + ":" + args[2] + ":" + args[3]);
                                if (interruptDown) Thread.currentThread().interrupt();
                                yield downResult;
                            }
                            case "nemu_input_event_touch_up" -> { events.add("up:" + args[0]); yield 0; }
                            default -> throw new AssertionError(method.getName());
                        };
                    });
        }
    }
}
