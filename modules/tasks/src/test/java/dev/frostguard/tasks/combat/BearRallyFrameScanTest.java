package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.*;
import dev.frostguard.api.domain.RawImageData;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BearRallyFrameScanTest {
    @Test
    void exactObservationReusesOcrButEveryNewObservationRescans() {
        var count = new AtomicInteger();
        var cache = new BearRallyFrameScan(raw -> {
            count.incrementAndGet();
            return List.of(new BearRallyScanner.RallyRow(null, 200, count.get(), "captain"));
        });
        var raw = RawImageData.capture(new byte[4], 1, 1, 32);
        var before = new BearFrameStream.Snapshot<>(1, Instant.now(), raw, BearNavigationPolicy.Screen.WAR_LIST);
        var first = cache.rows(before);
        assertSame(first, cache.rows(before));
        assertEquals(1, count.get());
        var newer = new BearFrameStream.Snapshot<>(2, Instant.now(), raw, BearNavigationPolicy.Screen.WAR_LIST);
        assertNotEquals(first, cache.rows(newer));
        assertEquals(2, count.get());
        assertTrue(cache.rows(new BearFrameStream.Snapshot<>(3, Instant.now(), raw,
                BearNavigationPolicy.Screen.FORMATION)).isEmpty());
        assertEquals(2, count.get());
    }
}
