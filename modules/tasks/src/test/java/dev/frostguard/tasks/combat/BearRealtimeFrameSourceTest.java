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
