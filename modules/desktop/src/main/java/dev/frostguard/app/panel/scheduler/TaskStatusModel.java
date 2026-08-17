package dev.frostguard.app.panel.scheduler;

import java.util.List;
import java.util.Objects;

import dev.frostguard.api.domain.DailyTaskStatusData;
import dev.frostguard.engine.listener.TaskStatusChangeListener;
import dev.frostguard.engine.service.TaskManagementService;

public class TaskStatusModel implements ITaskStatusModel {

	private final TaskManagementService taskManagementService;

	public TaskStatusModel() {
		this(TaskManagementService.shared());
	}

	TaskStatusModel(TaskManagementService taskManagementService) {
		this.taskManagementService = Objects.requireNonNull(taskManagementService, "taskManagementService");
	}

	@Override
	public List<DailyTaskStatusData> getDailyTaskStatusList(Long profileId) {
		return taskManagementService.fetchDailyProgress(profileId);
	}

	@Override
	public void addTaskStatusChangeListener(TaskStatusChangeListener listener) {
		taskManagementService.subscribeStatusChanges(listener);
	}
}
