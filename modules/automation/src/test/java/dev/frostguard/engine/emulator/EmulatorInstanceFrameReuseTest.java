package dev.frostguard.engine.emulator;

import dev.frostguard.api.domain.RawImageData;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EmulatorInstanceFrameReuseTest {

    @Test
    void reusesCachedFrameWithoutCapturingEagerly() {
        RawImageData cached = RawImageData.capture(new byte[4], 1, 1, 4);
        AtomicInteger captures = new AtomicInteger();

        RawImageData selected = EmulatorInstance.selectFrame(Map.of("device", cached), "device", true, () -> {
            captures.incrementAndGet();
            return RawImageData.capture(new byte[4], 1, 1, 4);
        });

        assertSame(cached, selected);
        assertEquals(0, captures.get());
    }

    @Test
    void capturesWhenReuseIsDisabledOrNoFrameExists() {
        RawImageData fresh = RawImageData.capture(new byte[4], 1, 1, 4);
        AtomicInteger captures = new AtomicInteger();

        assertSame(fresh, EmulatorInstance.selectFrame(Map.of(), "device", true, () -> {
            captures.incrementAndGet();
            return fresh;
        }));
        assertSame(fresh, EmulatorInstance.selectFrame(Map.of("device", fresh), "device", false, () -> {
            captures.incrementAndGet();
            return fresh;
        }));
        assertEquals(2, captures.get());
    }
}
