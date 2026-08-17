package dev.frostguard.engine.helper;

import dev.frostguard.api.domain.AreaData;

import java.awt.image.BufferedImage;

/** Verifies that a formation-bar swipe produced the measured large visual movement. */
final class FormationBarFrameComparator {

    private static final int PIXEL_DISTANCE_MIN = 60;
    private static final double MOVED_RATIO_MIN = 0.20;

    private FormationBarFrameComparator() {
    }

    static boolean moved(BufferedImage before, BufferedImage after, AreaData area) {
        return covers(before, area) && covers(after, area)
                && changedRatio(before, after, area) >= MOVED_RATIO_MIN;
    }

    static double changedRatio(BufferedImage before, BufferedImage after, AreaData area) {
        int x0 = Math.max(0, area.topLeft().getX());
        int y0 = Math.max(0, area.topLeft().getY());
        int x1 = Math.min(Math.min(before.getWidth(), after.getWidth()) - 1, area.bottomRight().getX());
        int y1 = Math.min(Math.min(before.getHeight(), after.getHeight()) - 1, area.bottomRight().getY());

        int changed = 0;
        int total = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int first = before.getRGB(x, y);
                int second = after.getRGB(x, y);
                int distance = channelDistance(first, second);
                if (distance >= PIXEL_DISTANCE_MIN) {
                    changed++;
                }
                total++;
            }
        }
        return total == 0 ? 0 : (double) changed / total;
    }

    private static int channelDistance(int first, int second) {
        return Math.abs(red(first) - red(second))
                + Math.abs(green(first) - green(second))
                + Math.abs(blue(first) - blue(second));
    }

    private static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    private static int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    private static int blue(int rgb) {
        return rgb & 0xFF;
    }

    private static boolean covers(BufferedImage image, AreaData area) {
        return area.topLeft().getX() >= 0
                && area.topLeft().getY() >= 0
                && area.bottomRight().getX() < image.getWidth()
                && area.bottomRight().getY() < image.getHeight();
    }
}
