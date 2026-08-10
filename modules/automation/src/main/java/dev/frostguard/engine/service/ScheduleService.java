package dev.frostguard.engine.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.StopBehaviorEnum;
import dev.frostguard.api.configs.TpConfigEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.BotStateData;
import dev.frostguard.api.domain.DailyTaskStatusData;
import dev.frostguard.api.domain.QueueProfileStateData;
import dev.frostguard.api.domain.QueueStateData;
import dev.frostguard.api.domain.TaskStateData;
import dev.frostguard.data.entity.Config;
import dev.frostguard.data.entity.ConfigTemplate;
import dev.frostguard.data.entity.DailyTask;
import dev.frostguard.data.entity.DailyTaskTemplate;
import dev.frostguard.data.entity.Profile;
import dev.frostguard.data.repository.ConfigRepository;
import dev.frostguard.data.repository.DailyTaskRepository;
import dev.frostguard.data.repository.ProfileRepository;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.listener.BotStateListener;
import dev.frostguard.engine.listener.QueueStateListener;
import dev.frostguard.engine.schedule.BearTrapParticipationSchedule;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.DelayedTaskRegistry;
import dev.frostguard.engine.schedule.StaminaDeferral;
import dev.frostguard.engine.schedule.TaskDispatcher;
import dev.frostguard.engine.schedule.TaskQueue;

/**
 * Coordinates emulator startup, profile queues, and persisted task schedules.
 */
public class ScheduleService {

	private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
	private static volatile ScheduleService singleton;

	private final TaskDispatcher dispatcher = new TaskDispatcher();
	private final CopyOnWriteArrayList<BotStateListener> botListeners = new CopyOnWriteArrayList<>();
	private final CopyOnWriteArrayList<QueueStateListener> queueListeners = new CopyOnWriteArrayList<>();
	private final DailyTaskRepository dailyTasks = DailyTaskRepository.getRepository();
	private final ProfileRepository profiles = ProfileRepository.getRepository();
	private final ConfigRepository configs = ConfigRepository.getRepository();

	private ScheduleService() {
	}

	public static ScheduleService obtain() {
		ScheduleService service = singleton;
		if (service != null) {
			return service;
		}
		synchronized (ScheduleService.class) {
			if (singleton == null) {
				singleton = new ScheduleService();
			}
			return singleton;
		}
	}

	public void launchEngine() {
		if (!initializeBridge()) {
			notifyBotState(false, false);
			return;
		}

		LinkedHashMap<String, String> globalConfig = ConfigService.obtain().loadGlobalSettings();
		applyEmulatorPaths(globalConfig);

		List<AccountDescriptor> accounts = ProfileService.obtain().fetchAllAccounts();
		List<AccountDescriptor> enabled = enabledAccounts(accounts);
		if (enabled.isEmpty()) {
			log(TpMessageSeverityEnum.WARNING, "ScheduleService", "-", "No enabled profiles found");
			return;
		}

		enabled.stream()
				.sorted(Comparator.comparing(AccountDescriptor::getPriority).reversed())
				.forEach(account -> prepareQueue(account, globalConfig));

		dispatcher.startAll();
		notifyQueueState(null, false);
		trackStart(enabled, globalConfig);
		notifyBotState(true, false);
	}

	public void addEngineObserver(BotStateListener observer) {
		if (observer != null) {
			botListeners.addIfAbsent(observer);
		}
	}

	public void addQueueObserver(QueueStateListener observer) {
		if (observer != null) {
			queueListeners.addIfAbsent(observer);
		}
	}

	public void haltEngine() {
		haltEngine(StopBehaviorEnum.DO_NOTHING);
	}

	// Changed by pernerch | Date: 2026-07-04 | Why: apply GUI-specific stop behavior configured in Instance Settings.
	public void haltEngineFromGui() {
		haltEngine(resolveStopBehavior(ConfigurationKeyEnum.STOP_BEHAVIOR_STRING));
	}

	// Changed by pernerch | Date: 2026-07-04 | Why: apply Telegram-specific stop behavior configured in Instance Settings.
	public void haltEngineFromTelegram() {
		haltEngine(resolveStopBehavior(ConfigurationKeyEnum.STOP_BEHAVIOR_TELEGRAM_STRING));
	}

	public void haltEngine(StopBehaviorEnum stopBehavior) {
		dispatcher.stopAll();
		if (stopBehavior == StopBehaviorEnum.CLOSE_EMULATOR) {
			closeEnabledEmulators();
		}
		try {
			AnalyticsService.getInstance().trackBotStopped("manual");
		} catch (Exception ignored) {
		}
		notifyBotState(false, false);
		notifyQueueState(null, false);
	}

	private StopBehaviorEnum resolveStopBehavior(ConfigurationKeyEnum key) {
		// Changed by pernerch | Date: 2026-07-04 | Why: centralize config lookup and default fallback for stop policies.
		if (key == null) {
			return StopBehaviorEnum.DO_NOTHING;
		}
		String rawValue = Optional.ofNullable(ConfigService.obtain().loadGlobalSettings())
				.map(cfg -> cfg.getOrDefault(key.name(), key.getDefaultValue()))
				.orElse(key.getDefaultValue());
		return StopBehaviorEnum.parse(rawValue);
	}

	private void closeEnabledEmulators() {
		// Changed by pernerch | Date: 2026-07-04 | Why: close each enabled profile emulator once when stop policy requests emulator shutdown.
		Set<String> emulatorsToClose = ProfileService.obtain().fetchAllAccounts().stream()
				.filter(account -> Boolean.TRUE.equals(account.getEnabled()))
				.map(AccountDescriptor::getEmulatorNumber)
				.filter(emulator -> emulator != null && !emulator.isBlank())
				.collect(Collectors.toCollection(LinkedHashSet::new));

		for (String emulator : emulatorsToClose) {
			try {
				EmulatorController.getInstance().closeEmulator(emulator);
				log(TpMessageSeverityEnum.INFO, "ScheduleService", "-",
						"Stopped bot and closed emulator " + emulator);
			} catch (Exception ex) {
				log(TpMessageSeverityEnum.WARNING, "ScheduleService", "-",
						"Failed to close emulator " + emulator + ": " + ex.getMessage());
			}
		}
	}

	public void suspendEngine() {
		dispatcher.pauseAll();
		notifyBotState(true, true);
		notifyQueueState(null, true);
	}

	public void resumeEngine() {
		dispatcher.resumeAll();
		notifyBotState(true, false);
		notifyQueueState(null, false);
	}

	public void suspendAccountQueue(Long accountId) {
		changeAccountPause(accountId, true);
	}

	public void resumeAccountQueue(Long accountId) {
		changeAccountPause(accountId, false);
	}

	// Changed by pernerch | Date: 2026-07-02 | Why: allow runtime profile-switch events
	// to refresh UI context (active profile name + stamina title) immediately.
	public void notifyActiveProfile(Long accountId) {
		if (accountId == null) {
			return;
		}

		boolean paused = dispatcher.getActiveQueueStates().stream()
				.filter(state -> state != null && accountId.equals(state.getProfileId()))
				.findFirst()
				.map(QueueProfileStateData::isPaused)
				.orElse(false);

		notifyQueueState(accountId, paused);
	}

	public void persistDailyCompletion(AccountDescriptor acct, TpDailyTaskEnum taskType, LocalDateTime nextRun) {
		persistDailyCompletion(acct, taskType, nextRun, null);
	}

	public void persistDailyCompletion(AccountDescriptor acct, TpDailyTaskEnum taskType,
			LocalDateTime nextRun, String customLabel) {
		persistDailyCompletion(acct, taskType, nextRun, customLabel, null);
	}

	public void persistDailyCompletion(AccountDescriptor acct, TpDailyTaskEnum taskType,
			LocalDateTime nextRun, String customLabel, StaminaDeferral staminaDeferral) {
		if (acct == null || taskType == null) {
			return;
		}
		DailyTask record = findDailyRecord(acct.getId(), taskType, customLabel);
		if (record == null) {
			record = newDailyRecord(acct, taskType, nextRun, customLabel);
			applyStaminaDeferral(record, staminaDeferral);
			dailyTasks.addDailyTask(record);
			return;
		}
		record.setPreviousRun(LocalDateTime.now());
		record.setScheduledAt(nextRun);
		applyStaminaDeferral(record, staminaDeferral);
		dailyTasks.saveDailyTask(record);
	}

	public void persistScheduleAdjustment(AccountDescriptor acct, TpDailyTaskEnum taskType,
			LocalDateTime nextRun, String customLabel) {
		persistScheduleAdjustment(acct, taskType, nextRun, customLabel, null);
	}

	public void persistScheduleAdjustment(AccountDescriptor acct, TpDailyTaskEnum taskType,
			LocalDateTime nextRun, String customLabel, StaminaDeferral staminaDeferral) {
		if (acct == null || taskType == null) {
			return;
		}
		DailyTask record = findDailyRecord(acct.getId(), taskType, customLabel);
		if (record == null) {
			record = newDailyRecord(acct, taskType, nextRun, customLabel);
			record.setPreviousRun(null);
			applyStaminaDeferral(record, staminaDeferral);
			dailyTasks.addDailyTask(record);
			return;
		}
		record.setScheduledAt(nextRun);
		applyStaminaDeferral(record, staminaDeferral);
		dailyTasks.saveDailyTask(record);
	}

	public void persistNextSchedule(AccountDescriptor acct, TpDailyTaskEnum taskType,
			LocalDateTime nextRun, String customLabel) {
		if (acct == null || taskType == null) {
			return;
		}
		DailyTask record = dailyTasks.findByAccountIdAndTaskType(acct.getId(), taskType);
		if (record == null) {
			dailyTasks.addDailyTask(newDailyRecord(acct, taskType, nextRun, customLabel));
			return;
		}
		record.setScheduledAt(nextRun);
		dailyTasks.saveDailyTask(record);
	}

	public void realignBearTrapParticipationSchedule(Long accountId) {
		if (accountId == null) {
			return;
		}

		AccountDescriptor account = ProfileService.obtain().fetchAllAccounts().stream()
				.filter(candidate -> accountId.equals(candidate.getId()))
				.findFirst()
				.orElse(null);
		if (account == null) {
			return;
		}
		if (!Boolean.TRUE.equals(account.getConfig(ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, Boolean.class))) {
			if (dispatcher.getQueue(accountId) != null) {
				evictTask(accountId, TpDailyTaskEnum.BEAR_TRAP);
			}
			return;
		}

		BearTrapParticipationSchedule.resolve(account).ifPresentOrElse(plan -> {
			TaskQueue queue = dispatcher.getQueue(accountId);
			boolean queueUpdated = queue != null
					&& queue.scheduleOrRescheduleQueuedTask(TpDailyTaskEnum.BEAR_TRAP, account, plan.nextRun());
			persistNextSchedule(account, TpDailyTaskEnum.BEAR_TRAP, plan.nextRun(), null);

			TaskStateData previous = TaskManagementService.shared().lookupTaskState(
					accountId,
					TpDailyTaskEnum.BEAR_TRAP.getId());
			TaskStateData aligned = TaskStateData.of(
					accountId,
					TpDailyTaskEnum.BEAR_TRAP.getId(),
					null,
					true,
					false,
					previous != null ? previous.getLastExecutionTime() : null,
					plan.nextRun());
			TaskManagementService.shared().recordTaskState(accountId, aligned);

			log(TpMessageSeverityEnum.INFO, "Bear Trap Event", account.getName(),
					"Participation schedule aligned with Timer " + plan.trapNumber()
							+ " for " + formatTime(plan.nextRun())
							+ (queueUpdated ? "" : " (persisted for the next queue load)"));
		}, () -> {
			TaskQueue queue = dispatcher.getQueue(accountId);
			boolean removed = queue != null && queue.dequeue(TpDailyTaskEnum.BEAR_TRAP);
			TaskStateData previous = TaskManagementService.shared().lookupTaskState(
					accountId,
					TpDailyTaskEnum.BEAR_TRAP.getId());
			TaskStateData unavailable = TaskStateData.of(
					accountId,
					TpDailyTaskEnum.BEAR_TRAP.getId(),
					null,
					false,
					false,
					previous != null ? previous.getLastExecutionTime() : null,
					null);
			TaskManagementService.shared().recordTaskState(accountId, unavailable);
			log(TpMessageSeverityEnum.WARNING, "Bear Trap Event", account.getName(),
					"Participation was unscheduled because the selected UTC timer is incomplete"
							+ (removed ? "" : " (no queued task was present)"));
		});
	}

	public void evictTask(Long accountId, TpDailyTaskEnum taskType) {
		evictTask(accountId, taskType, null);
	}

	public void evictTask(Long accountId, TpDailyTaskEnum taskType, String customLabel) {
		if (accountId == null || taskType == null) {
			return;
		}
		try {
			boolean dequeued = removeFromQueue(accountId, taskType, customLabel);
			log(TpMessageSeverityEnum.INFO, "ScheduleService", "Profile " + accountId,
					"Removed " + taskType.getName() + " from queue: " + dequeued);
			TaskManagementService.shared().recordTaskState(accountId, clearedState(accountId, taskType, customLabel));
			notifyBotState(true, false);
		} catch (Exception ex) {
			log(TpMessageSeverityEnum.ERROR, "ScheduleService", "Profile " + accountId,
					"Task removal failed for " + taskType.getName() + ": " + ex.getMessage());
		}
	}

	public void persistEmulatorPath(String settingIdentifier, String path) {
		Config row = findGlobalRow(settingIdentifier);
		if (row == null) {
			ConfigTemplate template = configs.getWatcherSetting(TpConfigEnum.GLOBAL_CONFIG);
			Config created = new Config();
			created.setIdentifier(settingIdentifier);
			created.setContent(path);
			created.setWatcherSetting(template);
			configs.addSetting(created);
			return;
		}
		row.setContent(path);
		configs.saveSetting(row);
	}

	public TaskDispatcher getCoordinator() {
		return dispatcher;
	}

	private boolean initializeBridge() {
		try {
			EmulatorController.getInstance().initialize();
			return true;
		} catch (Exception ex) {
			log(TpMessageSeverityEnum.ERROR, "ScheduleService", "-",
					"Engine launch aborted: " + ex.getMessage());
			ex.printStackTrace();
			return false;
		}
	}

	private List<AccountDescriptor> enabledAccounts(List<AccountDescriptor> accounts) {
		return accounts == null
				? List.of()
				: accounts.stream().filter(account -> Boolean.TRUE.equals(account.getEnabled())).collect(Collectors.toList());
	}

	private void prepareQueue(AccountDescriptor account, Map<String, String> globalConfig) {
		account.setGlobalSettings(globalConfig instanceof HashMap
				? (HashMap<String, String>) globalConfig
				: new HashMap<>(globalConfig == null ? Map.of() : globalConfig));

		dispatcher.registerAccount(account);
		TaskQueue queue = dispatcher.getQueue(account.getId());
		queue.enqueue(startupTaskFor(account));

		List<DailyTaskStatusData> progress = safeProgress(account.getId());
		Map<Integer, DailyTaskStatusData> progressByType = progress.stream()
				.filter(row -> row != null)
				.collect(Collectors.toMap(DailyTaskStatusData::getIdTpDailyTask, row -> row, (first, ignored) -> first));

		configDrivenFactories(account).forEach((configKey, factories) -> {
			if (Boolean.TRUE.equals(account.getConfig(configKey, Boolean.class))) {
				factories.stream().map(Supplier::get).forEach(task -> enqueuePlannedTask(account, queue, task, progressByType));
			}
		});

		addCustomTasks(account, queue, progress);
	}

	private DelayedTask startupTaskFor(AccountDescriptor account) {
		if (Boolean.TRUE.equals(account.getConfig(ConfigurationKeyEnum.SKIP_TUTORIAL_ENABLED_BOOL, Boolean.class))) {
			log(TpMessageSeverityEnum.INFO, "ScheduleService", account.getName(),
					"Tutorial bypass enabled; standard initialization skipped");
			return DelayedTaskRegistry.create(TpDailyTaskEnum.SKIP_TUTORIAL, account);
		}
		return DelayedTaskRegistry.create(TpDailyTaskEnum.INITIALIZE, account);
	}

	private EnumMap<ConfigurationKeyEnum, List<Supplier<DelayedTask>>> configDrivenFactories(AccountDescriptor account) {
		return Stream.of(TpDailyTaskEnum.values())
				.filter(type -> type.getConfigKey() != null)
				.collect(Collectors.groupingBy(
						TpDailyTaskEnum::getConfigKey,
						() -> new EnumMap<>(ConfigurationKeyEnum.class),
						Collectors.mapping(type -> (Supplier<DelayedTask>) () -> DelayedTaskRegistry.create(type, account),
								Collectors.toList())));
	}

	private void enqueuePlannedTask(AccountDescriptor account, TaskQueue queue, DelayedTask task,
			Map<Integer, DailyTaskStatusData> progressByType) {
		TaskStateData state = baseScheduledState(account.getId(), task.getTpTask().getId(), null);
		DailyTaskStatusData saved = progressByType.get(task.getTpDailyTaskId());
		boolean isBearTrap = task.getTpTask() == TpDailyTaskEnum.BEAR_TRAP;
		Optional<BearTrapParticipationSchedule.Plan> bearPlan = isBearTrap
				? BearTrapParticipationSchedule.resolve(account)
				: Optional.empty();
		if (isBearTrap && bearPlan.isEmpty()) {
			state.setScheduled(false);
			state.setNextExecutionTime(null);
			TaskManagementService.shared().recordTaskState(account.getId(), state);
			log(TpMessageSeverityEnum.WARNING, task.getTaskName(), account.getName(),
					"Participation was not queued because the selected UTC timer is incomplete");
			return;
		}
		if (bearPlan.isPresent()) {
			BearTrapParticipationSchedule.Plan plan = bearPlan.get();
			task.reschedule(plan.nextRun());
			if (saved != null) {
				task.setLastExecutionTime(saved.getLastExecution());
				state.setLastExecutionTime(saved.getLastExecution());
			}
			state.setNextExecutionTime(plan.nextRun());
			persistNextSchedule(account, TpDailyTaskEnum.BEAR_TRAP, plan.nextRun(), null);
			log(TpMessageSeverityEnum.INFO, task.getTaskName(), account.getName(),
					"Participation schedule aligned with Timer " + plan.trapNumber()
							+ ": " + formatTime(plan.nextRun()));
		} else if (saved == null) {
			scheduleFreshTask(task, account);
			state.setNextExecutionTime(task.getScheduled());
		} else {
			task.reschedule(saved.getNextSchedule());
			task.setLastExecutionTime(saved.getLastExecution());
			if (saved.hasStaminaDeferral()) {
				task.restoreStaminaDeferral(new StaminaDeferral(
						saved.getStaminaMinimumRequired(),
						saved.getStaminaRegenerationTarget(),
						saved.getStaminaEarliestRunnableAt()));
			}
			state.setLastExecutionTime(saved.getLastExecution());
			state.setNextExecutionTime(saved.getNextSchedule());
			log(TpMessageSeverityEnum.INFO, task.getTaskName(), account.getName(),
					"Schedule restored: " + formatTime(saved.getNextSchedule()));
		}
		TaskManagementService.shared().recordTaskState(account.getId(), state);
		queue.enqueue(task);
	}

	private void scheduleFreshTask(DelayedTask task, AccountDescriptor account) {
		if (task.getScheduled() == null) {
			task.reschedule(LocalDateTime.now());
			log(TpMessageSeverityEnum.INFO, task.getTaskName(), account.getName(), "Queued for immediate execution");
		} else {
			log(TpMessageSeverityEnum.INFO, task.getTaskName(), account.getName(),
					"Initial schedule: " + formatTime(task.getScheduled()));
		}
	}

	private void addCustomTasks(AccountDescriptor account, TaskQueue queue, List<DailyTaskStatusData> progress) {
		CustomTaskService customTasks = CustomTaskService.getInstance();
		var enabled = customTasks.getEnabledTasks();
		log(TpMessageSeverityEnum.INFO, "ScheduleService", account.getName(),
				"Custom tasks discovered: " + enabled.size());

		for (CustomTaskService.CustomTaskSettings settings : enabled) {
			DailyTaskStatusData saved = findCustomTaskStatus(settings, progress);
			DelayedTask task = scheduleCustomTask(account, queue, settings, saved, false, true);
			if (task == null) {
				log(TpMessageSeverityEnum.ERROR, "ScheduleService", account.getName(),
						"Custom task could not be created: " + settings.getCustomName());
				continue;
			}
			log(TpMessageSeverityEnum.INFO, settings.getCustomName(), account.getName(),
					"Custom task queued (offset=" + settings.getOffsetMinutes() + "m, priority=" + settings.getPriority() + ")");
		}
	}

	public DelayedTask scheduleCustomTask(AccountDescriptor account, TaskQueue queue,
			CustomTaskService.CustomTaskSettings settings, DailyTaskStatusData persistedStatus,
			boolean executeNow, boolean recurring) {
		if (account == null || queue == null || settings == null) {
			return null;
		}

		queue.dequeueByKey(settings.getClassName());

		DelayedTask customTask = CustomTaskService.getInstance().createTaskWithSettings(settings, account);
		if (customTask == null) {
			return null;
		}
		if (!applyCustomTaskSchedule(customTask, persistedStatus, executeNow)) {
			return null;
		}

		customTask.setRecurring(recurring);
		TaskManagementService.shared().recordTaskState(account.getId(),
				baseScheduledState(account.getId(), TpDailyTaskEnum.CUSTOM_TASK.getId(),
						settings.getClassName(), customTask.getScheduled()));
		queue.enqueue(customTask);
		return customTask;
	}

	private DailyTaskStatusData findCustomTaskStatus(CustomTaskService.CustomTaskSettings settings,
			List<DailyTaskStatusData> progress) {
		return progress.stream()
				.filter(row -> row != null)
				.filter(row -> row.getIdTpDailyTask() == TpDailyTaskEnum.CUSTOM_TASK.getId())
				.filter(row -> settings.getClassName().equals(row.getCustomLabel()))
				.findFirst()
				.orElse(null);
	}

	private boolean applyCustomTaskSchedule(DelayedTask task, DailyTaskStatusData persistedStatus, boolean executeNow) {
		if (persistedStatus != null) {
			task.setLastExecutionTime(persistedStatus.getLastExecution());
			LocalDateTime nextSchedule = persistedStatus.getNextSchedule();
			if (nextSchedule != null) {
				task.reschedule(nextSchedule);
				return true;
			}
			if (persistedStatus.getLastExecution() != null) {
				return false;
			}
		}

		if (executeNow) {
			task.reschedule(LocalDateTime.now());
			return true;
		}

		LocalDateTime initialSchedule = task.getScheduled();
		if (initialSchedule == null || initialSchedule.isBefore(LocalDateTime.now())) {
			task.reschedule(LocalDateTime.now());
		}
		return true;
	}

	private TaskStateData baseScheduledState(Long accountId, int taskTypeId, String customName) {
		return baseScheduledState(accountId, taskTypeId, customName, null);
	}

	private TaskStateData baseScheduledState(Long accountId, int taskTypeId, String customName, LocalDateTime nextRun) {
		TaskStateData state = new TaskStateData();
		state.setProfileId(accountId);
		state.setTaskId(taskTypeId);
		state.setCustomTaskName(customName);
		state.setExecuting(false);
		state.setScheduled(true);
		state.setNextExecutionTime(nextRun);
		return state;
	}

	private void changeAccountPause(Long accountId, boolean paused) {
		if (accountId == null) {
			return;
		}
		if (paused) {
			dispatcher.pauseAccount(accountId);
		} else {
			dispatcher.resumeAccount(accountId);
		}
		notifyQueueState(accountId, paused);
	}

	private DailyTask newDailyRecord(AccountDescriptor account, TpDailyTaskEnum taskType,
			LocalDateTime nextRun, String customLabel) {
		Profile profile = profiles.getAccountById(account.getId());
		DailyTaskTemplate template = dailyTasks.findWatcherDailyTaskById(taskType.getId());
		DailyTask record = new DailyTask();
		record.setAccount(profile);
		record.setDefinition(template);
		record.setCustomTaskLabel(customLabel);
		record.setPreviousRun(LocalDateTime.now());
		record.setScheduledAt(nextRun);
		return record;
	}

	private DailyTask findDailyRecord(Long accountId, TpDailyTaskEnum taskType, String customLabel) {
		return customLabel == null
				? dailyTasks.findByAccountIdAndTaskType(accountId, taskType)
				: dailyTasks.findByAccountIdTaskTypeAndCustomLabel(accountId, taskType, customLabel);
	}

	private void applyStaminaDeferral(DailyTask record, StaminaDeferral deferral) {
		if (deferral == null) {
			record.clearStaminaDeferral();
			return;
		}
		record.setStaminaMinimumRequired(deferral.minimumRequired());
		record.setStaminaRegenerationTarget(deferral.regenerationTarget());
		record.setStaminaEarliestRunnableAt(deferral.earliestRunnableAt());
	}

	private boolean removeFromQueue(Long accountId, TpDailyTaskEnum taskType, String customLabel) {
		TaskQueue queue = dispatcher.getQueue(accountId);
		if (queue == null) {
			log(TpMessageSeverityEnum.WARNING, "ScheduleService", "Profile " + accountId,
					"No queue found for " + taskType.getName());
			return false;
		}
		return taskType == TpDailyTaskEnum.CUSTOM_TASK && customLabel != null
				? queue.dequeueByKey(customLabel)
				: queue.dequeue(taskType);
	}

	private TaskStateData clearedState(Long accountId, TpDailyTaskEnum taskType, String customLabel) {
		TaskStateData state = new TaskStateData();
		state.setProfileId(accountId);
		state.setTaskId(taskType.getId());
		state.setCustomTaskName(taskType == TpDailyTaskEnum.CUSTOM_TASK ? customLabel : null);
		state.setScheduled(false);
		state.setExecuting(false);
		state.setLastExecutionTime(LocalDateTime.now());
		state.setNextExecutionTime(null);
		return state;
	}

	private Config findGlobalRow(String identifier) {
		List<Config> rows = configs.getGlobalSettings();
		if (rows == null) {
			return null;
		}
		return rows.stream()
				.filter(row -> row != null && row.getIdentifier() != null)
				.filter(row -> row.getIdentifier().equals(identifier))
				.findFirst()
				.orElse(null);
	}

	private List<DailyTaskStatusData> safeProgress(Long accountId) {
		List<DailyTaskStatusData> progress = dailyTasks.findDailyTaskStatusByAccount(accountId);
		return progress == null ? List.of() : progress;
	}

	private void notifyBotState(boolean running, boolean paused) {
		BotStateData state = new BotStateData();
		state.setRunning(running);
		state.setPaused(paused);
		state.setActionTime(LocalDateTime.now());
		botListeners.forEach(listener -> listener.onEngineStateTransition(state));
	}

	private void notifyQueueState(Long accountId, boolean paused) {
		if (queueListeners.isEmpty()) {
			return;
		}
		QueueStateData state = new QueueStateData(accountId, paused, dispatcher.getActiveQueueStates());
		queueListeners.forEach(listener -> listener.onQueueStateChanged(state));
	}

	private void applyEmulatorPaths(Map<String, String> globalConfig) {
		if (globalConfig == null) {
			return;
		}
		Set<String> pathKeys = Set.of(
				ConfigurationKeyEnum.MUMU_PATH_STRING.name(),
				ConfigurationKeyEnum.LDPLAYER_PATH_STRING.name(),
				ConfigurationKeyEnum.MEMU_PATH_STRING.name());
		globalConfig.entrySet().stream()
				.filter(entry -> pathKeys.contains(entry.getKey()))
				.forEach(entry -> persistEmulatorPath(entry.getKey(), entry.getValue()));
	}

	private void trackStart(List<AccountDescriptor> enabledAccounts, Map<String, String> globalConfig) {
		try {
			Set<String> activeTaskNames = new LinkedHashSet<>();
			for (AccountDescriptor account : enabledAccounts) {
				for (DailyTaskStatusData progress : safeProgress(account.getId())) {
					if (progress == null) {
						continue;
					}
					TpDailyTaskEnum taskType = TpDailyTaskEnum.fromId(progress.getIdTpDailyTask());
					if (taskType != null && taskType.getConfigKey() != null
							&& Boolean.TRUE.equals(account.getConfig(taskType.getConfigKey(), Boolean.class))) {
						activeTaskNames.add(taskType.getName());
					}
				}
			}
			String emulator = globalConfig == null
					? "unknown"
					: globalConfig.getOrDefault(ConfigurationKeyEnum.CURRENT_EMULATOR_STRING.name(), "unknown");
			AnalyticsService.getInstance().trackBotStarted(new ArrayList<>(activeTaskNames), enabledAccounts.size(), emulator);
			AnalyticsService.getInstance().trackEmulatorConnected(emulator);
			AnalyticsService.getInstance().trackProfileCount(enabledAccounts.size());
		} catch (Exception ignored) {
		}
	}

	private String formatTime(LocalDateTime time) {
		return time == null ? "immediately" : time.format(DISPLAY_TIME);
	}

	private void log(TpMessageSeverityEnum severity, String source, String profileName, String message) {
		LoggingService.obtain().emit(severity, source, profileName, message);
	}

}
