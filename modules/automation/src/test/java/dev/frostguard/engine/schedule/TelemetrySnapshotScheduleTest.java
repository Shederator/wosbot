package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

class TelemetrySnapshotScheduleTest {

    private static final Duration HOURLY = Duration.ofHours(1);

    @Test
    void exposesTheReportBookendAnchors() {
        assertEquals(LocalTime.of(8, 30), TelemetrySnapshotSchedule.WAKE_ANCHOR);
        assertEquals(LocalTime.of(23, 0), TelemetrySnapshotSchedule.SLEEP_ANCHOR);
    }

    @Test
    void snapsToWakeAnchorBeforeTheHourlyInterval() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 20, 8, 0);
        assertEquals(LocalDateTime.of(2026, 8, 20, 8, 30),
                TelemetrySnapshotSchedule.nextRun(now, HOURLY));
    }

    @Test
    void snapsToSleepAnchorBeforeTheHourlyInterval() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 20, 22, 45);
        assertEquals(LocalDateTime.of(2026, 8, 20, 23, 0),
                TelemetrySnapshotSchedule.nextRun(now, HOURLY));
    }

    @Test
    void usesTheNormalIntervalAwayFromBothAnchors() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 20, 14, 0);
        assertEquals(now.plusHours(1), TelemetrySnapshotSchedule.nextRun(now, HOURLY));
    }

    @Test
    void alwaysSchedulesStrictlyInTheFuture() {
        LocalDateTime base = LocalDateTime.of(2026, 8, 20, 0, 0);
        for (int minute = 0; minute < 24 * 60; minute++) {
            LocalDateTime now = base.plusMinutes(minute);
            LocalDateTime next = TelemetrySnapshotSchedule.nextRun(now, HOURLY);
            assertTrue(next.isAfter(now), "non-future schedule at " + now + " -> " + next);
        }
    }

    @Test
    void doesNotRepeatAnAnchorWhenRunExactlyOnIt() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 20, 8, 30);
        assertEquals(LocalDateTime.of(2026, 8, 20, 9, 30),
                TelemetrySnapshotSchedule.nextRun(now, HOURLY));
    }
}
