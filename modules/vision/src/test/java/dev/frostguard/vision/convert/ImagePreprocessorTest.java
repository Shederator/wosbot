package dev.frostguard.vision.convert;

import dev.frostguard.api.domain.RawImageData;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ImagePreprocessorTest {

    @Test
    void usesSmoothInterpolationForMagnifiedGlyphs() {
        RawImageData capture = RawImageData.capture(new byte[] {
                0, 0, 0, (byte) 255,
                (byte) 255, (byte) 255, (byte) 255, (byte) 255
        }, 2, 1, 4);

        BufferedImage prepared = ImagePreprocessor.prepareForOcr(capture, 0, 0, 2, 1, false, null);

        assertTrue(IntStream.range(0, prepared.getWidth())
                .map(x -> prepared.getRGB(x, 0) & 0xFF)
                .anyMatch(value -> value > 0 && value < 255));
    }
}
