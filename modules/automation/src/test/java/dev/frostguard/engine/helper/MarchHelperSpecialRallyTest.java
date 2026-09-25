package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class MarchHelperSpecialRallyTest {

    @Test
    void detectsGreenSpecialRallyIconAcrossItsMovableLeftRail() {
        BufferedImage image = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        paint(image, 12, 420, 41, 439, new Color(30, 190, 70));
        paint(image, 20, 425, 29, 434, new Color(120, 220, 210));

        assertTrue(MarchHelper.hasActiveSpecialRally(image));
    }

    @Test
    void ignoresGreenGatherIconWithoutTheCyanCrossedSwords() {
        BufferedImage image = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        paint(image, 12, 420, 41, 449, new Color(30, 190, 70));

        assertFalse(MarchHelper.hasActiveSpecialRally(image));
    }

    @Test
    void ignoresGreenWorldContentOutsideTheSpecialRallyRail() {
        BufferedImage image = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        paint(image, 100, 420, 180, 500, new Color(30, 190, 70));

        assertFalse(MarchHelper.hasActiveSpecialRally(image));
    }

    private static void paint(
            BufferedImage image, int x0, int y0, int x1, int y1, Color color) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
    }
}
