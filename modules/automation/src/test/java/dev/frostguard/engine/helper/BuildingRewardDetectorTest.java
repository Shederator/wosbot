package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.engine.helper.BuildingRewardDetector.RewardSearch;
import dev.frostguard.engine.helper.NavigationHelper.BuildingDestination;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildingRewardDetectorTest {

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // The app or an earlier test may already have loaded the native library.
        }
    }

    @Test
    void mapsBakeryToItsBundledColorSearch() {
        RewardSearch search = BuildingRewardDetector.searchFor(BuildingDestination.BAKERY);

        assertEquals(TemplatesEnum.BAKERY_REWARD_ICON, search.template());
        assertSame(CommonGameAreas.BAKERY_REWARD_ICON_SEARCH, search.area());
        assertEquals(80, search.threshold());
        assertEquals(3, search.maxAttempts());
        assertEquals(300L, search.delayMillis());
        assertTrue(search.template().existsAtPath());
    }

    @Test
    void mapsFarmToItsBundledColorSearch() {
        RewardSearch search = BuildingRewardDetector.searchFor(BuildingDestination.FARM);

        assertEquals(TemplatesEnum.FARM_REWARD_ICON, search.template());
        assertSame(CommonGameAreas.FARM_REWARD_ICON_SEARCH, search.area());
        assertEquals(75, search.threshold());
        assertEquals(5, search.maxAttempts());
        assertEquals(300L, search.delayMillis());
        assertTrue(search.template().existsAtPath());
    }

    @Test
    void detectsVisibleBakeryRewardInRedactedLiveFrame() throws IOException {
        assertVisible(BuildingDestination.BAKERY,
                "/building/bakery-farm/bakery-reward-present-20261023.png");
    }

    @Test
    void detectsVisibleFarmRewardInRedactedLiveFrame() throws IOException {
        assertVisible(BuildingDestination.FARM,
                "/building/bakery-farm/farm-reward-present-20261112.png");
    }

    private static void assertVisible(BuildingDestination destination, String frameResource)
            throws IOException {
        byte[] frame = resource(frameResource);
        RewardSearch search = BuildingRewardDetector.searchFor(destination);

        ImageSearchResultData hit = BuildingRewardDetector.detect(destination, (template, config) ->
                OpenCvPatternLocator.locatePattern(frame, template.getTemplate(),
                        config.getArea().topLeft(), config.getArea().bottomRight(),
                        config.getThreshold()));

        assertTrue(hit.isFound(), destination + " reward should be detected: " + hit);
        assertTrue(hit.getMatchScore() >= search.threshold(), hit.toString());
        assertTrue(hit.getPoint().isWithin(search.area().topLeft(), search.area().bottomRight()),
                hit.toString());
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = BuildingRewardDetectorTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
