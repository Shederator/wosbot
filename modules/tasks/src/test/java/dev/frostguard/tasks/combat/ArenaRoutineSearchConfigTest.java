package dev.frostguard.tasks.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArenaRoutineSearchConfigTest {

    @Test
    void retriesButtonsThatCanAppearAfterRenderingDelay() {
        assertEquals(3, ArenaRoutine.TRANSIENT_BUTTON_SEARCH.getMaxAttempts());
        assertEquals(500, ArenaRoutine.TRANSIENT_BUTTON_SEARCH.getDelayBetweenAttempts());
    }
}
