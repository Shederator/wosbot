package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class StorehouseChestScheduleTest {

    @Test
    void schedulesFiveMinuteRetryWhenStorehouseCannotBeOpenedBeforeFlowStarts() {
        StorehouseVisitFlow.VisitDecision decision = StorehouseVisitFlow.retryBeforeFlow(
                "Could not open Storehouse.");

        assertEquals(StorehouseVisitFlow.VisitState.RETRY_ON_ERROR, decision.state());
        assertEquals(Duration.ofMinutes(5), decision.delay());
        assertEquals("Could not open Storehouse.", decision.reason());
        assertEquals(0, decision.confirmedChestCollections());
        assertEquals(0, decision.confirmedStaminaCollections());
    }
}
