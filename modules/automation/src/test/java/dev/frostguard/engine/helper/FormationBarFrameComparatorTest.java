package dev.frostguard.engine.helper;

import dev.frostguard.engine.nav.CommonGameAreas;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormationBarFrameComparatorTest {

    @Test
    void confirmsMovementFromInitialViewToRightEnd() throws IOException {
        BufferedImage initial = frame("/formations/formation-slot-9-empty.png");
        BufferedImage right = frame("/formations/formation-slots-right-end.png");

        assertTrue(FormationBarFrameComparator.moved(initial, right, CommonGameAreas.RALLY_FLAG_BAR));
    }

    @Test
    void rejectsIncompleteCaptures() throws IOException {
        BufferedImage frame = frame("/formations/formation-slots-right-end.png");
        BufferedImage incomplete = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);

        assertFalse(FormationBarFrameComparator.moved(frame, incomplete, CommonGameAreas.RALLY_FLAG_BAR));
    }

    private BufferedImage frame(String path) throws IOException {
        try (var stream = getClass().getResourceAsStream(path)) {
            return ImageIO.read(Objects.requireNonNull(stream, "Missing test resource: " + path));
        }
    }
}
