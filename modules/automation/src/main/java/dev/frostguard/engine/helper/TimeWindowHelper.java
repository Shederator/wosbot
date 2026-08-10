package dev.frostguard.engine.helper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

// Base for repeating calendar-window tasks. Subclasses define boundaries;
// this class classifies "now" as BEFORE/INSIDE/AFTER and provides scheduling metadata.
public abstract class TimeWindowHelper {

    public enum WindowState { BEFORE, INSIDE, AFTER }

    // Immutable snapshot of a window evaluation result.
    public static final class WindowResult {
        private final WindowState state;
        private final Instant start;
        private final Instant end;
        private final Instant nextStart;
        private final long minsToNext;
        private final int durationMin;

        WindowResult(WindowState s, Instant start, Instant end, Instant nextStart, long minsToNext, int durationMin) {
            if (s == null || start == null || end == null || nextStart == null)
                throw new NullPointerException("WindowResult fields must not be null");
            this.state = s;
            this.start = start;
            this.end = end;
            this.nextStart = nextStart;
            this.minsToNext = minsToNext;
            this.durationMin = durationMin;
        }

        public WindowState getState()                        { return state; }
        public Instant     getCurrentWindowStart()           { return start; }
        public Instant     getCurrentWindowEnd()             { return end; }
        public Instant     getNextWindowStart()              { return nextStart; }
        public long        getMinutesUntilNextWindow()       { return minsToNext; }
        public int         getCurrentWindowDurationMinutes() { return durationMin; }

        @Override
        public String toString() {
            DateTimeFormatter fmt =
                    DateTimeFormatter.ofPattern("EEEE dd/MM/yyyy HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
            return "[" + state + " | " + durationMin + "min | " +
                    fmt.format(start) + " → " + fmt.format(end) +
                    " | next " + fmt.format(nextStart) + " in " + minsToNext + "min]";
        }
    }

    protected final WindowResult determineWindowState(Instant now, Instant open,
                                                       Instant close, Instant nextOpen, int durMin) {
        long eta;
        WindowState s;
        if (now.isBefore(open)) {
            s = WindowState.BEFORE;
            eta = ChronoUnit.MINUTES.between(now, open);
        } else if (now.isAfter(close)) {
            s = WindowState.AFTER;
            eta = ChronoUnit.MINUTES.between(now, nextOpen);
        } else {
            s = WindowState.INSIDE;
            eta = ChronoUnit.MINUTES.between(now, nextOpen);
        }
        return new WindowResult(s, open, close, nextOpen, eta, durMin);
    }

    // Returns the next time this task should execute based on window state.
    protected Instant getNextExecutionTime(WindowResult r) {
        return r.getState() == WindowState.INSIDE ? Instant.now() : r.getNextWindowStart();
    }

    protected static void requirePositive(int v, String name) {
        if (v <= 0) throw new IllegalArgumentException(name + " must be > 0, got " + v);
    }

    protected static void requireNonNegative(int v, String name) {
        if (v < 0) throw new IllegalArgumentException(name + " must be >= 0, got " + v);
    }
}
