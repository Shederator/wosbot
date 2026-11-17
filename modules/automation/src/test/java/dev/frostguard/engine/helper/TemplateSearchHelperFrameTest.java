package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.vision.match.OpenCvPatternLocator;

class TemplateSearchHelperFrameTest {

    @BeforeAll
    static void loadOpenCv() throws Exception {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded the native library in this JVM.
        }
    }

    @Test
    void oneCapturedFrameServesSeveralRealTemplateChecks() throws Exception {
        AtomicInteger captures = new AtomicInteger();
        RawImageData intelMap = rgbaFrame(image("/intel/live-20260819/intel-map.png"));
        TemplateSearchHelper helper = helper("0", captures, intelMap);

        TemplateSearchHelper.Frame frame = helper.captureFrame();

        assertTrue(frame.locatePatternMono(
                TemplatesEnum.INTEL_BEAST_GRAYSCALE,
                SearchConfigConstants.DEFAULT_SINGLE).isFound());
        frame.locatePatternMono(
                TemplatesEnum.INTEL_BEAST_GRAYSCALE_FC,
                SearchConfigConstants.DEFAULT_SINGLE);
        frame.locatePatternMono(
                TemplatesEnum.INTEL_JOURNEY_GRAYSCALE,
                SearchConfigConstants.DEFAULT_SINGLE);
        assertEquals(1, captures.get());
    }

    @Test
    void profileBoundHelpersDoNotShareTheirFrames() throws Exception {
        AtomicInteger firstCaptures = new AtomicInteger();
        AtomicInteger secondCaptures = new AtomicInteger();
        RawImageData intelMap = rgbaFrame(image("/intel/live-20260819/intel-map.png"));
        RawImageData blank = RawImageData.capture(new byte[720 * 1280 * 4], 720, 1280, 32);

        TemplateSearchHelper.Frame first = helper("0", firstCaptures, intelMap).captureFrame();
        TemplateSearchHelper.Frame second = helper("1", secondCaptures, blank).captureFrame();

        assertTrue(first.locatePatternMono(
                TemplatesEnum.INTEL_BEAST_GRAYSCALE,
                SearchConfigConstants.DEFAULT_SINGLE).isFound());
        assertFalse(second.locatePatternMono(
                TemplatesEnum.INTEL_BEAST_GRAYSCALE,
                SearchConfigConstants.DEFAULT_SINGLE).isFound());
        assertEquals(1, firstCaptures.get());
        assertEquals(1, secondCaptures.get());
    }

    private TemplateSearchHelper helper(
            String device, AtomicInteger captures, RawImageData frame) {
        AccountDescriptor profile = new AccountDescriptor(1L);
        profile.setDisplayName("frame-test-" + device);
        return new TemplateSearchHelper(
                EmulatorController.getInstance(), device, profile,
                () -> {
                    captures.incrementAndGet();
                    return frame;
                });
    }

    private BufferedImage image(String resource) throws Exception {
        return ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(resource)));
    }

    private static RawImageData rgbaFrame(BufferedImage image) {
        byte[] rgba = new byte[image.getWidth() * image.getHeight() * 4];
        int offset = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                rgba[offset++] = (byte) ((rgb >> 16) & 0xFF);
                rgba[offset++] = (byte) ((rgb >> 8) & 0xFF);
                rgba[offset++] = (byte) (rgb & 0xFF);
                rgba[offset++] = (byte) 0xFF;
            }
        }
        return RawImageData.capture(rgba, image.getWidth(), image.getHeight(), 32);
    }
}
