package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.OcrException;

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
    void detectsLighthouseRowAndReadsAdvertisedIntelGain() throws IOException, OcrException {
        ImageSearchResultData row = IntelScreenHelper.lighthouseRowAtBottom();
        assertEquals(new PointData(46, 649), row.getPoint());

        AreaData gainArea = IntelScreenHelper.gainAreaFor(row);
        String text = OcrEngine.recognizeText(rgbaFrame("daily-sidebar-gain.png"),
                gainArea.topLeft(), gainArea.bottomRight(), CommonOCRSettings.INTEL_GAIN_SETTINGS);

        assertEquals(8, IntelScreenHelper.parseAdvertisedGain(text).orElseThrow(),
                () -> "OCR text was: " + text);
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

    private static RawImageData rgbaFrame(String name) throws IOException {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(
                IntelNavigationFrameTest.class.getResourceAsStream(FIXTURE_ROOT + name)));
        byte[] rgba = new byte[image.getWidth() * image.getHeight() * 4];
        int offset = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                rgba[offset++] = (byte) ((argb >> 16) & 0xff);
                rgba[offset++] = (byte) ((argb >> 8) & 0xff);
                rgba[offset++] = (byte) (argb & 0xff);
                rgba[offset++] = (byte) ((argb >> 24) & 0xff);
            }
        }
        return RawImageData.capture(rgba, image.getWidth(), image.getHeight(), 4);
    }
}
