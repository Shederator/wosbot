package dev.frostguard.tasks.events;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FishingSessionPolicyTest {
    @Test
    void restartBudgetFailsClosedAndTargetRespectsActualCapacity() {
        for (Integer value : new Integer[] {null, -1, 11, Integer.MAX_VALUE})
            assertEquals(0, FishingSessionPolicy.restartLimit(value));
        assertEquals(2, FishingSessionPolicy.restartLimit(2));
        assertEquals(10, FishingSessionPolicy.catchTarget(20, 10));
        assertEquals(20, FishingSessionPolicy.catchTarget(null, 20));
    }

    @Test
    void shortHaulRetryNeedsActiveReturnFreshEvidenceAndRemainingBudget() {
        long now = 2_000_000_000L;
        var depth = new FishingDepthMonitor.Reading(new FishingDepthMonitor.Depth(15, 550), 10, now);
        var haul = new FishingHaulTracker.Reading(19, 20, 10, now, false);
        assertTrue(FishingSessionPolicy.shouldRestart(true, 0, 2, FishingPhaseTracker.Phase.ASCENDING, depth, haul, now, 20));
        assertFalse(FishingSessionPolicy.shouldRestart(false, 0, 2, FishingPhaseTracker.Phase.ASCENDING, depth, haul, now, 20));
        assertFalse(FishingSessionPolicy.shouldRestart(true, 2, 2, FishingPhaseTracker.Phase.ASCENDING, depth, haul, now, 20));
        assertFalse(FishingSessionPolicy.shouldRestart(true, 0, 2, FishingPhaseTracker.Phase.DESCENDING, depth, haul, now, 20));
        assertFalse(FishingSessionPolicy.shouldRestart(true, 0, 2, FishingPhaseTracker.Phase.ASCENDING, depth, haul, now + 1_000_000_000L, 20));
        assertFalse(FishingSessionPolicy.shouldRestart(true, 0, 2, FishingPhaseTracker.Phase.ASCENDING, depth, haul, now - 1, 20));
        assertFalse(FishingSessionPolicy.shouldRestart(true, 0, 2, FishingPhaseTracker.Phase.ASCENDING, depth, haul, now, 10));
        assertFalse(FishingSessionPolicy.shouldRestart(true, 0, 2, FishingPhaseTracker.Phase.ASCENDING,
                new FishingDepthMonitor.Reading(new FishingDepthMonitor.Depth(0, 550), 11, now), haul, now, 20));
        assertTrue(FishingSessionPolicy.shouldRestart(true, 0, 2, FishingPhaseTracker.Phase.ASCENDING,
                new FishingDepthMonitor.Reading(new FishingDepthMonitor.Depth(35, 550), 11, now), haul, now, 20));
        assertFalse(FishingSessionPolicy.shouldRestart(true, 0, 2, FishingPhaseTracker.Phase.ASCENDING,
                new FishingDepthMonitor.Reading(new FishingDepthMonitor.Depth(41, 550), 11, now), haul, now, 20));
    }
    @Test
    void malformedLimitsNeverAuthorizeAnUnboundedSession() {
        for (Integer value : new Integer[]{null, -1, 0, 11, Integer.MAX_VALUE}) {
            assertEquals(1, FishingSessionPolicy.castLimit(value));
        }
        assertEquals(1, FishingSessionPolicy.castLimit(1));
        assertEquals(10, FishingSessionPolicy.castLimit(10));
    }

    @Test
    void bothCurrentLabelsAreRequiredToAuthorizeHaulExit() {
        assertTrue(FishingSessionPolicy.verifiedHaul(" Haul ", " EXIT "));
        assertFalse(FishingSessionPolicy.verifiedHaul("Haul", null));
        assertFalse(FishingSessionPolicy.verifiedHaul(null, "Exit"));
        assertFalse(FishingSessionPolicy.verifiedHaul("Haul", "Restart"));
        assertFalse(FishingSessionPolicy.verifiedHaul("Fishing Tournament", "Exit"));
    }
}
