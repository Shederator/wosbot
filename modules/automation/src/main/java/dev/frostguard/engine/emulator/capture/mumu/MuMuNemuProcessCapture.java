package dev.frostguard.engine.emulator.capture.mumu;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.capture.CaptureResult;
import dev.frostguard.engine.emulator.capture.ScreenCapture;
import dev.frostguard.engine.emulator.capture.ScreenCaptureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Supervised native IPC capture backend for MuMu Player on Windows.
 * Launches and manages an isolated {@link NemuHelperWorker} child process.
 */
public class MuMuNemuProcessCapture implements ScreenCapture, NemuTouchInput {

    public static final String BACKEND_NAME = "mumu_ipc";
    public static final int MAX_DIMENSION = 7680;
    public static final long MAX_FRAME_BYTES = 128 * 1024 * 1024L;

    private static final Logger LOG = LoggerFactory.getLogger(MuMuNemuProcessCapture.class);
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CAPTURE_TIMEOUT = Duration.ofSeconds(3);
    private static final int SOCKET_TICK_MS = 100;

    private final Path mumuRoot;
    private final String targetInstance;
    private final ReentrantLock captureLock = new ReentrantLock();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong sessionGeneration = new AtomicLong(0);

    private volatile Process workerProcess;
    private volatile Socket socket;
    private volatile DataInputStream in;
    private volatile DataOutputStream out;
    private volatile int displayWidth;
    private volatile int displayHeight;
    private volatile boolean closed;

    public MuMuNemuProcessCapture(Path mumuRoot, String targetInstance) {
        this.mumuRoot = Objects.requireNonNull(mumuRoot, "mumuRoot required");
        this.targetInstance = Objects.requireNonNull(targetInstance, "targetInstance required");
    }

    @Override
    public boolean tryTouch(String instance, int x, int y, int endX, int endY, int durationMs)
            throws ScreenCaptureException, InterruptedException {
        if (!targetInstance.equals(instance)) throw new IllegalArgumentException("Touch instance mismatch");
        if (durationMs <= 0 || durationMs > NemuTouchGesture.MAX_DURATION_MS) {
            throw new IllegalArgumentException("Native gesture duration must be between 1 and 10000 ms");
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Touch cancelled");
        if (closed) throw new ScreenCaptureException(BACKEND_NAME, "Touch session is closed");
        try {
            if (!captureLock.tryLock(CAPTURE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ScreenCaptureException(BACKEND_NAME, "Timed out waiting for native input lock");
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw cancelled;
        }
        boolean dispatched = false;
        try {
            if (closed) throw new ScreenCaptureException(BACKEND_NAME, "Touch session is closed");
            try {
                ensureWorkerStarted();
                long readyDeadline = System.nanoTime() + CAPTURE_TIMEOUT.toNanos();
                out.write(NemuHelperWorker.CMD_TOUCH_SUPPORT);
                out.flush();
                int support = readIntWithDeadline(in, readyDeadline);
                if (support == 0) return false;
                if (support != 1) throw new IOException("Invalid native touch capability response");
            } catch (IOException unavailable) {
                stopWorker();
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Touch setup cancelled");
                if (closed) throw new ScreenCaptureException(BACKEND_NAME, "Touch session is closed", unavailable);
                LOG.warn("Native touch unavailable before dispatch on instance {}: {}", instance, unavailable.getMessage());
                return false;
            }
            if (x < 0 || x >= displayWidth || endX < 0 || endX >= displayWidth
                    || y < 0 || y >= displayHeight || endY < 0 || endY >= displayHeight) {
                throw new IllegalArgumentException("Touch point is outside the display");
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Touch cancelled before dispatch");
            DataOutputStream request = out;
            if (closed || request == null) throw new ScreenCaptureException(BACKEND_NAME, "Touch session is closed");
            long deadline = System.nanoTime() + CAPTURE_TIMEOUT.toNanos() + TimeUnit.MILLISECONDS.toNanos(durationMs);
            // Mark before writing: a partial write is already an ambiguous outcome.
            dispatched = true;
            request.write(NemuHelperWorker.CMD_TOUCH);
            request.writeInt(x);
            request.writeInt(y);
            request.writeInt(endX);
            request.writeInt(endY);
            request.writeInt(durationMs);
            request.flush();
            int result = readIntWithDeadline(in, deadline);
            if (result != 0) throw new IOException("Native gesture failed with rc=" + result);
            return true;
        } catch (InterruptedException cancelled) {
            stopWorker();
            Thread.currentThread().interrupt();
            throw cancelled;
        } catch (IOException failure) {
            stopWorker();
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Touch cancelled during cleanup");
            throw new ScreenCaptureException(BACKEND_NAME,
                    dispatched ? "Native touch outcome unknown; gesture was not replayed" : "Native touch failed before dispatch", failure);
        } finally {
            captureLock.unlock();
        }
    }

    @Override
    public CaptureResult capture(String instance) throws ScreenCaptureException, InterruptedException {
        if (closed) {
            throw new ScreenCaptureException(BACKEND_NAME, "Capture backend is closed");
        }
        if (!targetInstance.equals(instance)) {
            throw new ScreenCaptureException(BACKEND_NAME, "Target instance mismatch: expected "
                    + targetInstance + " but requested " + instance);
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Capture cancelled before lock acquisition");
        }

        boolean acquired;
        try {
            acquired = captureLock.tryLock(CAPTURE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new InterruptedException("Interrupted waiting for capture lock");
        }

        if (!acquired) {
            throw new ScreenCaptureException(BACKEND_NAME, "Timed out waiting for capture lock");
        }

        try {
            if (closed) {
                throw new ScreenCaptureException(BACKEND_NAME, "Capture backend is closed");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Capture cancelled before worker invocation");
            }

            ensureWorkerStarted();

            long start = System.nanoTime();
            long deadlineNanos = start + CAPTURE_TIMEOUT.toNanos();

            try {
                if (socket != null && !socket.isClosed()) {
                    socket.setSoTimeout(SOCKET_TICK_MS);
                }

                out.write(NemuHelperWorker.CMD_CAPTURE);
                out.flush();

                int rc = readIntWithDeadline(in, deadlineNanos);
                int size = readIntWithDeadline(in, deadlineNanos);

                if (rc != 0) {
                    stopWorker();
                    throw new ScreenCaptureException(BACKEND_NAME, "Nemu native capture failed with rc=" + rc + ", size=" + size);
                }

                long expectedSize = (long) displayWidth * displayHeight * 4L;
                if (size != expectedSize || size <= 0 || size > MAX_FRAME_BYTES) {
                    stopWorker();
                    throw new ScreenCaptureException(BACKEND_NAME, "Payload size mismatch or invalid: expected "
                            + expectedSize + " for " + displayWidth + "x" + displayHeight + " but received " + size);
                }

                byte[] pixels = new byte[size];
                readFullyWithDeadline(in, pixels, 0, size, deadlineNanos);

                if (Thread.currentThread().isInterrupted()) {
                    stopWorker();
                    throw new InterruptedException("Capture cancelled during payload read");
                }

                RawImageData image = RawImageData.capture(pixels, displayWidth, displayHeight, 32);
                if (!image.isValid()) {
                    stopWorker();
                    throw new ScreenCaptureException(BACKEND_NAME, "Constructed image is invalid for "
                            + displayWidth + "x" + displayHeight + " (bytes=" + pixels.length + ")");
                }

                if (Thread.currentThread().isInterrupted()) {
                    stopWorker();
                    throw new InterruptedException("Capture cancelled after frame construction");
                }

                long end = System.nanoTime();
                return new CaptureResult(
                        image,
                        BACKEND_NAME,
                        sequence.incrementAndGet(),
                        end,
                        Duration.ofNanos(end - start),
                        sessionGeneration.get(),
                        true
                );
            } catch (Exception e) {
                stopWorker();
                if (e instanceof InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Capture cancelled: " + e.getMessage());
                }
                if (e instanceof ScreenCaptureException sce) {
                    throw sce;
                }
                throw new ScreenCaptureException(BACKEND_NAME, "Capture error on instance " + instance + ": " + e.getMessage(), e);
            }
        } finally {
            captureLock.unlock();
        }
    }

    @Override
    public String backendName() {
        return BACKEND_NAME;
    }

    @Override
    public boolean isAvailable(String instance) {
        if (closed || !targetInstance.equals(instance) || !MuMuNemuDiscovery.isWindows()) {
            return false;
        }
        return MuMuNemuDiscovery.discover(mumuRoot).isPresent();
    }

    private void ensureWorkerStarted() throws ScreenCaptureException, InterruptedException {
        if (workerProcess != null && workerProcess.isAlive() && socket != null && socket.isConnected() && !socket.isClosed()) {
            return;
        }
        stopWorker();
        startWorker();
    }

    private void startWorker() throws ScreenCaptureException, InterruptedException {
        Optional<MuMuNemuDiscovery.NemuPaths> paths = MuMuNemuDiscovery.discover(mumuRoot);
        if (paths.isEmpty()) {
            throw new ScreenCaptureException(BACKEND_NAME, "MuMu Nemu IPC DLL not found under " + mumuRoot);
        }

        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!new File(javaBin).isFile()) {
            javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        }

        String classpath = System.getProperty("java.class.path");
        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-cp", classpath,
                NemuHelperWorker.class.getName(),
                paths.get().root().toAbsolutePath().toString(),
                targetInstance
        );
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        long deadlineNanos = System.nanoTime() + STARTUP_TIMEOUT.toNanos();

        try {
            workerProcess = pb.start();

            CompletableFuture<String> portLineFuture = new CompletableFuture<>();
            Thread stdoutDrainer = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(workerProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String firstLine = reader.readLine();
                    if (firstLine != null) {
                        portLineFuture.complete(firstLine);
                    } else {
                        portLineFuture.completeExceptionally(new EOFException("Helper process closed stdout before port announcement"));
                    }
                    char[] sink = new char[1024];
                    while (reader.read(sink) != -1) {
                        // drain remaining stdout noise
                    }
                } catch (Throwable t) {
                    portLineFuture.completeExceptionally(t);
                }
            }, "nemu-stdout-" + targetInstance);
            stdoutDrainer.setDaemon(true);
            stdoutDrainer.start();

            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new ScreenCaptureException(BACKEND_NAME, "Timed out starting Nemu helper worker");
            }

            String line;
            try {
                line = portLineFuture.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException te) {
                stopWorker();
                throw new ScreenCaptureException(BACKEND_NAME, "Timed out waiting for Nemu helper port line", te);
            } catch (ExecutionException ee) {
                stopWorker();
                throw new ScreenCaptureException(BACKEND_NAME, "Failed reading Nemu helper port line: "
                        + ee.getCause().getMessage(), ee);
            }

            if (line == null || !line.startsWith("PORT ")) {
                stopWorker();
                throw new ScreenCaptureException(BACKEND_NAME, "Unexpected handshake line from Nemu helper: " + line);
            }

            int port;
            try {
                port = Integer.parseInt(line.substring(5).trim());
            } catch (NumberFormatException nfe) {
                stopWorker();
                throw new ScreenCaptureException(BACKEND_NAME, "Malformed port from helper: " + line, nfe);
            }

            remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                stopWorker();
                throw new ScreenCaptureException(BACKEND_NAME, "Timed out before connecting to helper socket");
            }

            int connectTimeoutMs = (int) Math.min(TimeUnit.NANOSECONDS.toMillis(remainingNanos), 3000L);
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(SOCKET_TICK_MS);
            socket.connect(new InetSocketAddress("127.0.0.1", port), Math.max(connectTimeoutMs, 100));

            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());

            int status = readIntWithDeadline(in, deadlineNanos);
            if (status != 0) {
                stopWorker();
                throw new ScreenCaptureException(BACKEND_NAME, "Nemu helper handshake failed with status " + status);
            }

            displayWidth = readIntWithDeadline(in, deadlineNanos);
            displayHeight = readIntWithDeadline(in, deadlineNanos);

            long expectedSize = (long) displayWidth * displayHeight * 4L;
            if (displayWidth <= 0 || displayWidth > MAX_DIMENSION || displayHeight <= 0
                    || displayHeight > MAX_DIMENSION || expectedSize > MAX_FRAME_BYTES) {
                stopWorker();
                throw new ScreenCaptureException(BACKEND_NAME, "Invalid display dimensions from helper: "
                        + displayWidth + "x" + displayHeight + " (requires " + expectedSize + " bytes)");
            }

            sessionGeneration.incrementAndGet();
            LOG.info("MuMu Nemu IPC capture worker connected for instance {} ({}x{}) on port {}",
                    targetInstance, displayWidth, displayHeight, port);
        } catch (Exception e) {
            stopWorker();
            if (e instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Capture worker startup cancelled");
            }
            if (e instanceof ScreenCaptureException sce) {
                throw sce;
            }
            throw new ScreenCaptureException(BACKEND_NAME, "Failed to start Nemu helper: " + e.getMessage(), e);
        }
    }

    private void stopWorker() {
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {}
        }
        DataInputStream dis = in;
        in = null;
        if (dis != null) {
            try {
                dis.close();
            } catch (Exception ignored) {}
        }
        DataOutputStream dos = out;
        out = null;
        if (dos != null) {
            try {
                dos.close();
            } catch (Exception ignored) {}
        }

        Process p = workerProcess;
        workerProcess = null;
        if (p != null) {
            try {
                p.destroyForcibly();
                p.waitFor(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            } catch (Exception ignored) {
                p.destroyForcibly();
            }
        }
    }

    private int readIntWithDeadline(DataInputStream stream, long deadlineNanos) throws IOException, InterruptedException {
        int ch1 = readByteWithDeadline(stream, deadlineNanos);
        int ch2 = readByteWithDeadline(stream, deadlineNanos);
        int ch3 = readByteWithDeadline(stream, deadlineNanos);
        int ch4 = readByteWithDeadline(stream, deadlineNanos);
        return ((ch1 << 24) + (ch2 << 16) + (ch3 << 8) + ch4);
    }

    private int readByteWithDeadline(InputStream stream, long deadlineNanos) throws IOException, InterruptedException {
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Capture cancelled");
            }
            if (closed) {
                throw new ScreenCaptureException(BACKEND_NAME, "Capture backend is closed");
            }
            if (System.nanoTime() > deadlineNanos) {
                throw new ScreenCaptureException(BACKEND_NAME, "Capture operation exceeded deadline");
            }
            try {
                int b = stream.read();
                if (b < 0) {
                    throw new EOFException("Premature EOF from capture worker");
                }
                return b;
            } catch (SocketTimeoutException ste) {
                // Tick occurred: loop around to check deadline, interruption, and closed state
            }
        }
    }

    private void readFullyWithDeadline(InputStream stream, byte[] b, int off, int len, long deadlineNanos) throws IOException, InterruptedException {
        int n = 0;
        while (n < len) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Capture cancelled during payload transfer");
            }
            if (closed) {
                throw new ScreenCaptureException(BACKEND_NAME, "Capture backend is closed");
            }
            if (System.nanoTime() > deadlineNanos) {
                throw new ScreenCaptureException(BACKEND_NAME, "Capture payload delivery exceeded deadline");
            }
            try {
                int count = stream.read(b, off + n, len - n);
                if (count < 0) {
                    throw new EOFException("Premature EOF reading frame payload: received " + n + " of " + len + " bytes");
                }
                n += count;
            } catch (SocketTimeoutException ste) {
                // Tick occurred: loop around to check deadline, interruption, and closed state
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        stopWorker();
    }
}
