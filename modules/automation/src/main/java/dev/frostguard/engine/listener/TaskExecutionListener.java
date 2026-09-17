package dev.frostguard.engine.listener;

import dev.frostguard.api.domain.TaskExecutionSnapshotData;

@FunctionalInterface
public interface TaskExecutionListener {
    void onTaskExecutionChanged(TaskExecutionSnapshotData snapshot);
}
