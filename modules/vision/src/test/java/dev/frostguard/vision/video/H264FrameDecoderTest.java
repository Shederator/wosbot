package dev.frostguard.vision.video;

import java.util.Objects;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class H264FrameDecoderTest {
    @Test
    void decodesNativeEventFramesAndReturnsIndependentRgbaStorage() throws Exception {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/video/event.h264"));
                var decoder = new H264FrameDecoder(input)) {
            decoder.start();
            var first = decoder.nextFrame();
            assertNotNull(first);
            assertEquals(720, first.getWidth());
            assertEquals(1280, first.getHeight());
            assertEquals(32, first.getBpp());
            byte[] saved = first.getData().clone();
            var second = decoder.nextFrame();
            assertNotNull(second);
            assertNotSame(first.getData(), second.getData());
            assertArrayEquals(saved, first.getData());
            int blueButton = (1190 * 720 + 520) * 4;
            assertTrue((first.getData()[blueButton + 2] & 255) > (first.getData()[blueButton] & 255));
            assertEquals(255, first.getData()[blueButton + 3] & 255);
        }
    }
}
