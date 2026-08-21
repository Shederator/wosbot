package dev.frostguard.tasks.lifecycle;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloseableStartupOverlayPatternEvidenceTest {

    private static final PointData CLOSE_AREA_TOP_LEFT = new PointData(540, 65);
    private static final PointData CLOSE_AREA_BOTTOM_RIGHT = new PointData(680, 200);

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another saved-frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void detectsConcreteCloseControlFromObservedOfferOverlay() throws IOException {
        ImageSearchResultData close = inspect("/startup/closeable-offer-overlay-20260821.png");

        assertTrue(close.isFound());
        assertTrue(close.hasMatchedArea());
        assertTrue(close.getMatchScore() >= 90);
    }

    @Test
    void rejectsHigherPriorityAndNonCloseableStartupDialogs() throws IOException {
        for (String path : new String[] {
                "/startup/mandatory-update-dialog-20260820.png",
                "/startup/resource-download-prompt-20260817.png",
                "/startup/welcome-back-dialog-20260821.png" }) {
            assertFalse(inspect(path).isFound(), path);
        }
    }

    private static ImageSearchResultData inspect(String path) throws IOException {
        return OpenCvPatternLocator.locatePattern(
                bytes(path),
                TemplatesEnum.GAME_START_CLOSEABLE_OVERLAY_CLOSE.getTemplate(),
                CLOSE_AREA_TOP_LEFT,
                CLOSE_AREA_BOTTOM_RIGHT,
                90);
    }

    private static byte[] bytes(String path) throws IOException {
        try (InputStream stream = CloseableStartupOverlayPatternEvidenceTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
