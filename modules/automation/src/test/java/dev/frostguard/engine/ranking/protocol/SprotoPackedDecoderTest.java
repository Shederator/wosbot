package dev.frostguard.engine.ranking.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SprotoPackedDecoderTest {

    private final SprotoPackedDecoder decoder = new SprotoPackedDecoder();

    @Test
    void expandsZeroMasksAndRawRuns() {
        byte[] packed = {
                0x15, 1, 2, 3,
                (byte) 0xff, 0,
                11, 12, 13, 14, 15, 16, 17, 18
        };

        assertArrayEquals(new byte[]{
                1, 0, 2, 0, 3, 0, 0, 0,
                11, 12, 13, 14, 15, 16, 17, 18
        }, decoder.unpack(packed));
    }

    @Test
    void rejectsTruncatedPackedInput() {
        assertThrows(IllegalArgumentException.class, () -> decoder.unpack(new byte[]{0x03, 1}));
        assertThrows(IllegalArgumentException.class,
                () -> decoder.unpack(new byte[]{(byte) 0xff, 1, 1, 2, 3}));
    }
}
