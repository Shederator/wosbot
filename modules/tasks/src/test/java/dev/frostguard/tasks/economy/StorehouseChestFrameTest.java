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
    private static final double SEARCH_THRESHOLD = 90;
    private static final Duration VISIBLE_COUNTDOWN = Duration.ofHours(1).plusMinutes(1).plusSeconds(2);

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
        assertTrue(matches(TemplatesEnum.STOREHOUSE_STAMINA));
    }

    @Test
    void rejectsBothChestTemplatesOnTheStaminaCan() throws IOException {
        assertFalse(matches(TemplatesEnum.STOREHOUSE_CHEST));
        assertFalse(matches(TemplatesEnum.STOREHOUSE_CHEST_2));
    }

    @Test
    void readsTheGreenBuildingCountdown() throws Exception {
        String clock = OcrEngine.recognizeText(
                rgbaFrame(loadFrame()),
                StorehouseChestRoutine.FALLBACK_TIMER_TOP_LEFT,
                StorehouseChestRoutine.FALLBACK_TIMER_BOTTOM_RIGHT,
                StorehouseChestRoutine.buildingCountdownSettings());

        assertEquals(VISIBLE_COUNTDOWN, GameTimeUtils.parseDuration(clock));
    }

    @Test
    void whiteIsolationDoesNotReadTheBuildingCountdown() throws Exception {
        OcrSettingsData white = OcrSettingsData.assembler()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
                .stripBackground(true)
                .setTextColor(Color.WHITE)
                .charWhitelist("0123456789:")
                .build();

        String clock = OcrEngine.recognizeText(
                rgbaFrame(loadFrame()),
                StorehouseChestRoutine.FALLBACK_TIMER_TOP_LEFT,
                StorehouseChestRoutine.FALLBACK_TIMER_BOTTOM_RIGHT,
                white);

        assertFalse(GameTimeUtils.isAcceptedFormat(clock), () -> "White isolation read: " + clock);
    }

    private boolean matches(TemplatesEnum template) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/storehouse/city-can-visible.png")) {
            byte[] encoded = Objects.requireNonNull(stream, "Missing storehouse frame").readAllBytes();
            return OpenCvPatternLocator.locatePattern(
                    encoded, template, SCREEN_ORIGIN, SCREEN_LIMIT, SEARCH_THRESHOLD).isFound();
        }
    }

    private BufferedImage loadFrame() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/storehouse/city-can-visible.png")) {
            return ImageIO.read(Objects.requireNonNull(stream, "Missing storehouse frame"));
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
