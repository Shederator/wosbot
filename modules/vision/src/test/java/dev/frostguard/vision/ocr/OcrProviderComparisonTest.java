package dev.frostguard.vision.ocr;

import dev.frostguard.api.domain.OcrSettingsData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates PaddleOCR components that can run without a live emulator.
 *
 * <p>Provider-level tests require the model files to be present under
 * {@code modules/desktop/tools/paddle/} and are gated behind the system
 * property {@code frostguard.paddle.modelsDir}. Pass {@code -Dfrostguard.paddle.modelsDir=<path>}
 * when running locally; CI skips these tests automatically.
 */
public class OcrProviderComparisonTest {

    // =========================================================================
    //  Unit tests — no external resources needed
    // =========================================================================

    @Test
    void glyphFilterKeepsAllowedCharacters() {
        assertEquals("12345", PaddleOcrProvider.applyGlyphFilter("12345", "0123456789"));
    }

    @Test
    void glyphFilterRemovesDisallowedCharacters() {
        assertEquals("123", PaddleOcrProvider.applyGlyphFilter("1a2b3c", "0123456789"));
    }

    @Test
    void glyphFilterPassesThroughWhenNoWhitelist() {
        String input = "Hello World 123";
        assertEquals(input, PaddleOcrProvider.applyGlyphFilter(input, null));
        assertEquals(input, PaddleOcrProvider.applyGlyphFilter(input, ""));
    }

    @Test
    void glyphFilterHandlesEmptyInput() {
        assertEquals("", PaddleOcrProvider.applyGlyphFilter("", "0123456789"));
    }

    @Test
    void glyphFilterHandlesChineseCharactersWithEnglishWhitelist() {
        // Simulates the hallucination scenario: Chinese characters must be fully stripped.
        String raw = "山 de 12";
        assertEquals("12", PaddleOcrProvider.applyGlyphFilter(raw, "0123456789").trim());
    }

    // =========================================================================
    //  Integration test — requires Paddle model files
    // =========================================================================

    /**
     * Verifies PaddleOCR cold-init time and basic recognition on a synthetic image.
     * Only runs when {@code -Dfrostguard.paddle.modelsDir=<path>} is supplied.
     */
    @Test
    @EnabledIfSystemProperty(named = "frostguard.paddle.modelsDir", matches = ".+")
    void paddleProviderInitializesAndRecognizes() throws Exception {
        String modelsDirProp = System.getProperty("frostguard.paddle.modelsDir");
        Path modelsDir = Paths.get(modelsDirProp);

        long t0 = System.currentTimeMillis();
        PaddleOcrProvider paddle = new PaddleOcrProvider(modelsDir);
        long coldInit = System.currentTimeMillis() - t0;
        System.out.println("Paddle cold init (ms): " + coldInit);
        assertTrue(coldInit < 30_000, "Cold init took too long: " + coldInit + " ms");

        // Create a small white image — should return empty, not throw
        BufferedImage blankImage = new BufferedImage(200, 50, BufferedImage.TYPE_INT_RGB);
        OcrSettingsData cfg = OcrSettingsData.configurator()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
                .build();

        String result = paddle.recognizeText(blankImage, cfg);
        assertNotNull(result, "recognizeText must not return null");
    }
}
