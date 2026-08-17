package dev.frostguard.vision.convert;

import dev.frostguard.api.domain.RawImageData;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImageConverterTest {

    @Test
    void convertsRgbaWithoutSwappingChannels() {
        RawImageData capture = RawImageData.capture(new byte[] {
                (byte) 0x12, (byte) 0x34, (byte) 0x56, (byte) 0x78
        }, 1, 1, 4);

        BufferedImage image = ImageConverter.toBufferedImage(capture);

        assertEquals(0x123456, image.getRGB(0, 0) & 0xFFFFFF);
    }

    @Test
    void convertsLittleEndianRgb565Pixels() {
        RawImageData capture = RawImageData.capture(new byte[] {
                0x00, (byte) 0xF8,
                (byte) 0xE0, 0x07,
                0x1F, 0x00
        }, 3, 1, 2);

        BufferedImage image = ImageConverter.toBufferedImage(capture);

        assertEquals(0xF80000, image.getRGB(0, 0) & 0xFFFFFF);
        assertEquals(0x00FC00, image.getRGB(1, 0) & 0xFFFFFF);
        assertEquals(0x0000F8, image.getRGB(2, 0) & 0xFFFFFF);
    }
}
