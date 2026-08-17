package dev.frostguard.engine.listener;

import dev.frostguard.api.domain.TaskStateData;

// Observes per-task lifecycle transitions within the scheduling engine.
// Each notification carries the owning account and task type for
// multi-profile dashboard routing.
public interface TaskStatusChangeListener {

    // Delivers the updated state for a specific task on a specific account.
    void onTaskStatusTransition(Long accountId, int taskTypeId, TaskStateData status);

    // Called when all tasks for an account are being cleared at once.
    default void onAllTasksCleared(Long accountId) {}

    // Whether this listener is interested in events for the given account.
    default boolean isInterestedIn(Long accountId) {
        return true;
    }
}
