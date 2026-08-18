package dev.frostguard.vision.ocr;

import dev.frostguard.api.domain.OcrSettingsData;
import io.github.hzkitty.RapidOCR;
import io.github.hzkitty.entity.OcrConfig;
import io.github.hzkitty.entity.OcrResult;
import io.github.hzkitty.entity.ParamConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * OCR provider backed by PaddleOCR via RapidOCR4j (CPU execution).
 *
 * <p><b>Thread safety:</b> All recognition calls are serialized on this instance.
 * The underlying ONNX session is not known to be re-entrant. This constraint
 * may be relaxed in Step 3 after load testing.
 *
 * <p><b>Character filtering:</b> PaddleOCR has no native character whitelist.
 * {@link OcrSettingsData#allowedGlyphs()} is enforced as a post-recognition
 * filter. A warning is logged when more than 20 % of characters are removed,
 * which indicates the model is reading unexpected characters.
 *
 * <p><b>Layout mapping:</b> controlled via {@link ParamConfig#useDet} passed per-call.
 * <ul>
 *   <li>{@code SINGLE_LINE}, {@code SINGLE_WORD} → {@code useDet=false}: detector is skipped
 *       and the recognizer runs directly on the full crop. This is the correct mode for tight
 *       numeric crops (timers, counters) where the DBNet detector fails to find boxes.
 *   <li>{@code TEXT_BLOCK}, {@code SPARSE}, {@code AUTO} → {@code useDet=true}: full
 *       Detector → Classifier → Recognizer pipeline for multi-line UI screens.
 * </ul>
 *
 * <p><b>Model files</b> must be present under {@code modelsDir} before this
 * provider is constructed. Use {@link PaddleModelDownloader#ensureModels(Path)}
 * at bootstrap to guarantee this.
 */
public final class PaddleOcrProvider implements OcrProvider {

    private static final Logger log = LoggerFactory.getLogger(PaddleOcrProvider.class);

    private final RapidOCR engine;

    /**
     * Creates a provider using PaddleOCR models from {@code modelsDir}.
     *
     * @throws OcrException if the model directory is missing or the ONNX runtime
     *                      cannot be loaded (including native linkage failures)
     */
    public PaddleOcrProvider(Path modelsDir) throws OcrException {
        log.info("Initializing PaddleOCR provider from {}", modelsDir);
        long t0 = System.currentTimeMillis();
        try {
            OcrConfig config = new OcrConfig();
            OcrConfig.GlobalConfig global = config.getGlobal();
            global.setIntraOpNumThreads(2);
            global.setInterOpNumThreads(2);

            config.getDet().setModelPath(modelsDir.resolve("ch_PP-OCRv4_det_infer.onnx").toString());
            config.getCls().setModelPath(modelsDir.resolve("ch_ppocr_mobile_v2.0_cls_train.onnx").toString());
            config.getRec().setModelPath(modelsDir.resolve("en_PP-OCRv3_rec_infer.onnx").toString());

            this.engine = RapidOCR.create(config);
        } catch (Exception | Error e) {
            // Catch Error: JNA can throw LinkageError on a missing native DLL
            throw new OcrException("PaddleOCR initialization failed: " + e.getMessage(), e);
        }
        log.info("PaddleOCR provider ready — cold init {} ms",
                System.currentTimeMillis() - t0);
    }

    @Override
    public synchronized String recognizeText(BufferedImage preparedImage, String lang)
            throws OcrException {
        // lang is a Tesseract concept; Paddle models are fixed at load time.
        // Accept the parameter for interface compliance; log if non-English.
        if (lang != null && !lang.startsWith("eng")) {
            log.debug("PaddleOCR: lang='{}' has no effect — models are fixed at init", lang);
        }
        return recognizeText(preparedImage, OcrSettingsData.configurator().build());
    }

    @Override
    public synchronized String recognizeText(BufferedImage preparedImage, OcrSettingsData cfg)
            throws OcrException {
        long t0 = System.currentTimeMillis();
        log.debug("=== PaddleOCR Recognition Started === layout={}", cfg.textLayout());

        requireValidImage(preparedImage);

        ParamConfig params = buildParamConfig(cfg);
        OcrResult result;
        try {
            // RapidOCR4j accepts BufferedImage directly — no temp file needed
            result = engine.run(preparedImage, params);
        } catch (Exception | Error e) {
            throw new OcrException("PaddleOCR recognition failed", e);
        }

        String text = joinLines(result, cfg);
        text = applyGlyphFilter(text, cfg.allowedGlyphs());
        text = text.trim();

        log.debug("=== PaddleOCR Finished === elapsed={} ms, text='{}'",
                System.currentTimeMillis() - t0, text);
        return text;
    }

    // =========================================================================
    //  Layout configuration
    // =========================================================================

    /**
     * Builds per-call inference parameters from the OCR settings.
     *
     * <p>For {@code SINGLE_LINE} and {@code SINGLE_WORD} layouts, detection is disabled
     * ({@code useDet=false}). The recognizer then treats the entire crop as a single text
     * strip, which is the correct behaviour for tight numeric crops (timers, counters,
     * stamina values) where the DBNet detector fails to draw bounding boxes.
     */
    private static ParamConfig buildParamConfig(OcrSettingsData cfg) {
        ParamConfig params = new ParamConfig();
        OcrSettingsData.TextLayout layout = cfg.textLayout();
        boolean skipDetector = layout == OcrSettingsData.TextLayout.SINGLE_LINE
                            || layout == OcrSettingsData.TextLayout.SINGLE_WORD;
        params.setUseDet(!skipDetector);
        return params;
    }

    // =========================================================================
    //  Text extraction helpers
    // =========================================================================

    /** Joins recognized text lines. SINGLE_LINE layouts use a space; others use newline. */
    private static String joinLines(OcrResult result, OcrSettingsData cfg) {
        if (result == null || result.getStrRes() == null) return "";
        String joined = result.getStrRes().strip();
        // For single-line presets, collapse multi-line output to one line
        OcrSettingsData.TextLayout layout = cfg.textLayout();
        if (layout == OcrSettingsData.TextLayout.SINGLE_LINE
                || layout == OcrSettingsData.TextLayout.SINGLE_WORD) {
            joined = joined.replace("\n", " ").replace("\r", "").trim();
        }
        return joined;
    }

    /**
     * Removes characters not in {@code allowedGlyphs}.
     * Logs a warning when more than 20 % of characters are removed.
     */
    static String applyGlyphFilter(String text, String allowedGlyphs) {
        if (allowedGlyphs == null || allowedGlyphs.isEmpty()) return text;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (allowedGlyphs.indexOf(c) >= 0) sb.append(c);
        }
        String filtered = sb.toString();
        if (!text.isBlank()) {
            double removedRatio = 1.0 - (double) filtered.length() / text.length();
            if (removedRatio > 0.20) {
                log.warn("PaddleOCR glyph filter removed {} of characters — " +
                         "raw='{}', filtered='{}', allowed='{}'",
                         String.format("%.0f%%", removedRatio * 100), text, filtered, allowedGlyphs);
            }
        }
        return filtered;
    }

    // =========================================================================
    //  Validation
    // =========================================================================

    private static void requireValidImage(BufferedImage img) {
        if (img == null) throw new IllegalArgumentException("Prepared image must not be null.");
    }
}
