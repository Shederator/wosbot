package dev.frostguard.engine.emulator.capture.mumu;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.capture.AdbScreenCapture;
import dev.frostguard.engine.emulator.capture.CaptureResult;
import dev.frostguard.engine.emulator.capture.ScreenCaptureException;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MuMuNemuProcessCaptureFaultTest {

    @Test
    void preservesInterruptionDuringFailedWorkerCleanupWithoutFallback() throws Exception {
        try (MockWorkerFixture fixture = new MockWorkerFixture(2, 2)) {
            setField(fixture.capture, "workerProcess", new InterruptedCleanupProcess(fixture.idleProcess));
            FutureTask<Void> response = new FutureTask<>(() -> {
                assertEquals(NemuHelperWorker.CMD_CAPTURE, fixture.serverIn.read());
                fixture.serverOut.writeInt(0);
                fixture.serverOut.writeInt(4);
                fixture.serverOut.flush();
                return null;
            });
            Thread peer = new Thread(response);
            peer.start();
            AtomicInteger fallbackCalls = new AtomicInteger();
            AdbScreenCapture fallback = new AdbScreenCapture(instance -> {
                fallbackCalls.incrementAndGet();
                return RawImageData.capture(new byte[16], 2, 2, 32);
            });
            MuMuNemuScreenCapture composite = new MuMuNemuScreenCapture(fixture.capture, fallback);
            try {
                assertThrows(InterruptedException.class, () -> composite.capture("0"));
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(0, fallbackCalls.get(), "Cleanup cancellation must never invoke ADB");
            } finally {
                Thread.interrupted();
                composite.close();
                peer.join(2000);
            }
            response.get(1, TimeUnit.SECONDS);
        }
    }

    private static final class InterruptedCleanupProcess extends Process {
        private final Process delegate;

        private InterruptedCleanupProcess(Process delegate) {
            this.delegate = delegate;
        }

        @Override public OutputStream getOutputStream() { return delegate.getOutputStream(); }
        @Override public InputStream getInputStream() { return delegate.getInputStream(); }
        @Override public InputStream getErrorStream() { return delegate.getErrorStream(); }
        @Override public int waitFor() throws InterruptedException { return delegate.waitFor(); }
        @Override public int exitValue() { return delegate.exitValue(); }
        @Override public void destroy() { delegate.destroy(); }
        @Override public boolean isAlive() { return delegate.isAlive(); }
        @Override public Process destroyForcibly() { delegate.destroyForcibly(); return this; }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            // Model a cancellation arriving in Process.waitFor, which clears its flag.
            Thread.currentThread().interrupt();
            Thread.interrupted();
            throw new InterruptedException("Synthetic cancellation during worker cleanup");
        }
    }

    public static class IdleProcess {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(60000);
        }
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        Field f = null;
        while (clazz != null) {
            try {
                f = clazz.getDeclaredField(fieldName);
                break;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        if (f == null) {
            throw new NoSuchFieldException(fieldName);
        }
        f.setAccessible(true);
        f.set(target, value);
    }

    private static class MockWorkerFixture implements AutoCloseable {
        final ServerSocket listener;
        final Socket clientSocket;
        final Socket serverSocket;
        final DataInputStream serverIn;
        final DataOutputStream serverOut;
        final Process idleProcess;
        final MuMuNemuProcessCapture capture;

        MockWorkerFixture(int displayWidth, int displayHeight) throws Exception {
            this(displayWidth, displayHeight, "0");
        }

        MockWorkerFixture(int displayWidth, int displayHeight, String target) throws Exception {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            clientSocket = new Socket(InetAddress.getByName("127.0.0.1"), listener.getLocalPort());
            serverSocket = listener.accept();
            clientSocket.setTcpNoDelay(true);
            serverSocket.setTcpNoDelay(true);

            serverIn = new DataInputStream(serverSocket.getInputStream());
            serverOut = new DataOutputStream(serverSocket.getOutputStream());

            String javaBin = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
            if (!new java.io.File(javaBin).isFile()) {
                javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            }
            idleProcess = new ProcessBuilder(
                    javaBin,
                    "-cp", System.getProperty("java.class.path"),
                    IdleProcess.class.getName()
            ).start();

            capture = new MuMuNemuProcessCapture(Path.of("mockRoot"), target) {
                @Override
                public boolean isAvailable(String instance) {
                    return !isClosed() && target.equals(instance);
                }

                public boolean isClosed() {
                    try {
                        Field f = MuMuNemuProcessCapture.class.getDeclaredField("closed");
                        f.setAccessible(true);
                        return f.getBoolean(this);
                    } catch (Exception e) {
                        return false;
                    }
                }
            };

            setField(capture, "workerProcess", idleProcess);
            setField(capture, "socket", clientSocket);
            setField(capture, "in", new DataInputStream(clientSocket.getInputStream()));
            setField(capture, "out", new DataOutputStream(clientSocket.getOutputStream()));
            setField(capture, "displayWidth", displayWidth);
            setField(capture, "displayHeight", displayHeight);
        }

        @Override
        public void close() throws Exception {
            capture.close();
            try { clientSocket.close(); } catch (Exception ignored) {}
            try { serverSocket.close(); } catch (Exception ignored) {}
            try { listener.close(); } catch (Exception ignored) {}
            if (idleProcess != null) {
                idleProcess.destroyForcibly();
                idleProcess.waitFor(1, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void nativeTouchAcknowledgementKeepsCaptureProtocolAligned() throws Exception {
        try (MockWorkerFixture fixture = new MockWorkerFixture(720, 1280)) {
            FutureTask<Void> response = new FutureTask<>(() -> {
                receiveTouch(fixture, 0, null);
                assertEquals(NemuHelperWorker.CMD_CAPTURE, fixture.serverIn.read());
                fixture.serverOut.writeInt(0);
                fixture.serverOut.writeInt(720 * 1280 * 4);
                fixture.serverOut.write(new byte[720 * 1280 * 4]);
                fixture.serverOut.flush();
                return null;
            });
            new Thread(response).start();
            assertTrue(fixture.capture.tryTouch("0", 100, 200, 300, 400, 100));
            assertTrue(fixture.capture.capture("0").image().isValid());
            response.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void lostTouchAcknowledgementFailsInsteadOfPermittingFallback() throws Exception {
        try (MockWorkerFixture fixture = new MockWorkerFixture(720, 1280)) {
            FutureTask<Void> response = new FutureTask<>(() -> {
                receiveTouch(fixture, null, null);
                fixture.serverSocket.close();
                return null;
            });
            new Thread(response).start();
            ScreenCaptureException failure = assertThrows(ScreenCaptureException.class,
                    () -> fixture.capture.tryTouch("0", 100, 200, 300, 400, 100));
            assertTrue(failure.getMessage().contains("outcome unknown"));
            response.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void unavailableTouchCapabilityPermitsFallbackBeforeGesture() throws Exception {
        try (MockWorkerFixture fixture = new MockWorkerFixture(720, 1280)) {
            FutureTask<Void> response = new FutureTask<>(() -> {
                assertEquals(NemuHelperWorker.CMD_TOUCH_SUPPORT, fixture.serverIn.read());
                fixture.serverOut.writeInt(0);
                fixture.serverOut.flush();
                return null;
            });
            new Thread(response).start();
            assertFalse(fixture.capture.tryTouch("0", 100, 200, 300, 400, 100));
            response.get(3, TimeUnit.SECONDS);
            assertEquals(0, fixture.serverIn.available());
        }
    }

    @Test
    void rejectedNativeGestureDoesNotPermitAdbReplay() throws Exception {
        try (MockWorkerFixture fixture = new MockWorkerFixture(720, 1280)) {
            FutureTask<Void> response = new FutureTask<>(() -> { receiveTouch(fixture, 4, null); return null; });
            new Thread(response).start();
            assertThrows(ScreenCaptureException.class,
                    () -> fixture.capture.tryTouch("0", 100, 200, 300, 400, 100));
            response.get(3, TimeUnit.SECONDS);
            assertTrue(fixture.clientSocket.isClosed());
        }
    }

    @Test
    void separateInstancesDispatchConcurrentlyWithoutSharingACommandLock() throws Exception {
        try (MockWorkerFixture first = new MockWorkerFixture(720, 1280, "0");
             MockWorkerFixture second = new MockWorkerFixture(720, 1280, "1")) {
            CountDownLatch bothReady = new CountDownLatch(2);
            FutureTask<Void> peer0 = new FutureTask<>(() -> { receiveTouch(first, 0, bothReady); return null; });
            FutureTask<Void> peer1 = new FutureTask<>(() -> { receiveTouch(second, 0, bothReady); return null; });
            new Thread(peer0).start();
            new Thread(peer1).start();
            FutureTask<Boolean> input0 = new FutureTask<>(() -> first.capture.tryTouch("0", 100, 200, 300, 400, 100));
            FutureTask<Boolean> input1 = new FutureTask<>(() -> second.capture.tryTouch("1", 100, 200, 300, 400, 100));
            new Thread(input0).start();
            new Thread(input1).start();
            assertTrue(input0.get(4, TimeUnit.SECONDS));
            assertTrue(input1.get(4, TimeUnit.SECONDS));
            peer0.get(1, TimeUnit.SECONDS);
            peer1.get(1, TimeUnit.SECONDS);
            assertThrows(IllegalArgumentException.class,
                    () -> first.capture.tryTouch("1", 100, 200, 300, 400, 100));
        }
    }

    private static void receiveTouch(MockWorkerFixture fixture, Integer result, CountDownLatch ready) throws Exception {
        assertEquals(NemuHelperWorker.CMD_TOUCH_SUPPORT, fixture.serverIn.read());
        if (ready != null) {
            ready.countDown();
            assertTrue(ready.await(2, TimeUnit.SECONDS));
        }
        fixture.serverOut.writeInt(1);
        fixture.serverOut.flush();
        assertEquals(NemuHelperWorker.CMD_TOUCH, fixture.serverIn.read());
        assertEquals(100, fixture.serverIn.readInt());
        assertEquals(200, fixture.serverIn.readInt());
        assertEquals(300, fixture.serverIn.readInt());
        assertEquals(400, fixture.serverIn.readInt());
        assertEquals(100, fixture.serverIn.readInt());
        if (result != null) {
            fixture.serverOut.writeInt(result);
            fixture.serverOut.flush();
        }
    }

    @Test
    void rejectsShortFramePayloadBeforeReturningSuccess() throws Exception {
        // 2x2 RGBA image requires 16 bytes. Worker sends 4 bytes.
        try (MockWorkerFixture fixture = new MockWorkerFixture(2, 2)) {
            Thread workerThread = new Thread(() -> {
                try {
                    int cmd = fixture.serverIn.read();
                    assertEquals(NemuHelperWorker.CMD_CAPTURE, cmd);
                    fixture.serverOut.writeInt(0); // rc = 0
                    fixture.serverOut.writeInt(4); // size = 4 (mismatches expected 16)
                    fixture.serverOut.write(new byte[4]);
                    fixture.serverOut.flush();
                } catch (Exception ignored) {}
            });
            workerThread.start();

            ScreenCaptureException ex = assertThrows(ScreenCaptureException.class, () -> fixture.capture.capture("0"));
            assertTrue(ex.getMessage().contains("Payload size mismatch"),
                    "Expected payload size mismatch exception but got: " + ex.getMessage());
            workerThread.join(1000);
        }
    }

    @Test
    void compositeExecutesAdbFallbackWhenWorkerSendsMalformedPayloadSize() throws Exception {
        RawImageData adbFrame = RawImageData.capture(new byte[16], 2, 2, 32);
        AdbScreenCapture fallback = new AdbScreenCapture(instance -> adbFrame);

        try (MockWorkerFixture fixture = new MockWorkerFixture(2, 2)) {
            Thread workerThread = new Thread(() -> {
                try {
                    int cmd = fixture.serverIn.read();
                    assertEquals(NemuHelperWorker.CMD_CAPTURE, cmd);
                    fixture.serverOut.writeInt(0);
                    fixture.serverOut.writeInt(4); // malformed size: 4 instead of 16
                    fixture.serverOut.write(new byte[4]);
                    fixture.serverOut.flush();
                } catch (Exception ignored) {}
            });
            workerThread.start();

            MuMuNemuScreenCapture composite = new MuMuNemuScreenCapture(fixture.capture, fallback);
            CaptureResult result = composite.capture("0");

            assertNotNull(result);
            assertEquals("adb", result.backend(), "Composite must execute ADB fallback on malformed Nemu frame size");
            assertEquals(1, result.sequence());
            assertEquals(2L, result.sessionGeneration(), "Session generation must increment on fallback");
            workerThread.join(1000);
        }
    }

    @Test
    void unblocksImmediatelyWhenClosedDuringAcquisition() throws Exception {
        try (MockWorkerFixture fixture = new MockWorkerFixture(2, 2)) {
            CountDownLatch requestReceived = new CountDownLatch(1);
            Thread workerThread = new Thread(() -> {
                try {
                    fixture.serverIn.read();
                    requestReceived.countDown();
                    // Simulate hung worker that never responds
                    Thread.sleep(10000);
                } catch (Exception ignored) {}
            });
            workerThread.start();

            AtomicReference<Throwable> captureError = new AtomicReference<>();
            Thread captureThread = new Thread(() -> {
                try {
                    fixture.capture.capture("0");
                } catch (Throwable t) {
                    captureError.set(t);
                }
            });
            captureThread.start();

            assertTrue(requestReceived.await(2, TimeUnit.SECONDS));

            // Close should complete immediately without blocking behind capture
            long closeStart = System.nanoTime();
            fixture.capture.close();
            long closeDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStart);
            assertTrue(closeDurationMs < 250, "close() must not block behind capture (took " + closeDurationMs + " ms)");

            captureThread.join(500);
            assertFalse(captureThread.isAlive(), "Capture thread must unblock immediately on close");
            assertNotNull(captureError.get());

            workerThread.interrupt();
            workerThread.join(500);
        }
    }

    @Test
    void propagatesInterruptionDuringActiveAcquisitionWithoutReturningSuccess() throws Exception {
        try (MockWorkerFixture fixture = new MockWorkerFixture(2, 2)) {
            CountDownLatch requestReceived = new CountDownLatch(1);
            Thread workerThread = new Thread(() -> {
                try {
                    fixture.serverIn.read();
                    requestReceived.countDown();
                    // Worker delays response by 1.5 seconds
                    Thread.sleep(1500);
                    fixture.serverOut.writeInt(0);
                    fixture.serverOut.writeInt(16);
                    fixture.serverOut.write(new byte[16]);
                    fixture.serverOut.flush();
                } catch (Exception ignored) {}
            });
            workerThread.start();

            AtomicReference<String> outcome = new AtomicReference<>();
            Thread callerThread = new Thread(() -> {
                try {
                    fixture.capture.capture("0");
                    outcome.set("SUCCESS");
                } catch (InterruptedException ie) {
                    outcome.set("InterruptedException");
                } catch (Throwable t) {
                    outcome.set(t.getClass().getSimpleName());
                }
            });
            callerThread.start();

            assertTrue(requestReceived.await(2, TimeUnit.SECONDS));
            callerThread.interrupt();
            callerThread.join(250);

            assertFalse(callerThread.isAlive(), "Interrupted caller must unblock within 250ms");
            assertEquals("InterruptedException", outcome.get(), "Outcome must be InterruptedException, never SUCCESS");

            workerThread.join(2000);
        }
    }

    @Test
    void rejectsChangedNativeGeometry() throws Exception {
        try (MockWorkerFixture fixture = new MockWorkerFixture(720, 1280)) {
            Thread workerThread = new Thread(() -> {
                try {
                    int cmd = fixture.serverIn.read();
                    assertEquals(NemuHelperWorker.CMD_CAPTURE, cmd);
                    // Worker signals ERR_GEOMETRY_CHANGED (-3) e.g. on orientation change
                    fixture.serverOut.writeInt(NemuHelperWorker.ERR_GEOMETRY_CHANGED);
                    fixture.serverOut.writeInt(0);
                    fixture.serverOut.flush();
                } catch (Exception ignored) {}
            });
            workerThread.start();

            ScreenCaptureException ex = assertThrows(ScreenCaptureException.class, () -> fixture.capture.capture("0"));
            assertTrue(ex.getMessage().contains("rc=-3"), "Must reject with rc=-3 on geometry change");
            workerThread.join(1000);
        }
    }

    @Test
    void rejectsOversizedDimensions() {
        MuMuNemuProcessCapture capture = new MuMuNemuProcessCapture(Path.of("unused"), "0");
        assertThrows(ScreenCaptureException.class, () -> capture.capture("1"), "Must reject mismatched target instance");
    }
}
