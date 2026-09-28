package dev.frostguard.tasks.exploration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TundraTruckCountTest {

    @Test
    void parsesValidZeroAndPositiveCounts() {
        assertEquals(new TundraTruckEventRoutine.TruckCount(0, 4),
                TundraTruckEventRoutine.parseTruckCount("0 / 4"));
        assertEquals(new TundraTruckEventRoutine.TruckCount(2, 4),
                TundraTruckEventRoutine.parseTruckCount("2/4"));
    }

    @Test
    void rejectsUnreadableAndOutOfRangeCounts() {
        assertNull(TundraTruckEventRoutine.parseTruckCount(null));
        assertNull(TundraTruckEventRoutine.parseTruckCount(""));
        assertNull(TundraTruckEventRoutine.parseTruckCount("2 trucks"));
        assertNull(TundraTruckEventRoutine.parseTruckCount("5/4"));
        assertNull(TundraTruckEventRoutine.parseTruckCount("1/0"));
        assertNull(TundraTruckEventRoutine.parseTruckCount("999999999999/4"));
    }
}
