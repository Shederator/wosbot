package dev.frostguard.engine.helper;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.vision.color.GameColors;

import java.awt.image.BufferedImage;

/** Verifies the yellow outline the game draws around the active formation tile. */
final class FormationSelectionVerifier {

    // Live 720x1280 frames contain roughly 875-1,025 yellow pixels in a selected tile. The largest
    // incidental yellow/orange badge in the same strip measured 312 pixels.
    static final int SELECTED_YELLOW_PIXELS_MIN = 650;

    private FormationSelectionVerifier() {
    }

    static boolean isSelected(BufferedImage frame, AreaData slot) {
        int left = Math.max(0, slot.topLeft().getX());
        int top = Math.max(0, slot.topLeft().getY());
        int right = Math.min(frame.getWidth() - 1, slot.bottomRight().getX());
        int bottom = Math.min(frame.getHeight() - 1, slot.bottomRight().getY());
        int yellow = 0;
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                // The game's selected outline is inset by 7-10 px from this deliberately generous
                // slot window, so scanning only the window's outer edge misses the real outline.
                if (GameColors.isFormationSelectionYellow(frame.getRGB(x, y))) {
                    yellow++;
                }
            }
        }
        return yellow >= SELECTED_YELLOW_PIXELS_MIN;
    }
}
