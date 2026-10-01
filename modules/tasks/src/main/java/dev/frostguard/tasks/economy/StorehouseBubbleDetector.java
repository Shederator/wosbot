package dev.frostguard.tasks.economy;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.color.ColorComponents;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.color.PixelStats;

/**
 * Finds Storehouse reward bubbles as white speech frames, then classifies
 * the interior as crate wood or stamina copper.
 *
 * <p>The live task still uses template search. This detector is the colour
 * comparison path. Search is limited to the on-building bubble band so HUD
 * counters and other city bubbles are ignored. {@link #assess(BufferedImage)}
 * keeps rejected white regions for the overlay tool.</p>
 */
public final class StorehouseBubbleDetector {

    static final AreaData SEARCH_AREA = new AreaData(new PointData(180, 480), new PointData(540, 760));
    static final int MIN_WHITE_PIXELS = 400;
    static final int MIN_INNER_PIXELS = 80;
    static final int MIN_WIDTH = 50;
    static final int MAX_WIDTH = 140;
    static final int MIN_HEIGHT = 45;
    static final int MAX_HEIGHT = 140;
    private static final int ASSESS_MIN_WHITE_PIXELS = 200;

    public enum Kind {
        CHEST,
        STAMINA
    }

    private StorehouseBubbleDetector() {
    }

    public record Candidate(
            AreaData bounds,
            PointData center,
            int whitePixels,
            int woodPixels,
            int copperPixels,
            int width,
            int height,
            Kind kind,
            boolean accepted,
            String rejection) {
    }

    public static List<Candidate> locate(BufferedImage frame) {
        return assess(frame).stream().filter(Candidate::accepted).toList();
    }

    public static List<Candidate> assess(BufferedImage frame) {
        List<Candidate> candidates = new ArrayList<>();
        for (ColorComponents.Component component : ColorComponents.find(
                frame, SEARCH_AREA, GameColors::isLabelWhite, ASSESS_MIN_WHITE_PIXELS)) {
            candidates.add(toCandidate(frame, component));
        }
        return List.copyOf(candidates);
    }

    private static Candidate toCandidate(BufferedImage frame, ColorComponents.Component component) {
        int width = component.bounds().bottomRight().getX() - component.bounds().topLeft().getX() + 1;
        int height = component.bounds().bottomRight().getY() - component.bounds().topLeft().getY() + 1;
        int woodPixels = PixelStats.count(frame, component.bounds(), StorehouseBubbleDetector::isCrateWood);
        int copperPixels = PixelStats.count(frame, component.bounds(), StorehouseBubbleDetector::isStaminaCopper);
        Kind kind = null;
        if (woodPixels >= MIN_INNER_PIXELS && woodPixels > copperPixels) {
            kind = Kind.CHEST;
        } else if (copperPixels >= MIN_INNER_PIXELS && copperPixels > woodPixels) {
            kind = Kind.STAMINA;
        }
        String rejection = rejectionFor(component.pixelCount(), width, height, woodPixels, copperPixels, kind);
        return new Candidate(
                component.bounds(),
                component.center(),
                component.pixelCount(),
                woodPixels,
                copperPixels,
                width,
                height,
                kind,
                rejection == null,
                rejection);
    }

    private static String rejectionFor(int whitePixels, int width, int height,
            int woodPixels, int copperPixels, Kind kind) {
        if (whitePixels < MIN_WHITE_PIXELS) {
            return "white pixels " + whitePixels + " below " + MIN_WHITE_PIXELS;
        }
        if (width < MIN_WIDTH || width > MAX_WIDTH || height < MIN_HEIGHT || height > MAX_HEIGHT) {
            return "dimensions " + width + "x" + height
                    + " outside " + MIN_WIDTH + "-" + MAX_WIDTH + " x " + MIN_HEIGHT + "-" + MAX_HEIGHT;
        }
        if (kind == null) {
            return "inner wood " + woodPixels + " copper " + copperPixels
                    + " below " + MIN_INNER_PIXELS + " or tied";
        }
        return null;
    }

    /** Brown crate slats: red leads green, green stays above copper-can range. */
    static boolean isCrateWood(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return red >= 90 && red <= 210
                && green >= 70 && green <= 140
                && blue >= 20 && blue <= 90
                && red >= green + 20
                && green > blue;
    }

    /** Copper stamina can: red leads, green stays below crate wood. */
    static boolean isStaminaCopper(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return red >= 110 && red <= 210
                && green >= 20 && green <= 85
                && blue <= 55
                && red >= green + 40;
    }
}
