package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.TaskStateData;

class TelemetryTaskQueueLifecycleTest {

    @Test
    void telemetryCompletionPersistsAndRequeuesWithoutLeakingExecutionState() {
        AccountDescriptor profile = new AccountDescriptor(42L, "telemetry-test", "0", true, 0L, 30L);
        RecordingStateStore stateStore = new RecordingStateStore();
        TaskQueue queue = new TaskQueue(profile, stateStore);
        SchedulingTelemetryTask task = new SchedulingTelemetryTask(profile);
        task.setRecurring(true);

        assertFalse(queue.hasLiveExecutor());
        assertTrue(queue.executeTask(task));

        assertEquals(List.of(true, false), stateStore.executingStates);
        assertEquals(List.of(true, true), stateStore.scheduledStates);
        assertEquals(task.getScheduled(), stateStore.persistedNextRun);
        assertEquals(null, stateStore.persistedCustomTaskName);
        assertTrue(task.getScheduled().isAfter(task.captureTime));
        assertEquals(List.of(TpDailyTaskEnum.TELEMETRY_SNAPSHOT), queue.getNextQueuedTaskTypes(10));
        assertFalse(queue.isExecutingTask(TpDailyTaskEnum.TELEMETRY_SNAPSHOT));
        assertFalse(queue.isActive());
        assertFalse(queue.hasLiveExecutor());
    }

    private static final class SchedulingTelemetryTask extends DelayedTask {
        private LocalDateTime captureTime;

        private SchedulingTelemetryTask(AccountDescriptor profile) {
            super(profile, TpDailyTaskEnum.TELEMETRY_SNAPSHOT);
            setTaskName("Telemetry");
        }

        @Override
        public void run() {
            captureTime = LocalDateTime.now();
            reschedule(TelemetrySnapshotSchedule.nextRun(captureTime, Duration.ofHours(1)));
        }

        @Override
        protected void execute() {
            throw new AssertionError("The test overrides run() to avoid emulator interaction");
        }
    }

    private static final class RecordingStateStore implements TaskQueue.TaskExecutionStateStore {
        private final List<Boolean> executingStates = new ArrayList<>();
        private final List<Boolean> scheduledStates = new ArrayList<>();
        private LocalDateTime persistedNextRun;
        private String persistedCustomTaskName;

        @Override
        public void record(Long profileId, TaskStateData state) {
            assertEquals(42L, profileId);
            executingStates.add(state.isExecuting());
            scheduledStates.add(state.isScheduled());
        }

        @Override
        public void persistDailyCompletion(AccountDescriptor profile, TpDailyTaskEnum task,
                LocalDateTime nextRun, String customTaskName, StaminaDeferral staminaDeferral) {
            assertEquals(42L, profile.getId());
            assertEquals(TpDailyTaskEnum.TELEMETRY_SNAPSHOT, task);
            assertEquals(null, staminaDeferral);
            persistedNextRun = nextRun;
            persistedCustomTaskName = customTaskName;
        }
    }
}
