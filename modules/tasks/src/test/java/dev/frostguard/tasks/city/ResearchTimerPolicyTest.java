package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ResearchTimerPolicyTest {

    // matt/Claude, 2026-08-17: this test predated ResearchTimerPolicy's third revision
    // (see the class doc comment) and still asserted the FIRST, deliberately-abandoned
    // "half the remaining time" behavior -- the one the comment says left a 19h58m
    // research unlooked-at for ~10h. The policy itself was already correct; only the
    // test was stale. Rewritten to assert the current, documented, intentional
    // behavior: recheck at the real completion time plus a small settle margin.

    @Test
    void rechecksAtActualCompletionPlusMargin() {
        assertEquals(Duration.ofHours(12).plusSeconds(30),
                ResearchTimerPolicy.recheckDelay(Duration.ofHours(12)));
        assertEquals(Duration.ofMinutes(30).plusSeconds(30),
                ResearchTimerPolicy.recheckDelay(Duration.ofMinutes(30)));
        assertEquals(Duration.ofMinutes(4).plusSeconds(29),
                ResearchTimerPolicy.recheckDelay(Duration.ofMinutes(3).plusSeconds(59)));
    }

    @Test
    void keepsAtLeastOneMinuteBetweenChecks() {
        assertEquals(Duration.ofMinutes(1).plusSeconds(30),
                ResearchTimerPolicy.recheckDelay(Duration.ofMinutes(1)));
        assertEquals(Duration.ofMinutes(1).plusSeconds(29),
                ResearchTimerPolicy.recheckDelay(Duration.ofSeconds(59)));
        // Zero remaining time undershoots the floor even after the margin (0s + 30s = 30s < 1m),
        // so this is the one case that actually clamps to MINIMUM_RECHECK_DELAY.
        assertEquals(Duration.ofMinutes(1),
                ResearchTimerPolicy.recheckDelay(Duration.ZERO));
    }

    @Test
    void clampsToSanityCeilingForGarbledReads() {
        assertEquals(Duration.ofDays(7),
                ResearchTimerPolicy.recheckDelay(Duration.ofDays(400)));
    }

    @Test
    void fallsBackToMinimumForNullOrNegative() {
        assertEquals(Duration.ofMinutes(1), ResearchTimerPolicy.recheckDelay(null));
        assertEquals(Duration.ofMinutes(1), ResearchTimerPolicy.recheckDelay(Duration.ofMinutes(-5)));
    }
}
