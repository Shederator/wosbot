package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.ocr.OcrEngine;

class PetSkillsCooldownOcrFrameTest {

    @Test
    void readsSingleLineStaminaCooldown() throws Exception {
        BufferedImage image = loadFrame();

        String clock = OcrEngine.recognizeText(
                rgbaFrame(image),
                PetSkillsRoutine.STAMINA_COOLDOWN_OCR_AREA.topLeft(),
                PetSkillsRoutine.STAMINA_COOLDOWN_OCR_AREA.bottomRight(),
                CommonOCRSettings.RED_DURATION_SETTINGS);

        assertEquals(
                Duration.ofHours(13).plusMinutes(10).plusSeconds(32),
                GameTimeUtils.parseDuration(clock));
    }

    @Test
    void readsGatheringDayCooldownAcrossTwoLines() throws Exception {
        BufferedImage image = loadFrame();

        RawImageData frame = rgbaFrame(image);
        String timer = OcrEngine.recognizeText(
                frame,
                PetSkillsRoutine.GATHERING_COOLDOWN_OCR_AREA.topLeft(),
                PetSkillsRoutine.GATHERING_COOLDOWN_OCR_AREA.bottomRight(),
                CommonOCRSettings.RED_MULTILINE_DURATION_SETTINGS);

        assertTrue(GameTimeUtils.isAcceptedFormat(timer), () -> "Rejected timer: " + timer);
        assertEquals(
                Duration.ofDays(1).plusHours(10).plusMinutes(14).plusSeconds(57),
                GameTimeUtils.parseDuration(timer));
    }

    @Test
    void distinguishesLearnedSkillTilesFromEmptySlots() throws Exception {
        BufferedImage image = loadFrame();

        assertTrue(hasSkillTile(image, PetSkillsRoutine.PetSkill.STAMINA));
        assertTrue(hasSkillTile(image, PetSkillsRoutine.PetSkill.GATHERING));
        assertFalse(hasSkillTile(image, PetSkillsRoutine.PetSkill.FOOD));
        assertFalse(hasSkillTile(image, PetSkillsRoutine.PetSkill.TREASURE));
    }

    private boolean hasSkillTile(BufferedImage image, PetSkillsRoutine.PetSkill skill) {
        return PetSkillsRoutine.hasDistinctiveSkillTilePixels(
                image,
                new AreaData(skill.getTopLeft(), skill.getBottomRight()));
    }

    private BufferedImage loadFrame() throws Exception {
        return ImageIO.read(Objects.requireNonNull(
                getClass().getResourceAsStream("/pets/pet-skill-day-cooldown-20260810.png")));
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
