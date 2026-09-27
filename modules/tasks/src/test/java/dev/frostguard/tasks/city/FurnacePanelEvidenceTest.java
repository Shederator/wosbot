package dev.frostguard.tasks.city;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.helper.FurnacePanelDetector;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class FurnacePanelEvidenceTest {
    private static final String FRAME = "/city/furnace-detail-upgrade-20260927.png";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try { OpenCvPatternLocator.loadNativeLibrary(); } catch (UnsatisfiedLinkError ignored) {}
    }

    @Test
    void recognizesOrangeEntryWhereOldArrowSearchFailed() throws IOException {
        byte[] frame = resource(FRAME);
        var result = inspect(frame);
        assertTrue(result.actionable(), result.toString());
        var point = result.upgrade().getPoint();
        assertTrue(point.getX() > 488 && point.getX() < 700 && point.getY() > 674 && point.getY() < 734,
                result.toString());
        var oldArrow = OpenCvPatternLocator.locatePattern(frame, TemplatesEnum.BUILDING_BUTTON_UPGRADE,
                new PointData(0, 0), new PointData(720, 1280), 90);
        assertFalse(oldArrow.isFound(), "Old arrow unexpectedly detects Furnace entry: " + oldArrow);
        var finalConfirmation = OpenCvPatternLocator.locatePattern(frame, TemplatesEnum.GAME_HOME_SHORTCUTS_UPGRADE_TEXT,
                new PointData(350, 900), new PointData(700, 1255), 90);
        assertFalse(finalConfirmation.isFound(), "Furnace entry must not count as final confirmation");
    }

    @Test
    void recognizesTheSameEntryThroughRuntimeRawFrameMatching() throws IOException {
        var image = ImageIO.read(new ByteArrayInputStream(resource(FRAME)));
        byte[] pixels = new byte[image.getWidth() * image.getHeight() * 4];
        int offset = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                pixels[offset++] = (byte) (rgb >> 16);
                pixels[offset++] = (byte) (rgb >> 8);
                pixels[offset++] = (byte) rgb;
                pixels[offset++] = (byte) 255;
            }
        }
        var frame = RawImageData.capture(pixels, image.getWidth(), image.getHeight(), 32);
        var evidence = FurnacePanelDetector.inspect((template, area, threshold) ->
                OpenCvPatternLocator.locatePattern(frame, template.getTemplate(), area.topLeft(), area.bottomRight(), threshold));
        assertTrue(evidence.actionable(), evidence.toString());
        assertTrue(evidence.upgrade().getMatchedArea().topLeft().getY() > 674);
        assertTrue(evidence.upgrade().getMatchedArea().bottomRight().getY() < 734);
    }

    @Test
    void rejectsFinalFireCrystalConfirmationAsFurnaceEntry() throws IOException {
        assertFalse(inspect(resource("/city/fire-crystal-building-upgrade-ready-20260821.png")).actionable());
    }

    @Test
    void requiresPanelIdentityEvenWhenOrangeButtonIsVisible() throws IOException {
        assertFalse(inspect(masked(0, 650, 245, 110)).actionable());
    }

    @Test
    void refusesOtherFurnaceControlsWhenUpgradeIsMissing() throws IOException {
        var evidence = inspect(masked(475, 650, 240, 110));
        assertTrue(evidence.panelVisible());
        assertFalse(evidence.actionable(), "On/Off and Max controls must not become Upgrade");
    }

    private static FurnacePanelDetector.Evidence inspect(byte[] frame) {
        return FurnacePanelDetector.inspect((template, area, threshold) -> locate(frame, template, area, threshold));
    }

    private static ImageSearchResultData locate(byte[] frame, TemplatesEnum template, AreaData area, int threshold) {
        return OpenCvPatternLocator.locatePattern(frame, template, area.topLeft(), area.bottomRight(), threshold);
    }

    private static byte[] masked(int x, int y, int width, int height) throws IOException {
        var image = ImageIO.read(new ByteArrayInputStream(resource(FRAME)));
        var graphics = image.createGraphics();
        graphics.setColor(java.awt.Color.BLACK);
        graphics.fillRect(x, y, width, height);
        graphics.dispose();
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static byte[] resource(String path) throws IOException {
        try (var input = Objects.requireNonNull(FurnacePanelEvidenceTest.class.getResourceAsStream(path))) {
            return input.readAllBytes();
        }
    }
}
