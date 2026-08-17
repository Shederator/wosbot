package dev.frostguard.app.panel.scheduler;

import java.util.List;

import dev.frostguard.api.domain.DailyTaskStatusData;

@FunctionalInterface
public interface TaskCallback {

	void onTasksLoaded(List<DailyTaskStatusData> taskStatuses);

	default void onTaskLoadFailed(Throwable error) {
	}

	default void onTasksLoadedSafely(List<DailyTaskStatusData> taskStatuses) {
		onTasksLoaded(taskStatuses == null ? List.of() : taskStatuses);
	}

	static TaskCallback noop() {
		return taskStatuses -> {
		};
	}

	static TaskCallback nullSafe(TaskCallback callback) {
		return taskStatuses -> {
			if (callback != null) {
				callback.onTasksLoadedSafely(taskStatuses);
			}
		};
	}

}
