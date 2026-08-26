package dev.frostguard.vision.ocr;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides text recognition from pre-processed images using Tesseract.
 *
 * <p>The tessdata directory is located once and then cached for the lifetime
 * of the JVM.
 */
public final class TesseractOcrProvider implements OcrProvider {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcrProvider.class);

    /** Lazily resolved, then reused for every subsequent call. */
    private static volatile String resolvedTessdataDir;

    // =====================================================================
    //  Public entry points
    // =====================================================================

    @Override
    public String recognizeText(BufferedImage preparedImage, String lang) throws OcrException {
        long t0 = System.currentTimeMillis();
        log.debug("=== Recognition Started ===");

        requireValidCapture(preparedImage);

        long step = System.currentTimeMillis();
        Tesseract engine = configureTesseract(lang);
        log.debug("Engine config: {} ms", System.currentTimeMillis() - step);

        step = System.currentTimeMillis();
        String recognised = executeRecognition(engine, preparedImage);
        log.debug("Engine execution: {} ms", System.currentTimeMillis() - step);

        log.debug("=== Recognition Finished === elapsed={} ms, text='{}'",
                System.currentTimeMillis() - t0, recognised);
        return recognised;
    }

    @Override
    public String recognizeText(BufferedImage preparedImage, OcrSettingsData cfg) throws OcrException {
        long t0 = System.currentTimeMillis();
        log.debug("=== Recognition Started ===");

        requireValidCapture(preparedImage);

        long step = System.currentTimeMillis();
        Tesseract engine = configureTesseract(cfg); // throws UnsupportedOcrLanguageException for an explicit, unsatisfiable language request
        log.debug("Engine config: {} ms", System.currentTimeMillis() - step);

        step = System.currentTimeMillis();
        String recognised = cfg.preserveLineBreaks()
                ? executeRecognitionMultiline(engine, preparedImage)
                : executeRecognition(engine, preparedImage);
        log.debug("Engine execution: {} ms", System.currentTimeMillis() - step);

        log.debug("=== Recognition Finished === elapsed={} ms, text='{}'",
                System.currentTimeMillis() - t0, recognised);
        return recognised;
    }

    // =====================================================================
    //  Tesseract factory
    // =====================================================================

    private static Tesseract configureTesseract(String lang) {
        Tesseract t = new Tesseract();
        t.setDatapath(locateTessdata());
        t.setLanguage(lang);
        t.setConfigs(Collections.singletonList("quiet"));
        t.setPageSegMode(7); // SINGLE_LINE, matching the established default path
        t.setOcrEngineMode(1); // LSTM_ONLY
        return t;
    }

    /**
     * Builds an engine whose behaviour is controlled by {@code cfg}.
     *
     * <p>Language used to be hardcoded to "eng" here regardless of what any caller configured -
     * fine for HUD numbers, but silently corrupted anything non-Latin-script (chat is genuinely
     * multilingual). Honours {@link OcrSettingsData#language()}, falling back to "eng" so every
     * pre-existing caller (which never sets a language at all) is unaffected.
     *
     * <p>Only "eng" and "chi_sim" trained-data models are actually packaged with the app (see
     * {@link #SUPPORTED_LANGUAGES}). {@link #resolveSupportedLanguage} validates an explicit
     * request against what's genuinely available and throws {@link UnsupportedOcrLanguageException}
     * -- via this method's {@code throws OcrException} -- rather than silently substituting "eng":
     * running an unsupported script through the English model doesn't fail visibly, it produces
     * plausible-but-wrong glyph guesses, which is worse than an honest error for a caller that
     * asked for a specific language on purpose.
     *
     * <p>Package-private (not {@code private}) specifically so tests can inspect the {@link
     * Tesseract} instance this method configures -- see {@code TesseractOcrProviderTest} -- rather
     * than only asserting on {@link #resolveSupportedLanguage}'s return value in isolation.
     */
    static Tesseract configureTesseract(OcrSettingsData cfg) throws OcrException {
        Tesseract t = new Tesseract();
        t.setDatapath(locateTessdata());
        t.setLanguage(resolveSupportedLanguage(cfg.language()));
        t.setConfigs(Collections.singletonList("quiet"));

        if (cfg.hasTextLayout()) {
            t.setPageSegMode(mapTextLayout(cfg.textLayout()));
        } else {
            t.setPageSegMode(3); // AUTO
        }

        t.setOcrEngineMode(1); // LSTM_ONLY -- the only mode this app has ever shipped with

        if (cfg.hasAllowedChars()) {
            t.setVariable("tessedit_char_whitelist", cfg.getAllowedChars());
        }
        return t;
    }

    /** Trained-data models actually packaged with the app (see tools/tesseract/*.traineddata).
     *  "osd" (orientation/script detection) is packaged too but is never a valid recognition
     *  language on its own, so it's deliberately excluded here. */
    private static final java.util.Set<String> SUPPORTED_LANGUAGES = java.util.Set.of("eng", "chi_sim");

    /**
     * Validates a requested Tesseract language string (which may combine multiple languages with
     * "+", e.g. "eng+chi_sim") against what's actually packaged.
     *
     * <p>{@code null}/blank means the caller never asked for a language at all -- every
     * pre-existing call site before multilingual support existed -- and safely defaults to "eng",
     * unaffected. A non-blank request with ANY unsupported component is a caller explicitly
     * asking this app to read a script it cannot: that fails loudly via {@link
     * UnsupportedOcrLanguageException} instead of silently downgrading to English, which would
     * otherwise return confident-looking garbage for genuinely non-Latin text.
     */
    static String resolveSupportedLanguage(String requested) throws UnsupportedOcrLanguageException {
        if (requested == null || requested.isBlank()) {
            return "eng";
        }
        List<String> validated = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        for (String part : requested.split("\\+")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            if (SUPPORTED_LANGUAGES.contains(trimmed)) {
                validated.add(trimmed);
            } else {
                unsupported.add(trimmed);
            }
        }
        if (!unsupported.isEmpty()) {
            throw new UnsupportedOcrLanguageException(unsupported, SUPPORTED_LANGUAGES);
        }
        if (validated.isEmpty()) {
            // Requested something like "+" or all-whitespace components -- not a real language
            // request either way, so treat it the same as no request at all.
            return "eng";
        }
        return String.join("+", validated);
    }

    private static int mapTextLayout(TextLayout layout) {
        return switch (layout) {
            case SINGLE_LINE -> 7; // PSM_SINGLE_LINE
            case SINGLE_WORD -> 8; // PSM_SINGLE_WORD
            case TEXT_BLOCK  -> 6; // PSM_UNIFORM_BLOCK
            case SPARSE      -> 11; // PSM_SPARSE
            case AUTO        -> 3; // PSM_AUTO
            default          -> 3;
        };
    }

    /** Runs the engine and strips whitespace / line breaks. */
    private static String executeRecognition(Tesseract engine, BufferedImage img)
            throws OcrException {
        try {
            return normalizeSingleLine(engine.doOCR(img));
        } catch (TesseractException e) {
            throw new OcrException("Tesseract OCR failed", e);
        }
    }

    /**
     * Same as {@link #executeRecognition}, but keeps line breaks intact.
     *
     * <p>Multi-message reads (chat) NEED the line structure Tesseract already produces -
     * flattening was silently destroying it, a real reason chat capture was coming back as one
     * giant run-on blob instead of one line per message.
     */
    private static String executeRecognitionMultiline(Tesseract engine, BufferedImage img)
            throws OcrException {
        try {
            return normalizeMultiline(engine.doOCR(img));
        } catch (TesseractException e) {
            throw new OcrException("Tesseract OCR failed", e);
        }
    }

    /**
     * Flattens Tesseract's raw output to a single line: both {@code \n} and {@code \r} (so
     * Windows {@code \r\n}, bare {@code \r}, and bare {@code \n} line endings are all handled
     * identically) are dropped, then the result is trimmed. Split out from {@link
     * #executeRecognition} as a pure string transform -- no engine or image needed -- so it's
     * directly testable with literal strings instead of only reachable through a real OCR run.
     */
    static String normalizeSingleLine(String raw) {
        return raw.replace("\n", "").replace("\r", "").trim();
    }

    /**
     * Keeps line breaks but strips {@code \r} so a Windows {@code \r\n} collapses cleanly to the
     * {@code \n} Tesseract already emits on this platform -- downstream line-splitting (one chat
     * message per line) never sees a stray {@code \r} riding along with it. Split out from {@link
     * #executeRecognitionMultiline} for the same testability reason as {@link
     * #normalizeSingleLine}.
     */
    static String normalizeMultiline(String raw) {
        return raw.replace("\r", "").trim();
    }

    // =====================================================================
    //  Tessdata resolution
    // =====================================================================

    /**
     * Walks upward from the working directory looking for a
     * {@code lib/tesseract} or {@code tools/tesseract} folder that
     * contains at least one {@code .traineddata} file.
     */
    private static String locateTessdata() {
        if (resolvedTessdataDir != null) return resolvedTessdataDir;
        synchronized (TesseractOcrProvider.class) {
            if (resolvedTessdataDir != null) return resolvedTessdataDir;
            for (Path candidate : candidatePaths()) {
                File dir = candidate.toFile();
                if (containsTrainedModels(dir)) {
                    resolvedTessdataDir = dir.getAbsolutePath();
                    log.info("Tessdata located at {}", resolvedTessdataDir);
                    return resolvedTessdataDir;
                }
            }
            throw new IllegalStateException(
                    "No tessdata directory found — expected .traineddata files under lib/tesseract.");
        }
    }

    private static List<Path> candidatePaths() {
        List<Path> paths = new ArrayList<>();
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path ancestor = cwd; ancestor != null; ancestor = ancestor.getParent()) {
            paths.add(ancestor.resolve("lib").resolve("tesseract"));
            paths.add(ancestor.resolve("tools").resolve("tesseract"));
        }
        return paths;
    }

    private static boolean containsTrainedModels(File dir) {
        if (!dir.isDirectory()) return false;
        File[] models = dir.listFiles(f -> f.getName().endsWith(".traineddata"));
        return models != null && models.length > 0;
    }

    // =====================================================================
    //  Validation
    // =====================================================================

    private static void requireValidCapture(BufferedImage capture) {
        if (capture == null) {
            throw new IllegalArgumentException("Prepared image must not be null.");
        }
    }
}
