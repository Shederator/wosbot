package dev.frostguard.app.panel.taskworkbench;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.TaskExecutionEventData;
import dev.frostguard.api.domain.TaskExecutionSnapshotData;
import dev.frostguard.api.domain.TaskExecutionState;
import dev.frostguard.api.domain.TaskStepStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskFlowGraphRendererTest {

    @Test
    void waitingLoopNodeKeepsItsPreviousCompletionCount() {
        TaskExecutionSnapshotData snapshot = snapshot(
                null, null, null,
                "loop", "Loop", TaskStepStatus.WAITING,
                List.of(
                        event(1, TaskStepStatus.COMPLETED),
                        event(2, TaskStepStatus.WAITING)));

        var progress = TaskFlowGraphRenderer.progressFor("loop", snapshot);

        assertEquals("waiting", progress.style());
        assertEquals("WAITING - 1 completed", progress.label());
    }

    @Test
    void startedStepIsPresentedAsRunning() {
        TaskExecutionSnapshotData snapshot = snapshot(
                "loop", "Loop", TaskStepStatus.STARTED,
                null, null, null,
                List.of(event(1, TaskStepStatus.STARTED)));

        var progress = TaskFlowGraphRenderer.progressFor("loop", snapshot);

        assertEquals("started", progress.style());
        assertEquals("RUNNING", progress.label());
    }

    private static TaskExecutionSnapshotData snapshot(
            String currentId, String currentName, TaskStepStatus currentStatus,
            String nextId, String nextName, TaskStepStatus nextStatus,
            List<TaskExecutionEventData> history) {
        return new TaskExecutionSnapshotData(
                "run", 1L, "Profile", TpDailyTaskEnum.NOMADIC_MERCHANT, TaskExecutionState.PAUSED,
                currentId, currentName, currentStatus,
                nextId, nextName, nextStatus,
                null, null, null, null, history);
    }

    private static TaskExecutionEventData event(long sequence, TaskStepStatus status) {
        return new TaskExecutionEventData(
                sequence, LocalDateTime.now(), "loop", "Loop", status, null);
    }
}
