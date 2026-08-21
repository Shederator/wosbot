package dev.frostguard.engine.nav;

import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import dev.frostguard.api.domain.AreaData;

/** Classifies the selected sidebar tab from its measured fill brightness. */
public final class SidebarFrameClassifier {

    private static final double MIN_SELECTED_BRIGHTNESS = 140.0;
    private static final double MIN_SELECTED_MARGIN = 45.0;
    private static final int PANEL_ANCHOR_MIN_CHANNEL = 220;
    private static final double MIN_PANEL_ANCHOR_RATIO = 0.15;

    private SidebarFrameClassifier() {}

    public static Optional<SidebarSection> selectedSection(BufferedImage frame) {
        if (frame == null || frame.getWidth() < 440 || frame.getHeight() < 300) {
            return Optional.empty();
        }
        if (!hasPanelAnchor(frame)) {
            return Optional.empty();
        }

        Map<SidebarSection, Double> scores = new EnumMap<>(SidebarSection.class);
        for (SidebarSection section : SidebarSection.values()) {
            scores.put(section, meanBrightness(frame, CommonGameAreas.sidebarTabSample(section)));
        }

        SidebarSection selected = null;
        double best = Double.NEGATIVE_INFINITY;
        double second = Double.NEGATIVE_INFINITY;
        for (Map.Entry<SidebarSection, Double> entry : scores.entrySet()) {
            double score = entry.getValue();
            if (score > best) {
                second = best;
                best = score;
                selected = entry.getKey();
            } else if (score > second) {
                second = score;
            }
        }

        if (selected == null || best < MIN_SELECTED_BRIGHTNESS || best - second < MIN_SELECTED_MARGIN) {
            return Optional.empty();
        }
        return Optional.of(selected);
    }

    private static boolean hasPanelAnchor(BufferedImage frame) {
        AreaData area = CommonGameAreas.LEFT_MENU_CLOSE;
        int nearWhite = 0;
        int samples = 0;
        for (int y = area.topLeft().getY(); y <= area.bottomRight().getY(); y++) {
            for (int x = area.topLeft().getX(); x <= area.bottomRight().getX(); x++) {
                int rgb = frame.getRGB(x, y);
                int red = (rgb >> 16) & 0xff;
                int green = (rgb >> 8) & 0xff;
                int blue = rgb & 0xff;
                if (red >= PANEL_ANCHOR_MIN_CHANNEL
                        && green >= PANEL_ANCHOR_MIN_CHANNEL
                        && blue >= PANEL_ANCHOR_MIN_CHANNEL) {
                    nearWhite++;
                }
                samples++;
            }
        }
        return samples > 0 && (double) nearWhite / samples >= MIN_PANEL_ANCHOR_RATIO;
    }

    private static double meanBrightness(BufferedImage frame, AreaData area) {
        long total = 0;
        int samples = 0;
        for (int y = area.topLeft().getY(); y <= area.bottomRight().getY(); y += 2) {
            for (int x = area.topLeft().getX(); x <= area.bottomRight().getX(); x += 2) {
                int rgb = frame.getRGB(x, y);
                total += (rgb >> 16) & 0xff;
                total += (rgb >> 8) & 0xff;
                total += rgb & 0xff;
                samples += 3;
            }
        }
        return samples == 0 ? 0.0 : (double) total / samples;
    }
}
