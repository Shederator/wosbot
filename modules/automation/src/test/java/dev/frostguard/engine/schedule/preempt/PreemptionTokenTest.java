package dev.frostguard.engine.schedule.preempt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.frostguard.engine.error.StopExecutionException;

class PreemptionTokenTest {

    @Test
    void queueCancellationRemainsArmedUntilExecutionCompletes() {
        PreemptionToken token = new PreemptionToken();

        token.cancel();

        StopExecutionException first = assertThrows(StopExecutionException.class, token::check);
        assertTrue(first.isCancellation());
        assertThrows(StopExecutionException.class, token::check);

        token.clear();
        assertDoesNotThrow(token::check);
    }
}
