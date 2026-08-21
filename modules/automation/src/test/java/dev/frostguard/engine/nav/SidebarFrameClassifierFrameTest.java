package dev.frostguard.engine.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AreaData;

class SidebarFrameClassifierFrameTest {

    private static final String ROOT = "/navigation/sidebar-update-20260817/";

    @Test
    void detectsSelectedSectionAcrossAllSuppliedPostUpdateFrames() throws IOException {
        assertEquals(SidebarSection.CITY, selected("city.png"));
        assertEquals(SidebarSection.WILDERNESS, selected("wilderness.png"));
        assertEquals(SidebarSection.DAILY, selected("daily-top.png"));
        assertEquals(SidebarSection.DAILY, selected("daily-middle.png"));
        assertEquals(SidebarSection.DAILY, selected("daily-bottom.png"));
    }

    @Test
    void rejectsFramesThatCannotContainTheSidebarTabs() {
        assertTrue(SidebarFrameClassifier.selectedSection(new BufferedImage(100, 100,
                BufferedImage.TYPE_INT_RGB)).isEmpty());
    }

    @Test
    void rejectsTabLikeBrightnessWithoutVisiblePanelAnchor() {
        BufferedImage frame = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = frame.createGraphics();
        try {
            graphics.setColor(new Color(180, 180, 180));
            fill(graphics, CommonGameAreas.sidebarTabSample(SidebarSection.CITY));
            graphics.setColor(new Color(90, 90, 90));
            fill(graphics, CommonGameAreas.sidebarTabSample(SidebarSection.WILDERNESS));
            fill(graphics, CommonGameAreas.sidebarTabSample(SidebarSection.DAILY));
        } finally {
            graphics.dispose();
        }

        assertTrue(SidebarFrameClassifier.selectedSection(frame).isEmpty());
    }

    private SidebarSection selected(String name) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(ROOT + name)) {
            if (stream == null) {
                throw new IOException("Missing sidebar frame: " + name);
            }
            return SidebarFrameClassifier.selectedSection(ImageIO.read(stream)).orElseThrow();
        }
    }

    private void fill(Graphics2D graphics, AreaData area) {
        graphics.fillRect(area.topLeft().getX(), area.topLeft().getY(),
                area.bottomRight().getX() - area.topLeft().getX() + 1,
                area.bottomRight().getY() - area.topLeft().getY() + 1);
    }
}
