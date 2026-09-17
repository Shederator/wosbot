package dev.frostguard.engine.helper;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.vision.color.GameColors;

import java.awt.image.BufferedImage;

/** Verifies the yellow outline the game draws around the active formation tile. */
final class FormationSelectionVerifier {

    static final int SELECTED_YELLOW_PIXELS_MIN = 40;

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
                boolean border = x - left < 6 || right - x < 6 || y - top < 6 || bottom - y < 6;
                if (border && GameColors.isFormationSelectionYellow(frame.getRGB(x, y))) {
                    yellow++;
                }
            }
        }
        return yellow >= SELECTED_YELLOW_PIXELS_MIN;
    }
}
