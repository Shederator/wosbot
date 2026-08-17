package dev.frostguard.vision.ocr;

/**
 * Thrown when an OCR operation fails. This is a generic exception that wraps
 * provider-specific failures such as Tesseract exceptions.
 */
public class OcrException extends Exception {

    public OcrException(String message) {
        super(message);
    }

    public OcrException(String message, Throwable cause) {
        super(message, cause);
    }
}
