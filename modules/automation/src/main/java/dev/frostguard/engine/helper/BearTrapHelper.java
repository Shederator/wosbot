package dev.frostguard.engine.helper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Cyclic window calculator for the Bear Trap event.
// Opens variableMinutes before an anchor, closes fixedMinutes after it, repeats every cycleDays.
public final class BearTrapHelper extends TimeWindowHelper {

    private BearTrapHelper() {}

    public static WindowResult calculateWindow(Instant anchor, int varMin) {
        return calculateWindow(anchor, varMin, 30, 2, Clock.systemUTC());
    }

    public static boolean shouldRun(Instant anchor, int varMin) {
        return calculateWindow(anchor, varMin).getState() == WindowState.INSIDE;
    }

    public static WindowResult calculateWindow(Instant anchor, int varMin,
                                               int fixMin, int days, Clock clock) {
        requirePositive(fixMin, "fixMin");
        requireNonNegative(varMin, "varMin");
        requirePositive(days, "days");

        Instant now = Instant.now(clock);
        Instant firstOpen = anchor.minus(varMin, ChronoUnit.MINUTES);
        int span = fixMin + varMin;
        long cycleMinutes = Duration.ofDays(days).toMinutes();

        if (now.isBefore(firstOpen)) {
            return eval(now, firstOpen, firstOpen.plus(span, ChronoUnit.MINUTES), firstOpen, span);
        }

        long elapsed = firstOpen.until(now, ChronoUnit.MINUTES);
        long idx = elapsed / cycleMinutes;
        Instant open = firstOpen.plus(idx * cycleMinutes, ChronoUnit.MINUTES);
        return eval(now, open, open.plus(span, ChronoUnit.MINUTES),
                open.plus(cycleMinutes, ChronoUnit.MINUTES), span);
    }

    private static WindowResult eval(Instant now, Instant o, Instant c, Instant n, int s) {
        return new BearTrapHelper().determineWindowState(now, o, c, n, s);
    }
}
