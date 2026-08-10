package dev.frostguard.vision.ocr;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.TesseractSettingsData;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import net.sourceforge.tess4j.TesseractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a text extraction function with configurable retry semantics.
 *
 * <p>On each attempt the engine reads the given screen region; a caller-
 * supplied predicate decides whether the raw text is acceptable.  When the
 * predicate passes, the text is mapped to the desired return type via a
 * converter function.  If every attempt fails, {@code null} is returned.
 *
 * @param <R> the type the recognised text is converted into on success
 */
public class ResilientOcrExecutor<R> {

    private static final Logger log = LoggerFactory.getLogger(ResilientOcrExecutor.class);

    /**
     * Functional interface for text extraction operations.
     * Abstracts the actual OCR implementation details.
     */
    @FunctionalInterface
    public interface TextExtractor {
        /**
         * Extracts text from a rectangular region.
         *
         * @param config      engine configuration (may be null)
         * @param topLeft     upper-left corner
         * @param bottomRight lower-right corner
         * @return recognized text, never null
         * @throws IOException        if capture fails
         * @throws TesseractException if recognition fails
         */
        String extractText(TesseractSettingsData config, PointData topLeft, PointData bottomRight)
                throws IOException, TesseractException;
    }

    private final TextExtractor textExtractor;

    /**
     * @param textExtractor the recognition back-end to delegate to
     */
    public ResilientOcrExecutor(TextExtractor textExtractor) {
        this.textExtractor = Objects.requireNonNull(textExtractor, "textExtractor required");
    }

    /**
     * Attempts recognition up to {@code maxAttempts} times with a fixed
     * inter-attempt pause.
     *
     * @param upperLeft    upper-left corner of the capture area
     * @param lowerRight   lower-right corner of the capture area
     * @param maxAttempts  total number of tries before giving up
     * @param pauseMillis  wait between consecutive attempts
     * @param config       engine parameters (may be {@code null})
     * @param acceptor     predicate that returns {@code true} when the
     *                     raw text is good enough
     * @param transformer  maps accepted text into the return type
     * @return the transformed value, or {@code null} if all attempts fail
     */
    public R attemptRecognition(PointData upperLeft,
                                PointData lowerRight,
                                int maxAttempts,
                                long pauseMillis,
                                TesseractSettingsData config,
                                Predicate<String> acceptor,
                                Function<String, R> transformer) {

        for (int trial = 0; trial < maxAttempts; trial++) {
            log.debug("OCR trial {} / {}", trial + 1, maxAttempts);
            try {
                String raw = textExtractor.extractText(config, upperLeft, lowerRight);
                if (raw != null) {
                    if (acceptor.test(raw)) {
                        log.info("=== OCR Completed === Text: '{}', Match: true, Position: ({},{}) to ({},{})",
                                raw.trim().replace("\n", " "),
                                upperLeft.getX(), upperLeft.getY(),
                                lowerRight.getX(), lowerRight.getY());
                        return transformer.apply(raw);
                    } else if (trial == maxAttempts - 1) {
                        log.info("=== OCR Completed === Text: '{}', Match: false, Position: ({},{}) to ({},{})",
                                raw.trim().replace("\n", " "),
                                upperLeft.getX(), upperLeft.getY(),
                                lowerRight.getX(), lowerRight.getY());
                    }
                }
            } catch (IOException | TesseractException | RuntimeException ex) {
                log.warn("OCR trial {} failed: {}", trial + 1, ex.getMessage());
            }

            if (trial < maxAttempts - 1) {
                try {
                    Thread.sleep(pauseMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Convenience overload that unpacks a {@link AreaData} into its
     * corner coordinates before delegating.
     */
    public R attemptRecognition(AreaData area,
                                int maxAttempts,
                                long pauseMillis,
                                TesseractSettingsData config,
                                Predicate<String> acceptor,
                                Function<String, R> transformer) {
        return attemptRecognition(
                area.topLeft(), area.bottomRight(),
                maxAttempts, pauseMillis, config, acceptor, transformer);
    }
}
