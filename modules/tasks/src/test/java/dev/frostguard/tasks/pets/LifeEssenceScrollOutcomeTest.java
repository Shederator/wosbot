package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

class LifeEssenceScrollOutcomeTest {

    @Test
    void doesNotCountAnUnresolvedWeeklyScrollAsACompletedRun() {
        assertFalse(LifeEssenceRoutine.countsAsCompletedRun(true));
        assertTrue(LifeEssenceRoutine.countsAsCompletedRun(false));
    }

    @Test
    void storesTheNextMondayResetOnlyAsAConfirmedPurchaseTime() {
        ZonedDateTime tuesday = ZonedDateTime.of(2026, 9, 29, 15, 0, 0, 0, ZoneOffset.UTC);

        assertEquals(LocalDateTime.of(2026, 10, 5, 0, 0), LifeEssenceRoutine.nextMondayReset(tuesday));
    }
}
