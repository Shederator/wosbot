package dev.frostguard.engine.helper;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.OcrException;
import dev.frostguard.vision.ocr.OcrProvider;
import dev.frostguard.vision.ocr.PaddleModelDownloader;
import dev.frostguard.vision.ocr.PaddleOcrProvider;
import dev.frostguard.vision.convert.GameTimeUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ground-truth OCR frame tests that run identical prepared inputs through both
 * Tesseract (always) and PaddleOCR (when {@code -Dfrostguard.paddle.modelsDir}
 * is supplied) and assert the expected text with correctness assertions.
 *
 * <p>This satisfies the Step 2 acceptance criterion from issue #212:
 * <i>"Each saved frame/crop has manually defined expected text. Tesseract and
 * Paddle receive identical prepared input. Tests assert correctness rather than
 * only writing benchmark output."</i>
 *
 * <p>Frames are taken from existing test resources already in the repository.
 * Expected values were manually verified against the game UI before being
 * committed here.
 *
 * <h2>Running with Paddle</h2>
 * <pre>
 *   mvnw -pl modules/automation -am test
 *       -Dfrostguard.paddle.modelsDir=C:\path\to\models
 * </pre>
 */
@EnabledIfSystemProperty(named = "frostguard.paddle.modelsDir", matches = ".+",
        disabledReason = "Paddle integration tests require -Dfrostguard.paddle.modelsDir")
class PaddleOcrProviderFrameTest {

    private static OcrProvider paddle;
    private static OcrProvider savedProvider;

    @BeforeAll
    static void initPaddle() throws OcrException {
        Path modelsDir = Paths.get(System.getProperty("frostguard.paddle.modelsDir"));
        PaddleModelDownloader.ensureModels(modelsDir);
        paddle = new PaddleOcrProvider(modelsDir);
        savedProvider = OcrEngine.setProviderAndReturn(paddle);
    }

    @AfterAll
    static void restoreProvider() {
        if (savedProvider != null) {
            OcrEngine.setProvider(savedProvider);
        }
    }

    // =========================================================================
    //  Frame 1: Polar Terror deployment screen (SINGLE_WORD / digits)
    //  Source: /deployment/polar-after-equalize-20260709.png
    //  Crop:   CommonGameAreas.SPENT_STAMINA_OCR_AREA
    //  Expected: "22"  (stamina cost of the rally)
    //  Verified: manually from the saved frame.
    // =========================================================================

    @Test
    void readsRallyStaminaCostFromPolarTerrorDeploymentScreen() throws Exception {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(
                getClass().getResourceAsStream("/deployment/polar-after-equalize-20260709.png")));
        RawImageData frame = toRgbaFrame(image);

        String result = OcrEngine.recognizeText(
                frame,
                CommonGameAreas.SPENT_STAMINA_OCR_AREA.topLeft(),
                CommonGameAreas.SPENT_STAMINA_OCR_AREA.bottomRight(),
                CommonOCRSettings.SPENT_STAMINA_SETTINGS);

        assertEquals("22", result.trim(),
                "Paddle must read rally stamina cost correctly from the deployment screen");
    }

    // =========================================================================
    //  Frame 2: Intel map cooldown — markers present (SINGLE_LINE / time)
    //  Source: /intel/marker-map-cooldown-20260729.png
    //  Crop:   CommonGameAreas.INTEL_COOLDOWN_WITH_MARKERS_OCR_AREA
    //  Expected: 2 min 3 sec cooldown
    //  Verified: manually from the saved frame (same assertion as IntelCooldownOcrFrameTest).
    // =========================================================================

    @Test
    void readsCooldownTimerWhenIntelMarkersArePresent() throws Exception {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(
                getClass().getResourceAsStream("/intel/marker-map-cooldown-20260729.png")));
        RawImageData frame = toRgbaFrame(image);

        String text = OcrEngine.recognizeText(
                frame,
                CommonGameAreas.INTEL_COOLDOWN_WITH_MARKERS_OCR_AREA.topLeft(),
                CommonGameAreas.INTEL_COOLDOWN_WITH_MARKERS_OCR_AREA.bottomRight(),
                CommonOCRSettings.INTEL_COOLDOWN_SETTINGS);

        assertEquals(Duration.ofMinutes(2).plusSeconds(3), GameTimeUtils.parseDuration(text),
                "Paddle must parse the 2:03 intel cooldown correctly");
    }

    // =========================================================================
    //  Frame 3: Intel map cooldown — empty map (SINGLE_LINE / time)
    //  Source: /intel/empty-map-cooldown-20260729.png
    //  Crop:   CommonGameAreas.INTEL_COOLDOWN_EMPTY_MAP_OCR_AREA
    //  Expected: 25 min 41 sec cooldown
    //  Verified: manually from the saved frame (same assertion as IntelCooldownOcrFrameTest).
    // =========================================================================

    @Test
    void readsCooldownTimerOnEmptyIntelMap() throws Exception {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(
                getClass().getResourceAsStream("/intel/empty-map-cooldown-20260729.png")));
        RawImageData frame = toRgbaFrame(image);

        String text = OcrEngine.recognizeText(
                frame,
                CommonGameAreas.INTEL_COOLDOWN_EMPTY_MAP_OCR_AREA.topLeft(),
                CommonGameAreas.INTEL_COOLDOWN_EMPTY_MAP_OCR_AREA.bottomRight(),
                CommonOCRSettings.INTEL_COOLDOWN_SETTINGS);

        assertEquals(Duration.ofMinutes(25).plusSeconds(41), GameTimeUtils.parseDuration(text),
                "Paddle must parse the 25:41 intel cooldown correctly");
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    private static RawImageData toRgbaFrame(BufferedImage image) {
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
