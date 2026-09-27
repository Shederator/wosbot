package dev.frostguard.tasks.lifecycle;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.detection.CloseCrossDetector;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloseableStartupOverlayDetectionTest {

    private static final AreaData STARTUP_CLOSE_AREA = AreaData.of(540, 65, 680, 240);

    @Test
    void detectsStartupCloseControlUsingReusableDetector() throws IOException {
        List<CloseCrossDetector.Detection> detections = inspect(
                "/startup/closeable-offer-overlay-20260821.png");

        assertFalse(detections.isEmpty());
        assertTrue(detections.getFirst().score() >= 55.0);
        assertTrue(detections.getFirst().bounds().topLeft().getX() >= 540);
        assertTrue(detections.getFirst().bounds().bottomRight().getX() <= 680);
        assertTrue(Math.abs(detections.getFirst().center().getX() - 610) <= 8);
        assertTrue(Math.abs(detections.getFirst().center().getY() - 130) <= 8,
                () -> "expected startup cross near (610,130), got " + detections);
    }

    @Test
    void detectsCloseControlExtendingBelowPreviousSearchArea() throws IOException {
        String fixture = "/startup/closeable-offer-cross-below-old-limit.png";
        assertTrue(inspect(fixture, AreaData.of(0, 0, 139, 135)).isEmpty(),
                "the old bottom boundary must clip this cross");
        List<CloseCrossDetector.Detection> detections = inspect(
                fixture,
                AreaData.of(0, 0, 139, 174));

        assertFalse(detections.isEmpty());
        assertTrue(detections.getFirst().bounds().bottomRight().getY() <= 175);
        assertTrue(Math.abs(detections.getFirst().center().getX() - 70) <= 8);
        assertTrue(Math.abs(detections.getFirst().center().getY() - 110) <= 8,
                () -> "expected startup cross near (70,110), got " + detections);
    }

    @Test
    void rejectsHigherPriorityAndNonCloseableStartupDialogs() throws IOException {
        for (String path : new String[] {
                "/startup/mandatory-update-dialog-20260820.png",
                "/startup/resource-download-prompt-20260817.png",
                "/startup/welcome-back-dialog-20260821.png" }) {
            List<CloseCrossDetector.Detection> detections = inspect(path);
            assertTrue(detections.isEmpty(), path + ": " + detections);
        }
    }

    private static List<CloseCrossDetector.Detection> inspect(String path) throws IOException {
        return inspect(path, STARTUP_CLOSE_AREA);
    }

    private static List<CloseCrossDetector.Detection> inspect(String path, AreaData searchArea) throws IOException {
        return CloseCrossDetector.locate(rawRgbaFrame(frame(path)), searchArea);
    }

    private static BufferedImage frame(String path) throws IOException {
        try (InputStream stream = CloseableStartupOverlayDetectionTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(ImageIO.read(Objects.requireNonNull(stream,
                    "Missing test resource: " + path)), "Could not decode test resource: " + path);
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
