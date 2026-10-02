package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.AndroidFrameStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Long-lived Bear transport over Android's H.264 screenrecord stream.
 *
 * <p>The Android recorder has a platform-enforced 120 second lifetime. This source renews that
 * bounded recording without resetting Bear's session-level frame sequence. It retains only the
 * newest decoded frame and refuses frames that were already stale when observed.
 */
final class BearRealtimeFrameSource implements AutoCloseable {

    static final Duration MAXIMUM_TRANSPORT_FRAME_AGE = Duration.ofMillis(500);
    private static final Duration FRAME_DEADLINE = Duration.ofSeconds(5);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(10);
    private static final int MAX_CONSECUTIVE_RESTARTS_WITHOUT_FRAME = 2;

    interface StreamHandle extends AutoCloseable {
        void start() throws IOException;

        AndroidFrameStream.Frame latestAfter(long sequence);

        String failure();

        @Override
        void close();
    }

    @FunctionalInterface
    interface StreamFactory {
        StreamHandle create();
    }

    private final StreamFactory factory;
    private final BooleanSupplier interrupted;
    private StreamHandle stream;
    private long transportSequence;
    private int consecutiveRestartsWithoutFrame;
    private boolean closed;

    BearRealtimeFrameSource(String adb, String serial, BooleanSupplier interrupted) {
        this(adb, serial, interrupted, () -> null);
    }

    /** Each bounded recorder renewal asks {@code segments} for its own evidence sink. */
    BearRealtimeFrameSource(String adb, String serial, BooleanSupplier interrupted,
            Supplier<OutputStream> segments) {
        this(() -> adapt(new AndroidFrameStream(adb, serial, segments.get())), interrupted);
    }

    BearRealtimeFrameSource(StreamFactory factory, BooleanSupplier interrupted) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.interrupted = Objects.requireNonNull(interrupted, "interrupted");
    }

    BearFrameStream.Captured<RawImageData> next() {
        ensureOpen();
        long deadline = System.nanoTime() + FRAME_DEADLINE.toNanos();
        while (true) {
            requireNotInterrupted();
            ensureStarted();

            AndroidFrameStream.Frame frame = stream.latestAfter(transportSequence);
            if (frame != null) {
                transportSequence = frame.sequence();
                long receivedAgeNanos = Math.max(0L, System.nanoTime() - frame.receivedNanos());
                if (receivedAgeNanos <= MAXIMUM_TRANSPORT_FRAME_AGE.toNanos()) {
                    consecutiveRestartsWithoutFrame = 0;
                    return new BearFrameStream.Captured<>(
                            frame.image(), Instant.now().minusNanos(receivedAgeNanos));
                }
            }

            String failure = stream.failure();
            if (failure != null) {
                restart("stream ended: " + failure);
                deadline = System.nanoTime() + FRAME_DEADLINE.toNanos();
                continue;
            }
            if (System.nanoTime() >= deadline) {
                restart("no fresh decoded frame within " + FRAME_DEADLINE);
                deadline = System.nanoTime() + FRAME_DEADLINE.toNanos();
                continue;
            }
            LockSupport.parkNanos(POLL_INTERVAL.toNanos());
        }
    }

    private void ensureStarted() {
        if (stream != null) {
            return;
        }
        stream = factory.create();
        try {
            stream.start();
            transportSequence = 0L;
        } catch (IOException | RuntimeException failure) {
            closeCurrent();
            restartFailed("could not start Android frame stream", failure);
        }
    }

    private void restart(String reason) {
        closeCurrent();
        consecutiveRestartsWithoutFrame++;
        if (consecutiveRestartsWithoutFrame > MAX_CONSECUTIVE_RESTARTS_WITHOUT_FRAME) {
            throw new IllegalStateException(
                    "Bear Android frame stream exhausted bounded renewal: " + reason);
        }
    }

    private void restartFailed(String reason, Throwable failure) {
        consecutiveRestartsWithoutFrame++;
        if (consecutiveRestartsWithoutFrame > MAX_CONSECUTIVE_RESTARTS_WITHOUT_FRAME) {
            throw new IllegalStateException(reason, failure);
        }
    }

    private void requireNotInterrupted() {
        if (interrupted.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new BearFrameStream.CaptureInterruptedException();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Bear Android frame stream is closed");
        }
    }

    private void closeCurrent() {
        if (stream != null) {
            stream.close();
            stream = null;
        }
        transportSequence = 0L;
    }

    @Override
    public void close() {
        closed = true;
        closeCurrent();
    }

    private static StreamHandle adapt(AndroidFrameStream delegate) {
        return new StreamHandle() {
            @Override
            public void start() throws IOException {
                delegate.start();
            }

            @Override
            public AndroidFrameStream.Frame latestAfter(long sequence) {
                return delegate.latestAfter(sequence);
            }

            @Override
            public String failure() {
                return delegate.failure();
            }

            @Override
            public void close() {
                delegate.close();
            }
        };
    }
}
