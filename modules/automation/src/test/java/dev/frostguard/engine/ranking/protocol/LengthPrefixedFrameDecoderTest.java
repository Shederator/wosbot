package dev.frostguard.engine.ranking.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LengthPrefixedFrameDecoderTest {

    @Test
    void reassemblesFragmentedAndCoalescedFrames() {
        LengthPrefixedFrameDecoder decoder = new LengthPrefixedFrameDecoder(1024);

        assertTrue(decoder.accept(new byte[]{0, 3, 10}).isEmpty());
        List<byte[]> frames = decoder.accept(new byte[]{11, 12, 0, 2, 20, 21});

        assertEquals(2, frames.size());
        assertArrayEquals(new byte[]{10, 11, 12}, frames.get(0));
        assertArrayEquals(new byte[]{20, 21}, frames.get(1));
        decoder.requireComplete();
    }

    @Test
    void rejectsInvalidAndTruncatedFramesConservatively() {
        LengthPrefixedFrameDecoder invalid = new LengthPrefixedFrameDecoder(1024);
        assertThrows(IllegalArgumentException.class, () -> invalid.accept(new byte[]{0, 0}));

        LengthPrefixedFrameDecoder truncated = new LengthPrefixedFrameDecoder(1024);
        truncated.accept(new byte[]{0, 4, 1, 2});
        assertThrows(IllegalStateException.class, truncated::requireComplete);
    }
}
