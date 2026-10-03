package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.engine.schedule.BearTrapSessionLease;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BearEventWindowTest {

    private static final Instant LEASE_END = Instant.parse("2026-10-01T19:30:00Z");

    @Test
    void activeLeaseDecidesTheWindowEvenWhenTheScheduleWasChanged() {
        BearTrapSessionLease.Lease lease = new BearTrapSessionLease.Lease(
                7L, 1, Instant.parse("2026-10-01T18:55:00Z"), LEASE_END);
        BearEventWindow edited = new BearEventWindow(
                Instant.parse("2035-01-01T00:00:00Z"), Instant.parse("2035-01-01T00:30:00Z"), false);

        BearEventWindow window = BearEventWindow.resolve(Optional.of(lease), () -> Optional.of(edited))
                .orElseThrow();

        assertTrue(window.leaseOwned());
        assertEquals(LEASE_END, window.end());
        assertEquals(LEASE_END.minus(BearEventWindow.EVENT_DURATION), window.activation());
    }

    @Test
    void withoutALeaseTheConfiguredWindowIsUsed() {
        BearEventWindow configured = new BearEventWindow(
                Instant.parse("2026-10-03T19:00:00Z"), Instant.parse("2026-10-03T19:30:00Z"), false);

        BearEventWindow window = BearEventWindow.resolve(Optional.empty(), () -> Optional.of(configured))
                .orElseThrow();

        assertFalse(window.leaseOwned());
        assertEquals(configured, window);
    }

    @Test
    void withoutALeaseOrAnInsideConfiguredWindowThereIsNoSession() {
        assertTrue(BearEventWindow.resolve(Optional.empty(), Optional::empty).isEmpty());
    }
}
