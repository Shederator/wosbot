package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

class JourneyofLightQueueScheduleTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 12, 0);

    @Test
    void allUnreadableQueuesUseBoundedRetry() {
        JourneyofLightRoutine.QueueSchedule result =
                JourneyofLightRoutine.resolveQueueSchedule(NOW, java.util.Arrays.asList(null, null, null, null));

        assertEquals(NOW.plusMinutes(5), result.nextCheck());
        assertTrue(result.incomplete());
    }

    @Test
    void partialFailureCapsRetryAtEarliestValidQueue() {
        JourneyofLightRoutine.QueueSchedule result = JourneyofLightRoutine.resolveQueueSchedule(
                NOW, java.util.Arrays.asList(NOW.plusMinutes(2), null, NOW.plusMinutes(20)));

        assertEquals(NOW.plusMinutes(2), result.nextCheck());
        assertTrue(result.incomplete());
    }

    @Test
    void completeQueueReadUsesEarliestCompletion() {
        JourneyofLightRoutine.QueueSchedule result = JourneyofLightRoutine.resolveQueueSchedule(
                NOW, List.of(NOW.plusMinutes(20), NOW.plusMinutes(3), NOW.plusMinutes(9)));

        assertEquals(NOW.plusMinutes(3), result.nextCheck());
        assertFalse(result.incomplete());
    }
}
