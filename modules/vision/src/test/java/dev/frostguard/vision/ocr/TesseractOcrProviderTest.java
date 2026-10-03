package dev.frostguard.vision.ocr;

import dev.frostguard.api.domain.OcrSettingsData;
import net.sourceforge.tess4j.Tesseract;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Behavioural contract for multilingual OCR support.
 *
 * <p>Two properties matter, and both are here because getting them wrong fails silently rather than
 * loudly:
 * <ul>
 *   <li>An explicitly requested but unsupported language (say "ara" or "rus") must FAIL rather than
 *       quietly downgrade to "eng". A downgrade does not produce an error; it produces plausible
 *       Latin guesses at non-Latin glyphs -- output that reads as a successful recognition and is
 *       simply wrong.</li>
 *   <li>The contract is exercised end to end rather than against the settings object: the resolved
 *       language actually reaching the Tesseract engine, the packaged chi_sim model being genuinely
 *       usable rather than merely present, what preserveLineBreaks does to real recognition output,
 *       and LF/CRLF normalisation.</li>
 * </ul>
 */
class TesseractOcrProviderTest {

    // ------------------------------------------------------------------
    // resolveSupportedLanguage
    // ------------------------------------------------------------------

    @Test
    void noExplicitRequestFallsBackToEnglish() throws OcrException {
        // null/blank means the caller never asked for a language at all -- every pre-existing
        // call site before multilingual support existed. Must stay unaffected.
        assertEquals("eng", TesseractOcrProvider.resolveSupportedLanguage(null));
        assertEquals("eng", TesseractOcrProvider.resolveSupportedLanguage(""));
        assertEquals("eng", TesseractOcrProvider.resolveSupportedLanguage("   "));
    }

    @Test
    void packagedLanguagesPassThroughUnchanged() throws OcrException {
        assertEquals("eng", TesseractOcrProvider.resolveSupportedLanguage("eng"));
        assertEquals("chi_sim", TesseractOcrProvider.resolveSupportedLanguage("chi_sim"));
        assertEquals("eng+chi_sim", TesseractOcrProvider.resolveSupportedLanguage("eng+chi_sim"));
    }

    @Test
    void explicitlyRequestingAnUnpackagedLanguageFailsLoudlyInsteadOfSilentlyUsingEnglish() {
        // Arabic/Russian/Portuguese/Czech/French were selectable in config despite no trained-data
        // model ever being packaged for them. Silently substituting "eng" here used to return
        // confident-looking garbage for genuinely non-Latin text instead of an honest failure.
        UnsupportedOcrLanguageException ara = assertThrows(UnsupportedOcrLanguageException.class,
                () -> TesseractOcrProvider.resolveSupportedLanguage("ara"));
        assertEquals(List.of("ara"), ara.getRequestedUnsupported());

        UnsupportedOcrLanguageException rus = assertThrows(UnsupportedOcrLanguageException.class,
                () -> TesseractOcrProvider.resolveSupportedLanguage("rus"));
        assertEquals(List.of("rus"), rus.getRequestedUnsupported());
    }

    @Test
    void mixedRequestFailsOnTheUnsupportedComponentRatherThanSilentlyDroppingIt() {
        // A caller combining "eng+ara" explicitly wants Arabic read too -- silently returning
        // eng-only results without ever saying so would look like success while quietly failing
        // half the request.
        UnsupportedOcrLanguageException ex = assertThrows(UnsupportedOcrLanguageException.class,
                () -> TesseractOcrProvider.resolveSupportedLanguage("eng+ara"));
        assertEquals(List.of("ara"), ex.getRequestedUnsupported());
    }

    @Test
    void requestWithOnlyUnsupportedLanguagesFailsListingAllOfThem() {
        UnsupportedOcrLanguageException ex = assertThrows(UnsupportedOcrLanguageException.class,
                () -> TesseractOcrProvider.resolveSupportedLanguage("ara+rus"));
        assertEquals(List.of("ara", "rus"), ex.getRequestedUnsupported());
    }

    // ------------------------------------------------------------------
    // configureTesseract -- proves the resolved language actually reaches the engine, not just
    // that resolveSupportedLanguage's return value looks right in isolation.
    // ------------------------------------------------------------------

    @Test
    void resolvedLanguageIsActuallyAppliedToTheRealTesseractEngine() throws Exception {
        OcrSettingsData cfg = OcrSettingsData.configurator().language("chi_sim").build();

        Tesseract engine = TesseractOcrProvider.configureTesseract(cfg);

        // Tesseract (tess4j) exposes no public getter for the language it was configured with --
        // reflection on its private field is the only way to prove the setter was actually called
        // with OUR resolved value, not just that resolveSupportedLanguage() returns the right
        // string when called on its own.
        Field languageField = Tesseract.class.getDeclaredField("language");
        languageField.setAccessible(true);
        assertEquals("chi_sim", languageField.get(engine));
    }

    @Test
    void configureTesseractPropagatesTheUnsupportedLanguageFailure() {
        OcrSettingsData cfg = OcrSettingsData.configurator().language("ara").build();

        assertThrows(UnsupportedOcrLanguageException.class,
                () -> TesseractOcrProvider.configureTesseract(cfg));
    }

    // ------------------------------------------------------------------
    // The packaged chi_sim model is genuinely usable, not just present on disk.
    // ------------------------------------------------------------------

    @Test
    void chiSimTrainedDataModelLoadsAndRunsARealRecognitionPassWithoutError() {
        // The floor: tessdata lookup, native library load, chi_sim.traineddata load and a real
        // inference pass all complete. Runs anywhere, including a runner with no CJK font.
        OcrSettingsData cfg = OcrSettingsData.configurator().language("chi_sim").build();
        BufferedImage image = renderText(new String[]{"12345"}, 300, 80);

        assertDoesNotThrow(() -> new TesseractOcrProvider().recognizeText(image, cfg));
    }

    @Test
    void chiSimActuallyRecognisesChineseRatherThanGuessingLatinAtIt() throws OcrException {
        // The property that matters, and the one the test above does not reach: a model that loads
        // is not a model that reads. The failure being guarded is silent -- English pointed at
        // Chinese returns plausible Latin glyphs, so whether Chinese characters come back at all is
        // the only way to tell a working configuration from a broken one.
        //
        // Rendered rather than captured, deliberately. A real chat frame carries player names and
        // alliance tags and would need redacting to ship, and redacting the text is redacting the
        // thing under test. Synthesised glyphs are the same characters with none of that.
        Font cjk = firstFontThatCanDisplay(CHINESE_SAMPLE);
        assumeTrue(cjk != null,
                "no CJK-capable font here, so genuine Chinese cannot be rendered to read back");

        BufferedImage image = renderText(new String[]{CHINESE_SAMPLE}, 360, 110, cjk.deriveFont(Font.PLAIN, 48f));
        OcrSettingsData cfg = OcrSettingsData.configurator().language("chi_sim").build();

        String recognized = new TesseractOcrProvider().recognizeText(image, cfg);

        // Asserted by script rather than exact string. Per-glyph accuracy varies with font and
        // rendering, but a Latin-only configuration cannot emit a CJK codepoint at all, so its
        // presence is the discriminating signal and does not go flaky on the font.
        assertTrue(recognized.codePoints().anyMatch(TesseractOcrProviderTest::isCjk),
                "chi_sim should return Chinese for Chinese input, got: '" + recognized + "'");
    }

    @Test
    void englishModelPointedAtChineseReturnsNoChinese() throws OcrException {
        // The other half of the same claim, and what the original bug looked like: same image,
        // "eng", and nothing Chinese comes back -- just Latin guesswork that reads like a success.
        // Without this, the test above could pass on a provider that ignored the language entirely
        // and happened to default somewhere useful.
        Font cjk = firstFontThatCanDisplay(CHINESE_SAMPLE);
        assumeTrue(cjk != null, "no CJK-capable font here");

        BufferedImage image = renderText(new String[]{CHINESE_SAMPLE}, 360, 110, cjk.deriveFont(Font.PLAIN, 48f));
        OcrSettingsData cfg = OcrSettingsData.configurator().language("eng").build();

        String recognized = new TesseractOcrProvider().recognizeText(image, cfg);

        assertFalse(recognized.codePoints().anyMatch(TesseractOcrProviderTest::isCjk),
                "the English model cannot produce Chinese; if it did, the language never reached the engine: '"
                        + recognized + "'");
    }

    /** "Chinese chat" -- ordinary characters, no player data, nothing to redact. */
    private static final String CHINESE_SAMPLE = "中文聊天";

    private static boolean isCjk(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    /** The first installed font able to render every character of {@code text}, or null. */
    private static Font firstFontThatCanDisplay(String text) {
        for (Font font : GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts()) {
            if (font.canDisplayUpTo(text) == -1) {
                return font;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // preserveLineBreaks -- real effect on real recognition output, not just a flag being read.
    // ------------------------------------------------------------------

    @Test
    void preserveLineBreaksKeepsMultipleLinesSeparateInRealRecognitionOutput() throws OcrException {
        BufferedImage image = renderText(new String[]{"first line", "second line"}, 400, 140);
        OcrSettingsData cfg = OcrSettingsData.configurator()
                .textLayout(OcrSettingsData.TextLayout.TEXT_BLOCK)
                .preserveLineBreaks(true)
                .build();

        String recognized = new TesseractOcrProvider().recognizeText(image, cfg);

        assertTrue(recognized.contains("\n"),
                "Two visually separate lines should still be two lines in the output: '" + recognized + "'");
    }

    @Test
    void defaultBehaviorFlattensMultipleLinesIntoOne() throws OcrException {
        BufferedImage image = renderText(new String[]{"first line", "second line"}, 400, 140);
        OcrSettingsData cfg = OcrSettingsData.configurator()
                .textLayout(OcrSettingsData.TextLayout.TEXT_BLOCK)
                .preserveLineBreaks(false)
                .build();

        String recognized = new TesseractOcrProvider().recognizeText(image, cfg);

        assertFalse(recognized.contains("\n"),
                "Without preserveLineBreaks the result should be flattened to one line: '" + recognized + "'");
    }

    // ------------------------------------------------------------------
    // LF/CRLF normalization -- pure string transforms, no engine/image needed.
    // ------------------------------------------------------------------

    @Test
    void normalizeSingleLineDropsEveryLineEndingStyle() {
        assertEquals("ab", TesseractOcrProvider.normalizeSingleLine("a\nb"));
        assertEquals("ab", TesseractOcrProvider.normalizeSingleLine("a\r\nb"));
        assertEquals("ab", TesseractOcrProvider.normalizeSingleLine("a\rb"));
        assertEquals("hello", TesseractOcrProvider.normalizeSingleLine("  hello  \n"));
    }

    @Test
    void normalizeMultilineStripsCrSoWindowsLineEndingsCollapseToPlainLf() {
        // Tesseract emits plain \n on this platform; \r\n only shows up if something upstream
        // (or the OS pipe) reintroduces it. Stripping \r turns \r\n into a clean \n rather than
        // leaving a stray \r riding along on every line.
        assertEquals("a\nb", TesseractOcrProvider.normalizeMultiline("a\r\nb"));
        assertEquals("a\nb", TesseractOcrProvider.normalizeMultiline("a\nb"));
        assertEquals("ab", TesseractOcrProvider.normalizeMultiline("a\rb")); // bare \r alone isn't a line break, just noise -- dropped
        assertEquals("a\nb", TesseractOcrProvider.normalizeMultiline("  a\nb  "));
    }

    // ------------------------------------------------------------------

    /** Renders plain black text on a white background -- real Latin glyphs, guaranteed available
     *  on every JDK/CI image (unlike CJK fonts), for genuine end-to-end recognition tests. */
    private static BufferedImage renderText(String[] lines, int width, int height) {
        return renderText(lines, width, height, new Font("SansSerif", Font.PLAIN, 28));
    }

    /** Same, with an explicit font, so a CJK-capable face can be supplied when one exists. */
    private static BufferedImage renderText(String[] lines, int width, int height, Font font) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        g.setFont(font);
        int y = 40;
        for (String line : lines) {
            g.drawString(line, 15, y);
            y += 45;
        }
        g.dispose();
        return image;
    }
}
