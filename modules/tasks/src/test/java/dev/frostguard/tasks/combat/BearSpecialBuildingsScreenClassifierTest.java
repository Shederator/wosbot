package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class BearSpecialBuildingsScreenClassifierTest {

    @Test
    void recognizesOnlyTheConfiguredTrapGoButton() {
        BufferedImage image = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        int blue = new Color(45, 160, 235).getRGB();
        for (int y = 330; y < 390; y++) {
            for (int x = 520; x < 665; x++) {
                image.setRGB(x, y, blue);
            }
        }

        assertTrue(BearSpecialBuildingsScreenClassifier.isGoButtonReady(image, 1));
        assertFalse(BearSpecialBuildingsScreenClassifier.isGoButtonReady(image, 2));
    }

    @Test
    void rejectsTerritoryScreenWithoutAGoButton() {
        BufferedImage image = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);

        assertFalse(BearSpecialBuildingsScreenClassifier.isGoButtonReady(image, 1));
        assertFalse(BearSpecialBuildingsScreenClassifier.isGoButtonReady(image, 2));
        assertFalse(BearSpecialBuildingsScreenClassifier.isGoButtonReady(image, 3));
    }
}
