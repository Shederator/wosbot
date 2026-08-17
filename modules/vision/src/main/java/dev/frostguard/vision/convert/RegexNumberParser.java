package dev.frostguard.vision.convert;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RegexNumberParser {

    private RegexNumberParser() {} // Utility class

    // --- from RegexNumberParser.java ---
/** Matches a fraction with optional surrounding text: {@code …N / M…} */
    private static final Pattern FRACTION_PATTERN =
            Pattern.compile(".*?(\\d+)\\s*/\\s*(\\d+).*");



    /**
     * Applies the given regex to {@code text} and converts the first
     * capturing group (or the whole match when no groups exist) to an
     * {@link Integer}.
     *
     * @param text    the raw OCR string, may be {@code null}
     * @param pattern compiled pattern with ≥ 1 capturing group
     * @return the parsed integer, or {@code null} on any mismatch
     */
    public static Integer extractByPattern(String text, Pattern pattern) {
        Objects.requireNonNull(pattern, "pattern required");
        if (text == null) {
            return null;
        }
        Matcher m = pattern.matcher(text.trim());
        if (!m.matches()) {
            return null;
        }
        String segment = m.groupCount() >= 1 ? m.group(1) : m.group();
        try {
            return Integer.parseInt(segment);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Returns the numerator (first integer) of a fraction such as
     * {@code "3/10"}.
     *
     * @param text raw OCR string, may be {@code null}
     * @return the numerator, or {@code null} when parsing fails
     */
    public static Integer numerator(String text) {
        return fractionComponent(text, 1);
    }

    /**
     * Returns the denominator (second integer) of a fraction such as
     * {@code "3/10"}.
     *
     * @param text raw OCR string, may be {@code null}
     * @return the denominator, or {@code null} when parsing fails
     */
    public static Integer denominator(String text) {
        return fractionComponent(text, 2);
    }

    /**
     * Extracts either component of a fraction by position index.
     *
     * @param text     raw OCR string, may be {@code null}
     * @param position 1 for numerator, 2 for denominator
     * @return the requested component, or {@code null}
     */
    public static Integer fractionComponent(String text, int position) {
        if (text == null || position < 1 || position > 2) {
            return null;
        }
        Matcher m = FRACTION_PATTERN.matcher(text.trim());
        if (!m.matches()) {
            return null;
        }
        try {
            return Integer.parseInt(m.group(position));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // --- from RegexNumberParser.java ---


    /**
     * Checks whether {@code text} fully matches the given compiled pattern.
     *
     * @param text    the candidate string, may be {@code null}
     * @param pattern a pre-compiled regex, must not be {@code null}
     * @return {@code true} when the trimmed input satisfies the pattern
     */
    public static boolean conformsTo(String text, Pattern pattern) {
        Objects.requireNonNull(pattern, "pattern must not be null");
        if (text == null) {
            return false;
        }
        return pattern.matcher(text.trim()).matches();
    }

    /**
     * Determines whether the text contains a fraction expressed as two
     * integers separated by a slash, such as {@code "3/10"} or
     * {@code "12 / 100"}.
     *
     * @param text the candidate string, may be {@code null}
     * @return {@code true} when a valid fraction is present
     */
    public static boolean hasFractionSyntax(String text) {
        if (text == null) {
            return false;
        }
        return Pattern.compile(".*?\\d+\\s*/\\s*\\d+.*")
                .matcher(text.trim())
                .matches();
    }
}
