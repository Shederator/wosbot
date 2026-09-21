package dev.frostguard.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.tasks.pets.LifeEssenceMarkerDetector;
import dev.frostguard.vision.match.OpenCvPatternLocator;

class LiveRegressionPatternEvidenceTest {

    private static final PointData FULL_TOP_LEFT = new PointData(0, 0);
    private static final PointData FULL_BOTTOM_RIGHT = new PointData(720, 1280);

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void detectsCurrentArenaChallengeButton() throws IOException {
        assertMatch("/live-regressions-20260818/arena.png", TemplatesEnum.ARENA_CHALLENGE_BUTTON_CURRENT);
    }

    @Test
    void detectsCurrentLandOfHeroesQuickChallenge() throws IOException {
        assertMatch("/live-regressions-20260818/land-of-heroes.png",
                TemplatesEnum.LABYRINTH_QUICK_CHALLENGE_CURRENT);
    }

    @Test
    void detectsCurrentLifeEssenceCaringControl() throws IOException {
        assertMatch("/live-regressions-20260818/life-essence-caring.png",
                TemplatesEnum.LIFE_ESSENCE_DAILY_CARING_BUTTON_CURRENT);
    }

    @Test
    void detectsBothLifeEssenceMarkersByOrangeRegionInBothFrames() throws IOException {
        assertLifeEssenceMarkers("/live-regressions-20260818/life-essence-claim.png",
                List.of(new PointData(357, 408), new PointData(662, 356)));
        assertLifeEssenceMarkers("/live-regressions-20260922/life-essence-available-marker.png",
                List.of(new PointData(116, 146), new PointData(364, 363)));
    }

    private void assertLifeEssenceMarkers(String framePath, List<PointData> expectedMarkers) throws IOException {
        BufferedImage frame;
        try (InputStream stream = getClass().getResourceAsStream(framePath)) {
            frame = javax.imageio.ImageIO.read(Objects.requireNonNull(stream));
        }
        List<PointData> markers = LifeEssenceMarkerDetector.locate(frame);

        assertEquals(expectedMarkers.size(), markers.size(),
                () -> "Expected Life Essence markers in " + framePath + ": " + markers);
        for (PointData expected : expectedMarkers) {
            assertTrue(hasMarkerNear(markers, expected),
                    () -> "Missing Life Essence marker near " + expected + " in " + framePath + ": " + markers);
        }
    }

    @Test
    void detectsSelectedStorehouseAcrossCityLighting() throws IOException {
        ImageSearchResultData result = OpenCvPatternLocator.locatePattern(
                resource("/live-regressions-20260818/storehouse-selected.png"),
                TemplatesEnum.STOREHOUSE_SELECTED_CURRENT,
                new PointData(245, 515), new PointData(505, 575), 85);
        assertTrue(result.isFound(), () -> "Expected selected Storehouse title evidence: " + result);
    }

    @Test
    void detectsCurrentAllianceRecommendationMarker() throws IOException {
        assertMatch("/live-regressions-20260818/alliance-tech.png",
                TemplatesEnum.ALLIANCE_TECH_THUMB_UP_CURRENT);
    }

    @Test
    void detectsAllyTreasureFromPetAdventure() throws IOException {
        assertMatch("/live-regressions-20260818/pet-adventure.png", TemplatesEnum.PETS_ALLY_TREASURE);
    }

    @Test
    void detectsIdleConstructionQueueInExpandedRegion() throws IOException {
        ImageSearchResultData result = OpenCvPatternLocator.locatePattern(
                resource("/live-regressions-20260818/construction-queue.png"),
                TemplatesEnum.MARCH_QUEUE_STATUS_IDLE,
                new PointData(95, 370), new PointData(358, 407), 88);
        assertTrue(result.isFound(), () -> "Expected idle queue evidence: " + result);
    }

    private void assertMatch(String framePath, TemplatesEnum template) throws IOException {
        ImageSearchResultData result = OpenCvPatternLocator.locatePattern(
                resource(framePath), template, FULL_TOP_LEFT, FULL_BOTTOM_RIGHT, 90);
        assertTrue(result.isFound(), () -> "Expected " + template + " in " + framePath + ": " + result);
    }

    private byte[] resource(String path) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }

    private static boolean hasMarkerNear(List<PointData> markers, PointData expected) {
        return markers.stream().anyMatch(marker -> Math.abs(marker.getX() - expected.getX()) <= 20
                && Math.abs(marker.getY() - expected.getY()) <= 20);
    }
}
