package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.tasks.pets.LifeEssenceClaimPolicy.Observation;
import dev.frostguard.tasks.pets.LifeEssenceClaimPolicy.Outcome;
import dev.frostguard.tasks.pets.LifeEssenceClaimPolicy.State;
import dev.frostguard.tasks.pets.LifeEssenceClaimPolicy.Step;

class LifeEssenceClaimPolicyTest {

    private static final PointData FIRST = new PointData(100, 140);
    private static final PointData SECOND = new PointData(360, 360);
    private static final PointData THIRD = new PointData(660, 350);

    @Test
    void finishesAnAlreadyEmptyIslandWithoutCountingAClaim() {
        Step waiting = LifeEssenceClaimPolicy.advance(State.initial(), Observation.markers(List.of()));
        assertEquals(Outcome.WAIT, waiting.outcome());
        assertEquals(0, waiting.confirmedClaims());

        Step finished = LifeEssenceClaimPolicy.advance(waiting.next(), Observation.markers(List.of()));
        assertEquals(Outcome.FINISH, finished.outcome());
        assertEquals(0, finished.confirmedClaims());
        assertNull(finished.tap());
    }

    @Test
    void confirmsThreeDisappearingMarkersThenStops() {
        Step first = LifeEssenceClaimPolicy.advance(
                State.initial(), Observation.markers(List.of(FIRST, SECOND, THIRD)));
        assertEquals(Outcome.TAP, first.outcome());
        assertEquals(FIRST, first.tap());
        assertEquals(0, first.confirmedClaims());

        Step second = LifeEssenceClaimPolicy.advance(first.next(), Observation.markers(List.of(SECOND, THIRD)));
        assertEquals(Outcome.TAP, second.outcome());
        assertEquals(SECOND, second.tap());
        assertEquals(1, second.confirmedClaims());

        Step third = LifeEssenceClaimPolicy.advance(second.next(), Observation.markers(List.of(THIRD)));
        assertEquals(Outcome.TAP, third.outcome());
        assertEquals(THIRD, third.tap());
        assertEquals(2, third.confirmedClaims());

        Step done = LifeEssenceClaimPolicy.advance(third.next(), Observation.markers(List.of()));
        assertEquals(Outcome.FINISH, done.outcome());
        assertEquals(3, done.confirmedClaims());
    }

    @Test
    void finishesOnceAVisibleMarkerDisappearsAndTheNextCaptureStaysEmpty() {
        Step tapped = LifeEssenceClaimPolicy.advance(State.initial(), Observation.markers(List.of(FIRST)));
        Step waiting = LifeEssenceClaimPolicy.advance(tapped.next(), Observation.markers(List.of()));
        assertEquals(Outcome.WAIT, waiting.outcome());
        assertEquals(1, waiting.confirmedClaims());

        Step finished = LifeEssenceClaimPolicy.advance(waiting.next(), Observation.markers(List.of()));
        assertEquals(Outcome.FINISH, finished.outcome());
        assertEquals(1, finished.confirmedClaims());
    }

    @Test
    void doesNotCountAMarkerThatStaysAfterTheTap() {
        Step tapped = LifeEssenceClaimPolicy.advance(State.initial(), Observation.markers(List.of(FIRST)));
        assertEquals(Outcome.TAP, tapped.outcome());

        Step stillThere = LifeEssenceClaimPolicy.advance(tapped.next(), Observation.markers(List.of(FIRST)));
        assertEquals(Outcome.TAP, stillThere.outcome());
        assertEquals(0, stillThere.confirmedClaims());
        assertEquals(FIRST, stillThere.tap());

        State state = stillThere.next();
        Step last = stillThere;
        while (last.outcome() == Outcome.TAP) {
            last = LifeEssenceClaimPolicy.advance(state, Observation.markers(List.of(FIRST)));
            state = last.next();
        }
        assertEquals(Outcome.RETRY, last.outcome());
        assertEquals(0, last.confirmedClaims());
        assertEquals(LifeEssenceClaimPolicy.MAX_CAPTURES, last.next().captures());
    }

    @Test
    void retriesImmediatelyWhenTheFirstCaptureFails() {
        Step failed = LifeEssenceClaimPolicy.advance(State.initial(), Observation.failed());
        assertEquals(Outcome.RETRY, failed.outcome());
        assertEquals(0, failed.confirmedClaims());
    }

    @Test
    void retriesWhenALaterCaptureFailsAfterAConfirmedClaim() {
        Step first = LifeEssenceClaimPolicy.advance(State.initial(), Observation.markers(List.of(FIRST, SECOND)));
        Step second = LifeEssenceClaimPolicy.advance(first.next(), Observation.markers(List.of(SECOND)));
        assertEquals(1, second.confirmedClaims());

        Step failed = LifeEssenceClaimPolicy.advance(second.next(), Observation.failed());
        assertEquals(Outcome.RETRY, failed.outcome());
        assertEquals(1, failed.confirmedClaims());
    }

    @Test
    void doesNotTreatAnUnconfirmedScreenAsAnEmptyIsland() {
        Step unconfirmed = LifeEssenceClaimPolicy.advance(State.initial(), Observation.screenUnconfirmed());
        assertEquals(Outcome.RETRY, unconfirmed.outcome());
        assertEquals(0, unconfirmed.confirmedClaims());
    }
}
