package dev.frostguard.tasks.economy;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Overview, Backpack, Summary and Speedup are reached by fixed 720x1280 taps, which on their own
 * prove nothing about where the tap landed -- a mistap would seed the cache from the wrong panel.
 *
 * <p>{@code ResourceStockpileRoutine} now verifies arrival on each screen via {@code
 * verifyLandedOn} before OCR-ing anything (see that routine). The four landmark templates it
 * checks against were cropped from real, live-captured frames of each screen (2026-08-19), and
 * this test locks that evidence in: each landmark is detected inside ITS OWN real frame, and --
 * to prove these are genuinely distinguishing landmarks and not something that would match
 * anything -- rejected inside a DIFFERENT screen's real frame.</p>
 */
class ResourceStockpileDestinationEvidenceTest {

    private static final String OVERVIEW_FRAME = "/resourcestockpile/overview-panel-liveAccount-20260819.png";
    private static final String BACKPACK_FRAME = "/resourcestockpile/backpack-screen-liveAccount-20260819.png";
    private static final String SUMMARY_FRAME = "/resourcestockpile/resource-speedup-summary-liveAccount-20260819.png";
    private static final String SPEEDUP_FRAME = "/resourcestockpile/speedup-tab-liveAccount-20260819.png";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void overviewLandmarkDetectsInsideTheRealOverviewFrame() throws IOException {
        ImageSearchResultData hit = locate(OVERVIEW_FRAME, TemplatesEnum.RESOURCE_STOCKPILE_OVERVIEW_PANEL_TITLE,
                new PointData(0, 320), new PointData(250, 400));

        assertTrue(hit.isFound(), "Overview panel title should be detected in its own real frame: " + hit);
        assertTrue(hit.getMatchScore() >= 90, "Should match strongly against its own captured frame: " + hit);
    }

    @Test
    void overviewLandmarkIsAbsentFromTheBackpackFrame() throws IOException {
        ImageSearchResultData hit = locate(BACKPACK_FRAME, TemplatesEnum.RESOURCE_STOCKPILE_OVERVIEW_PANEL_TITLE,
                new PointData(0, 320), new PointData(250, 400));

        assertFalse(hit.isFound(), "The Overview landmark must not match a different screen: " + hit);
    }

    @Test
    void backpackLandmarkDetectsInsideTheRealBackpackFrame() throws IOException {
        ImageSearchResultData hit = locate(BACKPACK_FRAME, TemplatesEnum.RESOURCE_STOCKPILE_BACKPACK_SCREEN_TITLE,
                new PointData(0, 0), new PointData(300, 90));

        assertTrue(hit.isFound(), "Backpack screen title should be detected in its own real frame: " + hit);
        assertTrue(hit.getMatchScore() >= 90, hit.toString());
    }

    @Test
    void backpackLandmarkIsAbsentFromTheOverviewFrame() throws IOException {
        ImageSearchResultData hit = locate(OVERVIEW_FRAME, TemplatesEnum.RESOURCE_STOCKPILE_BACKPACK_SCREEN_TITLE,
                new PointData(0, 0), new PointData(300, 90));

        assertFalse(hit.isFound(), "The Backpack landmark must not match a different screen: " + hit);
    }

    @Test
    void summaryLandmarkDetectsInsideTheRealSummaryFrame() throws IOException {
        ImageSearchResultData hit = locate(SUMMARY_FRAME, TemplatesEnum.RESOURCE_STOCKPILE_SUMMARY_POPUP_TITLE,
                new PointData(50, 210), new PointData(680, 310));

        assertTrue(hit.isFound(), "Resource & Speedup Summary title should be detected in its own real frame: " + hit);
        assertTrue(hit.getMatchScore() >= 90, hit.toString());
    }

    @Test
    void summaryLandmarkIsAbsentFromTheBackpackFrame() throws IOException {
        ImageSearchResultData hit = locate(BACKPACK_FRAME, TemplatesEnum.RESOURCE_STOCKPILE_SUMMARY_POPUP_TITLE,
                new PointData(50, 210), new PointData(680, 310));

        assertFalse(hit.isFound(), "The Summary popup landmark must not match the plain Backpack screen behind it: " + hit);
    }

    @Test
    void speedupTabLandmarkDetectsInsideTheRealSpeedupFrame() throws IOException {
        ImageSearchResultData hit = locate(SPEEDUP_FRAME, TemplatesEnum.RESOURCE_STOCKPILE_SPEEDUP_TAB_HEADER,
                new PointData(50, 370), new PointData(680, 440));

        assertTrue(hit.isFound(), "Speedup tab header should be detected in its own real frame: " + hit);
        assertTrue(hit.getMatchScore() >= 90, hit.toString());
    }

    @Test
    void speedupTabLandmarkIsAbsentFromTheResourcesTabOfTheSameSummaryPopup() throws IOException {
        // The trickiest negative case: same popup, same dialog chrome, only the active tab differs
        // (Resources vs Speedup) -- proves the landmark is reading the actual table header, not
        // just "is the summary popup open at all."
        ImageSearchResultData hit = locate(SUMMARY_FRAME, TemplatesEnum.RESOURCE_STOCKPILE_SPEEDUP_TAB_HEADER,
                new PointData(50, 370), new PointData(680, 440));

        assertFalse(hit.isFound(), "The Speedup tab header must not match the Resources tab of the same popup: " + hit);
    }

    private static ImageSearchResultData locate(String frameResource, TemplatesEnum template,
                                                  PointData topLeft, PointData bottomRight) throws IOException {
        return OpenCvPatternLocator.locatePattern(resource(frameResource), template, topLeft, bottomRight, 85);
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = ResourceStockpileDestinationEvidenceTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
