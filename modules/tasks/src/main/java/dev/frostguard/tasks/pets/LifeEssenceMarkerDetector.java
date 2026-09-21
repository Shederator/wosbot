package dev.frostguard.tasks.pets;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.color.ColorComponents;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.color.PixelStats;

import java.awt.image.BufferedImage;
import java.util.List;

public final class LifeEssenceMarkerDetector {
    private LifeEssenceMarkerDetector() {
    }

    public static List<PointData> locate(BufferedImage frame) {
        return ColorComponents.find(frame,
                new AreaData(new PointData(0, 0),
                        new PointData(frame.getWidth() - 1, frame.getHeight() - 1)),
                LifeEssenceMarkerDetector::isMarkerOrange, 500).stream()
                .filter(LifeEssenceMarkerDetector::hasExpectedDimensions)
                .filter(marker -> PixelStats.count(frame, marker.bounds(), GameColors::isVividGreen) >= 250)
                .map(ColorComponents.Component::center)
                .toList();
    }

    private static boolean hasExpectedDimensions(ColorComponents.Component marker) {
        int width = marker.bounds().bottomRight().getX() - marker.bounds().topLeft().getX() + 1;
        int height = marker.bounds().bottomRight().getY() - marker.bounds().topLeft().getY() + 1;
        return width >= 60 && width <= 140 && height >= 50 && height <= 140;
    }

    private static boolean isMarkerOrange(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return red >= 220 && green >= 60 && green <= 205 && blue <= 90 && red >= green + 60;
    }
}
