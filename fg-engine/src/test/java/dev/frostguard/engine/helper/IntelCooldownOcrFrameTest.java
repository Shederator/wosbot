package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.ocr.TesseractOcrProvider;

class IntelCooldownOcrFrameTest {

    @Test
    void readsTopBannerWhileIntelMarkersRemain() throws Exception {
        assertCooldown(
                "/intel/marker-map-cooldown-20260729.png",
                CommonGameAreas.INTEL_COOLDOWN_WITH_MARKERS_OCR_AREA,
                Duration.ofMinutes(2).plusSeconds(3));
    }

    @Test
    void readsCenteredBannerAfterAllIntelRewardsAreClaimed() throws Exception {
        assertCooldown(
                "/intel/empty-map-cooldown-20260729.png",
                CommonGameAreas.INTEL_COOLDOWN_EMPTY_MAP_OCR_AREA,
                Duration.ofMinutes(25).plusSeconds(41));
    }

    private void assertCooldown(String resource, AreaData area, Duration expected) throws Exception {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(resource)));
        RawImageData frame = rgbaFrame(image);

        String text = TesseractOcrProvider.recognizeText(
                frame,
                area.topLeft(),
                area.bottomRight(),
                CommonOCRSettings.INTEL_COOLDOWN_SETTINGS);

        assertEquals(expected, GameTimeUtils.parseDuration(text));
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
