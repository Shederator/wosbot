package dev.frostguard.app.shared;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;

public final class SettingValidators {

    private static final DateTimeFormatter TIME_FORMATTER = new DateTimeFormatterBuilder()
            .parseStrict()
            .appendPattern("HH:mm")
            .toFormatter(Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

    private SettingValidators() {
    }

    public static SettingValidator<Integer> positiveInteger(String label) {
        return rangedInteger(label, 1, Integer.MAX_VALUE);
    }

    public static SettingValidator<Integer> nonNegativeInteger(String label) {
        return rangedInteger(label, 0, Integer.MAX_VALUE);
    }

    public static SettingValidator<Integer> integer(String label) {
        return input -> {
            String candidate = trimmed(input);
            if (!candidate.matches("-?\\d+")) {
                return ValidationResult.invalid(label + " must be a whole number.");
            }
            try {
                return ValidationResult.valid(Integer.parseInt(candidate));
            } catch (NumberFormatException exception) {
                return ValidationResult.invalid(label + " is outside the supported range.");
            }
        };
    }

    public static SettingValidator<Long> nonNegativeLong(String label) {
        return rangedLong(label, 0, Long.MAX_VALUE);
    }

    public static SettingValidator<Long> rangedLong(String label, long minimum, long maximum) {
        if (minimum > maximum) {
            throw new IllegalArgumentException("Minimum must not exceed maximum");
        }
        return input -> {
            String candidate = trimmed(input);
            if (!candidate.matches("\\d+")) {
                return ValidationResult.invalid(label + " must be a whole number.");
            }
            try {
                long value = Long.parseLong(candidate);
                if (value < minimum || value > maximum) {
                    return ValidationResult.invalid(rangeMessage(label, minimum, maximum));
                }
                return ValidationResult.valid(value);
            } catch (NumberFormatException exception) {
                return ValidationResult.invalid(rangeMessage(label, minimum, maximum));
            }
        };
    }

    public static SettingValidator<String> requiredText(String label) {
        return input -> {
            String candidate = trimmed(input);
            return candidate.isEmpty()
                    ? ValidationResult.invalid(label + " cannot be empty.")
                    : ValidationResult.valid(candidate);
        };
    }

    public static SettingValidator<Integer> rangedInteger(String label, int minimum, int maximum) {
        if (minimum > maximum) {
            throw new IllegalArgumentException("Minimum must not exceed maximum");
        }
        return input -> {
            String candidate = trimmed(input);
            if (!candidate.matches("\\d+")) {
                return ValidationResult.invalid(label + " must be a whole number.");
            }
            try {
                int value = Integer.parseInt(candidate);
                if (value < minimum || value > maximum) {
                    return ValidationResult.invalid(rangeMessage(label, minimum, maximum));
                }
                return ValidationResult.valid(value);
            } catch (NumberFormatException exception) {
                return ValidationResult.invalid(rangeMessage(label, minimum, maximum));
            }
        };
    }

    public static SettingValidator<LocalTime> localTime(String label) {
        return input -> {
            String candidate = trimmed(input);
            if (!candidate.matches("\\d{2}:\\d{2}")) {
                return ValidationResult.invalid(label + " must use HH:mm.");
            }
            try {
                return ValidationResult.valid(LocalTime.parse(candidate, TIME_FORMATTER));
            } catch (DateTimeParseException exception) {
                return ValidationResult.invalid(label + " must be between 00:00 and 23:59.");
            }
        };
    }

    public static SettingValidator<LocalTime> localTimeNoLaterThan(String label, LocalTime latest) {
        SettingValidator<LocalTime> base = localTime(label);
        return input -> {
            ValidationResult<LocalTime> result = base.validate(input);
            if (result.isValid() && result.value().isAfter(latest)) {
                return ValidationResult.invalid(label + " must be no later than "
                        + latest.format(TIME_FORMATTER) + ".");
            }
            return result;
        };
    }

    public static SettingValidator<LocalDateTime> localDateTime(String label, DateTimeFormatter formatter) {
        return input -> {
            String candidate = trimmed(input);
            try {
                return ValidationResult.valid(LocalDateTime.parse(candidate, formatter));
            } catch (DateTimeParseException exception) {
                return ValidationResult.invalid(label + " has an invalid date or time.");
            }
        };
    }

    private static String trimmed(String input) {
        return input == null ? "" : input.trim();
    }

    private static String rangeMessage(String label, int minimum, int maximum) {
        if (maximum == Integer.MAX_VALUE) {
            return label + " must be at least " + minimum + ".";
        }
        return label + " must be between " + minimum + " and " + maximum + ".";
    }

    private static String rangeMessage(String label, long minimum, long maximum) {
        if (maximum == Long.MAX_VALUE) {
            return label + " must be at least " + minimum + ".";
        }
        return label + " must be between " + minimum + " and " + maximum + ".";
    }
}
