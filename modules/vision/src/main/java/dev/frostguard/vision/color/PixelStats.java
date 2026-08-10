package dev.frostguard.vision.color;

import dev.frostguard.api.domain.AreaData;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.function.IntPredicate;

/**
 * Counts pixels of interest inside a screen region.
 *
 * <p>Colour is often the cheapest and most reliable signal the game offers: a greyed-out button, a
 * red cost, a green tick. Routines used to hand-roll the loop, which produced several slightly
 * different notions of the same colour. Build the predicate here instead.
 */
public final class PixelStats {

    private PixelStats() {}

    /** Number of pixels inside {@code area} that satisfy {@code matcher}. The area is clamped to the image. */
    public static int count(BufferedImage image, AreaData area, IntPredicate matcher) {
        int x0 = Math.max(0, area.topLeft().getX());
        int y0 = Math.max(0, area.topLeft().getY());
        int x1 = Math.min(image.getWidth() - 1, area.bottomRight().getX());
        int y1 = Math.min(image.getHeight() - 1, area.bottomRight().getY());

        int matches = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (matcher.test(image.getRGB(x, y))) {
                    matches++;
                }
            }
        }
        return matches;
    }

    /** Matches pixels whose every channel lies within {@code tolerance} of {@code target}. */
    public static IntPredicate near(Color target, int tolerance) {
        return rgb -> Math.abs(red(rgb) - target.getRed()) <= tolerance
                && Math.abs(green(rgb) - target.getGreen()) <= tolerance
                && Math.abs(blue(rgb) - target.getBlue()) <= tolerance;
    }

    private static int red(int rgb)   { return (rgb >> 16) & 0xFF; }
    private static int green(int rgb) { return (rgb >> 8) & 0xFF; }
    private static int blue(int rgb)  { return rgb & 0xFF; }
}
