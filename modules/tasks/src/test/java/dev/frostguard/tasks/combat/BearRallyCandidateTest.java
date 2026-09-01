package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BearRallyCandidateTest {

    @Test
    void keepsSignatureStableAsCountdownAdvances() {
        Instant firstObservation = Instant.parse("2026-08-18T10:00:00Z");
        BearRallyCandidate first = candidate(firstObservation, Duration.ofMinutes(4));
        BearRallyCandidate second = candidate(firstObservation.plusSeconds(20), Duration.ofSeconds(220));

        assertEquals(first.getCandidateKey(), second.getCandidateKey());
    }

    private BearRallyCandidate candidate(Instant observedAt, Duration countdown) {
        return new BearRallyCandidate(
                new PointData(100, 100),
                new AreaData(new PointData(0, 0), new PointData(200, 200)),
                " Host1 ", 2, 6, 25_000L, 100_000L, 75_000L,
                countdown, observedAt, true);
    }
}
