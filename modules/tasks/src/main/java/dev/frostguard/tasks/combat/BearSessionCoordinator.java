package dev.frostguard.tasks.combat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Drives one active Bear Hunt window from observed game state.
 *
 * <p>Times are wake-up hints only. An own rally becomes reusable only after its tracked march row is
 * observed idle, and a deployment counts only after the driver confirms the new rally row.
 */
final class BearSessionCoordinator {

    private static final Duration DEFAULT_LAUNCH_WINDOW = Duration.ofMinutes(5).plusSeconds(12);
    private static final Duration NORMAL_POLL = Duration.ofSeconds(1);
    private static final Duration RETURN_GUARD = Duration.ofSeconds(2);
    private static final int EXTRA_JOIN_ATTEMPTS = 6;

    enum State {
        LOCATE_BEAR,
        OWN_RALLY_READY,
        OWN_RALLY_STARTING,
        OWN_RALLY_ACTIVE,
        FILL_JOIN_SLOTS,
        WAIT_FOR_NEXT_USEFUL_DEADLINE,
        RECOVER_TO_KNOWN_SCREEN,
        FINISHED,
        CANCELLED
    }

    enum ExitReason {
        EVENT_ENDED,
        CANCELLED,
        UNRECOVERABLE_FAILURE
    }

    enum EventStatus {
        ACTIVE,
        ENDED,
        UNKNOWN
    }

    enum OwnRallyPhase {
        ABSENT,
        UNCLASSIFIED_ACTIVE,
        PREPARING,
        OUTBOUND,
        RETURNING,
        IDLE,
        UNKNOWN
    }

    enum OwnRallyStartOutcome {
        CONFIRMED,
        ALREADY_ACTIVE,
        TOO_LATE,
        FORMATION_UNAVAILABLE,
        MARCH_QUEUE_FULL,
        OCR_MISS,
        STALE_SCREEN,
        NAVIGATION_FAILURE,
        DEPLOY_NOT_CONFIRMED,
        FATAL
    }

    enum JoinOutcome {
        JOINED,
        RALLY_FULL,
        RALLY_DEPARTED,
        RALLY_GONE,
        ALREADY_JOINED_OR_MARCHING,
        FORMATION_UNAVAILABLE,
        MARCH_QUEUE_FULL,
        OCR_MISS,
        STALE_SCREEN,
        NAVIGATION_FAILURE,
        NO_JOINABLE_RALLY
    }

    record OwnRallyObservation(int slot, OwnRallyPhase phase, Duration releaseCountdown) {
        OwnRallyObservation {
            Objects.requireNonNull(phase, "phase");
        }

        static OwnRallyObservation absent() {
            return new OwnRallyObservation(0, OwnRallyPhase.ABSENT, null);
        }

        static OwnRallyObservation idle(int slot) {
            return new OwnRallyObservation(slot, OwnRallyPhase.IDLE, Duration.ZERO);
        }

        static OwnRallyObservation unclassifiedActive(int slot) {
            return new OwnRallyObservation(slot, OwnRallyPhase.UNCLASSIFIED_ACTIVE, null);
        }

        static OwnRallyObservation active(int slot, OwnRallyPhase phase, Duration releaseCountdown) {
            if (phase == OwnRallyPhase.ABSENT || phase == OwnRallyPhase.IDLE) {
                throw new IllegalArgumentException("active observation requires an active phase");
            }
            return new OwnRallyObservation(slot, phase, releaseCountdown);
        }

        boolean active() {
            return phase == OwnRallyPhase.PREPARING
                    || phase == OwnRallyPhase.OUTBOUND
                    || phase == OwnRallyPhase.RETURNING;
        }
    }

    record MarchSnapshot(
            boolean reliable,
            int freeSlots,
            OwnRallyObservation ownRally,
            Duration earliestExactRelease) {
        MarchSnapshot {
            Objects.requireNonNull(ownRally, "ownRally");
        }

        MarchSnapshot(boolean reliable, int freeSlots, OwnRallyObservation ownRally) {
            this(reliable, freeSlots, ownRally, null);
        }
    }

    record OwnRallyStartResult(
            OwnRallyStartOutcome outcome,
            int slot,
            Duration rallyCountdown,
            Duration oneWayTravel) {

        OwnRallyStartResult {
            Objects.requireNonNull(outcome, "outcome");
            rallyCountdown = rallyCountdown == null ? Duration.ZERO : rallyCountdown;
            oneWayTravel = oneWayTravel == null ? Duration.ZERO : oneWayTravel;
        }

        static OwnRallyStartResult confirmed(int slot, Duration rallyCountdown, Duration oneWayTravel) {
            return new OwnRallyStartResult(
                    OwnRallyStartOutcome.CONFIRMED, slot, rallyCountdown, oneWayTravel);
        }

        static OwnRallyStartResult alreadyActive(int slot) {
            return new OwnRallyStartResult(
                    OwnRallyStartOutcome.ALREADY_ACTIVE, slot, Duration.ZERO, Duration.ZERO);
        }

        static OwnRallyStartResult recoverable(OwnRallyStartOutcome outcome) {
            if (outcome == OwnRallyStartOutcome.CONFIRMED
                    || outcome == OwnRallyStartOutcome.ALREADY_ACTIVE
                    || outcome == OwnRallyStartOutcome.FATAL) {
                throw new IllegalArgumentException("outcome is not a recoverable failed start");
            }
            return new OwnRallyStartResult(outcome, 0, Duration.ZERO, Duration.ZERO);
        }
    }

    interface Driver {
        Instant now();

        boolean cancellationRequested();

        EventStatus eventStatus();

        MarchSnapshot readMarches(OptionalInt trackedOwnSlot, boolean mayAdoptExisting);

        OwnRallyStartResult startOwnRally(int formation);

        JoinOutcome joinNext(int formation);

        boolean recover(State resumeState);

        void pause(Duration duration);

        void stateChanged(State state);
    }

    private final Driver driver;
    private final Instant eventEnd;
    private final boolean callOwnRallies;
    private final int ownFormation;
    private final boolean joinRallies;
    private final List<Integer> joinFormations;

    private OptionalInt trackedOwnSlot = OptionalInt.empty();
    private boolean mayAdoptExisting = true;
    private int formationIndex;
    private Duration launchWindow = DEFAULT_LAUNCH_WINDOW;
    private boolean ownLaunchClosed;
    private State state = State.LOCATE_BEAR;

    BearSessionCoordinator(
            Driver driver,
            Instant eventEnd,
            boolean callOwnRallies,
            int ownFormation,
            boolean joinRallies,
            List<Integer> joinFormations) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.eventEnd = Objects.requireNonNull(eventEnd, "eventEnd");
        this.callOwnRallies = callOwnRallies;
        this.ownFormation = ownFormation;
        this.joinRallies = joinRallies;
        this.joinFormations = List.copyOf(joinFormations);
        if (joinRallies && this.joinFormations.isEmpty()) {
            throw new IllegalArgumentException("join formations cannot be empty when joining is enabled");
        }
    }

    static boolean shouldResumeNormalTasks(ExitReason exitReason) {
        return exitReason != ExitReason.CANCELLED;
    }

    static OptionalInt selectJoinCandidateRow(
            List<Integer> candidateRows, List<Integer> bearRows, int rowTolerance) {
        return candidateRows.stream()
                .sorted()
                .filter(candidate -> bearRows.stream()
                        .anyMatch(bear -> Math.abs(bear - candidate) <= rowTolerance))
                .mapToInt(Integer::intValue)
                .findFirst();
    }

    ExitReason run() {
        driver.stateChanged(state);
        while (driver.now().isBefore(eventEnd)) {
            if (driver.cancellationRequested()) {
                transition(State.CANCELLED);
                return ExitReason.CANCELLED;
            }

            EventStatus eventStatus = driver.eventStatus();
            if (eventStatus == EventStatus.ENDED) {
                transition(State.FINISHED);
                return ExitReason.EVENT_ENDED;
            }
            if (eventStatus == EventStatus.UNKNOWN) {
                recover(State.LOCATE_BEAR);
                continue;
            }

            MarchSnapshot snapshot = driver.readMarches(trackedOwnSlot, mayAdoptExisting);
            if (!snapshot.reliable()) {
                recover(state);
                continue;
            }
            updateOwnRallyTracking(snapshot.ownRally());
            int freeSlotsForJoining = snapshot.freeSlots();

            if (callOwnRallies
                    && trackedOwnSlot.isEmpty()
                    && snapshot.ownRally().phase() != OwnRallyPhase.UNCLASSIFIED_ACTIVE
                    && hasTimeForOwnRally()) {
                transition(State.OWN_RALLY_READY);
                transition(State.OWN_RALLY_STARTING);
                OwnRallyStartResult start = driver.startOwnRally(ownFormation);
                if (start.outcome() == OwnRallyStartOutcome.FATAL) {
                    return ExitReason.UNRECOVERABLE_FAILURE;
                }
                if (start.outcome() == OwnRallyStartOutcome.CONFIRMED) {
                    trackedOwnSlot = OptionalInt.of(start.slot());
                    launchWindow = start.rallyCountdown().plus(start.oneWayTravel());
                    freeSlotsForJoining = Math.max(0, freeSlotsForJoining - 1);
                    transition(State.OWN_RALLY_ACTIVE);
                } else if (start.outcome() == OwnRallyStartOutcome.ALREADY_ACTIVE) {
                    trackedOwnSlot = OptionalInt.of(start.slot());
                    transition(State.OWN_RALLY_ACTIVE);
                } else if (start.outcome() == OwnRallyStartOutcome.TOO_LATE) {
                    ownLaunchClosed = true;
                } else {
                    recover(State.OWN_RALLY_READY);
                    MarchSnapshot refreshed = driver.readMarches(trackedOwnSlot, true);
                    if (!refreshed.reliable()) {
                        continue;
                    }
                    if (refreshed.ownRally().phase() == OwnRallyPhase.UNCLASSIFIED_ACTIVE) {
                        mayAdoptExisting = true;
                    }
                    updateOwnRallyTracking(refreshed.ownRally());
                    freeSlotsForJoining = refreshed.freeSlots();
                    snapshot = refreshed;
                }
            }

            if (joinRallies && freeSlotsForJoining > 0) {
                transition(State.FILL_JOIN_SLOTS);
                fillJoinSlots(freeSlotsForJoining);
            }

            transition(State.WAIT_FOR_NEXT_USEFUL_DEADLINE);
            driver.pause(nextPause(snapshot));
        }

        transition(State.FINISHED);
        return ExitReason.EVENT_ENDED;
    }

    private void updateOwnRallyTracking(OwnRallyObservation observation) {
        if (trackedOwnSlot.isPresent()) {
            if (observation.phase() == OwnRallyPhase.IDLE
                    && observation.slot() == trackedOwnSlot.getAsInt()) {
                trackedOwnSlot = OptionalInt.empty();
                transition(State.OWN_RALLY_READY);
            } else if (observation.active()) {
                trackedOwnSlot = OptionalInt.of(observation.slot());
                transition(State.OWN_RALLY_ACTIVE);
            } else if (observation.phase() == OwnRallyPhase.UNKNOWN) {
                recover(State.OWN_RALLY_ACTIVE);
            }
            return;
        }

        if (observation.phase() != OwnRallyPhase.UNKNOWN
                && observation.phase() != OwnRallyPhase.UNCLASSIFIED_ACTIVE) {
            mayAdoptExisting = false;
        }
    }

    private void fillJoinSlots(int initiallyFreeSlots) {
        int remaining = initiallyFreeSlots;
        int attemptsRemaining = initiallyFreeSlots * Math.max(1, joinFormations.size()) + EXTRA_JOIN_ATTEMPTS;

        while (remaining > 0 && attemptsRemaining-- > 0 && driver.now().isBefore(eventEnd)) {
            if (driver.cancellationRequested()) {
                return;
            }
            int formation = joinFormations.get(formationIndex);
            JoinOutcome outcome = driver.joinNext(formation);
            switch (outcome) {
                case JOINED -> {
                    remaining--;
                    advanceFormation();
                }
                case FORMATION_UNAVAILABLE -> advanceFormation();
                case OCR_MISS, STALE_SCREEN, NAVIGATION_FAILURE -> recover(State.FILL_JOIN_SLOTS);
                case NO_JOINABLE_RALLY, MARCH_QUEUE_FULL -> {
                    return;
                }
                case RALLY_FULL, RALLY_DEPARTED, RALLY_GONE, ALREADY_JOINED_OR_MARCHING -> {
                    // Keep the same verified formation and move to the next rally candidate.
                }
            }
        }
    }

    private void advanceFormation() {
        formationIndex = (formationIndex + 1) % joinFormations.size();
    }

    private boolean hasTimeForOwnRally() {
        return !ownLaunchClosed && !driver.now().plus(launchWindow).isAfter(eventEnd);
    }

    private Duration nextPause(MarchSnapshot snapshot) {
        Duration untilEnd = Duration.between(driver.now(), eventEnd);
        Duration requested = NORMAL_POLL;
        if (trackedOwnSlot.isPresent()
                && snapshot.ownRally().phase() == OwnRallyPhase.RETURNING
                && (!joinRallies || snapshot.freeSlots() == 0)
                && snapshot.ownRally().releaseCountdown() != null
                && snapshot.ownRally().releaseCountdown().compareTo(RETURN_GUARD) > 0) {
            requested = snapshot.ownRally().releaseCountdown().minus(RETURN_GUARD);
        } else if (snapshot.freeSlots() == 0
                && snapshot.earliestExactRelease() != null
                && snapshot.earliestExactRelease().compareTo(RETURN_GUARD) > 0) {
            requested = snapshot.earliestExactRelease().minus(RETURN_GUARD);
        } else if ((!joinRallies || snapshot.freeSlots() == 0)
                && snapshot.ownRally().phase() == OwnRallyPhase.PREPARING
                && snapshot.ownRally().releaseCountdown() != null
                && snapshot.ownRally().releaseCountdown().compareTo(RETURN_GUARD) > 0) {
            requested = snapshot.ownRally().releaseCountdown().minus(RETURN_GUARD);
        } else if (trackedOwnSlot.isEmpty() && !hasTimeForOwnRally() && !joinRallies) {
            requested = untilEnd;
        }
        return requested.compareTo(untilEnd) > 0 ? untilEnd : requested;
    }

    private void recover(State resumeState) {
        transition(State.RECOVER_TO_KNOWN_SCREEN);
        driver.recover(resumeState);
        driver.pause(NORMAL_POLL);
        transition(resumeState);
    }

    private void transition(State next) {
        if (state != next) {
            state = next;
            driver.stateChanged(next);
        }
    }
}
