package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BearOwnRallyArmTest {

    private static final Instant NOW = Instant.parse("2026-10-03T19:05:00Z");
    private static final Instant EVENT_END = Instant.parse("2026-10-03T19:30:00Z");

    @Test
    void armedSendHoldsOnlyUntilTheExpectedReturnNotTheEventEnd() {
        Instant hold = BearOwnRallyArm.expectedReturn(NOW, 300, 45, EVENT_END);

        assertEquals(NOW.plusSeconds(300 + 90), hold);
        assertEquals(EVENT_END, BearOwnRallyArm.expectedReturn(NOW, 300, 45 * 60, EVENT_END),
                "the hold never extends past the event");
    }

    @Test
    void noInputSentClearsTheArmSoTheRallyCanBeRecreated() {
        for (BearVerifiedActionExecutor.Outcome outcome : new BearVerifiedActionExecutor.Outcome[] {
                BearVerifiedActionExecutor.Outcome.STALE_AUTHORIZATION,
                BearVerifiedActionExecutor.Outcome.NOT_AUTHORIZED,
                BearVerifiedActionExecutor.Outcome.INTERRUPTED}) {
            BearOwnRallyArm.Decision decision = BearOwnRallyArm.afterDeploy(
                    outcome, false, "own-deploy-missing-in-authorizing-frame", NOW);

            assertEquals(BearOwnRallyArm.Kind.ABORTED, decision.kind(), outcome.name());
            assertNull(decision.busyUntil(), outcome + " must not keep the own rally busy");
        }
    }

    @Test
    void sentButUnconfirmedIsResolvedByTheNextMarchReadWithinSeconds() {
        BearOwnRallyArm.Decision decision = BearOwnRallyArm.afterDeploy(
                BearVerifiedActionExecutor.Outcome.NOT_CONFIRMED, true, "", NOW);

        assertEquals(BearOwnRallyArm.Kind.SENT_UNCONFIRMED, decision.kind());
        assertTrue(Duration.between(NOW, decision.busyUntil()).compareTo(Duration.ofMinutes(1)) <= 0,
                "an unconfirmed send must be re-observed promptly, not assumed busy all event");
    }

    @Test
    void aRefusalBecauseTheRallyNoLongerFitsIsTooLate() {
        assertTrue(BearOwnRallyArm.afterDeploy(BearVerifiedActionExecutor.Outcome.NOT_AUTHORIZED,
                false, "own-rally-no-longer-fits", NOW).tooLate());
        assertEquals(BearOwnRallyArm.Kind.CONFIRMED, BearOwnRallyArm.afterDeploy(
                BearVerifiedActionExecutor.Outcome.CONFIRMED, true, "", NOW).kind());
    }
}
