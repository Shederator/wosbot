package dev.frostguard.engine.helper;

import dev.frostguard.api.domain.AreaData;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormationSelectionVerifierTest {

    private static final AreaData SLOT = AreaData.of(10, 10, 64, 68);

    @Test
    void confirmsTheYellowOutlineShownByASelectedFormation() {
        BufferedImage frame = solidFrame(new Color(21, 116, 183));
        Graphics2D graphics = frame.createGraphics();
        graphics.setColor(new Color(246, 191, 45));
        graphics.drawRect(10, 10, 53, 57);
        graphics.drawRect(11, 11, 51, 55);
        graphics.dispose();

        assertTrue(FormationSelectionVerifier.isSelected(frame, SLOT));
    }

    @Test
    void rejectsAnUnselectedBlueFormationTile() {
        BufferedImage frame = solidFrame(new Color(21, 116, 183));

        assertFalse(FormationSelectionVerifier.isSelected(frame, SLOT));
    }

    @Test
    void ignoresSmallIncidentalYellowDetailsInsideTheTile() {
        BufferedImage frame = solidFrame(new Color(21, 116, 183));
        frame.setRGB(30, 30, new Color(246, 191, 45).getRGB());
        frame.setRGB(31, 30, new Color(246, 191, 45).getRGB());

        assertFalse(FormationSelectionVerifier.isSelected(frame, SLOT));
    }

    private BufferedImage solidFrame(Color colour) {
        BufferedImage frame = new BufferedImage(80, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = frame.createGraphics();
        graphics.setColor(colour);
        graphics.fillRect(0, 0, frame.getWidth(), frame.getHeight());
        graphics.dispose();
        return frame;
    }
}
