package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class JourneyOfLightRetryPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 12, 0);
    private static final LocalDateTime DAILY_RESET = LocalDateTime.of(2026, 10, 2, 0, 0);

    @Test
    void backsOffAcrossThreeFailuresThenWaitsUntilReset() {
        assertEquals(NOW.plusMinutes(5), JourneyOfLightRetryPolicy.retryAt(NOW, DAILY_RESET, 1));
        assertEquals(NOW.plusMinutes(30), JourneyOfLightRetryPolicy.retryAt(NOW, DAILY_RESET, 2));
        assertEquals(DAILY_RESET, JourneyOfLightRetryPolicy.retryAt(NOW, DAILY_RESET, 3));
        assertEquals(DAILY_RESET, JourneyOfLightRetryPolicy.retryAt(NOW, DAILY_RESET, 4));
    }

    @Test
    void doesNotScheduleRetryPastTheUpcomingReset() {
        LocalDateTime upcomingReset = NOW.plusMinutes(3);

        assertEquals(upcomingReset, JourneyOfLightRetryPolicy.retryAt(NOW, upcomingReset, 1));
    }
}
