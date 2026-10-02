package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.AndroidFrameStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void eachFrameNamesTheSegmentAndDecodedFrameItCameFrom() {
        RawImageData first = RawImageData.capture(new byte[] {1, 1, 1, 1}, 1, 1, 4);
        RawImageData renewed = RawImageData.capture(new byte[] {2, 2, 2, 2}, 1, 1, 4);
        RawImageData screenshot = RawImageData.capture(new byte[] {3, 3, 3, 3}, 1, 1, 4);
        FakeHandle segmentOne = new FakeHandle(new AndroidFrameStream.Frame(first, 7, System.nanoTime()), null);
        FakeHandle segmentTwo = new FakeHandle(new AndroidFrameStream.Frame(renewed, 2, System.nanoTime()), null);
        Deque<FakeHandle> handles = new ArrayDeque<>(java.util.List.of(segmentOne, segmentTwo));
        BearRealtimeFrameSource source = new BearRealtimeFrameSource(handles::removeFirst, () -> false,
                () -> screenshot);

        source.next();
        assertEquals(new BearRealtimeFrameSource.FrameOrigin(
                BearRealtimeFrameSource.FrameOrigin.Kind.VIDEO, 1, 7), source.lastOrigin());
        source.next();
        assertEquals(new BearRealtimeFrameSource.FrameOrigin(
                BearRealtimeFrameSource.FrameOrigin.Kind.SCREENCAP, 1, 7), source.lastOrigin(),
                "a static-screen screenshot is not in the video; it names the last decoded frame before it");
        segmentOne.failure = "bounded recorder ended";
        source.next();
        assertEquals(new BearRealtimeFrameSource.FrameOrigin(
                BearRealtimeFrameSource.FrameOrigin.Kind.VIDEO, 2, 2), source.lastOrigin());
        source.close();
    }

    @Test
    void aRecordingFailureIsReportedWhileTheLiveStreamContinues() {
        RawImageData frame = RawImageData.capture(new byte[] {1, 1, 1, 1}, 1, 1, 4);
        FakeHandle handle = new FakeHandle(new AndroidFrameStream.Frame(frame, 1, System.nanoTime()), null);
        handle.recordingFailure = "disk full";
        BearRealtimeFrameSource source = new BearRealtimeFrameSource(() -> handle, () -> false);

        assertSame(frame, source.next().frame());
        assertEquals("disk full", source.recordingFailure());
        assertTrue(!handle.closed.get(), "a recording failure must not stop observation");
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
        private volatile String failure;
        private volatile String recordingFailure;
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
        public String recordingFailure() {
            return recordingFailure;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
