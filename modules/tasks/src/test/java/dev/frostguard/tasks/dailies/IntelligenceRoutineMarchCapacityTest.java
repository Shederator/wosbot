package dev.frostguard.tasks.dailies;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class IntelligenceRoutineMarchCapacityTest {

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
