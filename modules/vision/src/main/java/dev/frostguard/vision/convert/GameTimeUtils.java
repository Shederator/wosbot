package dev.frostguard.vision.convert;

import java.time.ZonedDateTime;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.DayOfWeek;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GameTimeUtils {

    private GameTimeUtils() {} // Utility class

    // --- from GameTimeUtils.java ---
private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Pattern HMS_PATTERN = Pattern.compile("(\\d{1,2}):(\\d{2}):(\\d{2})");



    /**
     * Computes the next daily reset instant (00:00 UTC tomorrow)
     * expressed in the JVM's local timezone.
     */
    public static LocalDateTime dailyResetTime() {
        ZonedDateTime utcNow = ZonedDateTime.now(UTC);
        ZonedDateTime tomorrowMidnightUtc = utcNow.toLocalDate().plusDays(1).atStartOfDay(UTC);
        return tomorrowMidnightUtc.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    /**
     * Returns whichever of the two daily cycle boundaries (00:00 UTC or
     * 12:00 UTC) comes soonest, expressed in local time.
     */
    public static LocalDateTime nextCycleReset() {
        ZonedDateTime utcNow = ZonedDateTime.now(UTC);

        ZonedDateTime midnightUtc = utcNow.toLocalDate().plusDays(1).atStartOfDay(UTC);
        ZonedDateTime noonUtc = utcNow.toLocalDate().atTime(12, 0).atZone(UTC);

        if (utcNow.isAfter(noonUtc)) {
            noonUtc = noonUtc.plusDays(1);
        }

        long secsToMidnight = utcNow.until(midnightUtc, ChronoUnit.SECONDS);
        long secsToNoon = utcNow.until(noonUtc, ChronoUnit.SECONDS);

        ZonedDateTime nearest = secsToMidnight < secsToNoon ? midnightUtc : noonUtc;
        return nearest.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    /**
     * Formats the remaining time until {@code target} as a human-readable
     * countdown string such as {@code "2 days 03:14:07"}.  Returns
     * {@code "ASAP"} when the target is already in the past.
     */
    public static String formatCountdown(LocalDateTime target) {
        LocalDateTime now = LocalDateTime.now();
        if (target.isBefore(now)) {
            return "ASAP";
        }

        Duration gap = Duration.between(now, target);
        long d = gap.toDays();
        long h = gap.toHours() % 24;
        long m = gap.toMinutes() % 60;
        long s = gap.getSeconds() % 60;

        StringBuilder sb = new StringBuilder();
        if (d > 0) {
            sb.append(d).append(" days ");
        }
        sb.append(String.format("%02d:%02d:%02d", h, m, s));
        return sb.toString();
    }

    /**
     * Describes how long ago {@code timestamp} occurred in a compact,
     * human-friendly form (e.g. {@code "12m ago"}, {@code "3h ago"}).
     * Returns {@code "Never"} for a {@code null} input.
     */
    public static String formatElapsed(LocalDateTime timestamp) {
        if (timestamp == null) {
            return "Never";
        }
        long minutesPast = ChronoUnit.MINUTES.between(timestamp, LocalDateTime.now());
        return humanizeElapsedMinutes(minutesPast);
    }

    /**
     * Ensures the proposed schedule does not fall within the final 5
     * minutes before the daily reset.  If it does, the schedule is
     * pulled back to exactly 5 minutes before the boundary.
     *
     * @param proposed the originally calculated schedule time
     * @return adjusted time, guaranteed to precede the 5-minute buffer
     */
    public static LocalDateTime clampToResetWindow(LocalDateTime proposed) {
        LocalDateTime resetBoundary = dailyResetTime();
        LocalDateTime safeLimit = resetBoundary.minusMinutes(5);
        return proposed.isAfter(safeLimit) ? safeLimit : proposed;
    }

    /**
     * Returns next Monday at 00:00 UTC, converted to the JVM's local
     * timezone.  If the current UTC day is already Monday, the result
     * points to the <em>following</em> Monday.
     */
    public static LocalDateTime nextWeekStart() {
        ZonedDateTime utcNow = ZonedDateTime.now(UTC);
        ZonedDateTime mondayUtc = utcNow
                .with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                .truncatedTo(ChronoUnit.DAYS);
        return mondayUtc.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    /**
     * Parses a {@code "H:mm:ss"} or {@code "HH:mm:ss"} string into a
     * total number of seconds.
     *
     * @param raw the time string to interpret
     * @return total seconds, or {@code -1} when the input is unparseable
     */
    public static long timeStringToSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        Matcher m = HMS_PATTERN.matcher(raw.trim());
        if (!m.find()) {
            return -1;
        }
        try {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            int sec = Integer.parseInt(m.group(3));
            return (long) h * 3_600 + (long) min * 60 + sec;
        } catch (NumberFormatException e) {
            System.out.println("Failed to interpret time token: " + raw);
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private static String humanizeElapsedMinutes(long mins) {
        if (mins < 1) {
            return "Just now";
        }
        if (mins < 60) {
            return mins + "m ago";
        }
        if (mins < 1_440) {
            return (mins / 60) + "h ago";
        }
        return (mins / 1_440) + "d ago";
    }

    // --- from GameTimeUtils.java ---
private static final DateTimeFormatter FULL_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final Pattern DAY_QUALIFIER =
            Pattern.compile("^(\\d+)d(.+)$", Pattern.CASE_INSENSITIVE);



    /**
     * Interprets a textual time span and produces an equivalent
     * {@link Duration}.
     *
     * @param input the span string (e.g. {@code "2d13:45:30"})
     * @return a non-negative {@link Duration}
     * @throws IllegalArgumentException when no known format matches
     */
    public static Duration parseDuration(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Input span is null or blank");
        }

        String normalised = input.trim().toLowerCase().replaceAll("\\s+", "");

        try {
            DayTimeSplit split = splitDayQualifier(normalised);

            Duration parsed;

            parsed = attemptColonSeparatedFull(split.timePart);
            if (parsed != null) return withAdditionalDays(parsed, split.dayCount);

            parsed = attemptColonHoursMinutes(split.timePart);
            if (parsed != null) return withAdditionalDays(parsed, split.dayCount);

            parsed = attemptColonMinutesSeconds(split.timePart);
            if (parsed != null) return withAdditionalDays(parsed, split.dayCount);

            parsed = attemptCompactSixDigit(split.timePart);
            if (parsed != null) return withAdditionalDays(parsed, split.dayCount);

            parsed = attemptCompactFourDigit(split.timePart);
            if (parsed != null) return withAdditionalDays(parsed, split.dayCount);

            parsed = attemptSecondsOnly(split.timePart);
            if (parsed != null) return withAdditionalDays(parsed, split.dayCount);

            throw new IllegalArgumentException(
                    "No recognised time-span layout matches: " + input);

        } catch (IllegalArgumentException propagate) {
            throw propagate;
        } catch (Exception wrapper) {
            throw new IllegalArgumentException(
                    "Unable to interpret time span: " + input, wrapper);
        }
    }

    /**
     * Interprets the input as a duration and adds it to the current
     * instant, yielding a future {@link LocalDateTime}.
     *
     * @param input the span string
     * @return the computed future instant, or {@code null} on failure
     */
    public static LocalDateTime resolveFromNow(String input) {
        Duration span = parseDuration(input);
        return span != null ? LocalDateTime.now().plus(span) : null;
    }

    // ------------------------------------------------------------------
    //  Day-qualifier handling
    // ------------------------------------------------------------------

    private static DayTimeSplit splitDayQualifier(String raw) {
        Matcher m = DAY_QUALIFIER.matcher(raw);
        if (m.matches()) {
            return new DayTimeSplit(
                    Integer.parseInt(m.group(1)), m.group(2).trim());
        }
        return new DayTimeSplit(0, raw);
    }

    private static Duration withAdditionalDays(Duration base, int days) {
        return days > 0 ? base.plusDays(days) : base;
    }

    // ------------------------------------------------------------------
    //  Parsing strategies (each returns null on mismatch)
    // ------------------------------------------------------------------

    /** {@code HH:mm:ss} with strict validation via {@link DateTimeFormatter}. */
    private static Duration attemptColonSeparatedFull(String segment) {
        try {
            LocalTime lt = LocalTime.parse(segment, FULL_TIME);
            return Duration.ofHours(lt.getHour())
                    .plusMinutes(lt.getMinute())
                    .plusSeconds(lt.getSecond());
        } catch (Exception ignored) {
            return null;
        }
    }

    /** {@code H:mm} or {@code HH:mm} – hours 0-23, minutes 0-59. */
    private static Duration attemptColonHoursMinutes(String segment) {
        if (!segment.matches("\\d{1,2}:\\d{2}")) {
            return null;
        }
        String[] parts = segment.split(":");
        int h   = Integer.parseInt(parts[0]);
        int min = Integer.parseInt(parts[1]);
        if (h < 0 || h > 23 || min < 0 || min > 59) {
            return null;
        }
        return Duration.ofHours(h).plusMinutes(min);
    }

    /** {@code m:ss} or {@code mm:ss} – both parts 0-59. */
    private static Duration attemptColonMinutesSeconds(String segment) {
        if (!segment.matches("\\d{1,2}:\\d{2}")) {
            return null;
        }
        String[] parts = segment.split(":");
        int min = Integer.parseInt(parts[0]);
        int sec = Integer.parseInt(parts[1]);
        if (min < 0 || min > 59 || sec < 0 || sec > 59) {
            return null;
        }
        return Duration.ofMinutes(min).plusSeconds(sec);
    }

    /** Compact {@code HHmmss} – exactly 6 digits. */
    private static Duration attemptCompactSixDigit(String segment) {
        if (segment.length() != 6 || !segment.matches("\\d{6}")) {
            return null;
        }
        int h   = Integer.parseInt(segment.substring(0, 2));
        int min = Integer.parseInt(segment.substring(2, 4));
        int sec = Integer.parseInt(segment.substring(4, 6));
        if (h < 0 || h > 23 || min < 0 || min > 59 || sec < 0 || sec > 59) {
            return null;
        }
        return Duration.ofHours(h).plusMinutes(min).plusSeconds(sec);
    }

    /**
     * Compact 4-digit.  Leading pair &gt; 23 ⇒ mm:ss, otherwise HH:mm.
     */
    private static Duration attemptCompactFourDigit(String segment) {
        if (segment.length() != 4 || !segment.matches("\\d{4}")) {
            return null;
        }
        int leading  = Integer.parseInt(segment.substring(0, 2));
        int trailing = Integer.parseInt(segment.substring(2, 4));
        if (trailing < 0 || trailing > 59) {
            return null;
        }
        if (leading > 23) {
            if (leading > 59) return null;
            return Duration.ofMinutes(leading).plusSeconds(trailing);
        }
        return Duration.ofHours(leading).plusMinutes(trailing);
    }

    /** One or two digit seconds (0-59). */
    private static Duration attemptSecondsOnly(String segment) {
        if (segment.length() > 2 || !segment.matches("\\d{1,2}")) {
            return null;
        }
        int sec = Integer.parseInt(segment);
        if (sec < 0 || sec > 59) {
            return null;
        }
        return Duration.ofSeconds(sec);
    }

    // ------------------------------------------------------------------
    //  Internal value holder
    // ------------------------------------------------------------------

    private static final class DayTimeSplit {
        final int dayCount;
        final String timePart;

        DayTimeSplit(int dayCount, String timePart) {
            this.dayCount = dayCount;
            this.timePart = timePart;
        }
    }

    // --- from GameTimeUtils.java ---
private static final DateTimeFormatter STRICT_HMS =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final Pattern TWO_PART_COLON  = Pattern.compile("(\\d{1,2}):(\\d{2})");
    private static final Pattern SIX_DIGITS      = Pattern.compile("\\d{6}");
    private static final Pattern FOUR_DIGITS      = Pattern.compile("\\d{4}");
    private static final Pattern ONE_OR_TWO_DIGITS = Pattern.compile("\\d{1,2}");




    /**
     * Checks whether the input can be interpreted as a valid time span in
     * any of the supported formats.
     *
     * @param input candidate string, may be {@code null}
     * @return {@code true} when at least one format matches
     */
    public static boolean isAcceptedFormat(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        try {
            String core = stripDayQualifier(input.trim());
            return checkFullColon(core)
                    || checkHoursMinutesColon(core)
                    || checkMinutesSecondsColon(core)
                    || checkCompactSix(core)
                    || checkCompactFour(core)
                    || checkSecondsOnly(core);
        } catch (Exception suppressed) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    //  Day qualifier
    // ------------------------------------------------------------------

    /** Strips an optional {@code "Nd"} prefix and returns the remainder. */
    private static String stripDayQualifier(String raw) {
        Matcher m = DAY_QUALIFIER.matcher(raw);
        return m.matches() ? m.group(2).trim() : raw;
    }

    // ------------------------------------------------------------------
    //  Individual format checkers
    // ------------------------------------------------------------------

    /** Strict {@code HH:mm:ss} via {@link DateTimeFormatter}. */
    private static boolean checkFullColon(String segment) {
        try {
            LocalTime.parse(segment, STRICT_HMS);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** {@code H:mm} or {@code HH:mm} with hours 0-23, minutes 0-59. */
    private static boolean checkHoursMinutesColon(String segment) {
        Matcher m = TWO_PART_COLON.matcher(segment);
        if (!m.matches()) return false;
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        return h >= 0 && h <= 23 && min >= 0 && min <= 59;
    }

    /** {@code m:ss} or {@code mm:ss} with both parts 0-59. */
    private static boolean checkMinutesSecondsColon(String segment) {
        Matcher m = TWO_PART_COLON.matcher(segment);
        if (!m.matches()) return false;
        int min = Integer.parseInt(m.group(1));
        int sec = Integer.parseInt(m.group(2));
        return min >= 0 && min <= 59 && sec >= 0 && sec <= 59;
    }

    /** Compact {@code HHmmss} – exactly 6 digits. */
    private static boolean checkCompactSix(String segment) {
        if (segment.length() != 6 || !SIX_DIGITS.matcher(segment).matches()) {
            return false;
        }
        int h   = Integer.parseInt(segment.substring(0, 2));
        int min = Integer.parseInt(segment.substring(2, 4));
        int sec = Integer.parseInt(segment.substring(4, 6));
        return h >= 0 && h <= 23 && min >= 0 && min <= 59 && sec >= 0 && sec <= 59;
    }

    /**
     * Compact 4-digit input.  Ambiguous between {@code HHmm} and
     * {@code mmss}; the leading pair is compared against 23 to choose.
     */
    private static boolean checkCompactFour(String segment) {
        if (segment.length() != 4 || !FOUR_DIGITS.matcher(segment).matches()) {
            return false;
        }
        int leading  = Integer.parseInt(segment.substring(0, 2));
        int trailing = Integer.parseInt(segment.substring(2, 4));
        if (leading > 23) {
            // Must be mm:ss
            return leading <= 59 && trailing >= 0 && trailing <= 59;
        }
        // Could be either interpretation — accept when trailing ≤ 59
        return trailing >= 0 && trailing <= 59;
    }

    /** One or two digit seconds (0-59). */
    private static boolean checkSecondsOnly(String segment) {
        if (segment.length() > 2 || !ONE_OR_TWO_DIGITS.matcher(segment).matches()) {
            return false;
        }
        int sec = Integer.parseInt(segment);
        return sec >= 0 && sec <= 59;
    }
}
