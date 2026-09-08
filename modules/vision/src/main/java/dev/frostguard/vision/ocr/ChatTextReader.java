package dev.frostguard.vision.ocr;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Something that can read the text off a region of a screen.
 *
 * <p>There are two, and they answer very nearly the same: a Python service holding the PP-OCR
 * models behind a local port, and the same models running in this process through ONNX. The
 * service reads well and cannot be shipped -- it is a gigabyte of libraries and a web server on
 * somebody's machine -- so the in-process reader exists to give a plain download the same
 * transcript. Naming the shape they share is what lets one be swapped for the other and the two be
 * compared on identical frames.
 */
public interface ChatTextReader {

    /** Whether this reader can be used at all right now. */
    boolean isUp();

    /**
     * Reads one region of a frame.
     *
     * @param language      {@code "en"} for Latin, {@code "ru"} for Cyrillic
     * @param minConfidence rows the reader was less sure of than this are not text
     * @return one line per piece of text found, positioned against the whole frame
     */
    List<TextLine> read(BufferedImage frame, int left, int top, int right, int bottom,
                        String language, double minConfidence);

    /** How this reader should be named in a log or on screen. */
    String name();
}
