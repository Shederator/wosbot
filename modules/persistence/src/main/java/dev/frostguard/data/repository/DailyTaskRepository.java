package dev.frostguard.data.repository;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.DailyTaskStatusData;
import dev.frostguard.data.access.DataStore;
import dev.frostguard.data.entity.DailyTask;
import dev.frostguard.data.entity.DailyTaskTemplate;

/**
 * Manages {@link DailyTask} persistence — schedule records,
 * status projections, and template lookups for the Frostguard
 * routine system.
 */
public class DailyTaskRepository {

	private static DailyTaskRepository instance;
	private final DataStore store = DataStore.getInstance();

	private DailyTaskRepository() {}

	public static DailyTaskRepository getRepository() {
		if (instance == null) {
			instance = new DailyTaskRepository();
		}
		return instance;
	}

	/** All schedule records for a given profile. */
	public List<DailyTask> routinesForProfile(Long profileId) {
		if (profileId == null) return Collections.emptyList();
		return queryByOwner(profileId);
	}

	/** Finds a specific non-custom routine for a profile and task type. */
	public Optional<DailyTask> findRoutine(Long profileId, TpDailyTaskEnum taskType) {
		if (profileId == null || taskType == null) return Optional.empty();
		return singleResult(store.executeQuery(
			"SELECT d FROM DailyTask d " +
			"WHERE d.owner.id = :profileId " +
			"AND d.routine.id = :taskTypeId " +
			"AND (d.customTaskLabel IS NULL OR d.customTaskLabel = '')",
			DailyTask.class,
			Map.of("profileId", profileId, "taskTypeId", taskType.getId())));
	}

	/** Finds a custom-labeled routine for a profile and task type. */
	public Optional<DailyTask> findCustomRoutine(Long profileId, TpDailyTaskEnum taskType, String label) {
		if (profileId == null || taskType == null || label == null) return Optional.empty();
		return singleResult(store.executeQuery(
			"SELECT d FROM DailyTask d " +
			"WHERE d.owner.id = :profileId " +
			"AND d.routine.id = :taskTypeId " +
			"AND d.customTaskLabel = :label",
			DailyTask.class,
			Map.of("profileId", profileId, "taskTypeId", taskType.getId(), "label", label)));
	}

	/** Status projection for all routines of a given profile. */
	public List<DailyTaskStatusData> statusSummary(Long profileId) {
		if (profileId == null) return Collections.emptyList();
		return store.executeQuery(
			"SELECT new dev.frostguard.api.domain.DailyTaskStatusData(" +
				"d.owner.id, d.routine.id, d.completedAt, d.nextRunAt, d.customTaskLabel, " +
				"d.staminaMinimumRequired, d.staminaRegenerationTarget, d.staminaEarliestRunnableAt" +
			") FROM DailyTask d WHERE d.owner.id = :profileId",
			DailyTaskStatusData.class,
			Map.of("profileId", profileId));
	}

	public boolean addDailyTask(DailyTask task) {
		return store.persist(task);
	}

	public boolean saveDailyTask(DailyTask task) {
		return store.merge(task);
	}

	public boolean deleteDailyTask(DailyTask task) {
		return store.remove(task);
	}

	public DailyTask getDailyTaskById(Long id) {
		if (id == null) return null;
		return store.lookup(DailyTask.class, id);
	}

	public DailyTaskTemplate findWatcherDailyTaskById(Integer id) {
		if (id == null) return null;
		return store.lookup(DailyTaskTemplate.class, id);
	}

	// Compatibility delegates
	public List<DailyTask> findByAccountId(Long accountId) {
		return routinesForProfile(accountId);
	}

	public DailyTask findByAccountIdAndTaskType(Long accountId, TpDailyTaskEnum taskType) {
		return findRoutine(accountId, taskType).orElse(null);
	}

	public DailyTask findByAccountIdTaskTypeAndCustomLabel(Long accountId, TpDailyTaskEnum taskType, String customLabel) {
		return findCustomRoutine(accountId, taskType, customLabel).orElse(null);
	}

	public List<DailyTaskStatusData> findDailyTaskStatusByAccount(Long accountId) {
		return statusSummary(accountId);
	}

	private List<DailyTask> queryByOwner(Long profileId) {
		return store.executeQuery(
			"SELECT d FROM DailyTask d WHERE d.owner.id = :profileId",
			DailyTask.class,
			Map.of("profileId", profileId));
	}

	private static <T> Optional<T> singleResult(List<T> results) {
		return results != null && !results.isEmpty()
			? Optional.of(results.get(0))
			: Optional.empty();
	}
}
