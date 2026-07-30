package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class GatherSameTargetRetryPolicyTest {

    @Test
    void retriesOneDifferentNodeBeforeStopping() {
        assertTrue(GatherSameTargetRetryPolicy.shouldSearchAnotherNode(1));
        assertFalse(GatherSameTargetRetryPolicy.shouldSearchAnotherNode(2));
    }

    @Test
    void coolsDownOnlyWhenAConflictLeavesSlotsUnfilled() {
        assertTrue(GatherSameTargetRetryPolicy.requiresCooldown(1, 1));
        assertFalse(GatherSameTargetRetryPolicy.requiresCooldown(1, 0));
        assertFalse(GatherSameTargetRetryPolicy.requiresCooldown(0, 1));
        assertEquals(Duration.ofMinutes(30), GatherSameTargetRetryPolicy.EXHAUSTED_RETRY_DELAY);
    }
}
