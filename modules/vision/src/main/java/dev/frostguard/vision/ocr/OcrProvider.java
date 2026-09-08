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

    /**
     * Recognises every line on the image and reports where each one sat.
     *
     * <p>The plain text methods above lose that, which forces a caller who needs to know which text
     * belongs to which part of the screen to crop first and recognise afterwards -- and a crop is a
     * guess about a boundary the reader could have measured. Recognising whole and keeping the
     * geometry is both more accurate and far cheaper: one call for a screen instead of one per
     * region.
     *
     * @return the lines found, in reading order, never {@code null}
     */
    java.util.List<TextLine> recognizeLines(BufferedImage preparedImage, OcrSettingsData cfg)
            throws OcrException;

    /** The same reading reported one word at a time, for callers that need word positions. */
    java.util.List<TextLine> recognizeWords(BufferedImage preparedImage, OcrSettingsData cfg)
            throws OcrException;
}
