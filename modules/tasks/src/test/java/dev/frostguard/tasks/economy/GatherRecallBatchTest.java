package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.frostguard.tasks.economy.GatherRoutine.ActiveGatherMarchCandidate;
import dev.frostguard.tasks.economy.GatherRoutine.GatherType;
import dev.frostguard.tasks.economy.GatherRoutine.RecallAttempt;

class GatherRecallBatchTest {

    @Test
    void reducedQueueLimitBlocksNewDeploymentsWithoutRequiringRecall() {
        assertTrue(GatherRoutine.hasReachedConfiguredQueueLimit(3, 1));
        assertTrue(GatherRoutine.hasReachedConfiguredQueueLimit(1, 1));
        assertEquals(false, GatherRoutine.hasReachedConfiguredQueueLimit(0, 1));
    }

    @Test
    void highPriorityRecallStopsAfterFirstScanWithoutControls() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 1, 0);
        List<ActiveGatherMarchCandidate> candidates = List.of(
                new ActiveGatherMarchCandidate(GatherType.MEAT, 0, now.plusHours(2)),
                new ActiveGatherMarchCandidate(GatherType.WOOD, 1, now.plusHours(3)));
        AtomicInteger attempts = new AtomicInteger();

        var result = GatherRoutine.executeRecallBatch(candidates, ignored -> {
            attempts.incrementAndGet();
            return RecallAttempt.CONTROLS_NOT_FOUND;
        });

        assertEquals(1, attempts.get());
        assertEquals(0, result.recalled());
        assertTrue(result.controlsMissing());
    }
}
