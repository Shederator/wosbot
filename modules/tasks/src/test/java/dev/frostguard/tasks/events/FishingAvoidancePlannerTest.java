package dev.frostguard.tasks.events;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.frostguard.tasks.events.FishingAvoidancePlanner.Fish;

class FishingAvoidancePlannerTest {
    @Test
    void preparesAnAlreadyClearEscapeBeforeTheThreatBecomesUrgent() {
        var route = FishingAvoidancePlanner.plan(360, 400,
                List.of(new Fish(360, 900, 100, 40, 0, -.6)), 100);
        assertTrue(route.safe());
        assertTrue(route.target() < 250 || route.target() > 470,
                "A clear early escape must not be postponed into later short guidance legs: " + route);
    }

    @Test
    void plansAnEscapeFromTheRealWallOutsideThePreferredLaneMargin() {
        var route = FishingAvoidancePlanner.plan(20, 400,
                List.of(new Fish(20, 600, 40, 30, 0, -.6)), 40);
        assertTrue(route.safe());
        assertTrue(route.target() > 80);
    }

    @Test
    void rejectsCollisionForAnIntermediateInputDelayEvenWhenBothEndpointsAreClear() {
        assertFalse(FishingAvoidancePlanner.clearFirstAction(300, 480, 400,
                List.of(new Fish(420, -3600, 10, 2, 0, 20)), 300, 550));
    }

    @Test
    void doesNotClaimALatencyLimitedEdgeEscapeIsReachable() {
        assertFalse(FishingAvoidancePlanner.plan(35, 400,
                List.of(new Fish(35, 500, 40, 30, 0, -.6)), 40).safe());
    }

    @Test
    void doesNotHoldForTheFirstLegWhenFeedbackPreventsThePlannedLateEscape() {
        // It enters the collision band after the old 150ms leg, before feedback settles.
        var route = FishingAvoidancePlanner.plan(360, 400,
                List.of(new Fish(360, 620, 100, 40, 0, -.6)), 100);
        assertTrue(route.safe());
        assertTrue(route.target() < 276 || route.target() > 444,
                "Escape must happen in this action, not an unavailable later correction");
    }

    @Test
    void keepsAShortSafeEscapeWhenTheDistantHorizonIsBlocked() {
        var objects = List.of(new Fish(360, 560, 100, 40, 0, -.6),
                new Fish(360, 1120, 720, 20, 0, -1));
        var route = FishingAvoidancePlanner.plan(360, 400, objects, 40);
        assertTrue(route.safe());
        assertTrue(route.target() < 276 || route.target() > 444);
        assertTrue(route.times().getLast() < 900);
        assertTrue(route.times().getLast() >= 300);
    }

    @Test
    void rejectsAThinFastObjectCrossingBetweenSamplingTicks() {
        assertFalse(FishingAvoidancePlanner.clearSweep(100, 600, 400,
                List.of(new Fish(350, -1660, 10, 2, 0, 20)), 200));
    }

    @Test
    void reportsNoRouteRatherThanCallingAHoldSafe() {
        var route = FishingAvoidancePlanner.plan(95, 400,
                List.of(new Fish(360, 400, 720, 100, 0, 0)), 40);
        assertFalse(route.safe());
        assertEquals(95, route.target());
    }

    @Test
    void takesSuccessiveGapsInsteadOfUnioningTheirPositions() {
        // First barrier leaves only the left side; the later one leaves only the right.
        var objects = List.of(new Fish(540, 520, 360, 20, 0, -1),
                new Fish(180, 1060, 360, 20, 0, -1));
        var route = FishingAvoidancePlanner.plan(300, 400, objects, 40);
        assertTrue(route.safe(), "A timed left-then-right route exists");
        assertTrue(route.target() < 313);
        assertTrue(route.positions().getLast() > 407);
        for (int i = 1; i < route.positions().size(); i++) {
            assertTrue(FishingAvoidancePlanner.clearTransition(route.positions().get(i - 1),
                    route.positions().get(i), 400, objects,
                    route.times().get(i - 1), route.times().get(i), i == 1));
        }
    }

    @Test
    void boundsMovementAndRejectsInvalidTiming() {
        var route = FishingAvoidancePlanner.plan(360, 400,
                List.of(new Fish(360, 560, 100, 40, 0, -0.6)), 40);
        assertTrue(route.safe());
        assertTrue(Math.abs(route.target() - 360) <= 180);
        assertFalse(FishingAvoidancePlanner.plan(360, 400, List.of(), Double.NaN).safe());
    }

    @Test
    void staysPutWhenNoFishWillCrossTheHook() {
        assertEquals(360, FishingAvoidancePlanner.target(360, 400,
                List.of(new Fish(360, 100, 80, 30, 0, -0.6)), 40));
    }

    @Test
    void movesOutOfTheFutureFishCorridorRatherThanParkingAtAnEdge() {
        int target = FishingAvoidancePlanner.target(360, 400,
                List.of(new Fish(360, 560, 100, 40, 0, -0.6)), 40);
        assertTrue(target < 276 || target > 444, "Target must avoid the padded fish corridor: " + target);
    }

    @Test
    void accountsForFishMovementAndWallBounces() {
        assertEquals(675, FishingAvoidancePlanner.reflectedX(680, 0.25, 20, 180), 0.01);
        assertFalse(FishingAvoidancePlanner.clearTransition(680, 680, 400,
                List.of(new Fish(680, 500, 40, 20, .25, -.6)), 0, 300, false));
    }

    @Test
    void doesNotTreatTheOppositeEdgeAsPermanentlySafe() {
        assertTrue(FishingAvoidancePlanner.target(35, 400,
                List.of(new Fish(35, 600, 40, 30, 0, -0.6)), 40) > 80);
    }

    @Test
    void holdsPositionInsteadOfClaimingCentreIsSafeWhenAllCorridorsAreBlocked() {
        assertEquals(95, FishingAvoidancePlanner.target(95, 400,
                List.of(new Fish(360, 400, 720, 100, 0, 0)), 40));
    }

    @Test
    void rejectsADragAcrossAnObjectEvenWhenTheDestinationIsClear() {
        assertFalse(FishingAvoidancePlanner.clearSweep(100, 600, 400,
                List.of(new Fish(350, 400, 40, 20, 0, 0)), 200));
    }

    @Test
    void permitsMovingOutOfAnExistingOverlapButNotDeeperIntoIt() {
        var objects = List.of(new Fish(360, 400, 80, 20, 0, 0));
        assertTrue(FishingAvoidancePlanner.clearSweep(350, 220, 400, objects, 200));
        assertFalse(FishingAvoidancePlanner.clearSweep(350, 500, 400, objects, 200));
    }
}
