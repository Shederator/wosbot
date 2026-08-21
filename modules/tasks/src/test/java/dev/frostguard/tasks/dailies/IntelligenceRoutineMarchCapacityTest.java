package dev.frostguard.tasks.dailies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

class IntelligenceRoutineMarchCapacityTest {

    @Test
    void survivorCooldownStartsOnlyAfterTwoSuccessfulDeployments() {
        assertEquals(20_000L, IntelligenceRoutine.SURVIVOR_BATCH_PAUSE_MILLIS);
        assertFalse(IntelligenceRoutine.survivorBatchCooldownRequired(0));
        assertFalse(IntelligenceRoutine.survivorBatchCooldownRequired(1));
        assertTrue(IntelligenceRoutine.survivorBatchCooldownRequired(2));
    }

    @Test
    void elapsedTravelEtasReleaseOnlyReturnedIntelMarches() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 3, 15);

        assertEquals(2, IntelligenceRoutine.countElapsedIntelMarches(now, List.of(
                now.minusSeconds(1), now, now.plusSeconds(1))));
    }

    @Test
    void configuredFlagLimitsBeastDeploymentToOneMarch() {
        assertEquals(1, IntelligenceRoutine.resolveIntelMarchCapacity(6, 3, true));
    }

    @Test
    void noFlagUsesAllConfiguredIdleMarches() {
        assertEquals(3, IntelligenceRoutine.resolveIntelMarchCapacity(6, 3, false));
    }

    @Test
    void noIdleMarchesAlwaysProducesZeroCapacity() {
        assertEquals(0, IntelligenceRoutine.resolveIntelMarchCapacity(6, 0, true));
        assertEquals(0, IntelligenceRoutine.resolveIntelMarchCapacity(6, 0, false));
    }
}
