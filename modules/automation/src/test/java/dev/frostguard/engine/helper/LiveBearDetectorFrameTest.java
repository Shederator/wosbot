package dev.frostguard.engine.helper;

import dev.frostguard.engine.nav.RallyFlagCoordinates;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Objects;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression evidence cropped from the 720x1280 live Bear run; no account data is retained. */
class LiveBearDetectorFrameTest {

    @Test
    void readsTheLiveLeftHandBearTimerAsFiveMinutes() throws IOException {
        BufferedImage frame = restore("timer-five.png", 90, 580);

        assertEquals(5, DeploymentHelper.selectedBearRallySetTimeMinutes(frame));
    }

    @Test
    void identifiesEveryCapturedYellowFormationAndRejectsAllOtherSlots() throws IOException {
        BufferedImage none = restore("formation-none.png", 0, 80);
        for (int slot = 1; slot <= 8; slot++) {
            assertFalse(FormationSelectionVerifier.isSelected(
                    none, RallyFlagCoordinates.selectionAreaForFlag(slot)));
        }

        for (int selected = 1; selected <= 6; selected++) {
            BufferedImage frame = restore("formation-" + selected + ".png", 0, 80);
            for (int slot = 1; slot <= 8; slot++) {
                if (slot == selected) {
                    assertTrue(FormationSelectionVerifier.isSelected(
                            frame, RallyFlagCoordinates.selectionAreaForFlag(slot)),
                            "live formation #" + selected + " must be selected");
                } else {
                    assertFalse(FormationSelectionVerifier.isSelected(
                            frame, RallyFlagCoordinates.selectionAreaForFlag(slot)),
                            "live formation #" + selected + " must not select #" + slot);
                }
            }
        }
    }

    private BufferedImage restore(String name, int x, int y) throws IOException {
        BufferedImage crop = ImageIO.read(Objects.requireNonNull(
                getClass().getResourceAsStream("/bear/" + name), name));
        BufferedImage frame = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = frame.createGraphics();
        graphics.drawImage(crop, x, y, null);
        graphics.dispose();
        return frame;
    }
}
