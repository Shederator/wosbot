package dev.frostguard.app.shared;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Optional;

public final class UtcDateTimeValue {

    private static final long RECURRENCE_SECONDS = 48L * 60L * 60L;

    private static final DateTimeFormatter PERSISTED_FORMAT = new DateTimeFormatterBuilder()
            .parseStrict()
            .appendPattern("dd-MM-uuuu HH:mm")
            .toFormatter(Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DATE_FORMAT = new DateTimeFormatterBuilder()
            .parseStrict()
            .appendPattern("dd-MM-uuuu")
            .toFormatter(Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter LOCAL_PREVIEW_FORMAT =
            DateTimeFormatter.ofPattern("dd-MM-uuuu HH:mm z", Locale.ENGLISH);

    private UtcDateTimeValue() {
    }

    public static Optional<LocalDateTime> parsePersisted(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(raw.trim(), PERSISTED_FORMAT));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public static String formatPersisted(LocalDateTime utcDateTime) {
        return utcDateTime.format(PERSISTED_FORMAT);
    }

    public static String formatUtcPreview(LocalDateTime utcDateTime) {
        return "Next activation: " + formatPersisted(utcDateTime) + " UTC";
    }

    public static LocalDateTime nextActivation(LocalDateTime anchor, Clock clock) {
        Instant anchorInstant = anchor.toInstant(ZoneOffset.UTC);
        Instant now = Instant.now(clock);
        if (!anchorInstant.isBefore(now)) {
            return anchor;
        }

        long elapsedSeconds = now.getEpochSecond() - anchorInstant.getEpochSecond();
        long completedCycles = Math.floorDiv(elapsedSeconds, RECURRENCE_SECONDS);
        Instant candidate = anchorInstant.plusSeconds(completedCycles * RECURRENCE_SECONDS);
        if (candidate.isBefore(now)) {
            candidate = candidate.plusSeconds(RECURRENCE_SECONDS);
        }
        return LocalDateTime.ofInstant(candidate, ZoneOffset.UTC);
    }

    public static Selection resolve(LocalDate date, String hourText, String minuteText) {
        if (date == null || isBlank(hourText) || isBlank(minuteText)) {
            return Selection.incomplete();
        }

        try {
            int hour = Integer.parseInt(hourText.trim());
            int minute = Integer.parseInt(minuteText.trim());
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return Selection.invalid();
            }
            return Selection.valid(date.atTime(hour, minute));
        } catch (NumberFormatException ignored) {
            return Selection.invalid();
        }
    }

    public static Selection resolveDateText(String dateText, String hourText, String minuteText) {
        if (isBlank(dateText)) {
            return Selection.incomplete();
        }
        try {
            return resolve(LocalDate.parse(dateText.trim(), DATE_FORMAT), hourText, minuteText);
        } catch (RuntimeException ignored) {
            return Selection.invalid();
        }
    }

    static String formatDate(LocalDate date) {
        return date == null ? "" : date.format(DATE_FORMAT);
    }

    static LocalDate parseDate(String text) {
        return isBlank(text) ? null : LocalDate.parse(text.trim(), DATE_FORMAT);
    }

    public static String formatLocalPreview(LocalDateTime utcDateTime, ZoneId localZone) {
        return "Local time: " + utcDateTime
                .atZone(ZoneOffset.UTC)
                .withZoneSameInstant(localZone)
                .format(LOCAL_PREVIEW_FORMAT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public enum Validation {
        VALID,
        INCOMPLETE,
        INVALID
    }

    public record Selection(LocalDateTime value, Validation validation) {
        static Selection valid(LocalDateTime value) {
            return new Selection(value, Validation.VALID);
        }

        static Selection incomplete() {
            return new Selection(null, Validation.INCOMPLETE);
        }

        static Selection invalid() {
            return new Selection(null, Validation.INVALID);
        }

        public boolean isValid() {
            return validation == Validation.VALID;
        }
    }
}
