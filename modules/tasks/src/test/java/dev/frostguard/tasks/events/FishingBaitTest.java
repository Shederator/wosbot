package dev.frostguard.tasks.events;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FishingBaitTest {
    @Test
    void requiresAnUnambiguousValidFractionBeforeSpendingFreeBait() {
        assertEquals(5, FishingMinigameRoutine.parseBait("5/10"));
        assertEquals(0, FishingMinigameRoutine.parseBait("0/10"));
        for (String invalid : new String[]{null, "", "10", "11/10", "1/0", "-1/10", "1/100", "999999999999/10"}) {
            assertEquals(-1, FishingMinigameRoutine.parseBait(invalid));
        }
    }
}
