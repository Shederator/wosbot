package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Objects;

import javax.imageio.ImageIO;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.OcrException;
import org.junit.jupiter.api.Test;

class PolarRewardDetailsFrameTest {

    @Test
    void recognizesExpertBonusAndSpecialRewardsInTheMeasuredInitialLayout() throws IOException, OcrException {
        RawImageData frame = rawRgba(loadFrame());

        String expertBonus = OcrEngine.recognizeText(frame,
                CommonGameAreas.POLAR_EXPERT_BONUS_HEADER.topLeft(),
                CommonGameAreas.POLAR_EXPERT_BONUS_HEADER.bottomRight(),
                CommonOCRSettings.POLAR_EXPERT_BONUS_SETTINGS);
        String specialRewards = OcrEngine.recognizeText(frame,
                CommonGameAreas.POLAR_SPECIAL_REWARDS_WITH_EXPERT_HEADER.topLeft(),
                CommonGameAreas.POLAR_SPECIAL_REWARDS_WITH_EXPERT_HEADER.bottomRight(),
                CommonOCRSettings.POLAR_SPECIAL_REWARDS_SETTINGS);

        assertTrue(normalize(expertBonus).contains("expertbonus"), () -> "Expert Bonus OCR='" + expertBonus + "'");
        assertTrue(normalize(specialRewards).matches(".*specialrewards\\(?10left.*"),
                () -> "Special Rewards OCR='" + specialRewards + "'");
    }

    @Test
    void recognizesSpecialRewardsWithoutExpertBonusInTheInitialLayout() throws IOException, OcrException {
        RawImageData frame = rawRgba(loadFrame("/polarterror/reward-details-without-expert-bonus-20261113.png"));

        String specialRewards = OcrEngine.recognizeText(frame,
                CommonGameAreas.POLAR_SPECIAL_REWARDS_WITHOUT_EXPERT_HEADER.topLeft(),
                CommonGameAreas.POLAR_SPECIAL_REWARDS_WITHOUT_EXPERT_HEADER.bottomRight(),
                CommonOCRSettings.POLAR_SPECIAL_REWARDS_SETTINGS);

        assertTrue(normalize(specialRewards).matches(".*specialrewards\\(?10left.*"),
                () -> "Special Rewards OCR='" + specialRewards + "'");
    }

    private static BufferedImage loadFrame() throws IOException {
        return loadFrame("/polarterror/reward-details-expert-bonus-20261112.png");
    }

    private static BufferedImage loadFrame(String resource) throws IOException {
        try (InputStream stream = PolarRewardDetailsFrameTest.class.getResourceAsStream(
                resource)) {
            return ImageIO.read(Objects.requireNonNull(stream, "Missing Polar Terror reward details fixture"));
        }
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static RawImageData rawRgba(BufferedImage image) {
        byte[] pixels = new byte[image.getWidth() * image.getHeight() * 4];
        for (int y = 0, offset = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++, offset += 4) {
                int argb = image.getRGB(x, y);
                pixels[offset] = (byte) (argb >> 16);
                pixels[offset + 1] = (byte) (argb >> 8);
                pixels[offset + 2] = (byte) argb;
                pixels[offset + 3] = (byte) 0xFF;
            }
        }
        return RawImageData.capture(pixels, image.getWidth(), image.getHeight(), 32);
    }
}
