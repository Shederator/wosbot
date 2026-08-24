package dev.frostguard.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.tasks.analytics.TelemetrySnapshotRoutine;

class TaskRegistrationsTelemetryTest {

    @BeforeAll
    static void createTestWorkspace() throws IOException {
        Files.createDirectories(Path.of(System.getProperty("frostguard.workspace")));
    }

    @Test
    void telemetryEnumCreatesBuiltInRecurringRoutine() {
        AccountDescriptor profile = new AccountDescriptor(42L, "telemetry-test", "0", true, 0L, 30L);

        DelayedTask task = TaskRegistrations.createTask(TpDailyTaskEnum.TELEMETRY_SNAPSHOT, profile);

        assertInstanceOf(TelemetrySnapshotRoutine.class, task);
        assertEquals(TpDailyTaskEnum.TELEMETRY_SNAPSHOT, task.getTpTask());
        assertTrue(task.isRecurring());
        assertTrue(Duration.between(LocalDateTime.now(), task.getScheduled()).abs().toSeconds() < 2);
    }
}
