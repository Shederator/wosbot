package dev.frostguard.engine.nav;

import dev.frostguard.api.domain.PointData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RallyFlagCoordinatesTest {

    @Test
    void mapsMeasuredRightEndSlotCentres() {
        assertEquals(new PointData(336, 120), RallyFlagCoordinates.pointForFlag(9));
        assertEquals(new PointData(409, 120), RallyFlagCoordinates.pointForFlag(10));
        assertEquals(new PointData(482, 120), RallyFlagCoordinates.pointForFlag(11));
        assertEquals(new PointData(556, 120), RallyFlagCoordinates.pointForFlag(12));
    }

    @Test
    void neverFallsBackToFormationOneForUnsupportedValues() {
        assertThrows(IllegalArgumentException.class, () -> RallyFlagCoordinates.pointForFlag(0));
        assertThrows(IllegalArgumentException.class, () -> RallyFlagCoordinates.pointForFlag(13));
    }
}
