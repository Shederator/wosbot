package dev.frostguard.tasks.pets;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.color.ColorComponents;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.color.PixelStats;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds Life Essence claim markers as orange regions whose vivid-green leaf
 * sits in the upper part of the region.
 *
 * <p>The bubble tail and the tutorial finger extend the orange region downward,
 * so on both saved island frames the leaf centroid is 6 to 14 pixels above the
 * box center. Alliance screens that pass the colour counts put that green mass
 * below the center or more than 20 pixels away. The search covers the whole
 * frame. Fixture coordinates are test assertions, not a search constraint.
 * {@link #assess(BufferedImage)} keeps rejected regions for comparison.
 */
public final class LifeEssenceMarkerDetector {
    static final int MIN_ORANGE_PIXELS = 500;
    static final int MIN_GREEN_PIXELS = 250;
    static final int MIN_WIDTH = 60;
    static final int MAX_WIDTH = 140;
    static final int MIN_HEIGHT = 50;
    static final int MAX_HEIGHT = 140;
    /** Leaf centroid must stay this close to the box center, and above it. */
    static final int GREEN_CENTER_TOLERANCE = 20;
    /** Keeps near-miss orange regions visible in assessments. */
    private static final int ASSESS_MIN_ORANGE_PIXELS = 200;

    private LifeEssenceMarkerDetector() {
    }

    public record Candidate(
            AreaData bounds,
            PointData center,
            PointData greenCenter,
            int orangePixels,
            int greenPixels,
            int width,
            int height,
            boolean accepted,
            String rejection) {
    }

    public static List<PointData> locate(BufferedImage frame) {
        return assess(frame).stream()
                .filter(Candidate::accepted)
                .map(Candidate::greenCenter)
                .toList();
    }

    public static List<Candidate> assess(BufferedImage frame) {
        AreaData fullFrame = new AreaData(new PointData(0, 0),
                new PointData(frame.getWidth() - 1, frame.getHeight() - 1));
        List<Candidate> candidates = new ArrayList<>();
        for (ColorComponents.Component component : ColorComponents.find(
                frame, fullFrame, LifeEssenceMarkerDetector::isMarkerOrange, ASSESS_MIN_ORANGE_PIXELS)) {
            candidates.add(toCandidate(frame, component));
        }
        return List.copyOf(candidates);
    }

    private static Candidate toCandidate(BufferedImage frame, ColorComponents.Component component) {
        int width = component.bounds().bottomRight().getX() - component.bounds().topLeft().getX() + 1;
        int height = component.bounds().bottomRight().getY() - component.bounds().topLeft().getY() + 1;
        int greenPixels = PixelStats.count(frame, component.bounds(), GameColors::isVividGreen);
        PointData boxCenter = component.center();
        PointData greenCenter = greenCenter(frame, component.bounds());
        String rejection = rejectionFor(component.pixelCount(), width, height, greenPixels, boxCenter, greenCenter);
        return new Candidate(
                component.bounds(),
                boxCenter,
                greenCenter,
                component.pixelCount(),
                greenPixels,
                width,
                height,
                rejection == null,
                rejection);
    }

    private static String rejectionFor(int orangePixels, int width, int height, int greenPixels,
            PointData boxCenter, PointData greenCenter) {
        if (orangePixels < MIN_ORANGE_PIXELS) {
            return "orange pixels " + orangePixels + " below " + MIN_ORANGE_PIXELS;
        }
        if (width < MIN_WIDTH || width > MAX_WIDTH || height < MIN_HEIGHT || height > MAX_HEIGHT) {
            return "dimensions " + width + "x" + height
                    + " outside " + MIN_WIDTH + "-" + MAX_WIDTH + " x " + MIN_HEIGHT + "-" + MAX_HEIGHT;
        }
        if (greenPixels < MIN_GREEN_PIXELS || greenCenter == null) {
            return "vivid green " + greenPixels + " below " + MIN_GREEN_PIXELS;
        }
        int offsetX = greenCenter.getX() - boxCenter.getX();
        int offsetY = greenCenter.getY() - boxCenter.getY();
        if (offsetY > 0 || Math.abs(offsetX) > GREEN_CENTER_TOLERANCE || Math.abs(offsetY) > GREEN_CENTER_TOLERANCE) {
            return "green centroid offset " + offsetX + "," + offsetY
                    + " is outside the upper leaf";
        }
        return null;
    }

    private static PointData greenCenter(BufferedImage frame, AreaData bounds) {
        int x0 = Math.max(0, bounds.topLeft().getX());
        int y0 = Math.max(0, bounds.topLeft().getY());
        int x1 = Math.min(frame.getWidth() - 1, bounds.bottomRight().getX());
        int y1 = Math.min(frame.getHeight() - 1, bounds.bottomRight().getY());
        long sumX = 0;
        long sumY = 0;
        int count = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (GameColors.isVividGreen(frame.getRGB(x, y))) {
                    sumX += x;
                    sumY += y;
                    count++;
                }
            }
        }
        if (count == 0) {
            return null;
        }
        return new PointData((int) (sumX / count), (int) (sumY / count));
    }

    private static boolean isMarkerOrange(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return red >= 220 && green >= 60 && green <= 205 && blue <= 90 && red >= green + 60;
    }
}
