package dev.frostguard.engine.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.frostguard.api.domain.DailyTaskStatusData;
import dev.frostguard.api.domain.TaskStateData;
import dev.frostguard.data.repository.DailyTaskRepository;
import dev.frostguard.engine.listener.TaskStatusChangeListener;

/**
 * In-memory view of live task states, keyed by profile and task identity.
 */
public class TaskManagementService {

	private static final TaskManagementService INSTANCE = new TaskManagementService();

	private final Map<Long, Map<String, TaskStateData>> statesByProfile = new ConcurrentHashMap<>();
	private final CopyOnWriteArrayList<TaskStatusChangeListener> listeners = new CopyOnWriteArrayList<>();
	private final DailyTaskRepository dailyTasks = DailyTaskRepository.getRepository();

	private TaskManagementService() {
	}

	public static TaskManagementService shared() {
		return INSTANCE;
	}

	public void recordTaskState(Long accountId, TaskStateData state) {
		if (accountId == null || state == null) {
			return;
		}
		String key = stateKey(state.getTaskId(), state.getCustomTaskName());
		statesByProfile
				.computeIfAbsent(accountId, ignored -> new ConcurrentHashMap<>())
				.put(key, state);
		announce(accountId, state.getTaskId(), state);
	}

	public TaskStateData lookupTaskState(Long accountId, int taskTypeId) {
		return lookupTaskState(accountId, taskTypeId, null);
	}

	public TaskStateData lookupTaskState(Long accountId, int taskTypeId, String customLabel) {
		Map<String, TaskStateData> profileStates = statesByProfile.get(accountId);
		return profileStates == null ? null : profileStates.get(stateKey(taskTypeId, customLabel));
	}

	public void subscribeStatusChanges(TaskStatusChangeListener observer) {
		if (observer != null) {
			listeners.addIfAbsent(observer);
		}
	}

	public List<DailyTaskStatusData> fetchDailyProgress(Long accountId) {
		List<DailyTaskStatusData> rows = dailyTasks.findDailyTaskStatusByAccount(accountId);
		return rows == null ? new ArrayList<>() : rows;
	}

	private String stateKey(Integer taskTypeId, String customLabel) {
		String base = String.valueOf(taskTypeId);
		return customLabel == null || customLabel.isEmpty() ? base : base + ":" + customLabel;
	}

	private void announce(Long accountId, int taskTypeId, TaskStateData state) {
		listeners.forEach(listener -> listener.onTaskStatusTransition(accountId, taskTypeId, state));
	}

}
