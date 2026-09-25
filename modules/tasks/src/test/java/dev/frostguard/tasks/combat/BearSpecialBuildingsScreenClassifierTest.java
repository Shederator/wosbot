package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class BearSpecialBuildingsScreenClassifierTest {

    @Test
    void recognizesOnlyTheConfiguredTrapGoButton() {
        BufferedImage image = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(45, 160, 235));
        graphics.fillRect(520, 330, 145, 60);
        graphics.dispose();

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
