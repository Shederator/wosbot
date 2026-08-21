package dev.frostguard.engine.nav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class SidebarViewportChangeDetectorTest {

    @Test
    void unchangedSettledViewportMarksAListBoundary() throws IOException {
        BufferedImage frame = resource("daily-top.png");

        assertFalse(SidebarViewportChangeDetector.materiallyChanged(frame, frame));
    }

    @Test
    void realDailyScrollMovesTheLeftIconColumnMaterially() throws IOException {
        BufferedImage top = resource("daily-top.png");
        BufferedImage middle = resource("daily-middle.png");

        assertTrue(SidebarViewportChangeDetector.materiallyChanged(top, middle));
    }

    @Test
    void hiddenCompletedRowsInvalidateAnyAssumedRowPosition() throws IOException {
        BufferedImage visible = resource("daily-hide-off.png");
        BufferedImage hidden = resource("daily-hide-on.png");

        assertTrue(SidebarViewportChangeDetector.materiallyChanged(visible, hidden));
    }

    private BufferedImage resource(String name) throws IOException {
        String path = "/navigation/sidebar-dynamic-20260821/" + name;
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            return ImageIO.read(Objects.requireNonNull(stream, "Missing test resource: " + path));
        }
    }
}
