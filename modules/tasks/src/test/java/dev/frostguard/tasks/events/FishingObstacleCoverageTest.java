package dev.frostguard.tasks.events;

import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import static org.junit.jupiter.api.Assertions.*;

class FishingObstacleCoverageTest {
    @BeforeAll
    static void loadNative() throws Exception { OpenCvPatternLocator.loadNativeLibrary(); }

    @Test
    void includesDarkAndBlueObjectsThatTheColourMaskCannotSee() {
        var image = water();
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(0x244c91));
            graphics.fillRect(200, 450, 120, 40);
            graphics.setColor(new Color(0x101010));
            graphics.fillRect(450, 650, 80, 50);
        } finally { graphics.dispose(); }
        var obstacles = FishingVision.obstacles(capture(image), 353, 183);
        for (int[] point : new int[][]{{260, 470}, {490, 675}}) {
            assertTrue(obstacles.stream().anyMatch(a -> a.topLeft().getX() <= point[0]
                    && a.bottomRight().getX() >= point[0] && a.topLeft().getY() <= point[1]
                    && a.bottomRight().getY() >= point[1]), "Missing obstacle: " + obstacles);
        }
    }

    @Test
    void uniformWaterAndExcludedHudDoNotCreateObjects() {
        var image = water();
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(10, 20, 140, 190);
            graphics.fillRect(655, 10, 45, 50);
        } finally { graphics.dispose(); }
        assertTrue(FishingVision.obstacles(capture(image), 353, 183).isEmpty());
    }

    private static BufferedImage water() {
        var image = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(0x0880b3));
            graphics.fillRect(0, 0, 720, 1280);
        } finally { graphics.dispose(); }
        return image;
    }

    private static RawImageData capture(BufferedImage image) {
        byte[] rgba = new byte[720 * 1280 * 4];
        for (int y = 0; y < 1280; y++) for (int x = 0; x < 720; x++) {
            int rgb = image.getRGB(x, y), offset = (y * 720 + x) * 4;
            rgba[offset] = (byte)(rgb >> 16); rgba[offset + 1] = (byte)(rgb >> 8);
            rgba[offset + 2] = (byte)rgb; rgba[offset + 3] = (byte)255;
        }
        return RawImageData.capture(rgba, 720, 1280, 32);
    }
}
