package dev.frostguard.engine.helper;

import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllianceChampionshipTabPatternEvidenceTest {

    private static final PointData HEADER_TOP_LEFT = new PointData(0, 80);
    private static final PointData HEADER_BOTTOM_RIGHT = new PointData(720, 210);

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
    void visibleChampionshipTabMatchesInsideEventHeader() throws IOException {
        byte[] frame = resource("/alliance/championship-tab-visible-20260728.png");

        ImageSearchResultData hit = OpenCvPatternLocator.locatePattern(
                frame,
                "/templates/alliance/championshipTab.png",
                HEADER_TOP_LEFT,
                HEADER_BOTTOM_RIGHT,
                90);

        assertTrue(hit.isFound(), "Visible Championship tab should be detected: " + hit);
        assertTrue(hit.getMatchScore() >= 95, "Championship tab should match strongly: " + hit);
        assertEquals(new PointData(401, 137), hit.getPoint());
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = AllianceChampionshipTabPatternEvidenceTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
