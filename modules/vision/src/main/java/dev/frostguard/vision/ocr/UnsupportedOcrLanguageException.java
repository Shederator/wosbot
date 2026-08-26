package dev.frostguard.vision.ocr;

import java.util.List;
import java.util.Set;

/**
 * Thrown when a caller explicitly requests an OCR language with no packaged trained-data model.
 *
 * <p>Silently falling back to "eng" for an explicit, unsupported request (e.g.
 * "ara", "rus") used to look successful -- Tesseract still returned text -- but that text was
 * Latin-model glyph guesses run against a script it cannot read, plausible-looking and wrong. A
 * caller that never asked for a specific language at all (the common case -- {@code null}/blank)
 * still gets the safe "eng" default unaffected; only an explicit, unsatisfiable request fails.</p>
 */
public class UnsupportedOcrLanguageException extends OcrException {

    private final List<String> requestedUnsupported;
    private final Set<String> supported;

    public UnsupportedOcrLanguageException(List<String> requestedUnsupported, Set<String> supported) {
        super("OCR language(s) " + requestedUnsupported + " have no packaged trained-data model "
                + "(supported: " + supported + ")");
        this.requestedUnsupported = List.copyOf(requestedUnsupported);
        this.supported = Set.copyOf(supported);
    }

    public List<String> getRequestedUnsupported() { return requestedUnsupported; }
    public Set<String> getSupported()             { return supported; }
}
