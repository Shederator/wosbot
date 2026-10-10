package dev.frostguard.vision.detection;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.detection.CloseCrossDetector.Region;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloseCrossDetectorTest {

    @Test
    void predefinedRegionsUseInclusiveFullFrameCoordinates() {
        assertEquals(AreaData.of(0, 0, 359, 639),
                CloseCrossDetector.areaFor(Region.UPPER_LEFT_QUARTER, 720, 1280));
        assertEquals(AreaData.of(360, 0, 719, 639),
                CloseCrossDetector.areaFor(Region.UPPER_RIGHT_QUARTER, 720, 1280));
        assertEquals(AreaData.of(0, 640, 359, 1279),
                CloseCrossDetector.areaFor(Region.LOWER_LEFT_QUARTER, 720, 1280));
        assertEquals(AreaData.of(360, 640, 719, 1279),
                CloseCrossDetector.areaFor(Region.LOWER_RIGHT_QUARTER, 720, 1280));
        assertEquals(AreaData.of(360, 0, 719, 1279),
                CloseCrossDetector.areaFor(Region.HALF_RIGHT, 720, 1280));
        assertEquals(AreaData.of(180, 320, 539, 959),
                CloseCrossDetector.areaFor(Region.MIDDLE, 720, 1280));
        assertEquals(AreaData.of(0, 0, 719, 1279),
                CloseCrossDetector.areaFor(Region.FULL_SCREEN, 720, 1280));
    }

    @Test
    void regionSearchReturnsCoordinatesRelativeToFullScreen() throws IOException {
        BufferedImage frame = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        BufferedImage source = read("cross-tip-middle-right.png");
        Graphics2D graphics = frame.createGraphics();
        graphics.drawImage(source.getSubimage(605, 415, 60, 60), 145, 170, null);
        graphics.dispose();

        List<CloseCrossDetector.Detection> detections = CloseCrossDetector.locate(
                frame, Region.UPPER_LEFT_QUARTER);

        assertTrue(detections.stream().anyMatch(match -> near(match.center(), 175, 200)),
                () -> "expected absolute coordinates near 175,200, got " + detections);
    }

    @Test
    void locatesObservedCrossStylesAcrossThemesAndPositions() throws IOException {
        assertNear("cross-offer-top-right.png", 610, 176);
        assertNear("cross-charm-master-live-top-right.png", 610, 176);
        assertNear("cross-blue-top-right.png", 680, 40);
        assertNear("cross-orange-top-right.png", 665, 155);
        assertNear("cross-tip-middle-right.png", 635, 445);
    }

    @Test
    void locatesCraftsmanStyleCrossWithSecondMaskedTemplate() throws IOException {
        BufferedImage frame = read("cross-craftsman-top-right.png");
        List<CloseCrossDetector.Detection> detections = CloseCrossDetector.locate(
                frame, AreaData.of(540, 65, 719, 280));

        assertTrue(detections.stream().anyMatch(candidate -> near(candidate.center(), 664, 229)),
                () -> "Craftsman cross was not found: " + detections);
    }

    @Test
    void selectsHalfRightAndAcceptsExplicitArea() throws IOException {
        BufferedImage source = read("cross-tip-middle-right.png");
        BufferedImage frame = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = frame.createGraphics();
        graphics.setColor(new Color(30, 36, 44));
        graphics.fillRect(0, 0, frame.getWidth(), frame.getHeight());
        graphics.drawImage(source.getSubimage(605, 415, 60, 60), 150, 170, null);
        graphics.drawImage(source.getSubimage(605, 415, 60, 60), 605, 415, null);
        graphics.dispose();

        List<CloseCrossDetector.Detection> defaultMatches = CloseCrossDetector.locate(frame, Region.HALF_RIGHT);
        assertTrue(defaultMatches.stream().anyMatch(match -> near(match.center(), 635, 445)));
        assertFalse(defaultMatches.stream().anyMatch(match -> near(match.center(), 180, 200)));

        List<CloseCrossDetector.Detection> bounded = CloseCrossDetector.locate(
                frame, AreaData.of(130, 150, 230, 250));
        assertTrue(bounded.stream().anyMatch(match -> near(match.center(), 180, 200)));
        assertFalse(bounded.stream().anyMatch(match -> near(match.center(), 600, 200)));
    }

    @Test
    void rejectsPlusControl() throws IOException {
        BufferedImage plus = read("candidate-more-control-negative.png");
        List<CloseCrossDetector.Detection> detections = CloseCrossDetector.locate(plus, Region.HALF_RIGHT);
        assertTrue(detections.isEmpty(), () -> "plus control matched as a close cross: " + detections);
    }

    @Test
    void rejectsAllianceMenuWithoutCloseControl() throws IOException {
        BufferedImage allianceMenu = read("alliance-menu-no-close-control.png");
        List<CloseCrossDetector.Detection> detections = CloseCrossDetector.locate(allianceMenu, Region.HALF_RIGHT);
        assertTrue(detections.isEmpty(), () -> "Alliance menu matched as a close cross: " + detections);
    }

    @Test
    void locatesCrossDirectlyFromRawEmulatorFrame() throws IOException {
        BufferedImage frame = read("cross-offer-top-right.png");
        List<CloseCrossDetector.Detection> detections = CloseCrossDetector.locate(
                rawRgbaFrame(frame), Region.HALF_RIGHT);

        assertTrue(detections.stream().anyMatch(candidate -> near(candidate.center(), 610, 176)),
                () -> "raw frame did not preserve the detected cross: " + detections);
    }

    private static void assertNear(String fixture, int x, int y) throws IOException {
        BufferedImage frame = read(fixture);
        List<CloseCrossDetector.Detection> detections = CloseCrossDetector.locate(frame, Region.HALF_RIGHT);
        CloseCrossDetector.Detection match = detections.stream()
                .filter(candidate -> near(candidate.center(), x, y))
                .findFirst()
                .orElse(null);
        assertTrue(match != null,
                () -> fixture + " expected a close cross near " + x + "," + y
                        + " but got " + detections);
        assertTrue(match.score() >= 78.0);
        assertTrue(match.center().isWithin(match.bounds().topLeft(), match.bounds().bottomRight()));
        assertTrue(match.width() >= 30 && match.height() >= 30);
    }

    private static boolean near(PointData point, int x, int y) {
        return Math.abs(point.getX() - x) <= 7 && Math.abs(point.getY() - y) <= 7;
    }

    private static BufferedImage read(String name) throws IOException {
        try (InputStream stream = CloseCrossDetectorTest.class.getResourceAsStream("/closebutton/" + name)) {
            return ImageIO.read(Objects.requireNonNull(stream, "Missing fixture " + name));
        }
    }

    private static RawImageData rawRgbaFrame(BufferedImage frame) {
        byte[] pixels = new byte[frame.getWidth() * frame.getHeight() * 4];
        for (int y = 0, offset = 0; y < frame.getHeight(); y++) {
            for (int x = 0; x < frame.getWidth(); x++, offset += 4) {
                int argb = frame.getRGB(x, y);
                pixels[offset] = (byte) (argb >> 16);
                pixels[offset + 1] = (byte) (argb >> 8);
                pixels[offset + 2] = (byte) argb;
                pixels[offset + 3] = (byte) 0xFF;
            }
        }
        return RawImageData.capture(pixels, frame.getWidth(), frame.getHeight(), 32);
    }
}
