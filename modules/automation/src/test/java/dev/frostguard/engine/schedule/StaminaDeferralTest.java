package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class StaminaDeferralTest {

    @Test
    void wakesImmediatelyOnceMinimumIsAvailable() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 20, 2, 15);
        StaminaDeferral deferral = new StaminaDeferral(145, 170, now.minusHours(1));

        assertEquals(now, deferral.revisedWakeAt(150, now));
    }

    @Test
    void keepsPreferredTargetWhenAdditionDoesNotReachMinimum() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 20, 2, 15);
        StaminaDeferral deferral = new StaminaDeferral(145, 170, now.minusHours(1));

        assertEquals(now.plusMinutes(250), deferral.revisedWakeAt(120, now));
    }

    @Test
    void rejectsTargetBelowRunnableMinimum() {
        assertThrows(IllegalArgumentException.class,
                () -> new StaminaDeferral(145, 140, LocalDateTime.now()));
    }

    @Test
    void neverWakesBeforeAnotherBlockingConstraintClears() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 20, 1, 4);
        LocalDateTime marchReturns = now.plusMinutes(3);
        StaminaDeferral deferral = new StaminaDeferral(145, 170, marchReturns);

        assertEquals(marchReturns, deferral.revisedWakeAt(200, now));
    }
}
