package dev.frostguard.tasks.dailies;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

final class IntelCyclePolicy {

    private static final int[] INTEL_REFRESH_HOURS_UTC = {0, 8, 16};

    private boolean cycleInProgress;

    Decision evaluateDailyAvailability(boolean available, LocalDateTime now, ZoneId localZone) {
        if (available) {
            cycleInProgress = true;
            return new Decision(Action.START_AVAILABLE_CYCLE, null);
        }
        if (cycleInProgress) {
            return new Decision(Action.RESUME_ACTIVE_CYCLE, null);
        }
        return new Decision(Action.WAIT_FOR_NEXT_REFRESH, nextRefresh(now, localZone));
    }

    void completeCycle() {
        cycleInProgress = false;
    }

    boolean isCycleInProgress() {
        return cycleInProgress;
    }

    static LocalDateTime nextRefresh(LocalDateTime now, ZoneId localZone) {
        ZonedDateTime nowUtc = now.atZone(localZone).withZoneSameInstant(ZoneOffset.UTC);
        LocalDate utcDate = nowUtc.toLocalDate();

        for (int hour : INTEL_REFRESH_HOURS_UTC) {
            ZonedDateTime candidate = ZonedDateTime.of(
                    utcDate, LocalTime.of(hour, 0, 1), ZoneOffset.UTC);
            if (candidate.isAfter(nowUtc)) {
                return candidate.withZoneSameInstant(localZone).toLocalDateTime();
            }
        }

        return ZonedDateTime.of(utcDate.plusDays(1), LocalTime.of(0, 0, 1), ZoneOffset.UTC)
                .withZoneSameInstant(localZone)
                .toLocalDateTime();
    }

    enum Action {
        START_AVAILABLE_CYCLE,
        RESUME_ACTIVE_CYCLE,
        WAIT_FOR_NEXT_REFRESH
    }

    record Decision(Action action, LocalDateTime nextRun) {
    }
}
