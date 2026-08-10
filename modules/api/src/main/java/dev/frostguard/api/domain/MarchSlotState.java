package dev.frostguard.api.domain;

import java.time.Duration;

/**
 * One row of the wilderness March Queue panel.
 *
 * <p>{@link #status()} is kept as the compact legacy view for existing consumers. New code should
 * prefer the separate dimensions: {@link #availability()}, {@link #activityType()},
 * {@link #movementPhase()}, {@link #resourceType()}, {@link #countdownKind()}, and
 * {@link #releaseConfidence()}.
 */
public record MarchSlotState(
        int slot,
        MarchSlotStatus status,
        MarchSlotAvailability availability,
        MarchActivityType activityType,
        MarchMovementPhase movementPhase,
        MarchResourceType resourceType,
        Duration countdown,
        MarchCountdownKind countdownKind,
        MarchSlotReleaseConfidence releaseConfidence,
        String evidence) {

    public MarchSlotState(int slot, MarchSlotStatus status, MarchSlotAvailability availability,
                          MarchActivityType activityType, MarchMovementPhase movementPhase,
                          MarchResourceType resourceType, Duration countdown,
                          MarchCountdownKind countdownKind, String evidence) {
        this(slot, status, availability, activityType, movementPhase, resourceType, countdown, countdownKind,
                releaseConfidenceFrom(availability, movementPhase, countdownKind), evidence);
    }

    public static MarchSlotState of(int slot, MarchSlotStatus status) {
        return new MarchSlotState(slot, status, null);
    }

    public MarchSlotState(int slot, MarchSlotStatus status, Duration countdown) {
        this(slot, status, availabilityFrom(status), activityTypeFrom(status), movementPhaseFrom(status),
                resourceTypeFrom(status), countdown, countdownKindFrom(status, countdown), null);
    }

    public boolean isIdle() {
        return availability == MarchSlotAvailability.IDLE;
    }

    public boolean isGather() {
        return activityType == MarchActivityType.GATHER;
    }

    /** True when the countdown marks the moment this slot frees up. */
    public boolean hasExactReleaseCountdown() {
        return countdown != null && countdownKind == MarchCountdownKind.RETURN
                && releaseConfidence == MarchSlotReleaseConfidence.EXACT;
    }

    /** True when the countdown marks the moment this slot frees up. */
    public boolean hasReturnCountdown() {
        return hasExactReleaseCountdown();
    }

    public MarchSlotState withEvidence(String evidence) {
        return new MarchSlotState(slot, status, availability, activityType, movementPhase, resourceType,
                countdown, countdownKind, releaseConfidence, evidence);
    }

    private static MarchSlotAvailability availabilityFrom(MarchSlotStatus status) {
        return switch (status) {
            case IDLE -> MarchSlotAvailability.IDLE;
            case LOCKED -> MarchSlotAvailability.LOCKED;
            case STATIONED, GATHERING, RETURNING, BUSY_UNKNOWN -> MarchSlotAvailability.OCCUPIED;
        };
    }

    private static MarchActivityType activityTypeFrom(MarchSlotStatus status) {
        return switch (status) {
            case IDLE, LOCKED -> MarchActivityType.NONE;
            case GATHERING, RETURNING -> MarchActivityType.GATHER;
            case STATIONED -> MarchActivityType.ENCAMPMENT;
            case BUSY_UNKNOWN -> MarchActivityType.UNKNOWN;
        };
    }

    private static MarchMovementPhase movementPhaseFrom(MarchSlotStatus status) {
        return switch (status) {
            case IDLE, LOCKED -> MarchMovementPhase.NONE;
            case STATIONED -> MarchMovementPhase.STATIONED;
            case GATHERING -> MarchMovementPhase.WORKING;
            case RETURNING -> MarchMovementPhase.RETURNING;
            case BUSY_UNKNOWN -> MarchMovementPhase.UNKNOWN;
        };
    }

    private static MarchResourceType resourceTypeFrom(MarchSlotStatus status) {
        return status == MarchSlotStatus.GATHERING || status == MarchSlotStatus.RETURNING
                ? MarchResourceType.UNKNOWN
                : null;
    }

    private static MarchCountdownKind countdownKindFrom(MarchSlotStatus status, Duration countdown) {
        if (countdown == null) {
            return MarchCountdownKind.NONE;
        }
        return switch (status) {
            case RETURNING -> MarchCountdownKind.RETURN;
            case GATHERING -> MarchCountdownKind.WORK_REMAINING;
            case BUSY_UNKNOWN -> MarchCountdownKind.UNKNOWN;
            case IDLE, LOCKED, STATIONED -> MarchCountdownKind.NONE;
        };
    }

    private static MarchSlotReleaseConfidence releaseConfidenceFrom(MarchSlotAvailability availability,
                                                                    MarchMovementPhase movementPhase,
                                                                    MarchCountdownKind countdownKind) {
        if (availability == MarchSlotAvailability.IDLE) {
            return MarchSlotReleaseConfidence.NOW;
        }
        if (availability == MarchSlotAvailability.LOCKED) {
            return MarchSlotReleaseConfidence.NEVER;
        }
        if (countdownKind == MarchCountdownKind.RETURN && movementPhase == MarchMovementPhase.RETURNING) {
            return MarchSlotReleaseConfidence.EXACT;
        }
        if (countdownKind == MarchCountdownKind.ARRIVAL
                || countdownKind == MarchCountdownKind.WORK_REMAINING
                || countdownKind == MarchCountdownKind.RALLY_START) {
            return MarchSlotReleaseConfidence.LOWER_BOUND;
        }
        if (movementPhase == MarchMovementPhase.STATIONED) {
            return MarchSlotReleaseConfidence.MANUAL_OR_UNKNOWN;
        }
        return MarchSlotReleaseConfidence.UNKNOWN;
    }
}
