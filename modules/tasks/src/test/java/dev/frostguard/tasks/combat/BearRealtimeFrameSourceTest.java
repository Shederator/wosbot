package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.AndroidFrameStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BearRealtimeFrameSourceTest {

    @Test
    void renewsEndedTransportWithoutResettingBearSource() {
        RawImageData expected = RawImageData.capture(new byte[] {0, 0, 0, 0}, 1, 1, 4);
        FakeHandle ended = new FakeHandle(null, "bounded recorder ended");
        FakeHandle replacement = new FakeHandle(
                new AndroidFrameStream.Frame(expected, 1, System.nanoTime()), null);
        Deque<FakeHandle> handles = new ArrayDeque<>();
        handles.add(ended);
        handles.add(replacement);
        BearRealtimeFrameSource source = new BearRealtimeFrameSource(handles::removeFirst, () -> false);

        assertSame(expected, source.next().frame());
        assertTrue(ended.closed.get());
        source.close();
        assertTrue(replacement.closed.get());
    }

    @Test
    void staticScreenIsSampledByAFreshScreenshotInsteadOfRestartingTheRecorder() {
        RawImageData streamed = RawImageData.capture(new byte[] {1, 1, 1, 1}, 1, 1, 4);
        RawImageData screenshot = RawImageData.capture(new byte[] {2, 2, 2, 2}, 1, 1, 4);
        FakeHandle stalled = new FakeHandle(
                new AndroidFrameStream.Frame(streamed, 1, System.nanoTime()), null);
        AtomicInteger factories = new AtomicInteger();
        BearRealtimeFrameSource source = new BearRealtimeFrameSource(() -> {
            factories.incrementAndGet();
            return stalled;
        }, () -> false, () -> screenshot);

        assertSame(streamed, source.next().frame());
        long started = System.nanoTime();
        BearFrameStream.Captured<RawImageData> unchanged = source.next();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertSame(screenshot, unchanged.frame(), "an unchanged screen is still current");
        assertTrue(elapsedMs < 1_000, "a static screen must not wait for the recorder deadline");
        assertTrue(!stalled.closed.get() && factories.get() == 1,
                "a healthy recorder on a static screen must not be restarted");
        source.close();
    }

    @Test
    void cancellationStopsBeforeStartingTransport() {
        AtomicInteger factories = new AtomicInteger();
        BearRealtimeFrameSource source = new BearRealtimeFrameSource(() -> {
            factories.incrementAndGet();
            return new FakeHandle(null, null);
        }, () -> true);

        assertThrows(BearFrameStream.CaptureInterruptedException.class, source::next);
        assertTrue(factories.get() == 0);
    }

    private static final class FakeHandle implements BearRealtimeFrameSource.StreamHandle {
        private final AndroidFrameStream.Frame frame;
        private final String failure;
        private final AtomicBoolean closed = new AtomicBoolean();

        private FakeHandle(AndroidFrameStream.Frame frame, String failure) {
            this.frame = frame;
            this.failure = failure;
        }

        @Override
        public void start() throws IOException {
        }

        @Override
        public AndroidFrameStream.Frame latestAfter(long sequence) {
            return frame != null && frame.sequence() > sequence ? frame : null;
        }

        @Override
        public String failure() {
            return failure;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
