package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.vision.match.OpenCvPatternLocator;

class IntelNavigationFrameTest {

    private static final String FIXTURE_ROOT = "/intel/live-20260819/";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded the native library in this JVM.
        }
    }

    @Test
    void detectsIntelRowAndGreenAvailabilityWithoutOcr() throws IOException {
        byte[] frame = resource("daily-sidebar-gain.png");
        ImageSearchResultData lighthouseRow = OpenCvPatternLocator.locatePattern(frame,
                TemplatesEnum.SIDEBAR_DAILY_LIGHTHOUSE_INTEL,
                CommonGameAreas.SIDEBAR_ROW_ICON_COLUMN.topLeft(),
                CommonGameAreas.SIDEBAR_ROW_ICON_COLUMN.bottomRight(), 88);

        assertTrue(lighthouseRow.isFound(), "Expected the Lighthouse Intel row icon");
        ImageSearchResultData gain = OpenCvPatternLocator.locatePattern(frame,
                TemplatesEnum.INTEL_GAIN_AVAILABLE,
                IntelScreenHelper.intelGainRowArea(lighthouseRow).topLeft(),
                IntelScreenHelper.intelGainRowArea(lighthouseRow).bottomRight(), 88);

        assertTrue(gain.isFound(), "Expected green Intel Gain only in the icon-anchored row");
        assertTrue(IntelScreenHelper.availableGreenPixels(bufferedFrame("daily-sidebar-gain.png"), gain) >= 150,
                "Expected the located Intel Gain pattern to retain its green active state");
    }

    @Test
    void anchorsIntelGainToTheShiftedIconRowInTheReportedFrame() throws IOException {
        byte[] frame = absoluteResource(
                "/navigation/sidebar-update-20260821/daily-dynamic-intel.png");
        ImageSearchResultData lighthouseRow = OpenCvPatternLocator.locatePattern(frame,
                TemplatesEnum.SIDEBAR_DAILY_LIGHTHOUSE_INTEL,
                CommonGameAreas.SIDEBAR_ROW_ICON_COLUMN.topLeft(),
                CommonGameAreas.SIDEBAR_ROW_ICON_COLUMN.bottomRight(), 88);

        assertTrue(lighthouseRow.isFound());
        ImageSearchResultData gain = OpenCvPatternLocator.locatePattern(frame,
                TemplatesEnum.INTEL_GAIN_AVAILABLE,
                IntelScreenHelper.intelGainRowArea(lighthouseRow).topLeft(),
                IntelScreenHelper.intelGainRowArea(lighthouseRow).bottomRight(), 88);

        assertTrue(gain.isFound(), "Expected Intel Gain inside the dynamically located row");
    }

    @Test
    void rejectsDailyAvailabilityWhenTheIntelIdentityIsMissing() throws IOException {
        byte[] frame = resource("intel-map.png");
        ImageSearchResultData row = OpenCvPatternLocator.locatePattern(frame,
                TemplatesEnum.INTEL_GAIN_AVAILABLE,
                CommonGameAreas.SIDEBAR_CONTENT.topLeft(),
                CommonGameAreas.SIDEBAR_CONTENT.bottomRight(), 88);

        assertFalse(row.isFound());
    }

    @Test
    void detectsDirectIntelShortcutOnWildernessFrame() throws IOException {
        byte[] frame = resource("daily-sidebar-gain.png");
        ImageSearchResultData shortcut = OpenCvPatternLocator.locatePattern(frame,
                TemplatesEnum.GAME_HOME_INTEL,
                new PointData(615, 800), new PointData(715, 930), 88);

        assertTrue(shortcut.isFound(), "Expected the direct Intel shortcut at the right side of Wilderness");
    }

    @Test
    void detectsIntelBubbleAfterOpeningLighthouse() throws IOException {
        byte[] frame = resource("lighthouse-selected-no-hand.png");
        ImageSearchResultData bubble = OpenCvPatternLocator.locatePattern(frame,
                TemplatesEnum.LIGHTHOUSE_INTEL_BUBBLE,
                new PointData(0, 100), new PointData(720, 1180), 88);

        assertTrue(bubble.isFound(), "Expected the Intel bubble over the selected Lighthouse");
        assertTrue(Math.abs(bubble.getPoint().getX() - 360) <= 3, () -> "x=" + bubble.getPoint());
        assertTrue(Math.abs(bubble.getPoint().getY() - 575) <= 3, () -> "y=" + bubble.getPoint());
    }

    @Test
    void detectsTutorialHandThatMustBeDismissedBeforeTappingIntelBubble() throws IOException {
        byte[] frame = resource("lighthouse-selected.png");
        ImageSearchResultData hand = OpenCvPatternLocator.locatePattern(frame,
                TemplatesEnum.SKIP_TUTORIAL_HAND,
                new PointData(0, 100), new PointData(720, 1180), 88);

        assertTrue(hand.isFound(), "Expected the tutorial hand on the supplied Lighthouse frame");
    }

    @Test
    void confirmsIntelMapAndFindsNormalBeastMarkerInFcFrame() throws IOException {
        byte[] frame = resource("intel-map.png");
        ImageSearchResultData screen = OpenCvPatternLocator.locatePattern(frame,
                TemplatesEnum.INTEL_SCREEN_1, new PointData(0, 0), new PointData(720, 1280), 88);
        ImageSearchResultData beast = OpenCvPatternLocator.locatePatternMono(frame,
                TemplatesEnum.INTEL_BEAST_GRAYSCALE, new PointData(0, 100), new PointData(720, 1180), 88);

        assertTrue(screen.isFound(), "Expected Intel screen anchor after tapping the Lighthouse bubble");
        assertTrue(beast.isFound(), "Expected a normal skull marker on the supplied FC-era Intel map");
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream stream = IntelNavigationFrameTest.class.getResourceAsStream(FIXTURE_ROOT + name)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + name).readAllBytes();
        }
    }

    private static byte[] absoluteResource(String path) throws IOException {
        try (InputStream stream = IntelNavigationFrameTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }

    private static BufferedImage bufferedFrame(String name) throws IOException {
        return ImageIO.read(Objects.requireNonNull(
                IntelNavigationFrameTest.class.getResourceAsStream(FIXTURE_ROOT + name)));
    }
}
