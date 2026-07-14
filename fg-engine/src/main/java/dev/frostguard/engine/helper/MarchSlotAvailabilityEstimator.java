package dev.frostguard.engine.helper;

import dev.frostguard.api.domain.MarchActivityType;
import dev.frostguard.api.domain.MarchMovementPhase;
import dev.frostguard.api.domain.MarchSlotAvailability;
import dev.frostguard.api.domain.MarchSlotReleaseConfidence;
import dev.frostguard.api.domain.MarchSlotState;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * Converts March Queue row semantics into conservative wake-up hints.
 *
 * <p>Only Returning is an exact release. Other visible countdowns usually describe the next phase,
 * so callers should treat them as recheck hints unless the estimate says otherwise.
 */
public final class MarchSlotAvailabilityEstimator {

    private MarchSlotAvailabilityEstimator() {
    }

    public record Settings(
            Duration lowerBoundRecheckBuffer,
            Duration gatherReturnBuffer,
            Duration unknownOccupiedRecheck,
            Duration stationedRecheck) {
    }

    public static Optional<Duration> estimateNextCheck(MarchSlotState slot, Settings settings) {
        if (slot == null || settings == null) {
            return Optional.empty();
        }
        if (slot.availability() == MarchSlotAvailability.IDLE) {
            return Optional.of(Duration.ZERO);
        }
        if (slot.availability() == MarchSlotAvailability.LOCKED) {
            return Optional.empty();
        }
        if (slot.hasExactReleaseCountdown()) {
            return Optional.of(slot.countdown());
        }
        if (slot.releaseConfidence() == MarchSlotReleaseConfidence.LOWER_BOUND && slot.countdown() != null) {
            return Optional.of(lowerBoundCheck(slot, settings));
        }
        if (slot.movementPhase() == MarchMovementPhase.STATIONED) {
            return Optional.of(settings.stationedRecheck());
        }
        if (slot.availability() == MarchSlotAvailability.OCCUPIED
                || slot.availability() == MarchSlotAvailability.UNKNOWN) {
            return Optional.of(settings.unknownOccupiedRecheck());
        }
        return Optional.empty();
    }

    public static Optional<LocalDateTime> estimateEarliestCheckAt(Collection<MarchSlotState> slots,
                                                                  LocalDateTime now,
                                                                  Settings settings) {
        if (slots == null || slots.isEmpty() || now == null) {
            return Optional.empty();
        }
        return slots.stream()
                .map(slot -> estimateNextCheck(slot, settings).map(now::plus))
                .flatMap(Optional::stream)
                .min(Comparator.naturalOrder());
    }

    private static Duration lowerBoundCheck(MarchSlotState slot, Settings settings) {
        if (slot.activityType() == MarchActivityType.GATHER
                && slot.movementPhase() == MarchMovementPhase.WORKING) {
            return slot.countdown().plus(settings.gatherReturnBuffer());
        }
        return slot.countdown().plus(settings.lowerBoundRecheckBuffer());
    }
}
