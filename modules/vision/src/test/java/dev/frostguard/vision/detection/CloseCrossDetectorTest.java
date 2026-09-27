package dev.frostguard.vision.detection;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloseCrossDetectorTest {

    @Test
    void locatesObservedCrossStylesAcrossThemesAndPositions() throws IOException {
        assertNear("cross-offer-top-right.png", 610, 176);
        assertNear("cross-blue-top-right.png", 680, 40);
        assertNear("cross-orange-top-right.png", 665, 155);
        assertNear("cross-tip-middle-right.png", 635, 445);
    }

    @Test
    void restrictsSearchToCallerAreaAndDefaultsToRightHalf() throws IOException {
        BufferedImage source = read("cross-tip-middle-right.png");
        BufferedImage frame = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = frame.createGraphics();
        graphics.setColor(new Color(30, 36, 44));
        graphics.fillRect(0, 0, frame.getWidth(), frame.getHeight());
        graphics.drawImage(source.getSubimage(605, 415, 60, 60), 150, 170, null);
        graphics.drawImage(source.getSubimage(605, 415, 60, 60), 605, 415, null);
        graphics.dispose();

        List<CloseCrossDetector.Detection> defaultMatches = CloseCrossDetector.locate(frame);
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
        List<CloseCrossDetector.Detection> detections = CloseCrossDetector.locate(plus);
        assertTrue(detections.isEmpty(), () -> "plus control matched as a close cross: " + detections);
    }

    private static void assertNear(String fixture, int x, int y) throws IOException {
        BufferedImage frame = read(fixture);
        List<CloseCrossDetector.Detection> detections = CloseCrossDetector.locate(frame);
        CloseCrossDetector.Detection match = detections.stream()
                .filter(candidate -> near(candidate.center(), x, y))
                .findFirst()
                .orElse(null);
        assertTrue(match != null,
                () -> fixture + " expected a close cross near " + x + "," + y
                        + " but got " + detections);
        assertTrue(match.score() >= 55.0);
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
}
