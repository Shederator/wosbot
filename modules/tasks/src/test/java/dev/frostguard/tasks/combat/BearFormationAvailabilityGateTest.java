package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BearFormationAvailabilityGateTest {

    @Test
    void blocksRepeatedRowInputsUntilBoundedRecheckWhenNoCountdownIsKnown() {
        Instant now = Instant.parse("2026-10-01T18:00:00Z");
        BearFormationAvailabilityGate gate = new BearFormationAvailabilityGate();

        gate.block(now, now.plusSeconds(30), null, List.of());

        assertTrue(gate.blocked(now.plusMillis(250)));
        assertTrue(gate.blocked(now.plusSeconds(4)));
        gate.clearIfDue(now.plusSeconds(5));
        assertFalse(gate.blocked(now.plusSeconds(5)));
    }

    @Test
    void ownRallyDeadlinePreemptsFormationWait() {
        Instant now = Instant.parse("2026-10-01T18:00:00Z");
        BearFormationAvailabilityGate gate = new BearFormationAvailabilityGate();

        gate.block(now, now.plusSeconds(30), now.plusSeconds(2), List.of());

        assertTrue(gate.blocked(now.plusSeconds(1)));
        gate.clearIfDue(now.plusSeconds(2));
        assertFalse(gate.blocked(now.plusSeconds(2)));
    }
}
