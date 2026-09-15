package dev.frostguard.tasks.events;

import java.util.Objects;
import javax.imageio.ImageIO;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.ocr.TesseractOcrSession;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FishingHudSessionTest {
    @Test
    void savedInventoryFramesDistinguishReservationsAndReadRemainingCounts() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var unselected = fixture("items-unselected.png");
        var selected = fixture("stabilizer-selected.png");
        for (var item : FishingLoadoutPolicy.Item.values()) {
            assertFalse(FishingLoadoutPolicy.selected(unselected, item), item.name());
            assertEquals(item == FishingLoadoutPolicy.Item.STABILIZER, FishingLoadoutPolicy.selected(selected, item), item.name());
        }
        try (var session = new TesseractOcrSession(CommonOCRSettings.STAMINA_FRACTION_SETTINGS)) {
            assertEquals(37, FishingLoadoutPolicy.count(session.recognize(unselected, FishingLoadoutPolicy.Item.STABILIZER.countArea())));
            assertEquals(36, FishingLoadoutPolicy.count(session.recognize(selected, FishingLoadoutPolicy.Item.STABILIZER.countArea())));
            assertEquals(38, FishingLoadoutPolicy.count(session.recognize(unselected, FishingLoadoutPolicy.Item.LANTERN.countArea())));
        }
    }
    @Test
    void readsTheIndependentLineSetupFromTheEventOverview() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        try (var session = new TesseractOcrSession(CommonOCRSettings.FISHING_DEPTH_SETTINGS)) {
            assertEquals(550, FishingDepthMonitor.readSetupMaximum(session, fixture("event-current.png")));
        }
    }
    @Test
    void readsVariedTrialDepthsAndRejectsTheAmbiguousFlashFrame() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        try (var session = new TesseractOcrSession(CommonOCRSettings.FISHING_DEPTH_SETTINGS)) {
            for (int expected : new int[]{19, 32, 48}) {
                assertEquals(new FishingDepthMonitor.Depth(expected, 100),
                        FishingDepthMonitor.read(session, fixture("depth-" + expected + ".png")));
            }
            assertNull(FishingDepthMonitor.read(session, fixture("depth-44-flash.png")),
                    "An unreadable repeated digit must not turn 44m into 4m");
        }
    }

    private RawImageData fixture(String name) throws Exception {
        var image = ImageIO.read(Objects.requireNonNull(getClass().getResource("/fishing/" + name)));
        byte[] pixels = new byte[image.getWidth() * image.getHeight() * 4];
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int rgb = image.getRGB(x, y), offset = (y * image.getWidth() + x) * 4;
            pixels[offset] = (byte) (rgb >> 16);
            pixels[offset + 1] = (byte) (rgb >> 8);
            pixels[offset + 2] = (byte) rgb;
            pixels[offset + 3] = (byte) 255;
        }
        return RawImageData.capture(pixels, image.getWidth(), image.getHeight(), 32);
    }

    @Test
    void repeatedlyReadsRealHudWithoutReinitializingItsModels() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"),
                "Native saved-frame OCR check uses the bundled Windows Tesseract runtime");
        var image = ImageIO.read(Objects.requireNonNull(getClass().getResource("/fishing/active.png")));
        byte[] pixels = new byte[image.getWidth() * image.getHeight() * 4];
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int rgb = image.getRGB(x, y), offset = (y * image.getWidth() + x) * 4;
            pixels[offset] = (byte) (rgb >> 16);
            pixels[offset + 1] = (byte) (rgb >> 8);
            pixels[offset + 2] = (byte) rgb;
            pixels[offset + 3] = (byte) 255;
        }
        var frame = RawImageData.capture(pixels, image.getWidth(), image.getHeight(), 32);
        try (var session = new TesseractOcrSession(CommonOCRSettings.FISHING_DEPTH_SETTINGS)) {
            for (int i = 0; i < 20; i++) {
                assertEquals(new FishingDepthMonitor.Depth(172, 550), FishingDepthMonitor.read(session, frame));
            }
        }
    }
}
