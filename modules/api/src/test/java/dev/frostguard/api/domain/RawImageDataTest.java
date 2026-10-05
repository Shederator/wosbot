package dev.frostguard.api.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawImageDataTest {

    @Test
    void interpretsScreencapDepthAsBitsPerPixel() {
        RawImageData rgb565 = RawImageData.capture(new byte[2 * 3 * 2], 2, 3, 16);
        RawImageData rgba8888 = RawImageData.capture(new byte[2 * 3 * 4], 2, 3, 32);

        assertTrue(rgb565.isValid());
        assertEquals(2, rgb565.getBytesPerPixel());
        assertEquals(4, rgb565.stride());
        assertTrue(rgba8888.isValid());
        assertEquals(4, rgba8888.getBytesPerPixel());
        assertEquals(8, rgba8888.stride());
    }

    @Test
    void rejectsTruncatedOrUnsupportedCaptures() {
        assertFalse(RawImageData.capture(new byte[2 * 3 * 4 - 1], 2, 3, 32).isValid());
        assertFalse(RawImageData.capture(new byte[2 * 3 * 4], 2, 3, 24).isValid());
    }
}
