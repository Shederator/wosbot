package dev.frostguard.tasks.events;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FishingPhaseTrackerTest {
    @Test
    void contactPrimingNeedsFreshDepthAndAnObservedUninterruptedTopBand() {
        var tracker = new FishingPhaseTracker();
        assertFalse(tracker.canPrimeContact(true));
        tracker.observeHook(577, 1);
        assertFalse(tracker.canPrimeContact(true));
        tracker.observeHook(183, 2);
        assertTrue(tracker.canPrimeContact(true));
        assertFalse(tracker.canPrimeContact(false));
        assertFalse(tracker.descentAuthorized(), "Stationary contact is not permission to steer during startup");
        tracker.observeHook(300, 3);
        assertFalse(tracker.canPrimeContact(true));
    }
    @Test
    void fallingDepthAndVerifiedReturnHookBandCanConfirmAscentWhenCaughtObjectsObscureMotion() {
        var tracker = new FishingPhaseTracker();
        tracker.observeHook(183, 1);
        tracker.observe(depth(5), 1, -.6);
        tracker.observe(depth(6), 2, -.6);
        tracker.observe(depth(7), 3, -.6);
        tracker.observe(depth(70), 4, -.6);
        tracker.observeHook(1027, 2);
        assertEquals(FishingPhaseTracker.Phase.DESCENDING, tracker.observe(depth(68), 5, null));
        assertEquals(FishingPhaseTracker.Phase.ASCENDING, tracker.observe(depth(66), 6, null));
        assertFalse(tracker.canSteer(true, -.6));
    }

    @Test
    void returnHookBandWithoutFallingDepthCannotAuthorizeAscent() {
        var tracker = new FishingPhaseTracker();
        tracker.observeHook(183, 1);
        tracker.observe(depth(5), 1, -.6);
        tracker.observe(depth(6), 2, -.6);
        tracker.observe(depth(7), 3, -.6);
        tracker.observeHook(1027, 2);
        tracker.observe(depth(8), 4, null);
        assertEquals(FishingPhaseTracker.Phase.DESCENDING, tracker.observe(depth(9), 5, null));
        assertFalse(tracker.canSteer(true, -.6));
    }

    @Test
    void missingDepthRequiresFreshDescendingMotionAndCannotBypassHookTransition() {
        var tracker = new FishingPhaseTracker();
        tracker.observeHook(183, 1);
        tracker.observe(depth(5), 1, -0.6);
        tracker.observe(depth(6), 2, -0.6);
        tracker.observe(depth(7), 3, -0.6);
        assertTrue(tracker.canSteer(false, -0.6));
        assertFalse(tracker.canSteer(false, null));
        assertFalse(tracker.canSteer(false, 0.6));
        tracker.observeHook(278, 2);
        assertFalse(tracker.canSteer(true, -0.6), "Turnaround wins over both depth and scene-motion evidence");
    }
    @Test
    void sparseSceneCanConfirmInitialDescentOnlyWithAnObservedTopHookBand() {
        var tracker = new FishingPhaseTracker();
        tracker.observe(depth(5), 1, null);
        tracker.observe(depth(6), 2, null);
        assertEquals(FishingPhaseTracker.Phase.UNKNOWN, tracker.observe(depth(7), 3, null));
        tracker.observeHook(183, 1);
        tracker.observe(depth(8), 4, null);
        assertEquals(FishingPhaseTracker.Phase.DESCENDING, tracker.observe(depth(9), 5, null));
        tracker.observe(depth(4), 6, null);
        assertEquals(FishingPhaseTracker.Phase.DESCENDING, tracker.observe(depth(3), 7, null),
                "Sparse or missing motion evidence cannot confirm an OCR-only return");
    }
    @Test
    void recordsTurnaroundWhileDepthPhaseIsStillUnknown() {
        var tracker = new FishingPhaseTracker();
        tracker.observeHook(183, 1);
        tracker.observeHook(257, 2);
        tracker.observeHook(527, 3);
        tracker.observe(depth(41), 1, -0.6);
        tracker.observe(depth(44), 2, -0.6);
        tracker.observe(depth(45), 3, -0.6);
        assertEquals(FishingPhaseTracker.Phase.DESCENDING, tracker.phase());
        assertFalse(tracker.descentAuthorized(), "Late OCR cannot reauthorize steering after a visible turnaround");
    }
    @Test
    void fallingOcrDigitsCannotAuthorizeAscentWhileTheSceneStillMovesDownwardRelativeToTheHook() {
        var tracker = new FishingPhaseTracker();
        tracker.observe(depth(5), 1, -0.6);
        tracker.observe(depth(6), 2, -0.6);
        tracker.observe(depth(12), 3, -0.6);
        tracker.observe(depth(7), 4, -0.6);
        assertEquals(FishingPhaseTracker.Phase.DESCENDING, tracker.observe(depth(2), 5, -0.6));
        tracker.observe(depth(50), 6, -0.6);
        tracker.observe(depth(48), 7, 0.6);
        assertEquals(FishingPhaseTracker.Phase.ASCENDING, tracker.observe(depth(46), 8, 0.6));
    }

    @Test
    void aLargeHookTransitionBlocksDescentEvenBeforeOcrConfirmsTheReturn() {
        var tracker = new FishingPhaseTracker();
        tracker.observe(depth(5), 1, -0.6);
        tracker.observe(depth(6), 2, -0.6);
        tracker.observe(depth(7), 3, -0.6);
        tracker.observeHook(183, 10);
        assertTrue(tracker.descentAuthorized());
        tracker.observeHook(184, 11);
        tracker.observeHook(524, 12);
        assertFalse(tracker.descentAuthorized());
        tracker.observeHook(183, 12);
        assertFalse(tracker.descentAuthorized(), "A repeated frame cannot clear the transition guard");
    }

    @Test
    void recognisesSlowDepthProgressWithoutWaitingForTwoMetreJumps() {
        var tracker = new FishingPhaseTracker();
        tracker.observe(depth(5), 1, -0.6);
        tracker.observe(depth(6), 2, -0.6);
        assertEquals(FishingPhaseTracker.Phase.DESCENDING, tracker.observe(depth(7), 3, -0.6));
    }

    @Test
    void confirmsDirectionFromDistinctDepthReadsAndNeverReturnsToDescentAfterTurnaround() {
        var tracker = new FishingPhaseTracker();
        assertEquals(FishingPhaseTracker.Phase.UNKNOWN, tracker.observe(depth(165), 1, -0.6));
        tracker.observe(depth(170), 2, -0.6);
        assertEquals(FishingPhaseTracker.Phase.DESCENDING, tracker.observe(depth(181), 3, -0.6));
        tracker.observe(depth(200), 4, -0.6);
        tracker.observe(depth(193), 5, 0.6);
        assertEquals(FishingPhaseTracker.Phase.ASCENDING, tracker.observe(depth(175), 6, 0.6));
        tracker.observe(depth(180), 7, 0.6);
        assertEquals(FishingPhaseTracker.Phase.ASCENDING, tracker.observe(depth(185), 8, 0.6));
        assertEquals(200, tracker.peak());
    }

    @Test
    void duplicateMissingAndEquipmentMismatchReadsCannotManufactureTurnaround() {
        var tracker = new FishingPhaseTracker();
        tracker.observe(depth(165), 1, -0.6);
        tracker.observe(depth(170), 2, -0.6);
        tracker.observe(depth(181), 3, -0.6);
        tracker.observe(depth(160), 3, 0.6);
        tracker.observe(null, 4, null);
        tracker.observe(new FishingDepthMonitor.Depth(10, 100), 5, 0.6);
        assertEquals(FishingPhaseTracker.Phase.DESCENDING, tracker.phase());
    }

    private static FishingDepthMonitor.Depth depth(int value) {
        return new FishingDepthMonitor.Depth(value, 550);
    }
}
