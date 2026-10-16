package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.PointData;

import java.awt.Color;
import java.awt.image.BufferedImage;

/** Distinguishes an enabled coloured Bear rally plus from the same control in disabled grey. */
final class BearJoinButtonClassifier {

    private static final int SAMPLE_RADIUS_X = 24;
    private static final int SAMPLE_RADIUS_Y = 16;
    private static final float MIN_SATURATION = 0.28f;
    private static final float MIN_BRIGHTNESS = 0.28f;
    private static final int MIN_COLOURED_PIXELS = 18;

    private BearJoinButtonClassifier() {
    }

    static Evidence inspect(BufferedImage image, PointData center) {
        int left = Math.max(0, center.getX() - SAMPLE_RADIUS_X);
        int right = Math.min(image.getWidth() - 1, center.getX() + SAMPLE_RADIUS_X);
        int top = Math.max(0, center.getY() - SAMPLE_RADIUS_Y);
        int bottom = Math.min(image.getHeight() - 1, center.getY() + SAMPLE_RADIUS_Y);
        int colouredPixels = 0;

        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                Color color = new Color(image.getRGB(x, y), true);
                float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
                if (hsb[1] >= MIN_SATURATION && hsb[2] >= MIN_BRIGHTNESS) {
                    colouredPixels++;
                }
            }
        }

        return new Evidence(colouredPixels >= MIN_COLOURED_PIXELS, colouredPixels);
    }

    record Evidence(boolean enabled, int colouredPixels) {
    }
}
