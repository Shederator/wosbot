package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ResearchTimerPolicyTest {

    @Test
    void rechecksLongAndShortResearchAtHalfTime() {
        assertEquals(Duration.ofHours(6),
                ResearchTimerPolicy.recheckDelay(Duration.ofHours(12)));
        assertEquals(Duration.ofMinutes(15),
                ResearchTimerPolicy.recheckDelay(Duration.ofMinutes(30)));
        assertEquals(Duration.ofSeconds(119).plusMillis(500),
                ResearchTimerPolicy.recheckDelay(Duration.ofMinutes(3).plusSeconds(59)));
    }

    @Test
    void keepsAtLeastOneMinuteBetweenChecks() {
        assertEquals(Duration.ofMinutes(1),
                ResearchTimerPolicy.recheckDelay(Duration.ofMinutes(1)));
        assertEquals(Duration.ofMinutes(1),
                ResearchTimerPolicy.recheckDelay(Duration.ofSeconds(59)));
        assertEquals(Duration.ofMinutes(1),
                ResearchTimerPolicy.recheckDelay(Duration.ZERO));
    }
}
