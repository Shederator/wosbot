package dev.frostguard.vision.convert;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Robust parser for compact and formatted numbers in game UI text (e.g. "1200", "1,200", "1.2K", "1.5M").
 * Converts formatted string representation into an exact long value safely without numeric overflow.
 */
public final class CompactGameNumberParser {

    private static final Pattern COMPACT_NUMBER_PATTERN = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*([KMkm])?$");

    private CompactGameNumberParser() {
        // Utility class
    }

    /**
     * Parses a string representation of a compact game number.
     *
     * @param input the raw OCR text string, e.g. "1.2K", "500", "3.5M"
     * @return parsed value as a non-negative long, or -1 if invalid/unparseable
     */
    public static long parseCompactNumber(String input) {
        if (input == null || input.isBlank()) {
            return -1L;
        }

        String trimmed = input.trim();
        if (trimmed.contains(",") && !trimmed.matches("[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]+)?\\s*[KMkm]?")) {
            return -1L;
        }
        String cleaned = trimmed.replace(",", "").toUpperCase(Locale.ROOT);
        Matcher matcher = COMPACT_NUMBER_PATTERN.matcher(cleaned);
        if (!matcher.matches()) {
            return -1L;
        }

        try {
            BigDecimal baseValue = new BigDecimal(matcher.group(1));
            if (baseValue.compareTo(BigDecimal.ZERO) < 0) {
                return -1L;
            }

            String unit = matcher.group(2);
            if (unit != null) {
                switch (unit) {
                    case "K":
                        baseValue = baseValue.multiply(BigDecimal.valueOf(1_000));
                        break;
                    case "M":
                        baseValue = baseValue.multiply(BigDecimal.valueOf(1_000_000));
                        break;
                    default:
                        break;
                }
            }

            long result = baseValue.longValueExact();
            return result >= 0 ? result : -1L;
        } catch (Exception ex) {
            return -1L;
        }
    }
}
