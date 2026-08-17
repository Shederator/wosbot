package dev.frostguard.app.panel.scheduler;

import java.util.List;

import dev.frostguard.api.domain.DailyTaskStatusData;
import dev.frostguard.engine.listener.TaskStatusChangeListener;

public interface ITaskStatusModel {

	List<DailyTaskStatusData> getDailyTaskStatusList(Long profileId);

	void addTaskStatusChangeListener(TaskStatusChangeListener listener);

	default boolean hasStatusRows(Long profileId) {
		List<DailyTaskStatusData> rows = getDailyTaskStatusList(profileId);
		return rows != null && !rows.isEmpty();
	}

	default List<DailyTaskStatusData> safeDailyTaskStatusList(Long profileId) {
		List<DailyTaskStatusData> rows = getDailyTaskStatusList(profileId);
		return rows == null ? List.of() : rows;
	}

	static ITaskStatusModel empty() {
		return new ITaskStatusModel() {
			@Override
			public List<DailyTaskStatusData> getDailyTaskStatusList(Long profileId) {
				return List.of();
			}

			@Override
			public void addTaskStatusChangeListener(TaskStatusChangeListener listener) {
			}
		};
	}

}
