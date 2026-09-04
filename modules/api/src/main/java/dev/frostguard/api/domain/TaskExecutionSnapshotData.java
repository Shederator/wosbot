package dev.frostguard.api.domain;

import dev.frostguard.api.configs.TpDailyTaskEnum;

import java.util.List;

public record TaskExecutionSnapshotData(
        String runId,
        Long profileId,
        String profileName,
        TpDailyTaskEnum taskType,
        TaskExecutionState state,
        String currentStep,
        TaskStepStatus currentStepStatus,
        String lastStep,
        TaskStepStatus lastStepStatus,
        String message,
        List<TaskExecutionEventData> history) {

    public TaskExecutionSnapshotData {
        history = history == null ? List.of() : List.copyOf(history);
    }

    public static TaskExecutionSnapshotData idle() {
        return new TaskExecutionSnapshotData(
                null, null, null, null, TaskExecutionState.IDLE,
                null, null, null, null, null, List.of());
    }
}
