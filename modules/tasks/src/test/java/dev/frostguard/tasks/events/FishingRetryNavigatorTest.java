package dev.frostguard.tasks.events;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.AndroidFrameStream;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import javax.imageio.ImageIO;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FishingRetryNavigatorTest {
    @BeforeAll static void nativeLibrary() throws Exception { OpenCvPatternLocator.loadNativeLibrary(); }

    @Test void pauseExitAndGoFishReuseOnlyTheAlreadyPaidStage() throws Exception {
        var port = new FakePort("active", "paused", "suspended");
        FishingRetryNavigator.suspend(port);
        assertEquals(2, port.taps.size());
        assertEquals(1, port.releases);
        port.frames.add(fixture("suspended"));
        port.frames.add(fixture("active"));
        FishingRetryNavigator.resume(port);
        assertEquals(3, port.taps.size());
        assertTrue(port.taps.get(0).topLeft().getY() < 85);
        assertTrue(port.taps.get(1).topLeft().getY() >= 570);
        assertTrue(port.taps.get(2).topLeft().getY() >= 1040);
    }

    @Test void interruptionBeforeSuspensionSendsNoTap() throws Exception {
        var port = new FakePort("active");
        port.cancelled = true;
        assertThrows(IllegalStateException.class, () -> FishingRetryNavigator.suspend(port));
        assertTrue(port.taps.isEmpty());
    }

    @Test void stageLoadingAfterGoFishWaitsWithoutAnotherTriggerTap() throws Exception {
        var port = new FakePort("suspended-after-reload", "event", "event", "active");
        FishingRetryNavigator.resume(port);
        assertEquals(1, port.taps.size());
        assertEquals(4, port.sequence);
        assertTrue(port.taps.getFirst().topLeft().getY() >= 1040);
    }

    @Test void interruptionAfterPauseCannotTapExitOrSelectAnotherCast() throws Exception {
        var port = new FakePort("active", "paused", "suspended");
        port.cancelAfterTap = 1;
        assertThrows(IllegalStateException.class, () -> FishingRetryNavigator.suspend(port));
        assertEquals(1, port.taps.size());
    }

    @Test void ordinaryOverviewCannotAuthorizeRestartOrPause() throws Exception {
        var port = new FakePort("event");
        assertThrows(IllegalStateException.class, () -> FishingRetryNavigator.resume(port));
        assertTrue(port.taps.isEmpty());
        var other = new FakePort("event");
        assertThrows(IllegalStateException.class, () -> FishingRetryNavigator.suspend(other));
        assertTrue(other.taps.isEmpty());
    }

    private static class FakePort implements FishingRetryNavigator.Port {
        final ArrayDeque<RawImageData> frames = new ArrayDeque<>();
        final List<AreaData> taps = new ArrayList<>();
        AndroidFrameStream.Frame current;
        int releases, cancelAfterTap = Integer.MAX_VALUE;
        boolean cancelled;
        long sequence;
        FakePort(String... names) throws Exception { for (var name : names) frames.add(fixture(name)); }
        public AndroidFrameStream.Frame frame() { return current; }
        public void fresh() {
            if (frames.isEmpty()) throw new IllegalStateException("No supplied evidence for the next state");
            current = new AndroidFrameStream.Frame(frames.remove(), ++sequence, System.nanoTime());
        }
        public void release() { releases++; }
        public void tap(AreaData area) { taps.add(area); cancelled |= taps.size() >= cancelAfterTap; }
        public void checkCancellation() { if (cancelled) throw new IllegalStateException("Cancelled"); }
    }

    private static RawImageData fixture(String name) throws Exception {
        var image = ImageIO.read(Objects.requireNonNull(FishingRetryNavigatorTest.class.getResource("/fishing/" + name + ".png")));
        byte[] pixels = new byte[720 * 1280 * 4];
        for (int y = 0; y < 1280; y++) for (int x = 0; x < 720; x++) {
            int rgb = image.getRGB(x, y), offset = (y * 720 + x) * 4;
            pixels[offset] = (byte)(rgb >> 16); pixels[offset + 1] = (byte)(rgb >> 8);
            pixels[offset + 2] = (byte)rgb; pixels[offset + 3] = (byte)255;
        }
        return RawImageData.capture(pixels, 720, 1280, 32);
    }
}
