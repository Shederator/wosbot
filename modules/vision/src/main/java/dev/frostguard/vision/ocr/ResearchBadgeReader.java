package dev.frostguard.vision.ocr;

import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.ResearchBadgeData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the stable slash shared by research progress badges and classifies the
 * two surrounding digits independently. Keeping the denominator out of a full
 * fraction template prevents visually dominant prefixes such as {@code 1/}
 * from making {@code 1/3} match {@code 1/5}.
 */
public final class ResearchBadgeReader {

    private static final Logger log = LoggerFactory.getLogger(ResearchBadgeReader.class);
    private static final String SLASH_TEMPLATE = "/templates/research/progressSlash.png";
    private static final Map<Integer, String> DIGIT_PATTERNS = Map.of(
            0, "/templates/research/progressDigit0.png",
            1, "/templates/research/progressDigit1.png",
            2, "/templates/research/progressDigit2.png",
            3, "/templates/research/progressDigit3.png",
            4, "/templates/research/progressDigit4.png",
            5, "/templates/research/progressDigit5.png",
            6, "/templates/research/progressDigit6.png");
    private static final List<Integer> NUMERATOR_DIGITS = List.of(0, 1, 2, 3, 4, 5);
    private static final List<Integer> PATTERN_DENOMINATORS = List.of(3, 4, 5, 6);
    private static final double SLASH_THRESHOLD = 84.0;
    private static final double DIGIT_THRESHOLD = 55.0;
    private static final double DIGIT_WIN_MARGIN = 7.0;
    private static final int SLASH_MAX_HITS = 30;
    private static final int BADGE_HALF_WIDTH = 24;
    private static final int BADGE_HALF_HEIGHT = 15;

    private ResearchBadgeReader() {}

    public static List<ResearchBadgeData> read(RawImageData frame, PointData topLeft,
                                               PointData bottomRight) {
        List<ImageSearchResultData> slashMatches = OpenCvPatternLocator.locateAllPatterns(
                frame, SLASH_TEMPLATE, topLeft, bottomRight,
                SLASH_THRESHOLD, SLASH_MAX_HITS);
        List<ResearchBadgeData> badges = new ArrayList<>();
        for (ImageSearchResultData slashMatch : slashMatches) {
            int centerX = slashMatch.getPoint().getX();
            int centerY = slashMatch.getPoint().getY();
            DigitMatch numerator = classifyDigit(
                    frame, centerX, centerY, NUMERATOR_DIGITS, true);
            if (numerator == null) {
                log.debug("Research badge at ({}, {}) has no unambiguous numerator pattern.",
                        centerX, centerY);
                continue;
            }

            DigitMatch denominator = classifyDigit(
                    frame, centerX, centerY, PATTERN_DENOMINATORS, false);
            if (denominator == null) {
                log.debug("Research badge at ({}, {}) has no unambiguous denominator pattern.",
                        centerX, centerY);
                continue;
            }

            int maximum = denominator.value();
            double confidence = Math.min(slashMatch.getMatchScore(),
                    Math.min(numerator.score(), denominator.score()));
            if (numerator.value() < maximum) {
                log.debug("Research badge patterns at ({}, {}): {}/{}, confidence={}",
                        centerX, centerY, numerator.value(), maximum,
                        String.format("%.1f", confidence));
                badges.add(new ResearchBadgeData(
                        numerator.value(), maximum, new PointData(centerX, centerY),
                        confidence));
            }
        }
        return badges;
    }

    private static DigitMatch classifyDigit(RawImageData frame, int centerX, int centerY,
                                            List<Integer> allowedDigits, boolean numerator) {
        PointData topLeft = new PointData(
                numerator ? centerX - BADGE_HALF_WIDTH : centerX + 4,
                centerY - BADGE_HALF_HEIGHT);
        PointData bottomRight = new PointData(
                numerator ? centerX - 4 : centerX + BADGE_HALF_WIDTH,
                centerY + BADGE_HALF_HEIGHT);

        DigitMatch best = null;
        DigitMatch second = null;
        for (int digit : allowedDigits) {
            ImageSearchResultData result = OpenCvPatternLocator.locatePattern(
                    frame, DIGIT_PATTERNS.get(digit), topLeft, bottomRight, 0.0);
            DigitMatch candidate = new DigitMatch(digit, result.getMatchScore());
            if (best == null || candidate.score() > best.score()) {
                second = best;
                best = candidate;
            } else if (second == null || candidate.score() > second.score()) {
                second = candidate;
            }
        }

        double runnerUp = second == null ? 0.0 : second.score();
        log.debug("Research {} digit best={}, score={}, runnerUp={}",
                numerator ? "numerator" : "denominator",
                best == null ? "n/a" : best.value(),
                best == null ? "n/a" : String.format("%.1f", best.score()),
                String.format("%.1f", runnerUp));
        if (best == null || best.score() < DIGIT_THRESHOLD
                || best.score() - runnerUp < DIGIT_WIN_MARGIN) {
            return null;
        }
        return best;
    }

    private record DigitMatch(int value, double score) {}
}
