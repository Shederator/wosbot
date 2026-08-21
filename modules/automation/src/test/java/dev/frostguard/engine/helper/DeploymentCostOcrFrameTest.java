package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.ocr.OcrEngine;

class DeploymentCostOcrFrameTest {

    @Test
    void singleWordModeReadsRealTwoDigitRallyCost() throws Exception {
        assertEquals("22", readStaminaCost("/deployment/polar-after-equalize-20260709.png"));
    }

    @Test
    void singleWordModeReadsRealOneDigitIntelCost() throws Exception {
        assertEquals("9", readStaminaCost("/deployment/intel-beast-formation-20260819.png"));
    }

    @Test
    void travelRegionReadsCompleteTimerOnPolarFormation() throws Exception {
        assertEquals("00:02:16", readTravelTime("/deployment/polar-after-equalize-20260709.png"));
    }

    @Test
    void travelRegionDoesNotTurnIntelTimerIntoTenHours() throws Exception {
        assertEquals("00:00:15", readTravelTime("/deployment/intel-beast-formation-20260819.png"));
    }

    private String readTravelTime(String resource) throws Exception {
        RawImageData frame = rgbaFrame(image(resource));
        return OcrEngine.recognizeText(
                frame,
                CommonGameAreas.TRAVEL_TIME_OCR_AREA.topLeft(),
                CommonGameAreas.TRAVEL_TIME_OCR_AREA.bottomRight(),
                CommonOCRSettings.TRAVEL_TIME_SETTINGS).trim();
    }

    private String readStaminaCost(String resource) throws Exception {
        RawImageData frame = rgbaFrame(image(resource));
        return OcrEngine.recognizeText(
                frame,
                CommonGameAreas.SPENT_STAMINA_OCR_AREA.topLeft(),
                CommonGameAreas.SPENT_STAMINA_OCR_AREA.bottomRight(),
                CommonOCRSettings.SPENT_STAMINA_SETTINGS).trim();
    }

    private BufferedImage image(String resource) throws Exception {
        return ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(resource)));
    }

    private RawImageData rgbaFrame(BufferedImage image) {
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
