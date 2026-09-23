package dev.frostguard.tasks.pets;

import java.util.List;

import dev.frostguard.api.domain.PointData;

/**
 * Decides the next Life Essence claim step from a fresh capture.
 *
 * <p>The island shows at most three markers. A valid capture with none is a
 * finished collection, including an island that was already empty. A failed
 * capture, an unconfirmed screen, or a marker that is still present when the
 * six-capture bound is exhausted is a retry, not a completed collection.
 */
final class LifeEssenceClaimPolicy {

    static final int MAX_CONFIRMED_CLAIMS = 3;
    static final int MAX_CAPTURES = 6;
    static final int EMPTY_CAPTURES_TO_FINISH = 2;
    static final int MARKER_PROXIMITY = 20;

    private LifeEssenceClaimPolicy() {
    }

    enum Kind {
        MARKERS, FAILED, SCREEN_UNCONFIRMED
    }

    record Observation(Kind kind, List<PointData> markers) {
        static Observation failed() {
            return new Observation(Kind.FAILED, List.of());
        }

        static Observation screenUnconfirmed() {
            return new Observation(Kind.SCREEN_UNCONFIRMED, List.of());
        }

        static Observation markers(List<PointData> markers) {
            return new Observation(Kind.MARKERS, List.copyOf(markers));
        }
    }

    enum Outcome {
        TAP, WAIT, FINISH, RETRY
    }

    record State(int captures, int confirmedClaims, int consecutiveEmpty, PointData pendingTap) {
        static State initial() {
            return new State(0, 0, 0, null);
        }
    }

    record Step(Outcome outcome, PointData tap, int confirmedClaims, State next, String reason) {
    }

    static Step advance(State state, Observation observation) {
        int captures = state.captures() + 1;
        if (observation.kind() != Kind.MARKERS) {
            return retry(captures, state.confirmedClaims(), state.consecutiveEmpty(), observation.kind().name());
        }

        int confirmed = state.confirmedClaims();
        if (state.pendingTap() != null && !hasMarkerNear(observation.markers(), state.pendingTap())) {
            confirmed++;
        }
        if (confirmed >= MAX_CONFIRMED_CLAIMS) {
            return finish(captures, confirmed, "three claims confirmed");
        }
        if (observation.markers().isEmpty()) {
            int emptyCaptures = state.consecutiveEmpty() + 1;
            if (emptyCaptures >= EMPTY_CAPTURES_TO_FINISH) {
                return finish(captures, confirmed, "no markers on two consecutive captures");
            }
            if (captures >= MAX_CAPTURES) {
                return finish(captures, confirmed, "no markers on the last capture");
            }
            return new Step(Outcome.WAIT, null, confirmed,
                    new State(captures, confirmed, emptyCaptures, null),
                    "no markers yet");
        }
        if (captures >= MAX_CAPTURES) {
            return retry(captures, confirmed, 0, "capture bound exhausted with markers still visible");
        }
        PointData tap = observation.markers().getFirst();
        return new Step(Outcome.TAP, tap, confirmed,
                new State(captures, confirmed, 0, tap),
                "marker still present");
    }

    static boolean hasMarkerNear(List<PointData> markers, PointData tap) {
        return markers.stream().anyMatch(marker -> Math.abs(marker.getX() - tap.getX()) <= MARKER_PROXIMITY
                && Math.abs(marker.getY() - tap.getY()) <= MARKER_PROXIMITY);
    }

    private static Step finish(int captures, int confirmed, String reason) {
        return new Step(Outcome.FINISH, null, confirmed,
                new State(captures, confirmed, 0, null), reason);
    }

    private static Step retry(int captures, int confirmed, int consecutiveEmpty, String reason) {
        return new Step(Outcome.RETRY, null, confirmed,
                new State(captures, confirmed, consecutiveEmpty, null), reason);
    }
}
