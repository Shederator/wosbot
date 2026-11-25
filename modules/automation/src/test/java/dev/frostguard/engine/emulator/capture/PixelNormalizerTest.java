package dev.frostguard.engine.emulator.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PixelNormalizerTest {

    @Test
    void flipsEvenHeightBufferCorrectly() {
        // 2x2 RGBA image (4 bytes per pixel)
        // Row 0: [1, 1, 1, 1], [2, 2, 2, 2]
        // Row 1: [3, 3, 3, 3], [4, 4, 4, 4]
        byte[] pixels = new byte[]{
                1, 1, 1, 1,   2, 2, 2, 2,
                3, 3, 3, 3,   4, 4, 4, 4
        };

        PixelNormalizer.flipY(pixels, 2, 2, 4);

        // After flip:
        // Row 0 should be old Row 1: [3, 3, 3, 3], [4, 4, 4, 4]
        // Row 1 should be old Row 0: [1, 1, 1, 1], [2, 2, 2, 2]
        assertArrayEquals(new byte[]{
                3, 3, 3, 3,   4, 4, 4, 4,
                1, 1, 1, 1,   2, 2, 2, 2
        }, pixels);
    }

    @Test
    void flipsOddHeightBufferCorrectly() {
        // 2x3 1-byte image (width=2, height=3, bpp=1)
        // Row 0: 10, 20
        // Row 1: 30, 40 (middle row remains unchanged)
        // Row 2: 50, 60
        byte[] pixels = new byte[]{
                10, 20,
                30, 40,
                50, 60
        };

        PixelNormalizer.flipY(pixels, 2, 3, 1);

        assertArrayEquals(new byte[]{
                50, 60,
                30, 40,
                10, 20
        }, pixels);
    }

    @Test
    void doubleFlipRestoresOriginalPixels() {
        byte[] original = new byte[]{
                1, 2, 3, 4, 5, 6,
                7, 8, 9, 10, 11, 12,
                13, 14, 15, 16, 17, 18,
                19, 20, 21, 22, 23, 24
        };
        byte[] copy = original.clone();

        PixelNormalizer.flipY(copy, 3, 4, 2);
        assertFalse(java.util.Arrays.equals(original, copy));

        PixelNormalizer.flipY(copy, 3, 4, 2);
        assertArrayEquals(original, copy);
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(NullPointerException.class, () -> PixelNormalizer.flipY(null, 10, 10, 4));
        assertThrows(IllegalArgumentException.class, () -> PixelNormalizer.flipY(new byte[16], 0, 2, 4));
        assertThrows(IllegalArgumentException.class, () -> PixelNormalizer.flipY(new byte[16], 2, -1, 4));
        assertThrows(IllegalArgumentException.class, () -> PixelNormalizer.flipY(new byte[16], 2, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> PixelNormalizer.flipY(new byte[15], 2, 2, 4)); // 15 < 16
    }
}
