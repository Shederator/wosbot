package dev.frostguard.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.tasks.pets.LifeEssenceMarkerDetector;
import dev.frostguard.tasks.pets.TemplateLifeEssenceSearch;
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

    @Test
    void rejectsAllianceScreensAsLifeEssenceMarkers() throws IOException {
        assertNoLifeEssenceMarkers(readResource("/alliance/tech-battle-recommendation-20260810.png"),
                "/alliance/tech-battle-recommendation-20260810.png");
        Path championship = Path.of("..", "automation", "src", "test", "resources",
                "alliance", "championship-tab-visible-20260728.png");
        assertTrue(Files.isRegularFile(championship),
                () -> "Missing alliance fixture: " + championship.toAbsolutePath());
        assertNoLifeEssenceMarkers(javax.imageio.ImageIO.read(championship.toFile()), championship.toString());
    }

    @Test
    void templateSearchMatchesOneLeafOnEachSavedIsland() throws IOException {
        assertTemplateCenters("/live-regressions-20260818/life-essence-claim.png",
                List.of(new PointData(662, 356)));
        assertTemplateCenters("/live-regressions-20260922/life-essence-available-marker.png",
                List.of(new PointData(360, 348)));
    }

    @Test
    void reportsPartiallyFilledLifeEssenceMarkersWithoutFailing() throws IOException {
        BufferedImage frame = readResource("/live-regressions-20260923/life-essence-partially-filled.png");
        String summary = LifeEssenceMarkerDetector.assess(frame).stream()
                .map(candidate -> (candidate.accepted() ? "accepted" : "rejected")
                        + " center=(" + candidate.center().getX() + "," + candidate.center().getY() + ")"
                        + " size=" + candidate.width() + "x" + candidate.height()
                        + (candidate.rejection() == null ? "" : " reason=" + candidate.rejection()))
                .reduce((left, right) -> left + "; " + right)
                .orElse("no orange region above the assessment floor");
        System.err.println("WARNING partially filled Life Essence frame: " + summary);
    }

    private void assertTemplateCenters(String framePath, List<PointData> expected) throws IOException {
        List<PointData> found = new TemplateLifeEssenceSearch(resource(framePath)).find(readResource(framePath));
        assertEquals(expected.size(), found.size(), () -> framePath + " template centers: " + found);
        for (PointData point : expected) {
            assertTrue(found.stream().anyMatch(actual -> near(actual, point)),
                    () -> "Missing template center near " + point + " in " + framePath + ": " + found);
        }
    }

    private static boolean near(PointData actual, PointData expected) {
        return Math.abs(actual.getX() - expected.getX()) <= 20
                && Math.abs(actual.getY() - expected.getY()) <= 20;
    }

    private void assertLifeEssenceMarkers(String framePath, List<PointData> expectedMarkers) throws IOException {
        BufferedImage frame = readResource(framePath);
        List<PointData> markers = LifeEssenceMarkerDetector.locate(frame);

        assertEquals(expectedMarkers.size(), markers.size(),
                () -> "Expected Life Essence markers in " + framePath + ": " + markers);
        for (PointData expected : expectedMarkers) {
            assertTrue(hasMarkerNear(markers, expected),
                    () -> "Missing Life Essence marker near " + expected + " in " + framePath + ": " + markers);
        }
    }

    private static void assertNoLifeEssenceMarkers(BufferedImage frame, String label) {
        List<PointData> markers = LifeEssenceMarkerDetector.locate(frame);
        assertEquals(List.of(), markers, () -> "Unexpected Life Essence markers in " + label + ": " + markers);
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

    private BufferedImage readResource(String path) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            return javax.imageio.ImageIO.read(Objects.requireNonNull(stream, "Missing test resource: " + path));
        }
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
