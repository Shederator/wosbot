package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import dev.frostguard.data.entity.DailyTask;

class PolarTerrorHuntingRoutineTest {

    @Test
    void missingIntelTaskIsNotTreatedAsDue() {
        assertFalse(PolarTerrorHuntingRoutine.isIntelDueSoon(null, LocalDateTime.now()));
    }

    @Test
    void intelWithoutScheduleIsNotTreatedAsDue() {
        assertFalse(PolarTerrorHuntingRoutine.isIntelDueSoon(new DailyTask(), LocalDateTime.now()));
    }

    @Test
    void intelWithinFiveMinutesIsDue() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 18, 4, 25);
        DailyTask intel = new DailyTask();
        intel.setScheduledAt(now.plusMinutes(4));

        assertTrue(PolarTerrorHuntingRoutine.isIntelDueSoon(intel, now));
    }
}
