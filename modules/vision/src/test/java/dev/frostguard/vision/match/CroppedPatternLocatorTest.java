package dev.frostguard.vision.match;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import java.util.Objects;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CroppedPatternLocatorTest {
    @BeforeAll
    static void loadNative() throws Exception { OpenCvPatternLocator.loadNativeLibrary(); }

    @Test
    void croppedMatchesRetainGlobalCoordinatesAndTemplateDimensions() throws Exception {
        String template = TemplatesEnum.FISHING_HOOK.getTemplate();
        var image = ImageIO.read(Objects.requireNonNull(getClass().getResource(template)));
        int width = 320, height = 240, left = 83, top = 91;
        byte[] pixels = new byte[width * height * 4];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int rgb = x >= left && x < left + image.getWidth() && y >= top && y < top + image.getHeight()
                    ? image.getRGB(x - left, y - top) : 0x244c91;
            int index = (y * width + x) * 4;
            pixels[index] = (byte) (rgb >> 16);
            pixels[index + 1] = (byte) (rgb >> 8);
            pixels[index + 2] = (byte) rgb;
            pixels[index + 3] = (byte) 255;
        }
        var frame = RawImageData.capture(pixels, width, height, 32);
        var global = OpenCvPatternLocator.locateAllPatternsMono(frame, template,
                new PointData(0, 0), new PointData(width, height), 95, 1);
        var cropped = OpenCvPatternLocator.locateAllPatternsMonoCropped(frame, template,
                new PointData(left, top), new PointData(left + image.getWidth(), top + image.getHeight()), 95, 1);
        assertEquals(1, global.size());
        assertEquals(1, cropped.size());
        assertEquals(global.getFirst().getPoint(), cropped.getFirst().getPoint());
        assertEquals(global.getFirst().getTemplateSize(), cropped.getFirst().getTemplateSize());
        assertEquals(global.getFirst().getMatchedArea(), cropped.getFirst().getMatchedArea());
    }

    @Test
    void invalidRegionsCannotProduceCoordinatesOutsideTheFrame() {
        var frame = RawImageData.capture(new byte[100 * 100 * 4], 100, 100, 32);
        String template = TemplatesEnum.FISHING_HOOK.getTemplate();
        assertTrue(OpenCvPatternLocator.locateAllPatternsMonoCropped(frame, template,
                new PointData(-1, 0), new PointData(50, 50), 80, 8).isEmpty());
        assertTrue(OpenCvPatternLocator.locateAllPatternsMonoCropped(frame, template,
                new PointData(20, 40), new PointData(10, 30), 80, 8).isEmpty());
        assertTrue(OpenCvPatternLocator.locateAllPatternsMonoCropped(frame, template,
                new PointData(0, 0), new PointData(101, 50), 80, 8).isEmpty());
    }
}
