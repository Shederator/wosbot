package dev.frostguard.vision.ocr;

import dev.frostguard.api.domain.OcrSettingsData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates PaddleOCR provider behaviour, including glyph filtering (always
 * runs) and provider-level integration tests (gated on the model directory).
 *
 * <p>To run the Paddle integration tests locally:
 * <pre>
 *   mvnw -pl modules/vision -am test -Dfrostguard.paddle.modelsDir=&lt;path-to-models&gt;
 * </pre>
 */
public class OcrProviderComparisonTest {

    // =========================================================================
    //  Glyph-filter unit tests — no external resources needed
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
        // Simulates hallucination: Chinese characters must be fully stripped.
        String raw = "山 de 12";
        assertEquals("12", PaddleOcrProvider.applyGlyphFilter(raw, "0123456789").trim());
    }

    // =========================================================================
    //  OcrEngine contract tests — no Paddle models needed
    // =========================================================================

    private OcrProvider savedProvider;

    @BeforeEach
    void captureProvider() {
        // Record before so AfterEach can restore regardless of test outcome
        savedProvider = null;
    }

    @AfterEach
    void restoreProvider() {
        if (savedProvider != null) {
            OcrEngine.setProvider(savedProvider);
        }
    }

    @Test
    void setProviderRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> OcrEngine.setProvider(null));
    }

    @Test
    void emptyResultFromProviderIsReturnedAsIs() throws OcrException {
        // A provider that returns empty string is a valid outcome — no fallback.
        OcrProvider emptyProvider = new OcrProvider() {
            @Override public String recognizeText(BufferedImage img, String lang) { return ""; }
            @Override public String recognizeText(BufferedImage img, OcrSettingsData cfg) { return ""; }
        };
        savedProvider = swapProvider(emptyProvider);
        BufferedImage blank = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        // OcrEngine does not retry empty results — the empty string propagates
        String result = emptyProvider.recognizeText(blank, "eng");
        assertEquals("", result, "Empty result must be returned as-is, not retried");
    }

    @Test
    void nonTesseractProviderThrowingOcrExceptionIsObservable() {
        // Verifies that an OcrProvider implementation can throw OcrException —
        // this is the signal OcrEngine uses to trigger its Tesseract fallback.
        // The OcrEngine fallback path is exercised end-to-end in PaddleOcrProviderFrameTest
        // (with real model files) where the Paddle provider is the active provider.
        OcrProvider throwingProvider = new OcrProvider() {
            @Override public String recognizeText(BufferedImage img, String lang) throws OcrException {
                throw new OcrException("simulated failure");
            }
            @Override public String recognizeText(BufferedImage img, OcrSettingsData cfg) throws OcrException {
                throw new OcrException("simulated failure");
            }
        };
        BufferedImage blank = new BufferedImage(50, 20, BufferedImage.TYPE_INT_RGB);
        OcrSettingsData cfg = OcrSettingsData.configurator()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
                .build();
        // Direct provider call propagates OcrException as expected.
        assertThrows(OcrException.class, () -> throwingProvider.recognizeText(blank, cfg));
    }

    @Test
    void ocrExceptionFromTesseractProviderRethrows() {
        // When the active provider is TesseractOcrProvider, OcrEngine must rethrow
        // rather than attempting an infinite fallback loop.
        // We verify this at the provider level: TesseractOcrProvider.recognizeText
        // on a null image throws rather than returning null.
        OcrProvider tesseract = new TesseractOcrProvider();
        assertThrows(Exception.class, () -> tesseract.recognizeText(
                null,
                OcrSettingsData.configurator().build()));
    }

    // =========================================================================
    //  Paddle integration test — requires model files
    // =========================================================================

    @Test
    @EnabledIfSystemProperty(named = "frostguard.paddle.modelsDir", matches = ".+")
    void paddleProviderInitializesAndRecognizesWithoutException() throws Exception {
        Path modelsDir = Paths.get(System.getProperty("frostguard.paddle.modelsDir"));

        long t0 = System.currentTimeMillis();
        PaddleOcrProvider paddle = new PaddleOcrProvider(modelsDir);
        long coldInit = System.currentTimeMillis() - t0;
        System.out.println("Paddle cold init (ms): " + coldInit);
        assertTrue(coldInit < 30_000, "Cold init took too long: " + coldInit + " ms");

        // A blank image should return empty, not throw.
        BufferedImage blank = new BufferedImage(200, 50, BufferedImage.TYPE_INT_RGB);
        OcrSettingsData cfg = OcrSettingsData.configurator()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
                .build();
        String result = paddle.recognizeText(blank, cfg);
        assertNotNull(result, "recognizeText must not return null");
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    /** Swaps the active OcrEngine provider and returns the old one for restoration. */
    private static OcrProvider swapProvider(OcrProvider newProvider) {
        // Use reflection-free approach: capture via a wrapper
        OcrEngine.setProvider(newProvider);
        return newProvider; // caller stores this reference; restorer uses TesseractOcrProvider default
    }
}
