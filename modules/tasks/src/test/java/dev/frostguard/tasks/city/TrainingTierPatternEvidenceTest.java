package dev.frostguard.tasks.city;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Objects;

import static dev.frostguard.api.configs.TemplatesEnum.TRAINING_MARKSMAN_T6;
import static dev.frostguard.api.configs.TemplatesEnum.TRAINING_MARKSMAN_T7;
import static dev.frostguard.api.configs.TemplatesEnum.TRAINING_MARKSMAN_T8;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingTierPatternEvidenceTest {

    private static final PointData TROOP_LIST_TOP_LEFT = new PointData(0, 600);
    private static final PointData TROOP_LIST_BOTTOM_RIGHT = new PointData(720, 820);

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            // Must be loadNativeLibrary(), not extractAndLoadNative(...dll):
            // the bundled DLL is a Windows image, so naming it directly makes
            // this test fail with UnsatisfiedLinkError on the Linux CI runner
            // and takes the whole nightly bundle down with it. This selects the
            // native image for the host platform, exactly like every sibling
            // frame test does.
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // The app and other tests may already have loaded the native library in this JVM.
        }
    }

    @Test
    void visibleMarksmanTiersRemainDetectableWhenT6IsPromotable() throws IOException {
        byte[] frame = resource("/training/marksman-t6-promotable-20260727.png");

        ImageSearchResultData t6 = locate(frame, TRAINING_MARKSMAN_T6);
        ImageSearchResultData t7 = locate(frame, TRAINING_MARKSMAN_T7);
        ImageSearchResultData t8 = locate(frame, TRAINING_MARKSMAN_T8);

        assertTrue(t6.getMatchScore() >= 95, "Promotable T6 should remain detectable: " + t6);
        assertTrue(t7.getMatchScore() >= 95, "Visible T7 should remain detectable: " + t7);
        assertTrue(t8.getMatchScore() >= 95, "Selected T8 should remain detectable: " + t8);
        assertTrue(t6.getPoint().getX() < t7.getPoint().getX()
                        && t7.getPoint().getX() < t8.getPoint().getX(),
                "Detected tiers should preserve their visible order");
    }

    private static ImageSearchResultData locate(byte[] frame, TemplatesEnum template) {
        return OpenCvPatternLocator.locatePattern(
                frame,
                template,
                TROOP_LIST_TOP_LEFT,
                TROOP_LIST_BOTTOM_RIGHT,
                0);
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = TrainingTierPatternEvidenceTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
