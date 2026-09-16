package dev.frostguard.tasks.events;

import java.util.Objects;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import static org.junit.jupiter.api.Assertions.*;

class FishingVisionTest {
    @Test
    void recoversAHiddenGlowOnlyWhenBothVisibleHookPartsAgree() throws Exception {
        var canvas = new java.awt.image.BufferedImage(720, 1280, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = canvas.createGraphics();
        try {
            graphics.setColor(new java.awt.Color(0x0880b3));
            graphics.fillRect(0, 0, 720, 1280);
            graphics.drawImage(ImageIO.read(Objects.requireNonNull(getClass().getResource(
                    "/fishing/hook-hidden-by-hud.png"))), 0, 100, null);
        } finally { graphics.dispose(); }
        var previous = new FishingVision.Hook(new dev.frostguard.api.domain.PointData(156, 183));
        var found = FishingVision.hook(capture(canvas), previous);
        assertNotNull(found);
        assertEquals(20, found.point().getX(), 3);
        assertEquals(183, found.point().getY(), 3);
        assertNull(FishingVision.hook(capture(canvas)), "No prior attachment: do not infer a hidden glow");
        graphics = canvas.createGraphics();
        try {
            graphics.setColor(new java.awt.Color(0x0880b3));
            graphics.fillRect(31, 255, 32, 40);
        } finally { graphics.dispose(); }
        assertNull(FishingVision.hook(capture(canvas), previous), "A body alone is not hook identity");
    }

    @Test
    void findsAnAttachmentAtTheActualWallWithoutCroppingItsIcon() throws Exception {
        var canvas = new java.awt.image.BufferedImage(720, 1280, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = canvas.createGraphics();
        try {
            graphics.setColor(new java.awt.Color(0x0880b3));
            graphics.fillRect(0, 0, 720, 1280);
            graphics.drawImage(ImageIO.read(Objects.requireNonNull(getClass().getResource(
                    "/fishing/hook-at-wall.png"))), 0, 215, null);
        } finally { graphics.dispose(); }
        var found = FishingVision.hook(capture(canvas));
        assertNotNull(found);
        assertEquals(20, found.point().getX(), 3);
        assertEquals(370, found.point().getY(), 3);
    }

    @Test
    void confirmsAnAttachmentWhoseNearestRopeIsCoveredByTheHud() throws Exception {
        var canvas = new java.awt.image.BufferedImage(720, 1280, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = canvas.createGraphics();
        try {
            graphics.setColor(new java.awt.Color(0x0880b3));
            graphics.fillRect(0, 0, 720, 1280);
            graphics.drawImage(ImageIO.read(Objects.requireNonNull(getClass().getResource(
                    "/fishing/hook-under-hud.png"))), 0, 100, null);
        } finally { graphics.dispose(); }
        var found = FishingVision.hook(capture(canvas),
                new FishingVision.Hook(new dev.frostguard.api.domain.PointData(267, 200)));
        assertNotNull(found);
        assertEquals(120, found.point().getX(), 3);
        assertEquals(220, found.point().getY(), 3);
    }

    @Test
    void doesNotReportTheVisibleHookAndItsRopeAsAnObstacle() throws Exception {
        var canvas = new java.awt.image.BufferedImage(720, 1280, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = canvas.createGraphics();
        try {
            graphics.setColor(new java.awt.Color(0x0880b3));
            graphics.fillRect(0, 0, 720, 1280);
            graphics.drawImage(ImageIO.read(Objects.requireNonNull(getClass().getResource(
                    "/fishing/hook-with-rope.png"))), 290, 100, null);
            graphics.setColor(new java.awt.Color(0xaaa060));
            graphics.fillRect(320, 340, 60, 20);
        } finally { graphics.dispose(); }
        var obstacles = FishingVision.obstacles(capture(canvas), 353, 183);
        assertFalse(obstacles.stream().anyMatch(a -> a.topLeft().getX() <= 353
                && a.bottomRight().getX() >= 353 && a.topLeft().getY() <= 248
                && a.bottomRight().getY() >= 248), "Own hook must be absent: " + obstacles);
        assertTrue(obstacles.stream().anyMatch(a -> a.topLeft().getY() >= 330
                && a.bottomRight().getY() <= 370), "Incoming object must remain: " + obstacles);
    }

    private static RawImageData capture(java.awt.image.BufferedImage image) {
        byte[] bytes = new byte[720 * 1280 * 4];
        for (int y = 0; y < 1280; y++) for (int x = 0; x < 720; x++) {
            int rgb = image.getRGB(x, y), i = (y * 720 + x) * 4;
            bytes[i] = (byte)(rgb >> 16); bytes[i + 1] = (byte)(rgb >> 8);
            bytes[i + 2] = (byte)rgb; bytes[i + 3] = (byte)255;
        }
        return RawImageData.capture(bytes, 720, 1280, 32);
    }

    @Test
    void disconnectsOwnRopeWithoutMaskingIncomingObjects() {
        assertFalse(FishingVision.isObstaclePixel(353, 145, 0x555555, 353, 183));
        assertTrue(FishingVision.isObstaclePixel(353, 350, 0xaaa060, 353, 183));
        assertTrue(FishingVision.isObstaclePixel(380, 145, 0xaaa060, 353, 183));
    }

    @Test
    void doesNotDiscardAnObstacleMergedWithTheHook() {
        assertFalse(FishingVision.isHookArea(dev.frostguard.api.domain.AreaData.of(300, 180, 500, 300), 353, 183));
        assertTrue(FishingVision.isHookArea(dev.frostguard.api.domain.AreaData.of(313, 160, 391, 287), 353, 183));
    }

    @BeforeAll
    static void loadNative() throws Exception { OpenCvPatternLocator.loadNativeLibrary(); }

    @Test
    void groupsUncataloguedFishWithoutConfusingWaterWithForeground() throws Exception {
        assertFalse(FishingVision.isForeground(0x244c91));
        assertTrue(FishingVision.isForeground(0xaaa060));
        var image = ImageIO.read(Objects.requireNonNull(getClass().getResource("/fishing/active.png")));
        byte[] bytes = new byte[720 * 1280 * 4];
        for (int y = 0; y < 1280; y++) for (int x = 0; x < 720; x++) {
            int rgb = image.getRGB(x, y), offset = (y * 720 + x) * 4;
            bytes[offset] = (byte) (rgb >> 16);
            bytes[offset + 1] = (byte) (rgb >> 8);
            bytes[offset + 2] = (byte) rgb;
            bytes[offset + 3] = (byte) 255;
        }
        var frame = RawImageData.capture(bytes, 720, 1280, 32);
        var hook = FishingVision.hook(frame);
        assertNotNull(hook, () -> "The glow must also be attached to the fishing line; candidates="
                + OpenCvPatternLocator.locateAllPatternsMono(frame,
                        dev.frostguard.api.configs.TemplatesEnum.FISHING_HOOK.getTemplate(),
                        new dev.frostguard.api.domain.PointData(20, 70),
                        new dev.frostguard.api.domain.PointData(700, 1260), 80, 8));
        assertTrue(Math.abs(hook.point().getX() - 353) <= 3);
        assertTrue(Math.abs(hook.point().getY() - 183) <= 3);
        var nearby = FishingVision.hook(frame, hook);
        assertNotNull(nearby);
        assertEquals(hook.point(), nearby.point());
        // During the return transition the attachment can leave the narrow tracking band.
        var recovered = FishingVision.hook(frame, new FishingVision.Hook(new dev.frostguard.api.domain.PointData(353, 1027)));
        assertNotNull(recovered);
        assertEquals(hook.point(), recovered.point());
        var obstacles = FishingVision.obstacles(frame, 352, 183);
        for (int[] fish : new int[][]{{235, 450}, {230, 633}, {175, 935}, {410, 1120}}) {
            assertTrue(obstacles.stream().anyMatch(area -> fish[0] >= area.topLeft().getX()
                    && fish[0] <= area.bottomRight().getX() && fish[1] >= area.topLeft().getY()
                    && fish[1] <= area.bottomRight().getY()), "Missing fish at " + fish[0] + "," + fish[1] + ": " + obstacles);
        }
    }
}
