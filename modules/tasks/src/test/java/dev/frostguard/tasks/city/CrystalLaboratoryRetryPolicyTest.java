package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CrystalLaboratoryRetryPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 12, 9, 0);
    private static final LocalDateTime DAILY_RESET = LocalDateTime.of(2026, 9, 13, 0, 0);

    @Test
    void backsOffAndEventuallyWaitsForDailyReset() {
        assertEquals(NOW.plusMinutes(5),
                CrystalLaboratoryRetryPolicy.retryAt(NOW, DAILY_RESET, 1));
        assertEquals(NOW.plusMinutes(30),
                CrystalLaboratoryRetryPolicy.retryAt(NOW, DAILY_RESET, 2));
        assertEquals(DAILY_RESET,
                CrystalLaboratoryRetryPolicy.retryAt(NOW, DAILY_RESET, 3));
        assertEquals(DAILY_RESET,
                CrystalLaboratoryRetryPolicy.retryAt(NOW, DAILY_RESET, 20));
    }
}
