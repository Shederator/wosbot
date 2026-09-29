package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.nav.LeftMenuTextSettings;
import dev.frostguard.tasks.city.ConstructionBlockerRegistry.Consumer;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.vision.ocr.OcrEngine;

class CityUpgradeTrainingBusyFrameTest {

    private static final String FRAME = "/city/lancer-camp-training-busy-20260928.png";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void readsTheCampNameAndFullClockWithoutAnUpgradeButton() throws Exception {
        byte[] encoded = resource();
        RawImageData frame = rgbaFrame(ImageIO.read(new ByteArrayInputStream(encoded)));
        String name = OcrEngine.recognizeText(
                frame,
                UpgradeBuildingsRoutine.BUILDING_NAME_AREA_VALUE.topLeft(),
                UpgradeBuildingsRoutine.BUILDING_NAME_AREA_VALUE.bottomRight(),
                LeftMenuTextSettings.WHITE_SETTINGS).trim();
        String clock = OcrEngine.recognizeText(
                frame,
                TrainingCampBusyRead.CLOCK_AREA.topLeft(),
                TrainingCampBusyRead.CLOCK_AREA.bottomRight(),
                CommonOCRSettings.MARCH_QUEUE_TIMER_SETTINGS).trim();
        ImageSearchResultData upgrade = OpenCvPatternLocator.locatePattern(
                encoded,
                TemplatesEnum.BUILDING_BUTTON_UPGRADE,
                new PointData(0, 0),
                new PointData(720, 1280),
                90);

        assertEquals("LancerCamp", name);
        assertEquals("07:41:01", clock);
        assertFalse(upgrade.isFound(), () -> "Upgrade control should stay absent: " + upgrade);
        TrainingCampBusyRead.Decision decision = TrainingCampBusyRead.positive(name, clock, upgrade.isFound());
        assertEquals(Consumer.LANCER, decision.camp());
        assertEquals(Duration.ofHours(7).plusMinutes(41).plusSeconds(1), decision.remaining());
    }

    private static byte[] resource() throws IOException {
        try (InputStream stream = CityUpgradeTrainingBusyFrameTest.class.getResourceAsStream(FRAME)) {
            return Objects.requireNonNull(stream, "Missing " + FRAME).readAllBytes();
        }
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
