package dev.frostguard.engine.helper;

import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the quit-game dialog against a real 720x1280 live-account capture: the detection template
 * matches inside the frame, and the Cancel button's on-screen region, found by scanning for its
 * orange fill, contains the point {@link QuitDialogGuard} taps. A tap at that point was observed
 * to dismiss the dialog on a live account without quitting the game.
 *
 * <p>Evidence level: saved real-frame verification.
 */
class QuitGameDialogCancelButtonEvidenceTest {

    private static final PointData CANCEL_BUTTON = QuitDialogGuard.CANCEL_BUTTON;

    private static final String FRAME_RESOURCE = "/system/quitGameDialog-liveAccount-20260819.png";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // The app and other tests may already have loaded the native library in this JVM.
        }
    }

    @Test
    void detectionTemplateMatchesInsideTheRealLiveAccountFrame() throws IOException {
        byte[] frame = resource(FRAME_RESOURCE);

        ImageSearchResultData hit = OpenCvPatternLocator.locatePattern(
                frame,
                "/templates/system/quitGameDialog.png",
                new PointData(0, 0),
                new PointData(720, 1280),
                90);

        assertTrue(hit.isFound(), "quitGameDialog.png should detect inside the real captured dialog: " + hit);
    }

    @Test
    void committedCancelCoordinateLandsInsideTheRealButtonOnLiveAccountEvidence() throws IOException {
        BufferedImage frame;
        try (InputStream in = getClass().getResourceAsStream(FRAME_RESOURCE)) {
            frame = ImageIO.read(Objects.requireNonNull(in, "Missing evidence frame: " + FRAME_RESOURCE));
        }

        // The Cancel button is a solid orange fill (roughly RGB 235,90,20 at its center, sampled
        // from the real frame). Scan for it rather than hardcoding a bounding box, so this test
        // fails clearly -- "no button-colored region found" -- if the dialog's art ever changes
        // instead of silently passing on a stale assumption.
        int minX = frame.getWidth(), maxX = 0, minY = frame.getHeight(), maxY = 0;
        int matched = 0;
        for (int y = 0; y < frame.getHeight(); y++) {
            for (int x = 0; x < frame.getWidth(); x++) {
                int rgb = frame.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (r > 200 && g > 80 && g < 160 && b < 60) {
                    matched++;
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
            }
        }

        assertTrue(matched > 500,
                "Expected a solid orange Cancel-button region in the real frame, found " + matched + " matching pixels");
        assertTrue(CANCEL_BUTTON.getX() >= minX && CANCEL_BUTTON.getX() <= maxX
                        && CANCEL_BUTTON.getY() >= minY && CANCEL_BUTTON.getY() <= maxY,
                "QuitDialogGuard's Cancel tap point " + CANCEL_BUTTON
                        + " should land inside the real button's on-screen region ["
                        + minX + "," + minY + "]-[" + maxX + "," + maxY + "], measured from live-account evidence");
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = QuitGameDialogCancelButtonEvidenceTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
