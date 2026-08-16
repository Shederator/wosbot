package dev.frostguard.vision.ocr;

import dev.frostguard.api.domain.OcrSettingsData;

import java.awt.image.BufferedImage;

/**
 * Abstraction over the OCR backend used for in-game text extraction.
 */
public interface OcrProvider {

    /**
     * Recognizes text from a pre-processed image.
     *
     * @param preparedImage pre-processed, cropped, and scaled image
     * @param lang          OCR language code (e.g. {@code "eng"})
     * @return trimmed recognized text, never {@code null}
     */
    String recognizeText(BufferedImage preparedImage, String lang) throws OcrException;

    /**
     * Recognizes text from a pre-processed image using explicit OCR presets.
     *
     * @param preparedImage pre-processed, cropped, and scaled image
     * @param cfg           tuning parameters (layout, whitelist, etc.)
     * @return trimmed recognized text, never {@code null}
     */
    String recognizeText(BufferedImage preparedImage, OcrSettingsData cfg) throws OcrException;
}
