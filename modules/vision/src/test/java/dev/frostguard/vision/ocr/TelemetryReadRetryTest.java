package dev.frostguard.vision.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.PointData;

/**
 * Retry behaviour for the telemetry HUD reads.
 *
 * <p>The bug this pins down: {@code readStringValue} accepts any non-null string as a successful
 * attempt, so a malformed-but-non-empty OCR read — garbage that fails to parse as a number — was
 * treated as a completed read. It consumed the whole attempt budget on one bad frame instead of
 * re-capturing, and a genuinely empty read got retries the malformed read did not.
 *
 * <p>The fix makes parseability itself the retry acceptor. These tests drive the real
 * {@link ResilientOcrExecutor} with a scripted extractor rather than asserting on the predicate in
 * isolation, so they exercise the actual loop: how many captures happen, and what comes back.
 *
 * <p>Evidence level: automated tests. The malformed strings below are real shapes the HUD OCR has
 * produced (an empty read, a bare separator, a units suffix with no digits).
 */
class TelemetryReadRetryTest {

    private static final PointData TL = new PointData(130, 48);
    private static final PointData BR = new PointData(272, 96);

    /** The acceptor the telemetry reads use: parseability decides, not mere non-nullness. */
    private static final Predicate<String> PARSEABLE =
            candidate -> candidate != null && HudNumberParser.parseScaled(candidate) != null;

    private static final Function<String, Long> TRANSFORM = HudNumberParser::parseScaled;

    /** Feeds a fixed sequence of OCR results and counts how many captures were asked for. */
    private static final class ScriptedExtractor implements ResilientOcrExecutor.TextExtractor {
        private final Deque<String> script;
        private final AtomicInteger captures = new AtomicInteger();

        ScriptedExtractor(List<String> reads) {
            this.script = new ArrayDeque<>(reads);
        }

        @Override
        public String extractText(dev.frostguard.api.domain.OcrSettingsData config,
                                  PointData topLeft, PointData bottomRight) {
            captures.incrementAndGet();
            return script.isEmpty() ? "" : script.poll();
        }

        int captures() {
            return captures.get();
        }
    }

    @Test
    void aMalformedReadIsRetriedRatherThanTrusted() {
        ScriptedExtractor extractor = new ScriptedExtractor(List.of("---", "12.4M"));
        ResilientOcrExecutor<Long> executor = new ResilientOcrExecutor<>(extractor);

        Long value = executor.attemptRecognition(TL, BR, 5, 0L, null, PARSEABLE, TRANSFORM);

        assertEquals(12_400_000L, value, "the second, parseable read is the one that should win");
        assertEquals(2, extractor.captures(), "the malformed read must cost one attempt, not all of them");
    }

    @Test
    void severalMalformedReadsInARowStillReachAGoodOne() {
        ScriptedExtractor extractor = new ScriptedExtractor(List.of("", "M", "..", "1.174.000"));
        ResilientOcrExecutor<Long> executor = new ResilientOcrExecutor<>(extractor);

        Long value = executor.attemptRecognition(TL, BR, 5, 0L, null, PARSEABLE, TRANSFORM);

        assertEquals(1_174_000L, value);
        assertEquals(4, extractor.captures());
    }

    @Test
    void anAllMalformedReadReturnsNullRatherThanGarbage() {
        ScriptedExtractor extractor = new ScriptedExtractor(List.of("---", "M", "..", "", "?"));
        ResilientOcrExecutor<Long> executor = new ResilientOcrExecutor<>(extractor);

        Long value = executor.attemptRecognition(TL, BR, 5, 0L, null, PARSEABLE, TRANSFORM);

        assertNull(value, "nothing parseable was ever read, so the caller must get null, not a guess");
        assertEquals(5, extractor.captures(), "the full attempt budget should be spent before giving up");
    }

    @Test
    void aGoodFirstReadCostsExactlyOneCapture() {
        ScriptedExtractor extractor = new ScriptedExtractor(List.of("839,000,000"));
        ResilientOcrExecutor<Long> executor = new ResilientOcrExecutor<>(extractor);

        Long value = executor.attemptRecognition(TL, BR, 5, 0L, null, PARSEABLE, TRANSFORM);

        assertEquals(839_000_000L, value);
        assertEquals(1, extractor.captures(), "a clean read must not burn extra captures");
    }

    @Test
    void anEmptyReadIsRetriedTheSameWayAMalformedOneIs() {
        // Before the fix these two cases behaved differently: empty was retried, malformed was not.
        ScriptedExtractor emptyFirst = new ScriptedExtractor(List.of("", "12.4M"));
        ScriptedExtractor malformedFirst = new ScriptedExtractor(List.of("---", "12.4M"));

        Long fromEmpty = new ResilientOcrExecutor<Long>(emptyFirst)
                .attemptRecognition(TL, BR, 5, 0L, null, PARSEABLE, TRANSFORM);
        Long fromMalformed = new ResilientOcrExecutor<Long>(malformedFirst)
                .attemptRecognition(TL, BR, 5, 0L, null, PARSEABLE, TRANSFORM);

        assertEquals(fromEmpty, fromMalformed);
        assertEquals(emptyFirst.captures(), malformedFirst.captures());
    }
}
