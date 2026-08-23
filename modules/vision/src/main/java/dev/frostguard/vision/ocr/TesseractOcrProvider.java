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

    /**
     * Recognises the whole image once and reports each line of text with its position.
     *
     * <p>The reader's own line boxes are used, not word boxes reassembled into lines. Reassembling
     * was tried, because a line box on a chat frame swallows the avatar beside the text and so
     * starts far left of where the text visibly starts. It measured worse: grouping words back into
     * lines has to decide which words share a line, and short words carry no ascender, so "para"
     * and "las" sit a few pixels off the words around them and land in the neighbouring line. The
     * sentence then reassembles out of order -- "Por lo menos cumplir con la para puntuacion las
     * para recompensas diarias". The engine already knows the reading order; taking it apart to put
     * it back together only loses that.
     *
     * <p>The imprecise left edge costs nothing here, because what distinguishes a sender line from
     * a message is that it carries an alliance tag, not where it starts.
     *
     * <p>Page segmentation is forced to AUTO whatever the caller configured: the single-line mode
     * the cropping callers rely on asserts there is exactly one line, which is the opposite of what
     * this method is for.
     */
    @Override
    public List<TextLine> recognizeLines(BufferedImage preparedImage, OcrSettingsData cfg)
            throws OcrException {
        requireValidCapture(preparedImage);
        Tesseract engine = configureTesseract(cfg);
        engine.setPageSegMode(3); // AUTO -- find the text rather than assert its shape
        try {
            List<net.sourceforge.tess4j.Word> found = engine.getWords(preparedImage,
                    net.sourceforge.tess4j.ITessAPI.TessPageIteratorLevel.RIL_TEXTLINE);
            return assembleRows(found);
        } catch (RuntimeException e) {
            throw new OcrException("Line recognition failed", e);
        }
    }

    /**
     * The same reading, reported one word at a time with where each word sat.
     *
     * <p>Line boxes are what the transcript is assembled from, but they cannot say which part of a
     * line is a word and which is a piece of the bubble the reader mistook for one. A snowman
     * ornament on the bubble's edge is white, the same as the text, so no colour test separates
     * them -- what separates them is that the ornament is one or two characters sitting on its own
     * with a gap between it and the sentence. That is a judgement about word positions, so word
     * positions are what this returns.
     */
    @Override
    public List<TextLine> recognizeWords(BufferedImage preparedImage, OcrSettingsData cfg)
            throws OcrException {
        requireValidCapture(preparedImage);
        Tesseract engine = configureTesseract(cfg);
        engine.setPageSegMode(3); // AUTO -- same reading as recognizeLines, reported finer
        try {
            List<net.sourceforge.tess4j.Word> found = engine.getWords(preparedImage,
                    net.sourceforge.tess4j.ITessAPI.TessPageIteratorLevel.RIL_WORD);
            List<TextLine> words = new ArrayList<>(found.size());
            for (net.sourceforge.tess4j.Word w : found) {
                if (w.getText() == null || w.getText().isBlank()
                        || w.getConfidence() < MIN_LINE_CONFIDENCE) {
                    continue;
                }
                java.awt.Rectangle r = w.getBoundingBox();
                words.add(new TextLine(w.getText().trim(), r.x, r.y, r.width, r.height,
                        w.getConfidence()));
            }
            return words;
        } catch (RuntimeException e) {
            throw new OcrException("Word recognition failed", e);
        }
    }


    /**
     * Puts the reader's fragments back into the rows they were printed on.
     *
     * <p>A line of chat does not come back as one box. Against the bubble's background the reader
     * breaks a single printed row into several pieces -- "Por lo menos", "para", "cumplir con la" --
     * and gives them all but identical tops. Ordered by top, those pieces interleave and the
     * sentence reassembles scrambled. Ordered by left within the row they were printed on, they
     * reassemble exactly.
     *
     * <p>Rows are found by vertical overlap rather than by distance between edges or centres. Both
     * of those depend on which glyphs a fragment happens to contain: a piece holding only "para"
     * has neither ascender nor tall capital and sits a few pixels off its neighbours, which is
     * enough to throw it into the row above or below. Overlap does not care about that.
     */
    private static List<TextLine> assembleRows(List<net.sourceforge.tess4j.Word> found) {
        List<net.sourceforge.tess4j.Word> usable = new ArrayList<>();
        for (net.sourceforge.tess4j.Word w : found) {
            if (w.getText() != null && !w.getText().isBlank()
                    && w.getConfidence() >= MIN_LINE_CONFIDENCE) {
                usable.add(w);
            }
        }
        usable.sort((a, b) -> Integer.compare(a.getBoundingBox().y, b.getBoundingBox().y));

        List<List<net.sourceforge.tess4j.Word>> rows = new ArrayList<>();
        for (net.sourceforge.tess4j.Word w : usable) {
            java.awt.Rectangle r = w.getBoundingBox();
            List<net.sourceforge.tess4j.Word> home = null;
            for (List<net.sourceforge.tess4j.Word> row : rows) {
                if (overlapsVertically(r, boundsOf(row))) {
                    home = row;
                    break;
                }
            }
            if (home == null) {
                home = new ArrayList<>();
                rows.add(home);
            }
            home.add(w);
        }

        List<TextLine> out = new ArrayList<>(rows.size());
        for (List<net.sourceforge.tess4j.Word> row : rows) {
            row.sort((a, b) -> Integer.compare(a.getBoundingBox().x, b.getBoundingBox().x));
            StringBuilder text = new StringBuilder();
            float confidence = 0f;
            for (net.sourceforge.tess4j.Word w : row) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(w.getText().trim().replace((char) 10, ' ').trim());
                confidence += w.getConfidence();
            }
            java.awt.Rectangle b = boundsOf(row);
            out.add(new TextLine(text.toString().trim(), b.x, b.y, b.width, b.height,
                    confidence / row.size()));
        }
        out.sort((a, b) -> Integer.compare(a.top(), b.top()));
        return out;
    }

    /** How much of the shorter box must fall inside the taller one to be the same printed row. */
    private static final double ROW_OVERLAP_SHARE = 0.5;

    private static boolean overlapsVertically(java.awt.Rectangle a, java.awt.Rectangle b) {
        int top = Math.max(a.y, b.y);
        int bottom = Math.min(a.y + a.height, b.y + b.height);
        int shared = bottom - top;
        return shared > 0 && shared >= ROW_OVERLAP_SHARE * Math.min(a.height, b.height);
    }

    private static java.awt.Rectangle boundsOf(List<net.sourceforge.tess4j.Word> row) {
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = 0, bottom = 0;
        for (net.sourceforge.tess4j.Word w : row) {
            java.awt.Rectangle r = w.getBoundingBox();
            left = Math.min(left, r.x);
            top = Math.min(top, r.y);
            right = Math.max(right, r.x + r.width);
            bottom = Math.max(bottom, r.y + r.height);
        }
        return new java.awt.Rectangle(left, top, right - left, bottom - top);
    }

    /** Below this the reader is guessing at noise rather than reading text. */
    private static final float MIN_LINE_CONFIDENCE = 30f;


}
