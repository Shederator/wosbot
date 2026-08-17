package dev.frostguard.engine.nav;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.FormationSlots;
import dev.frostguard.api.domain.PointData;

public final class RallyFlagCoordinates {

    // Measured tile centres of the formation screen's flag strip. The tiles sit 74.3px apart, not the
    // 70px this table used to assume, so the old values drifted up to 22px left by slot 8. Taps
    // survived that because a tile is ~74px wide, but anything needing real precision - such as
    // matching the padlock of a locked slot - did not.
    private static final int[] INITIAL_SLOT_CENTRE_X = { 62, 136, 210, 285, 359, 433, 507, 582 };
    private static final int[] RIGHT_END_SLOT_CENTRE_X = { 336, 409, 482, 556 };
    private static final int SLOT_CENTRE_Y = 120;
    private static final int SLOT_HALF_WIDTH = 27;
    private static final int SLOT_TOP_Y = 92;
    private static final int SLOT_BOTTOM_Y = 150;

    private RallyFlagCoordinates() {
    }

    public static PointData pointForFlag(int flagNumber) {
        if (!FormationSlots.supports(flagNumber)) {
            throw new IllegalArgumentException("Unsupported formation slot: " + flagNumber);
        }
        int centreX = flagNumber <= INITIAL_SLOT_CENTRE_X.length
                ? INITIAL_SLOT_CENTRE_X[flagNumber - 1]
                : RIGHT_END_SLOT_CENTRE_X[flagNumber - 9];
        return new PointData(centreX, SLOT_CENTRE_Y);
    }

    public static AreaData areaForFlag(int flagNumber) {
        int centreX = pointForFlag(flagNumber).getX();
        return AreaData.of(centreX - SLOT_HALF_WIDTH, SLOT_TOP_Y,
                centreX + SLOT_HALF_WIDTH, SLOT_BOTTOM_Y);
    }
}
