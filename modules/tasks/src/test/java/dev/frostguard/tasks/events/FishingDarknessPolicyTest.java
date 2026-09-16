package dev.frostguard.tasks.events;

import dev.frostguard.api.domain.RawImageData;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FishingDarknessPolicyTest {
    @Test
    void cornerIsChosenOnceAndDarknessRequiresDistinctFrames() {
        var policy = new FishingDarknessPolicy(true);
        var dark = frame(0, 0, 0);
        assertEquals(70, policy.corner());
        assertFalse(policy.observe(dark, 1));
        assertFalse(policy.observe(dark, 1));
        assertFalse(policy.observe(dark, 2));
        assertTrue(policy.observe(dark, 3));
        assertEquals(70, policy.corner());
        assertFalse(policy.observe(frame(8, 128, 179), 4));
        assertEquals(650, new FishingDarknessPolicy(false).corner());
    }

    private static RawImageData frame(int r, int g, int b) {
        byte[] pixels = new byte[720 * 1280 * 4];
        for (int offset = 0; offset < pixels.length; offset += 4) {
            pixels[offset] = (byte)r; pixels[offset + 1] = (byte)g;
            pixels[offset + 2] = (byte)b; pixels[offset + 3] = (byte)255;
        }
        return RawImageData.capture(pixels, 720, 1280, 32);
    }
}
