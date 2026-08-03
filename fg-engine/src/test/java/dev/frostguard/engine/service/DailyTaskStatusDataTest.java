package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import dev.frostguard.api.domain.DailyTaskStatusData;
import org.junit.jupiter.api.Test;

class DailyTaskStatusDataTest {

    @Test
    void projectedStaminaDeferralSurvivesScheduleCopy() {
        LocalDateTime lastRun = LocalDateTime.of(2026, 7, 21, 1, 39);
        LocalDateTime nextRun = LocalDateTime.of(2026, 7, 21, 2, 10);
        LocalDateTime marchFloor = LocalDateTime.of(2026, 7, 21, 1, 42);
        DailyTaskStatusData data = new DailyTaskStatusData(
                2L, 18, lastRun, nextRun, null, 145, 170, marchFloor);

        DailyTaskStatusData copy = data.withUpdatedSchedule(nextRun.plusMinutes(5));

        assertTrue(copy.hasStaminaDeferral());
        assertEquals(145, copy.getStaminaMinimumRequired());
        assertEquals(170, copy.getStaminaRegenerationTarget());
        assertEquals(marchFloor, copy.getStaminaEarliestRunnableAt());
    }
}
