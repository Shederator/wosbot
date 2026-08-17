package dev.frostguard.engine.emulator;

import dev.frostguard.api.domain.PointData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmulatorInstanceSwipeTest {

    @Test
    void includesExplicitDurationWhenRequested() {
        assertEquals("input swipe 650 120 250 120 600", EmulatorInstance.swipeCommand(
                new PointData(650, 120), new PointData(250, 120), 600));
    }

    @Test
    void preservesExistingSwipeCommandWithoutDuration() {
        assertEquals("input swipe 650 120 250 120", EmulatorInstance.swipeCommand(
                new PointData(650, 120), new PointData(250, 120), null));
    }
}
