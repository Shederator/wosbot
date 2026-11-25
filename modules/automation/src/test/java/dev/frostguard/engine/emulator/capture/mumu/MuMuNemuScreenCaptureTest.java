package dev.frostguard.engine.emulator.capture.mumu;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.capture.AdbScreenCapture;
import dev.frostguard.engine.emulator.capture.CaptureResult;
import dev.frostguard.engine.emulator.capture.ScreenCapture;
import dev.frostguard.engine.emulator.capture.ScreenCaptureException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class MuMuNemuScreenCaptureTest {

    @Test
    void tracksReconnectsAvailabilityTransitionsAndInstanceChanges() throws Exception {
        AtomicBoolean available = new AtomicBoolean(true);
        AtomicLong nativeGeneration = new AtomicLong(7);
        AtomicLong adbGeneration = new AtomicLong(1);
        ScreenCapture primary = source("mumu_ipc", nativeGeneration, available);
        ScreenCapture fallback = source("adb", adbGeneration, new AtomicBoolean(true));
        MuMuNemuScreenCapture composite = new MuMuNemuScreenCapture(primary, fallback);

        assertEquals(1, composite.capture("0").sessionGeneration());
        assertEquals(1, composite.capture("0").sessionGeneration());
        nativeGeneration.set(8);
        assertEquals(2, composite.capture("0").sessionGeneration());
        available.set(false);
        assertEquals(3, composite.capture("0").sessionGeneration());
        assertEquals(3, composite.capture("0").sessionGeneration());
        adbGeneration.set(2);
        assertEquals(4, composite.capture("0").sessionGeneration());
        available.set(true);
        assertEquals(5, composite.capture("0").sessionGeneration());
        CaptureResult nextInstance = composite.capture("1");
        assertEquals(6, nextInstance.sessionGeneration());
        assertEquals(8, nextInstance.sequence());
    }

    @Test
    void repeatedFailedPrimaryAttemptsDoNotCreateNewFallbackSessions() throws Exception {
        ScreenCapture primary = new ScreenCapture() {
            @Override
            public CaptureResult capture(String instance) throws ScreenCaptureException {
                throw new ScreenCaptureException("mumu_ipc", "Synthetic unavailable renderer");
            }
            @Override public String backendName() { return "mumu_ipc"; }
            @Override public boolean isAvailable(String instance) { return true; }
        };
        MuMuNemuScreenCapture composite = new MuMuNemuScreenCapture(primary,
                source("adb", new AtomicLong(1), new AtomicBoolean(true)));
        assertEquals(2, composite.capture("0").sessionGeneration());
        CaptureResult sameFallback = composite.capture("0");
        assertEquals(2, sameFallback.sessionGeneration());
        assertEquals(2, sameFallback.sequence());
    }

    @Test
    void rejectsInFlightPrimaryAndFallbackResultsAfterClose() throws Exception {
        for (boolean usePrimary : new boolean[]{true, false}) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ScreenCapture blocked = new ScreenCapture() {
                @Override
                public CaptureResult capture(String instance) throws InterruptedException {
                    entered.countDown();
                    if (!release.await(3, TimeUnit.SECONDS)) {
                        throw new AssertionError("Capture was not released");
                    }
                    return observation("blocked", 1);
                }
                @Override public String backendName() { return "blocked"; }
                @Override public boolean isAvailable(String instance) { return true; }
            };
            ScreenCapture unavailable = source("unavailable", new AtomicLong(1), new AtomicBoolean(false));
            MuMuNemuScreenCapture composite = usePrimary
                    ? new MuMuNemuScreenCapture(blocked, unavailable)
                    : new MuMuNemuScreenCapture(unavailable, blocked);
            FutureTask<CaptureResult> request = new FutureTask<>(() -> composite.capture("0"));
            Thread caller = new Thread(request);
            caller.start();
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                long generation = composite.currentSessionGeneration();
                composite.close();
                assertTrue(composite.currentSessionGeneration() > generation);
                release.countDown();
                ExecutionException error = assertThrows(ExecutionException.class,
                        () -> request.get(2, TimeUnit.SECONDS));
                assertInstanceOf(ScreenCaptureException.class, error.getCause());
                assertTrue(error.getCause().getMessage().contains("closed"));
            } finally {
                release.countDown();
                caller.join(2000);
                composite.close();
            }
        }
    }

    @Test
    void doesNotStartFallbackWhenClosedDuringPrimaryFailure() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger fallbackCalls = new AtomicInteger();
        ScreenCapture primary = new ScreenCapture() {
            @Override
            public CaptureResult capture(String instance) throws ScreenCaptureException, InterruptedException {
                entered.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError("Primary was not released");
                }
                throw new ScreenCaptureException("mumu_ipc", "Synthetic failure after close");
            }
            @Override public String backendName() { return "mumu_ipc"; }
            @Override public boolean isAvailable(String instance) { return true; }
        };
        ScreenCapture fallback = new AdbScreenCapture(instance -> {
            fallbackCalls.incrementAndGet();
            return observation("adb", 1).image();
        });
        MuMuNemuScreenCapture composite = new MuMuNemuScreenCapture(primary, fallback);
        FutureTask<CaptureResult> request = new FutureTask<>(() -> composite.capture("0"));
        Thread caller = new Thread(request);
        caller.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            composite.close();
            release.countDown();
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> request.get(2, TimeUnit.SECONDS));
            assertInstanceOf(ScreenCaptureException.class, error.getCause());
            assertEquals(0, fallbackCalls.get());
        } finally {
            release.countDown();
            caller.join(2000);
            composite.close();
        }
    }

    private static ScreenCapture source(String name, AtomicLong generation, AtomicBoolean available) {
        return new ScreenCapture() {
            @Override public CaptureResult capture(String instance) { return observation(name, generation.get()); }
            @Override public String backendName() { return name; }
            @Override public boolean isAvailable(String instance) { return available.get(); }
        };
    }

    private static CaptureResult observation(String name, long generation) {
        return new CaptureResult(RawImageData.capture(new byte[16], 2, 2, 32), name,
                1, System.nanoTime(), Duration.ZERO, generation, true);
    }

    @Test
    void usesPrimaryWhenAvailableAndSuccessful() throws Exception {
        RawImageData primaryImage = RawImageData.capture(new byte[16], 2, 2, 32);
        RawImageData fallbackImage = RawImageData.capture(new byte[16], 2, 2, 32);

        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();

        ScreenCapture primary = new ScreenCapture() {
            @Override public CaptureResult capture(String instance) {
                primaryCalls.incrementAndGet();
                return new CaptureResult(primaryImage, "mumu_ipc", 41, 100L, Duration.ofMillis(2));
            }
            @Override public String backendName() { return "mumu_ipc"; }
            @Override public boolean isAvailable(String instance) { return true; }
        };

        ScreenCapture fallback = new ScreenCapture() {
            @Override public CaptureResult capture(String instance) {
                fallbackCalls.incrementAndGet();
                return new CaptureResult(fallbackImage, "adb", 1, 100L, Duration.ofMillis(200));
            }
            @Override public String backendName() { return "adb"; }
            @Override public boolean isAvailable(String instance) { return true; }
        };

        MuMuNemuScreenCapture composite = new MuMuNemuScreenCapture(primary, fallback);
        CaptureResult result = composite.capture("0");

        assertEquals(1, primaryCalls.get());
        assertEquals(0, fallbackCalls.get());
        assertSame(primaryImage, result.image());
        assertEquals("mumu_ipc", result.backend());
        assertEquals(1, result.sequence());
        assertEquals(1L, result.sessionGeneration());
    }

    @Test
    void preservesMonotonicSequenceAndIncrementsSessionGenerationOnFallback() throws Exception {
        RawImageData primaryImage = RawImageData.capture(new byte[16], 2, 2, 32);
        RawImageData fallbackImage = RawImageData.capture(new byte[16], 2, 2, 32);

        AtomicInteger requests = new AtomicInteger();

        ScreenCapture primary = new ScreenCapture() {
            @Override public CaptureResult capture(String instance) throws ScreenCaptureException {
                if (requests.incrementAndGet() == 1) {
                    return new CaptureResult(primaryImage, "mumu_ipc", 41, 100L, Duration.ofMillis(2));
                }
                throw new ScreenCaptureException("mumu_ipc", "Synthetic native helper failure");
            }
            @Override public String backendName() { return "mumu_ipc"; }
            @Override public boolean isAvailable(String instance) { return true; }
        };

        ScreenCapture fallback = new ScreenCapture() {
            @Override public CaptureResult capture(String instance) {
                return new CaptureResult(fallbackImage, "adb", 1, 100L, Duration.ofMillis(200));
            }
            @Override public String backendName() { return "adb"; }
            @Override public boolean isAvailable(String instance) { return true; }
        };

        MuMuNemuScreenCapture composite = new MuMuNemuScreenCapture(primary, fallback);

        // First capture uses primary
        CaptureResult r1 = composite.capture("0");
        assertEquals("mumu_ipc", r1.backend());
        assertEquals(1, r1.sequence());
        assertEquals(1L, r1.sessionGeneration());

        // Second capture fails primary, falls back to ADB
        CaptureResult r2 = composite.capture("0");
        assertEquals("adb", r2.backend());
        assertEquals(2, r2.sequence(), "Composite sequence must strictly increase monotonically across fallback");
        assertEquals(2L, r2.sessionGeneration(), "Session generation must increment on fallback transition");
    }

    @Test
    void skipsPrimaryWhenPrimaryIsNotAvailable() throws Exception {
        RawImageData fallbackImage = RawImageData.capture(new byte[16], 2, 2, 32);

        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();

        ScreenCapture primary = new ScreenCapture() {
            @Override public CaptureResult capture(String instance) {
                primaryCalls.incrementAndGet();
                return null;
            }
            @Override public String backendName() { return "mumu_ipc"; }
            @Override public boolean isAvailable(String instance) { return false; }
        };

        ScreenCapture fallback = new ScreenCapture() {
            @Override public CaptureResult capture(String instance) {
                fallbackCalls.incrementAndGet();
                return new CaptureResult(fallbackImage, "adb", 1, 100L, Duration.ofMillis(200));
            }
            @Override public String backendName() { return "adb"; }
            @Override public boolean isAvailable(String instance) { return true; }
        };

        MuMuNemuScreenCapture composite = new MuMuNemuScreenCapture(primary, fallback);
        CaptureResult result = composite.capture("0");

        assertEquals(0, primaryCalls.get());
        assertEquals(1, fallbackCalls.get());
        assertSame(fallbackImage, result.image());
        assertEquals(1, result.sequence());
    }

    @Test
    void propagatesInterruptionWithoutExecutingFallback() {
        AtomicInteger fallbackCalls = new AtomicInteger();

        ScreenCapture primary = new ScreenCapture() {
            @Override public CaptureResult capture(String instance) throws InterruptedException {
                throw new InterruptedException("Capture cancelled in primary");
            }
            @Override public String backendName() { return "mumu_ipc"; }
            @Override public boolean isAvailable(String instance) { return true; }
        };

        ScreenCapture fallback = new ScreenCapture() {
            @Override public CaptureResult capture(String instance) {
                fallbackCalls.incrementAndGet();
                return null;
            }
            @Override public String backendName() { return "adb"; }
            @Override public boolean isAvailable(String instance) { return true; }
        };

        MuMuNemuScreenCapture composite = new MuMuNemuScreenCapture(primary, fallback);

        assertThrows(InterruptedException.class, () -> composite.capture("0"));
        assertEquals(0, fallbackCalls.get(), "Fallback must never be invoked when cancelled/interrupted");
    }

    @Test
    void rejectsCaptureAfterClose() {
        ScreenCapture dummy = new ScreenCapture() {
            @Override public CaptureResult capture(String instance) { return null; }
            @Override public String backendName() { return "dummy"; }
            @Override public boolean isAvailable(String instance) { return true; }
        };

        MuMuNemuScreenCapture composite = new MuMuNemuScreenCapture(dummy, dummy);
        composite.close();

        assertFalse(composite.isAvailable("0"));
        assertThrows(ScreenCaptureException.class, () -> composite.capture("0"));
    }

    @Test
    void closesBothBackendsOnClose() {
        AtomicBoolean primaryClosed = new AtomicBoolean();
        AtomicBoolean fallbackClosed = new AtomicBoolean();

        ScreenCapture primary = new ScreenCapture() {
            @Override public CaptureResult capture(String instance) { return null; }
            @Override public String backendName() { return "mumu_ipc"; }
            @Override public boolean isAvailable(String instance) { return true; }
            @Override public void close() { primaryClosed.set(true); }
        };

        ScreenCapture fallback = new ScreenCapture() {
            @Override public CaptureResult capture(String instance) { return null; }
            @Override public String backendName() { return "adb"; }
            @Override public boolean isAvailable(String instance) { return true; }
            @Override public void close() { fallbackClosed.set(true); }
        };

        MuMuNemuScreenCapture composite = new MuMuNemuScreenCapture(primary, fallback);
        composite.close();

        assertTrue(primaryClosed.get());
        assertTrue(fallbackClosed.get());
    }
}
