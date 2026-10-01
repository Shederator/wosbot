package dev.frostguard.vision.convert;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses game counts such as {@code 57,785}, {@code 420K}, and {@code 1.5M}. */
public final class CompactGameNumberParser {

    private static final Pattern VALUE = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*([KM])?$");

    private CompactGameNumberParser() {
    }

    public static long parse(String input) {
        if (input == null || input.isBlank()) {
            return -1;
        }
        String trimmed = input.trim();
        if (trimmed.contains(",")
                && !trimmed.matches("[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]+)?\\s*[KMkm]?")) {
            return -1;
        }
        Matcher matcher = VALUE.matcher(trimmed.replace(",", "").toUpperCase(Locale.ROOT));
        if (!matcher.matches()) {
            return -1;
        }
        try {
            BigDecimal value = new BigDecimal(matcher.group(1));
            if ("K".equals(matcher.group(2))) {
                value = value.multiply(BigDecimal.valueOf(1_000));
            } else if ("M".equals(matcher.group(2))) {
                value = value.multiply(BigDecimal.valueOf(1_000_000));
            }
            long parsed = value.longValueExact();
            return parsed >= 0 ? parsed : -1;
        } catch (ArithmeticException invalid) {
            return -1;
        }
    }
}
