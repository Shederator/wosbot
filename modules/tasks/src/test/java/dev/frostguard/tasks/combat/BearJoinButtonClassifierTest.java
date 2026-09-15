package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.PointData;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BearJoinButtonClassifierTest {

    @Test
    void acceptsBlueEnabledButtonWithoutDependingOnExactShade() {
        BufferedImage image = buttonFrame(new Color(42, 126, 218));

        BearJoinButtonClassifier.Evidence evidence =
                BearJoinButtonClassifier.inspect(image, new PointData(50, 50));

        assertTrue(evidence.enabled());
        assertTrue(evidence.colouredPixels() >= 18);
    }

    @Test
    void acceptsLegacyGreenEnabledButton() {
        BufferedImage image = buttonFrame(new Color(37, 183, 86));

        assertTrue(BearJoinButtonClassifier.inspect(image, new PointData(50, 50)).enabled());
    }

    @Test
    void rejectsGreyDisabledButton() {
        BufferedImage image = buttonFrame(new Color(135, 135, 135));

        BearJoinButtonClassifier.Evidence evidence =
                BearJoinButtonClassifier.inspect(image, new PointData(50, 50));

        assertFalse(evidence.enabled());
    }

    private static BufferedImage buttonFrame(Color buttonColor) {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(30, 30, 30));
            graphics.fillRect(0, 0, 100, 100);
            graphics.setColor(buttonColor);
            graphics.fillRoundRect(28, 37, 44, 26, 8, 8);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(47, 42, 6, 16);
            graphics.fillRect(42, 47, 16, 6);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
