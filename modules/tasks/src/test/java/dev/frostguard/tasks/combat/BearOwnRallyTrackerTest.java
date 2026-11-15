package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.MarchActivityType;
import dev.frostguard.api.domain.MarchCountdownKind;
import dev.frostguard.api.domain.MarchMovementPhase;
import dev.frostguard.api.domain.MarchSlotAvailability;
import dev.frostguard.api.domain.MarchSlotReleaseConfidence;
import dev.frostguard.api.domain.MarchSlotState;
import dev.frostguard.api.domain.MarchSlotStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BearOwnRallyTrackerTest {

    @Test
    void identifiesRallyThatOccupiedAnIdleSlot() {
        List<MarchSlotState> before = List.of(idle(1), rally(2, MarchMovementPhase.PREPARING, null));
        List<MarchSlotState> after = List.of(rally(1, MarchMovementPhase.PREPARING, null),
                rally(2, MarchMovementPhase.PREPARING, null));

        assertEquals(1, BearOwnRallyTracker.identifyNewRallySlot(before, after));
    }

    @Test
    void doesNotClaimAnExistingRallyAsNew() {
        List<MarchSlotState> before = List.of(rally(2, MarchMovementPhase.PREPARING, null));
        List<MarchSlotState> after = List.of(rally(2, MarchMovementPhase.PREPARING, null));

        assertNull(BearOwnRallyTracker.identifyNewRallySlot(before, after));
    }

    @Test
    void exactReturnCountdownPausesAgainstTheTrackedSlot() {
        Duration countdown = Duration.ofSeconds(12);

        BearOwnRallyTracker.Observation observation = BearOwnRallyTracker.observe(
                3, List.of(rally(3, MarchMovementPhase.RETURNING, countdown)));

        assertEquals(BearOwnRallyTracker.State.RETURNING, observation.state());
        assertEquals(countdown, observation.releaseCountdown());
    }

    @Test
    void idleTrackedSlotMeansTheOwnMarchReturned() {
        BearOwnRallyTracker.Observation observation =
                BearOwnRallyTracker.observe(4, List.of(idle(4)));

        assertEquals(BearOwnRallyTracker.State.RETURNED, observation.state());
    }

    private static MarchSlotState idle(int slot) {
        return MarchSlotState.of(slot, MarchSlotStatus.IDLE);
    }

    private static MarchSlotState rally(int slot, MarchMovementPhase phase, Duration countdown) {
        boolean returning = phase == MarchMovementPhase.RETURNING;
        return new MarchSlotState(slot, MarchSlotStatus.BUSY_UNKNOWN, MarchSlotAvailability.OCCUPIED,
                MarchActivityType.RALLY, phase, null, countdown,
                returning ? MarchCountdownKind.RETURN : MarchCountdownKind.RALLY_START,
                returning ? MarchSlotReleaseConfidence.EXACT : MarchSlotReleaseConfidence.LOWER_BOUND,
                "test");
    }
}
