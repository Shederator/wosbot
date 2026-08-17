package dev.frostguard.engine.helper;

import dev.frostguard.api.domain.MarchActivityType;
import dev.frostguard.api.domain.MarchCountdownKind;
import dev.frostguard.api.domain.MarchMovementPhase;
import dev.frostguard.api.domain.MarchResourceType;
import dev.frostguard.api.domain.MarchSlotAvailability;
import dev.frostguard.api.domain.MarchSlotReleaseConfidence;
import dev.frostguard.api.domain.MarchSlotState;
import dev.frostguard.api.domain.MarchSlotStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarchSlotAvailabilityEstimatorTest {

    private static final MarchSlotAvailabilityEstimator.Settings SETTINGS =
            new MarchSlotAvailabilityEstimator.Settings(
                    Duration.ofMinutes(1),
                    Duration.ofMinutes(5),
                    Duration.ofMinutes(5),
                    Duration.ofHours(1));

    @Test
    void returningCountdownIsExactNextCheck() {
        MarchSlotState slot = slot(MarchSlotStatus.RETURNING, MarchSlotAvailability.OCCUPIED,
                MarchActivityType.GATHER, MarchMovementPhase.RETURNING, MarchResourceType.WOOD,
                Duration.ofMinutes(3), MarchCountdownKind.RETURN, MarchSlotReleaseConfidence.EXACT);

        assertEquals(Optional.of(Duration.ofMinutes(3)),
                MarchSlotAvailabilityEstimator.estimateNextCheck(slot, SETTINGS));
    }

    @Test
    void gatheringWorkRemainingAddsReturnBuffer() {
        MarchSlotState slot = slot(MarchSlotStatus.GATHERING, MarchSlotAvailability.OCCUPIED,
                MarchActivityType.GATHER, MarchMovementPhase.WORKING, MarchResourceType.WOOD,
                Duration.ofMinutes(20), MarchCountdownKind.WORK_REMAINING,
                MarchSlotReleaseConfidence.LOWER_BOUND);

        assertEquals(Optional.of(Duration.ofMinutes(25)),
                MarchSlotAvailabilityEstimator.estimateNextCheck(slot, SETTINGS));
    }

    @Test
    void gatherOutboundRechecksAfterArrivalInsteadOfTreatingTimerAsFreeSlot() {
        MarchSlotState slot = slot(MarchSlotStatus.GATHERING, MarchSlotAvailability.OCCUPIED,
                MarchActivityType.GATHER, MarchMovementPhase.OUTBOUND, MarchResourceType.COAL,
                Duration.ofMinutes(2), MarchCountdownKind.ARRIVAL,
                MarchSlotReleaseConfidence.LOWER_BOUND);

        assertEquals(Optional.of(Duration.ofMinutes(3)),
                MarchSlotAvailabilityEstimator.estimateNextCheck(slot, SETTINGS));
    }

    @Test
    void lockedSlotHasNoAutomaticCheck() {
        MarchSlotState slot = slot(MarchSlotStatus.LOCKED, MarchSlotAvailability.LOCKED,
                MarchActivityType.NONE, MarchMovementPhase.NONE, null, null,
                MarchCountdownKind.NONE, MarchSlotReleaseConfidence.NEVER);

        assertTrue(MarchSlotAvailabilityEstimator.estimateNextCheck(slot, SETTINGS).isEmpty());
    }

    @Test
    void earliestCheckUsesAllSlotSemantics() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 14, 12, 0);
        MarchSlotState stationed = slot(MarchSlotStatus.STATIONED, MarchSlotAvailability.OCCUPIED,
                MarchActivityType.ENCAMPMENT, MarchMovementPhase.STATIONED, null, null,
                MarchCountdownKind.NONE, MarchSlotReleaseConfidence.MANUAL_OR_UNKNOWN);
        MarchSlotState returning = slot(MarchSlotStatus.RETURNING, MarchSlotAvailability.OCCUPIED,
                MarchActivityType.GATHER, MarchMovementPhase.RETURNING, MarchResourceType.MEAT,
                Duration.ofMinutes(8), MarchCountdownKind.RETURN, MarchSlotReleaseConfidence.EXACT);
        MarchSlotState unknown = slot(MarchSlotStatus.BUSY_UNKNOWN, MarchSlotAvailability.OCCUPIED,
                MarchActivityType.UNKNOWN, MarchMovementPhase.UNKNOWN, MarchResourceType.UNKNOWN, null,
                MarchCountdownKind.UNKNOWN, MarchSlotReleaseConfidence.UNKNOWN);

        assertEquals(Optional.of(now.plusMinutes(5)),
                MarchSlotAvailabilityEstimator.estimateEarliestCheckAt(
                        List.of(stationed, returning, unknown), now, SETTINGS));
    }

    private static MarchSlotState slot(MarchSlotStatus status, MarchSlotAvailability availability,
                                       MarchActivityType activityType, MarchMovementPhase movementPhase,
                                       MarchResourceType resourceType, Duration countdown,
                                       MarchCountdownKind countdownKind,
                                       MarchSlotReleaseConfidence releaseConfidence) {
        return new MarchSlotState(1, status, availability, activityType, movementPhase, resourceType,
                countdown, countdownKind, releaseConfidence, "test");
    }
}
