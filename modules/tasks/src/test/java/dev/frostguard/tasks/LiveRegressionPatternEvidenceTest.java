package dev.frostguard.tasks;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
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
    void detectsCurrentLifeEssenceClaim() throws IOException {
        assertMatch("/live-regressions-20260818/life-essence-claim.png",
                TemplatesEnum.LIFE_ESSENCE_CLAIM_CURRENT);
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
}
