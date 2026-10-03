package dev.frostguard.engine.helper;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.FormationSlots;
import dev.frostguard.engine.nav.RallyFlagCoordinates;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.color.PixelStats;
import java.awt.image.BufferedImage;
import java.util.Objects;

/** Detects the orange exclamation badge shown on a saved formation that is already marching. */
public final class FormationOccupancyVerifier {

    private static final int BADGE_ORANGE_PIXELS_MIN = 140;

    private FormationOccupancyVerifier() {
    }

    public static boolean isOccupied(BufferedImage frame, int flagNumber) {
        Objects.requireNonNull(frame, "frame");
        if (!FormationSlots.supports(flagNumber)) {
            throw new IllegalArgumentException("Unsupported formation slot: " + flagNumber);
        }
        int centreX = RallyFlagCoordinates.pointForFlag(flagNumber).getX();
        AreaData badge = AreaData.of(centreX + 12, 80, centreX + 40, 109);
        return PixelStats.count(frame, badge, GameColors::isActionOrange)
                >= BADGE_ORANGE_PIXELS_MIN;
    }
}
