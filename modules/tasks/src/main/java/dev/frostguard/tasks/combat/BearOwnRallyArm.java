package dev.frostguard.tasks.combat;

import java.time.Duration;
import java.time.Instant;

/**
 * Decides how long the bot's own rally counts as busy around a Send tap. An event-end
 * placeholder is never used: it suppressed every later own rally after one ambiguous send.
 */
final class BearOwnRallyArm {

    /** Long enough for the Special row to appear in a fresh march read after a real send. */
    static final Duration UNCONFIRMED_SEND_HOLD = Duration.ofSeconds(30);

    enum Kind {
        CONFIRMED,
        SENT_UNCONFIRMED,
        ABORTED
    }

    record Decision(Kind kind, Instant busyUntil, boolean tooLate) {
    }

    private BearOwnRallyArm() {
    }

    /** Send + countdown + two one-way trips, capped at the event end. */
    static Instant expectedReturn(Instant sentAt, long rallySeconds, long oneWayTravelSeconds, Instant eventEnd) {
        Instant expected = sentAt.plusSeconds(rallySeconds + 2 * oneWayTravelSeconds);
        return expected.isAfter(eventEnd) ? eventEnd : expected;
    }

    static Decision afterDeploy(
            BearVerifiedActionExecutor.Outcome outcome,
            boolean inputSent,
            String refusal,
            Instant now) {
        if (outcome == BearVerifiedActionExecutor.Outcome.CONFIRMED) {
            return new Decision(Kind.CONFIRMED, null, false);
        }
        if (!inputSent) {
            return new Decision(Kind.ABORTED, null, refusal != null && refusal.contains("fits"));
        }
        return new Decision(Kind.SENT_UNCONFIRMED, now.plus(UNCONFIRMED_SEND_HOLD), false);
    }
}
