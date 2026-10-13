package dev.frostguard.tasks.exploration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

class LabyrinthDungeon1BannerFrameTest {

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void findsLandFragmentOnTheLiveLandOfHeroesBanner() throws IOException {
        ImageSearchResultData hit = OpenCvPatternLocator.locatePattern(
                resource("/labyrinth/loh-banner-live.png"),
                TemplatesEnum.LABYRINTH_DUNGEON_1,
                new PointData(0, 0),
                new PointData(260, 90),
                90);

        assertTrue(hit.isFound(), () -> "Land fragment should match the live LoH banner: " + hit);
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = LabyrinthDungeon1BannerFrameTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
