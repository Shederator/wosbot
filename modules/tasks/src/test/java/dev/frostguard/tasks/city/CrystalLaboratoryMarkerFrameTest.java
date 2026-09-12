package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

class CrystalLaboratoryMarkerFrameTest {

    private static final String FRAME = "/city/crystal-lab-building-marker-emulator-0.png";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void detectsTheCrystalLaboratoryBuildingMarkerFromTheLiveFrame() throws IOException {
        ImageSearchResultData marker = OpenCvPatternLocator.locatePattern(
                resource(FRAME),
                TemplatesEnum.CRYSTAL_LAB_BUILDING_MARKER,
                new PointData(0, 0),
                new PointData(160, 160),
                90);

        assertTrue(marker.isFound(), "The live Crystal Laboratory building marker should be detected: " + marker);
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = CrystalLaboratoryMarkerFrameTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
