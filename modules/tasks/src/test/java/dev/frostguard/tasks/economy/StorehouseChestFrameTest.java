package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.vision.ocr.OcrEngine;

class StorehouseChestFrameTest {

    private static final PointData SCREEN_ORIGIN = new PointData(0, 0);
    private static final PointData SCREEN_LIMIT = new PointData(720, 1280);
    private static final double STAMINA_SEARCH_THRESHOLD = 90;
    private static final Duration VISIBLE_COUNTDOWN = Duration.ofHours(1).plusMinutes(1).plusSeconds(2);
    private static final Duration WHITE_PILL_COUNTDOWN = Duration.ofMinutes(15).plusSeconds(58);
    private static final Duration CONSTRUCTION_COUNTDOWN = Duration.ofDays(3)
            .plusHours(3)
            .plusMinutes(53)
            .plusSeconds(22);

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded the native library in this JVM.
        }
    }

    @Test
    void detectsTheVisibleStaminaCan() throws IOException {
        assertTrue(matches("city-can-visible.png", TemplatesEnum.STOREHOUSE_STAMINA, STAMINA_SEARCH_THRESHOLD));
        assertTrue(matches("construction-stamina-3d.png", TemplatesEnum.STOREHOUSE_STAMINA, STAMINA_SEARCH_THRESHOLD));
    }

    @Test
    void rejectsBothChestTemplatesOnTheStaminaCan() throws IOException {
        assertFalse(matches("city-can-visible.png", TemplatesEnum.STOREHOUSE_CHEST,
                StorehouseChestRoutine.CHEST_SEARCH_THRESHOLD));
        assertFalse(matches("city-can-visible.png", TemplatesEnum.STOREHOUSE_CHEST_2,
                StorehouseChestRoutine.CHEST_SEARCH_THRESHOLD));
        assertFalse(matches("construction-stamina-3d.png", TemplatesEnum.STOREHOUSE_CHEST,
                StorehouseChestRoutine.CHEST_SEARCH_THRESHOLD));
        assertFalse(matches("construction-stamina-3d.png", TemplatesEnum.STOREHOUSE_CHEST_2,
                StorehouseChestRoutine.CHEST_SEARCH_THRESHOLD));
    }

    @Test
    void detectsTheNightCrateBelowTheDefaultNinetyCut() throws IOException {
        assertTrue(matches("night-crate-ready.png", TemplatesEnum.STOREHOUSE_CHEST,
                StorehouseChestRoutine.CHEST_SEARCH_THRESHOLD));
        assertTrue(matches("night-crate-ready.png", TemplatesEnum.STOREHOUSE_CHEST_2,
                StorehouseChestRoutine.CHEST_SEARCH_THRESHOLD));
        assertFalse(matches("night-crate-ready.png", TemplatesEnum.STOREHOUSE_CHEST, STAMINA_SEARCH_THRESHOLD));
        assertFalse(matches("night-crate-ready.png", TemplatesEnum.STOREHOUSE_CHEST_2, STAMINA_SEARCH_THRESHOLD));
        assertFalse(matches("night-crate-ready.png", TemplatesEnum.STOREHOUSE_STAMINA, STAMINA_SEARCH_THRESHOLD));
    }

    @Test
    void rejectsChestAndStaminaOnACooldownPill() throws IOException {
        assertFalse(matches("cooldown-white-pill.png", TemplatesEnum.STOREHOUSE_CHEST,
                StorehouseChestRoutine.CHEST_SEARCH_THRESHOLD));
        assertFalse(matches("cooldown-white-pill.png", TemplatesEnum.STOREHOUSE_CHEST_2,
                StorehouseChestRoutine.CHEST_SEARCH_THRESHOLD));
        assertFalse(matches("cooldown-white-pill.png", TemplatesEnum.STOREHOUSE_STAMINA, STAMINA_SEARCH_THRESHOLD));
    }

    @Test
    void readsTheGreenBuildingCountdown() throws Exception {
        String clock = recognize("city-can-visible.png", StorehouseChestRoutine.buildingCountdownSettings());

        assertEquals(VISIBLE_COUNTDOWN, GameTimeUtils.parseDuration(clock));
    }

    @Test
    void whiteIsolationDoesNotReadTheGreenBuildingCountdown() throws Exception {
        OcrSettingsData whiteWithoutDay = OcrSettingsData.assembler()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
                .stripBackground(true)
                .setTextColor(Color.WHITE)
                .charWhitelist("0123456789:")
                .build();

        String clock = recognize("city-can-visible.png", whiteWithoutDay);

        assertFalse(GameTimeUtils.isAcceptedFormat(clock), () -> "White isolation read: " + clock);
        assertFalse(GameTimeUtils.isAcceptedFormat(
                recognize("city-can-visible.png", StorehouseChestRoutine.buildingCountdownWhiteSettings())));
    }

    @Test
    void readsTheWhiteCooldownPill() throws Exception {
        String clock = recognize("cooldown-white-pill.png", StorehouseChestRoutine.buildingCountdownWhiteSettings());

        assertEquals(WHITE_PILL_COUNTDOWN, GameTimeUtils.parseDuration(clock));
        assertFalse(GameTimeUtils.isAcceptedFormat(
                recognize("cooldown-white-pill.png", StorehouseChestRoutine.buildingCountdownSettings())));
    }

    @Test
    void readsTheConstructionDayQualifierWithWhiteIsolation() throws Exception {
        String clock = recognize("construction-stamina-3d.png",
                StorehouseChestRoutine.buildingCountdownWhiteSettings());

        assertEquals(CONSTRUCTION_COUNTDOWN, GameTimeUtils.parseDuration(clock));
    }

    private boolean matches(String frame, TemplatesEnum template, double threshold) throws IOException {
        return OpenCvPatternLocator.locatePattern(
                encoded(frame), template, SCREEN_ORIGIN, SCREEN_LIMIT, threshold).isFound();
    }

    private String recognize(String frame, OcrSettingsData settings) throws Exception {
        return OcrEngine.recognizeText(
                rgbaFrame(loadFrame(frame)),
                StorehouseChestRoutine.FALLBACK_TIMER_TOP_LEFT,
                StorehouseChestRoutine.FALLBACK_TIMER_BOTTOM_RIGHT,
                settings);
    }

    private byte[] encoded(String frame) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/storehouse/" + frame)) {
            return Objects.requireNonNull(stream, "Missing storehouse frame: " + frame).readAllBytes();
        }
    }

    private BufferedImage loadFrame(String frame) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/storehouse/" + frame)) {
            return ImageIO.read(Objects.requireNonNull(stream, "Missing storehouse frame: " + frame));
        }
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
