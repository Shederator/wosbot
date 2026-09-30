package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void keepsTheFallbackFlagWhenAnOutOfRangeTimerStillReturnsATime() {
        assertTrue(StorehouseChestRoutine.fallbackAfterRead(true, NOW.plusHours(1)));
        assertEquals("timer unreadable or invalid (fallback)", StorehouseChestRoutine.scheduleReason(true));
    }

    @Test
    void callsAReadableCountdownValidatedOnlyWhenTheReaderAcceptedIt() {
        assertFalse(StorehouseChestRoutine.fallbackAfterRead(false, NOW.plusMinutes(30)));
        assertTrue(StorehouseChestRoutine.fallbackAfterRead(false, null));
        assertEquals("validated chest timer", StorehouseChestRoutine.scheduleReason(false));
    }

    @Test
    void retriesOneHourWhenTheChestIsAbsentAndTheCountdownIsUnread() {
        assertEquals(NOW.plusHours(StorehouseChestRoutine.UNREADABLE_WITHOUT_CHEST_HOURS),
                StorehouseChestRoutine.nextVisitWhenChestAbsent(NOW, null));
        assertEquals(NOW.plusHours(StorehouseChestRoutine.UNREADABLE_WITHOUT_CHEST_HOURS),
                StorehouseChestRoutine.nextVisitWhenChestAbsent(NOW, NOW.minusMinutes(1)));
    }

    @Test
    void keepsAReadableBuildingCountdownWhenTheChestIsAbsent() {
        LocalDateTime remaining = NOW.plusMinutes(15).plusSeconds(58);

        assertEquals(remaining, StorehouseChestRoutine.nextVisitWhenChestAbsent(NOW, remaining));
    }
}
