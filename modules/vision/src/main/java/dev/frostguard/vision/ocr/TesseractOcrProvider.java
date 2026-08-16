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
        Tesseract engine = configureTesseract(cfg);
        log.debug("Engine config: {} ms", System.currentTimeMillis() - step);

        step = System.currentTimeMillis();
        String recognised = executeRecognition(engine, preparedImage);
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

    /** Builds an engine whose behaviour is controlled by {@code cfg}. */
    private static Tesseract configureTesseract(OcrSettingsData cfg) {
        Tesseract t = new Tesseract();
        t.setDatapath(locateTessdata());
        t.setLanguage("eng");
        t.setConfigs(Collections.singletonList("quiet"));

        if (cfg.hasTextLayout()) {
            t.setPageSegMode(mapTextLayout(cfg.textLayout()));
        } else {
            t.setPageSegMode(3); // AUTO
        }

        t.setOcrEngineMode(1); // Default to LSTM_ONLY for our use cases

        if (cfg.hasAllowedChars()) {
            t.setVariable("tessedit_char_whitelist", cfg.getAllowedChars());
        }
        return t;
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
            return engine.doOCR(img).replace("\n", "").replace("\r", "").trim();
        } catch (TesseractException e) {
            throw new OcrException("Tesseract OCR failed", e);
        }
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
