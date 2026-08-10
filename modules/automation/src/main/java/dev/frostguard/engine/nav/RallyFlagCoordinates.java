package dev.frostguard.engine.nav;

import dev.frostguard.api.domain.PointData;

public final class RallyFlagCoordinates {

    // Measured tile centres of the formation screen's flag strip. The tiles sit 74.3px apart, not the
    // 70px this table used to assume, so the old values drifted up to 22px left by slot 8. Taps
    // survived that because a tile is ~74px wide, but anything needing real precision - such as
    // matching the padlock of a locked slot - did not.
    private static final int[] SLOT_CENTRE_X = { 62, 136, 210, 285, 359, 433, 507, 582 };
    private static final int SLOT_CENTRE_Y = 120;

    private RallyFlagCoordinates() {
    }

    public static PointData pointForFlag(int flagNumber) {
        int index = (flagNumber >= 1 && flagNumber <= SLOT_CENTRE_X.length) ? flagNumber - 1 : 0;
        return new PointData(SLOT_CENTRE_X[index], SLOT_CENTRE_Y);
    }
}
