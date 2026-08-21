package dev.frostguard.engine.nav;

import java.awt.image.BufferedImage;

import dev.frostguard.api.domain.AreaData;

/** Detects whether the stable left icon column moved after a sidebar swipe. */
public final class SidebarViewportChangeDetector {

    private static final int SAMPLE_STEP = 2;
    private static final double MATERIAL_CHANGE_THRESHOLD = 8.0;

    private SidebarViewportChangeDetector() {}

    public static boolean materiallyChanged(BufferedImage before, BufferedImage after) {
        if (before == null || after == null
                || before.getWidth() != after.getWidth()
                || before.getHeight() != after.getHeight()) {
            throw new IllegalArgumentException("Comparable sidebar frames are required");
        }

        AreaData area = CommonGameAreas.SIDEBAR_ROW_ICON_COLUMN;
        long totalDifference = 0;
        int samples = 0;
        for (int y = area.topLeft().getY(); y < area.bottomRight().getY(); y += SAMPLE_STEP) {
            for (int x = area.topLeft().getX(); x < area.bottomRight().getX(); x += SAMPLE_STEP) {
                totalDifference += Math.abs(luma(before.getRGB(x, y)) - luma(after.getRGB(x, y)));
                samples++;
            }
        }
        return samples > 0 && (double) totalDifference / samples >= MATERIAL_CHANGE_THRESHOLD;
    }

    private static int luma(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return (red * 299 + green * 587 + blue * 114) / 1_000;
    }
}
