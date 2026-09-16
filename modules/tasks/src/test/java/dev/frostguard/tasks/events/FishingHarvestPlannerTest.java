package dev.frostguard.tasks.events;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FishingHarvestPlannerTest {
    @Test
    void uncertainGainCannotSendTheCaughtStackOffscreen() {
        assertEquals(-83, FishingHarvestPlanner.boundedDrag(279, -192));
        assertEquals(0, FishingHarvestPlanner.boundedDrag(56, -192));
        assertEquals(237, FishingHarvestPlanner.boundedDrag(56, 250));
        assertEquals(0, FishingHarvestPlanner.boundedDrag(675, 250));
        assertEquals(-100, FishingHarvestPlanner.boundedDrag(500, -100));
        assertEquals(-83, FishingHarvestPlanner.boundedDrag(279, Integer.MIN_VALUE));
    }
    @Test
    void quantityPursuitAvoidsWallLanesWhereTheCaughtStackHidesTheHook() {
        assertEquals(239, FishingHarvestPlanner.fillTarget(279, 1093,
                List.of(opportunity(31, 800, 0), opportunity(74, 800, 0),
                        opportunity(239, 800, 0), opportunity(650, 800, 0)), 150, 15).orElseThrow());
        assertTrue(FishingHarvestPlanner.fillTarget(279, 1093,
                List.of(opportunity(31, 800, 0)), 150, 15).isEmpty());
    }
    @Test
    void quantityFallbackSeeksAnUpcomingObjectRatherThanDoingNothingOnReturn() {
        assertEquals(460, FishingHarvestPlanner.fillTarget(360, 1093,
                List.of(opportunity(460, 793, 0)), 150, 20).orElseThrow());
    }

    @Test
    void doesNotAbandonAnAlignedImminentCatchForADistantOne() {
        assertEquals(216, FishingHarvestPlanner.fillTarget(216, 1093,
                List.of(opportunity(230, 990, 0), opportunity(650, 500, 0)), 150, 7).orElseThrow());
    }

    @Test
    void fallbackDoesNotInventValuesOrTreatAForegroundBoxAsAVerifiedHead() {
        assertEquals(360, FishingHarvestPlanner.fillTarget(360, 1000,
                List.of(opportunity(350, 800, 0), opportunity(600, 450, 0)), 100, 20).orElseThrow());
        assertTrue(FishingHarvestPlanner.target(360, 1000, List.of(), 100, 20).isEmpty());
    }

    @Test
    void fallbackRespectsCapacityMotionInputDelayAndReachability() {
        assertTrue(FishingHarvestPlanner.fillTarget(360, 1000,
                List.of(opportunity(400, 700, 0)), 100, 0).isEmpty());
        assertTrue(FishingHarvestPlanner.fillTarget(360, 1000,
                List.of(opportunity(600, 960, 0)), 150, 20).isEmpty());
        assertTrue(FishingHarvestPlanner.fillTarget(360, 1000,
                List.of(new FishingHarvestPlanner.Opportunity(400, 700, 80, 40, 0, -0.6)), 100, 20).isEmpty());
        assertTrue(FishingHarvestPlanner.fillTarget(360, 1000,
                List.of(opportunity(400, 1050, 0)), 100, 20).isEmpty());
    }

    @Test
    void movingObjectsUseAnExplicitUnverifiedLeadingSideHeuristic() {
        assertEquals(410, FishingHarvestPlanner.fillTarget(360, 1000,
                List.of(opportunity(360, 700, 0.06)), 100, 20).orElseThrow());
    }

    private static FishingHarvestPlanner.Opportunity opportunity(int x, int y, double vx) {
        return new FishingHarvestPlanner.Opportunity(x, y, 80, 40, vx, 0.6);
    }
    @Test
    void prefersTheHigherValueReachableHeadInsteadOfTheClosestBody() {
        assertEquals(500, FishingHarvestPlanner.target(360, 900, List.of(
                catchable(350, 700, 10), catchable(500, 700, 70)), 100, 3).orElseThrow());
    }

    @Test
    void ignoresUnidentifiedObjectsAndHealthyDivers() {
        assertTrue(FishingHarvestPlanner.target(360, 900, List.of(
                new FishingHarvestPlanner.Candidate(360, 700, 0, 0.6, 150, 0.4, true),
                new FishingHarvestPlanner.Candidate(360, 700, 0, 0.6, 150, 1, false)),
                100, 3).isEmpty());
    }

    @Test
    void fullHaulStopsSteeringAndTargetsAlreadyBelowTheHookAreIgnored() {
        assertTrue(FishingHarvestPlanner.target(360, 900,
                List.of(catchable(400, 700, 150)), 100, 0).isEmpty());
        assertTrue(FishingHarvestPlanner.target(360, 900,
                List.of(catchable(400, 950, 150)), 100, 3).isEmpty());
    }

    @Test
    void rejectsTargetsThatPassBeforeInputCompletes() {
        assertTrue(FishingHarvestPlanner.target(360, 900,
                List.of(catchable(600, 850, 150)), 200, 3).isEmpty());
    }

    @Test
    void predictsHeadMovementAndRevalidatesTheInterceptAtTheWall() {
        var moving = new FishingHarvestPlanner.Candidate(670, 700, 0.25, 0.6, 70, 1, true);
        int target = FishingHarvestPlanner.target(500, 900, List.of(moving), 100, 3).orElseThrow();
        assertTrue(target >= 30 && target <= 690);
        assertNotEquals(670, target);
    }

    private static FishingHarvestPlanner.Candidate catchable(int x, int y, int points) {
        return new FishingHarvestPlanner.Candidate(x, y, 0, 0.6, points, 1, true);
    }
}
