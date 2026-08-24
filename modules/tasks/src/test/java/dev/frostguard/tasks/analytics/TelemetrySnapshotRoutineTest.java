package dev.frostguard.tasks.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.TelemetrySnapshotSchedule;

class TelemetrySnapshotRoutineTest {

    @Test
    void usesSixHourDefaultAndAcceptsProfileInterval() {
        AccountDescriptor profile = profile();
        assertEquals(Duration.ofHours(6), TelemetrySnapshotSchedule.configuredInterval(profile));

        profile.setConfig(ConfigurationKeyEnum.TELEMETRY_INTERVAL_HOURS_INT, 12);
        assertEquals(Duration.ofHours(12), TelemetrySnapshotSchedule.configuredInterval(profile));
    }

    @Test
    void rejectsOutOfRangePersistedIntervalConservatively() {
        AccountDescriptor profile = profile();
        profile.setConfig(ConfigurationKeyEnum.TELEMETRY_INTERVAL_HOURS_INT, 0);
        assertEquals(Duration.ofHours(6), TelemetrySnapshotSchedule.configuredInterval(profile));

        profile.setConfig(ConfigurationKeyEnum.TELEMETRY_INTERVAL_HOURS_INT, 25);
        assertEquals(Duration.ofHours(6), TelemetrySnapshotSchedule.configuredInterval(profile));
    }

    private static AccountDescriptor profile() {
        return new AccountDescriptor(42L, "telemetry-test", "0", true, 0L, 30L);
    }
}
