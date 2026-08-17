package dev.frostguard.tasks.lifecycle;

import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceDownloadPromptPatternEvidenceTest {

    private static final String FRAME = "/startup/resource-download-prompt-20260817.png";
    private static final String DOWNLOAD_NOW_TEMPLATE = "/templates/home/downloadNowButton.png";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another saved-frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void detectsDownloadNowInsteadOfEnteringWithMissingResources() throws IOException {
        ImageSearchResultData hit = OpenCvPatternLocator.locatePattern(
                resource(FRAME),
                DOWNLOAD_NOW_TEMPLATE,
                new PointData(0, 0),
                new PointData(720, 1280),
                90);

        assertTrue(hit.isFound(), "The supplied resource prompt should expose Download Now: " + hit);
        assertTrue(hit.getMatchScore() >= 90, "Download Now should meet the runtime threshold: " + hit);
        assertTrue(hit.getPoint().col() >= 370,
                "The detected target must be the right Download Now button, not left Enter Game: " + hit);
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = ResourceDownloadPromptPatternEvidenceTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
