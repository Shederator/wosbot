package dev.frostguard.tasks.alliance;

import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllianceTechThumbPatternEvidenceTest {

    private static final PointData TREE_TOP_LEFT = new PointData(0, 316);
    private static final PointData TREE_BOTTOM_RIGHT = new PointData(720, 1280);
    private static final String THUMB_TEMPLATE = "/templates/alliance/techThumbUp.png";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void rejectsResearchableArrowsWhenNoRecommendationIsVisible() throws IOException {
        ImageSearchResultData hit = locate("/alliance/tech-growth-without-recommendation-20260810.png");

        assertFalse(hit.isFound(), "Researchable arrows must not be mistaken for the thumbs-up marker: " + hit);
    }

    @Test
    void detectsRecommendedNodeInBattleTree() throws IOException {
        ImageSearchResultData hit = locate("/alliance/tech-battle-recommendation-20260810.png");

        assertTrue(hit.isFound(), "The supplied Battle frame should expose the thumbs-up marker: " + hit);
        assertTrue(hit.getMatchScore() >= 90, "The thumbs-up marker should meet the runtime threshold: " + hit);
    }

    private ImageSearchResultData locate(String frameResource) throws IOException {
        return OpenCvPatternLocator.locatePattern(
                resource(frameResource),
                THUMB_TEMPLATE,
                TREE_TOP_LEFT,
                TREE_BOTTOM_RIGHT,
                90);
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = AllianceTechThumbPatternEvidenceTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
