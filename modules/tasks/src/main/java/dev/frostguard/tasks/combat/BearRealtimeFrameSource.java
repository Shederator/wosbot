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
    /**
     * Android's recorder only emits a frame when the display changes, so a healthy stream is
     * silent on a static screen. After this wait a direct screenshot samples the current screen.
     */
    static final Duration STATIC_SCREEN_WAIT = Duration.ofMillis(300);

    interface StreamHandle extends AutoCloseable {
        void start() throws IOException;

        AndroidFrameStream.Frame latestAfter(long sequence);

        String failure();

        /** Why the evidence recording of this stream stopped, or {@code null} while it is healthy. */
        default String recordingFailure() {
            return null;
        }

        @Override
        void close();
    }

    /**
     * Where a returned frame came from, so a journal line can be matched to its exact pixels: a
     * decoded frame of a recorded segment, or a screenshot taken after that segment's last frame.
     */
    record FrameOrigin(Kind kind, int segment, long decodedFrame) {
        enum Kind { VIDEO, SCREENCAP }
    }

    @FunctionalInterface
    interface StreamFactory {
        StreamHandle create();
    }

    private final StreamFactory factory;
    private final BooleanSupplier interrupted;
    private final Supplier<RawImageData> staticScreenSample;
    private StreamHandle stream;
    private int segment;
    private FrameOrigin lastOrigin;
    private long transportSequence;
    private int consecutiveRestartsWithoutFrame;
    private boolean closed;

    /**
     * @param segments           each bounded recorder renewal asks for its own evidence sink
     * @param staticScreenSample direct screenshot used while a healthy recorder is silent
     */
    BearRealtimeFrameSource(String adb, String serial, BooleanSupplier interrupted,
            Supplier<OutputStream> segments, Supplier<RawImageData> staticScreenSample) {
        this(() -> adapt(new AndroidFrameStream(adb, serial, segments.get())), interrupted,
                staticScreenSample);
    }

    BearRealtimeFrameSource(StreamFactory factory, BooleanSupplier interrupted) {
        this(factory, interrupted, null);
    }

    BearRealtimeFrameSource(StreamFactory factory, BooleanSupplier interrupted,
            Supplier<RawImageData> staticScreenSample) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.interrupted = Objects.requireNonNull(interrupted, "interrupted");
        this.staticScreenSample = staticScreenSample;
    }

    BearFrameStream.Captured<RawImageData> next() {
        ensureOpen();
        long deadline = System.nanoTime() + FRAME_DEADLINE.toNanos();
        long staticAfter = System.nanoTime() + STATIC_SCREEN_WAIT.toNanos();
        while (true) {
            requireNotInterrupted();
            ensureStarted();

            AndroidFrameStream.Frame frame = stream.latestAfter(transportSequence);
            if (frame != null) {
                transportSequence = frame.sequence();
                long receivedAgeNanos = Math.max(0L, System.nanoTime() - frame.receivedNanos());
                if (receivedAgeNanos <= MAXIMUM_TRANSPORT_FRAME_AGE.toNanos()) {
                    consecutiveRestartsWithoutFrame = 0;
                    lastOrigin = new FrameOrigin(FrameOrigin.Kind.VIDEO, segment, frame.sequence());
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
            if (System.nanoTime() >= staticAfter && transportSequence > 0) {
                BearFrameStream.Captured<RawImageData> sampled = sampleStaticScreen();
                if (sampled != null) {
                    consecutiveRestartsWithoutFrame = 0;
                    lastOrigin = new FrameOrigin(FrameOrigin.Kind.SCREENCAP, segment, transportSequence);
                    return sampled;
                }
            }
            if (System.nanoTime() >= deadline) {
                restart("no fresh decoded frame within " + FRAME_DEADLINE);
                deadline = System.nanoTime() + FRAME_DEADLINE.toNanos();
                continue;
            }
            LockSupport.parkNanos(POLL_INTERVAL.toNanos());
        }
    }

    private BearFrameStream.Captured<RawImageData> sampleStaticScreen() {
        if (staticScreenSample == null) return null;
        // Stamp before the request: the screenshot is at least this fresh.
        Instant requestedAt = Instant.now();
        try {
            RawImageData screen = staticScreenSample.get();
            return screen == null ? null : new BearFrameStream.Captured<>(screen, requestedAt);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private void ensureStarted() {
        if (stream != null) {
            return;
        }
        stream = factory.create();
        // Each stream asks the capture for its own segment file, so both count the same way.
        segment++;
        try {
            stream.start();
            transportSequence = 0L;
        } catch (IOException | RuntimeException failure) {
            closeCurrent();
            restartFailed("could not start Android frame stream", failure);
        }
    }

    /** The origin of the frame {@link #next()} returned last, or {@code null} before the first. */
    FrameOrigin lastOrigin() {
        return lastOrigin;
    }

    /** Why the current segment's evidence recording stopped, or {@code null} while it is healthy. */
    String recordingFailure() {
        return stream == null ? null : stream.recordingFailure();
    }

    /** The segment the current stream records into; 0 before the first stream starts. */
    int segment() {
        return segment;
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
            public String recordingFailure() {
                return delegate.recordingFailure();
            }

            @Override
            public void close() {
                delegate.close();
            }
        };
    }
}
