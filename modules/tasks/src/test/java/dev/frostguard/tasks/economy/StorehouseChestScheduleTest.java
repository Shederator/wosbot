package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class StorehouseChestScheduleTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 27, 12, 49, 59);

    @Test
    void keepsAFutureChestCountdown() {
        LocalDateTime chestCountdown = NOW.plusHours(1).plusMinutes(1).plusSeconds(2);

        assertEquals(chestCountdown, StorehouseChestRoutine.nextChestVisit(NOW, chestCountdown));
    }

    @Test
    void retriesFiveMinutesWhenTheChestCountdownIsMissing() {
        assertEquals(NOW.plusMinutes(5), StorehouseChestRoutine.nextChestVisit(NOW, null));
    }

    @Test
    void retriesFiveMinutesWhenTheChestCountdownIsAlreadyPast() {
        assertEquals(NOW.plusMinutes(5), StorehouseChestRoutine.nextChestVisit(NOW, NOW.minusMinutes(1)));
    }

    @Test
    void keepsAChestCountdownThatIsExactlyNow() {
        assertEquals(NOW, StorehouseChestRoutine.nextChestVisit(NOW, NOW));
    }
}
