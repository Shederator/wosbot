package dev.frostguard.engine.helper;

import java.time.*;
import java.time.temporal.ChronoUnit;

// Alliance Championship window evaluator.
// Active from Monday 00:01 UTC through Tuesday 22:55:59.999 UTC each week.
public final class AllianceChampionshipHelper extends TimeWindowHelper {

    private AllianceChampionshipHelper() {}

    public static WindowResult calculateWindow()             { return calculateWindow(Clock.systemUTC()); }
    public static boolean      isInsideWindow()              { return calculateWindow().getState() == WindowState.INSIDE; }
    public static Instant      getNextExecutionTime()        { return new AllianceChampionshipHelper().getNextExecutionTime(calculateWindow()); }

    public static WindowResult calculateWindow(Clock clock) {
        ZonedDateTime utc = ZonedDateTime.now(clock.withZone(ZoneOffset.UTC));
        ZonedDateTime open  = utc.with(DayOfWeek.MONDAY).with(LocalTime.of(0, 1));
        ZonedDateTime close = utc.with(DayOfWeek.TUESDAY).with(LocalTime.of(22, 55, 59, 999_999_999));
        int mins = (int) open.until(close, ChronoUnit.MINUTES);
        return new AllianceChampionshipHelper().determineWindowState(
                utc.toInstant(), open.toInstant(), close.toInstant(), open.plusWeeks(1).toInstant(), mins);
    }
}
