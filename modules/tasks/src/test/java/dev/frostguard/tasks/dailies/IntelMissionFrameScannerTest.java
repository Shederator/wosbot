package dev.frostguard.tasks.dailies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.helper.TemplateSearchHelper;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.vision.match.OpenCvPatternLocator;

class IntelMissionFrameScannerTest {

    @BeforeAll
    static void loadOpenCv() throws Exception {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded the native library in this JVM.
        }
    }

    @Test
    void scansOrderedMarkerVariantsOnOneCapturedFrame() throws Exception {
        AtomicInteger captures = new AtomicInteger();
        RawImageData intelMap = rgbaFrame(image());
        AccountDescriptor profile = new AccountDescriptor(1L);
        profile.setDisplayName("intel-frame-test");
        TemplateSearchHelper templates = new TemplateSearchHelper(
                EmulatorController.getInstance(), "0", profile,
                () -> {
                    captures.incrementAndGet();
                    return intelMap;
                });

        TemplateSearchHelper.Frame frame = templates.captureFrame();
        IntelMissionFrameScanner.Match match = IntelMissionFrameScanner.findFirst(
                frame,
                new TemplatesEnum[] {
                        TemplatesEnum.INTEL_BEAST_GRAYSCALE_FC,
                        TemplatesEnum.INTEL_BEAST_GRAYSCALE_FC1,
                        TemplatesEnum.INTEL_BEAST_GRAYSCALE
                },
                SearchConfigConstants.DEFAULT_SINGLE);

        assertTrue(match.result().isFound());
        assertEquals(TemplatesEnum.INTEL_BEAST_GRAYSCALE, match.template());
        assertEquals(1, captures.get());
    }

    private BufferedImage image() throws Exception {
        Path fromRoot = Path.of("modules", "automation", "src", "test", "resources",
                "intel", "live-20260819", "intel-map.png");
        Path fixture = Files.isRegularFile(fromRoot)
                ? fromRoot
                : Path.of("..", "automation", "src", "test", "resources",
                        "intel", "live-20260819", "intel-map.png");
        return ImageIO.read(fixture.toFile());
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
