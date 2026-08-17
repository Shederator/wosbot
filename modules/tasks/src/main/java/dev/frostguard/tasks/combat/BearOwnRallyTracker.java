package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.MarchActivityType;
import dev.frostguard.api.domain.MarchSlotState;

import java.time.Duration;
import java.util.List;

/** Pure decisions for associating an own Bear rally with its March Queue slot. */
final class BearOwnRallyTracker {

    private BearOwnRallyTracker() {
    }

    static Integer identifyNewRallySlot(List<MarchSlotState> before, List<MarchSlotState> after) {
        for (MarchSlotState current : after) {
            if (current.activityType() != MarchActivityType.RALLY) {
                continue;
            }
            MarchSlotState previous = find(before, current.slot());
            if (previous == null || previous.isIdle() || previous.activityType() != MarchActivityType.RALLY) {
                return current.slot();
            }
        }
        return null;
    }

    static Observation observe(Integer trackedSlot, List<MarchSlotState> slots) {
        if (trackedSlot == null || slots.isEmpty()) {
            return new Observation(State.UNKNOWN, null);
        }
        MarchSlotState tracked = find(slots, trackedSlot);
        if (tracked == null) {
            return new Observation(State.UNKNOWN, null);
        }
        if (tracked.isIdle()) {
            return new Observation(State.RETURNED, Duration.ZERO);
        }
        if (tracked.hasExactReleaseCountdown()) {
            return new Observation(State.RETURNING, tracked.countdown());
        }
        return new Observation(State.ACTIVE, null);
    }

    private static MarchSlotState find(List<MarchSlotState> slots, int slotNumber) {
        return slots.stream().filter(slot -> slot.slot() == slotNumber).findFirst().orElse(null);
    }

    enum State {
        ACTIVE,
        RETURNING,
        RETURNED,
        UNKNOWN
    }

    record Observation(State state, Duration releaseCountdown) {
    }
}
