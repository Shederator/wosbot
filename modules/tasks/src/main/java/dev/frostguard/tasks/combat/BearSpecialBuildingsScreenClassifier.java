package dev.frostguard.tasks.combat;

import java.awt.image.BufferedImage;

/** Detects the configured Hunting Trap Go button without OCR or another capture. */
final class BearSpecialBuildingsScreenClassifier {

    private static final double MIN_GO_BLUE_RATIO = 0.35;

    private BearSpecialBuildingsScreenClassifier() {}

    static boolean isGoButtonReady(BufferedImage image, int trapNumber) {
        if (image == null || image.getWidth() < 100 || image.getHeight() < 100) {
            return false;
        }
        return switch (trapNumber) {
            case 1 -> blueRatio(image, 520.0 / 720, 330.0 / 1280, 665.0 / 720, 390.0 / 1280)
                    >= MIN_GO_BLUE_RATIO;
            case 2 -> blueRatio(image, 520.0 / 720, 505.0 / 1280, 665.0 / 720, 575.0 / 1280)
                    >= MIN_GO_BLUE_RATIO;
            default -> false;
        };
    }

    private static double blueRatio(BufferedImage image,
            double left, double top, double right, double bottom) {
        int x1 = (int) Math.round(image.getWidth() * left);
        int y1 = (int) Math.round(image.getHeight() * top);
        int x2 = Math.min(image.getWidth(), (int) Math.round(image.getWidth() * right));
        int y2 = Math.min(image.getHeight(), (int) Math.round(image.getHeight() * bottom));
        int matched = 0;
        int total = 0;
        for (int y = y1; y < y2; y += 2) {
            for (int x = x1; x < x2; x += 2) {
                int rgb = image.getRGB(x, y);
                int red = rgb >> 16 & 0xFF;
                int green = rgb >> 8 & 0xFF;
                int blue = rgb & 0xFF;
                if (red <= 100 && green >= 120 && green <= 210 && blue >= 190) {
                    matched++;
                }
                total++;
            }
        }
        return total == 0 ? 0 : (double) matched / total;
    }
}
