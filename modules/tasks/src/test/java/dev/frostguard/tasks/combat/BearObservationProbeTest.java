package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BearObservationProbeTest {
    @Test
    void reportsExpiredAnalysisWithoutRequestingAnotherFrameOrInput() {
        var captures = new AtomicInteger();
        var stream = BearFrameStream.fromTimestampedSource(() -> {
            captures.incrementAndGet();
            return new BearFrameStream.Captured<>("war", Instant.EPOCH);
        }, ignored -> BearNavigationPolicy.Screen.WAR_LIST, () -> false,
                (failure, attempt) -> false, 1,
                Clock.fixed(Instant.EPOCH.plusSeconds(2), ZoneOffset.UTC));
        var frame = stream.next();
        var lines = new ArrayList<String>();
        BearObservationProbe.inspect(stream, frame, observed -> {
            assertSame(frame, observed); return 3;
        }, () -> 0L, lines::add);
        assertEquals(1, captures.get());
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains("frame=1 rows=3"));
        assertTrue(lines.getFirst().contains("decisionAgeMs=2000"));
        assertTrue(lines.getFirst().contains("withinFreshnessBudget=false inputSent=false"));
    }

    @Test
    void nonWarScreenDoesNotRunRowAnalysis() {
        var stream = new BearFrameStream<>(() -> "world",
                ignored -> BearNavigationPolicy.Screen.WORLD, () -> false);
        BearObservationProbe.inspect(stream, stream.next(), frame -> {
            fail("No row OCR on a different screen"); return 0;
        }, () -> 0L, ignored -> fail("No row diagnostic without a scan"));
    }
}
