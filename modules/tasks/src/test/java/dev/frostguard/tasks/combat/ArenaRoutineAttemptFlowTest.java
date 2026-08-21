package dev.frostguard.tasks.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaRoutineAttemptFlowTest {

    @Test
    void skipsRefreshAfterDefeatWhenLastAttemptWasConsumed() {
        assertFalse(ArenaRoutine.shouldRefreshAfterDefeat(0));
    }

    @Test
    void keepsRefreshAfterDefeatWhenAnotherAttemptRemains() {
        assertTrue(ArenaRoutine.shouldRefreshAfterDefeat(1));
    }
}
