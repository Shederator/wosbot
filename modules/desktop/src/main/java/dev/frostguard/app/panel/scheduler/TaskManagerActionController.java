package dev.frostguard.app.panel.scheduler;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.DailyTaskStatusData;
import dev.frostguard.api.domain.TaskStateData;
import dev.frostguard.app.panel.profile.IProfileModel;
import dev.frostguard.app.panel.profile.ProfileCallback;
import dev.frostguard.app.panel.profile.ProfileModel;
import dev.frostguard.engine.listener.TaskStatusChangeListener;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.DelayedTaskRegistry;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.service.CustomTaskService;
import dev.frostguard.engine.service.LoggingService;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.engine.service.TaskManagementService;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class TaskManagerActionController implements TaskStatusChangeListener {

	private static final DateTimeFormatter SCHEDULE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final TaskManagerLayoutController taskManagerLayoutController;
	private final IProfileModel profileModel;
	private final ITaskStatusModel taskStatusModel;

	public TaskManagerActionController(TaskManagerLayoutController taskManagerLayoutController) {
		this.taskManagerLayoutController = taskManagerLayoutController;
		this.profileModel = new ProfileModel();
		this.taskStatusModel = new TaskStatusModel();
		this.taskStatusModel.addTaskStatusChangeListener(this);
	}

	public void loadProfiles(ProfileCallback callback) {
		loadAsync(profileModel::getProfiles, profiles -> {
			if (callback != null) {
				callback.onProfilesLoaded(profiles);
			}
		});
	}

	public void loadDailyTaskStatus(Long profileId, TaskCallback callback) {
		loadAsync(() -> taskStatusModel.getDailyTaskStatusList(profileId), taskStates -> {
			if (callback != null) {
				callback.onTasksLoaded(taskStates);
			}
		});
	}

	private <T> void loadAsync(Supplier<T> supplier, Consumer<T> onSuccess) {
		CompletableFuture.supplyAsync(supplier)
				.thenAccept(onSuccess)
				.exceptionally(ex -> {
					ex.printStackTrace();
					return null;
				});
	}

	@Override
	public void onTaskStatusTransition(Long profileId, int taskNameId, TaskStateData taskState) {
		if (taskManagerLayoutController != null) {
			taskManagerLayoutController.updateTaskStatus(profileId, taskNameId, taskState);
		}
	}

	public boolean isQueueActive(Long profileId) {
		return queueFor(profileId) != null;
	}

	public boolean canRemoveTask(TaskManagerAux task) {
		return task.scheduledProperty().get() && !task.executingProperty().get();
	}

	public void executeTaskNow(TaskManagerAux task, boolean recurring) {
		AccountDescriptor profile = findProfileById(task.getProfileId());
		if (profile == null) {
			System.err.println("Profile not found: " + task.getProfileId());
			return;
		}

		TaskQueue queue = queueFor(profile.getId());
		if (queue == null) {
			showErrorAlert("Error", "No active queue found for profile: " + profile.getName());
			return;
		}

		LocalDateTime now = LocalDateTime.now();
		ScheduleService.obtain().persistDailyCompletion(profile, task.getTaskEnum(), now, task.getCustomTaskName());

		if (task.getTaskEnum() == TpDailyTaskEnum.CUSTOM_TASK) {
			enqueueCustomTaskNow(queue, profile, task, now, recurring);
			return;
		}

		queue.runNow(task.getTaskEnum(), recurring);
	}

	public void executeTaskDirectly(TaskManagerAux task) {
		AccountDescriptor profile = findProfileById(task.getProfileId());
		if (profile == null) {
			System.err.println("Profile not found: " + task.getProfileId());
			return;
		}

		TaskQueue queue = queueFor(profile.getId());
		if (queue == null) {
			System.err.println("No active queue found for profile: " + profile.getName());
			return;
		}

		if (task.scheduledProperty().get()) {
			scheduleTaskInQueue(queue, task, LocalDateTime.now(), true, profile);
			emitTaskLog(profile, "TaskExecutor",
					"Executed scheduled task " + task.getTaskEnum().getName() + " and marked as recurring");
			return;
		}

		executeTaskNow(task, false);
		emitTaskLog(profile, "TaskExecutor", "Executed task " + task.getTaskEnum().getName() + " one time");
	}

	public void removeTask(TaskManagerAux task, Runnable onSuccess) {
		AccountDescriptor profile = findProfileById(task.getProfileId());
		if (profile == null) {
			System.err.println("Profile not found: " + task.getProfileId());
			return;
		}

		if (!confirmRemoval(task, profile)) {
			return;
		}

		ScheduleService.obtain().evictTask(profile.getId(), task.getTaskEnum(), task.getCustomTaskName());
		if (onSuccess != null) {
			onSuccess.run();
		}
	}

	public void showScheduleDialog(TaskManagerAux task) {
		AccountDescriptor profile = findProfileById(task.getProfileId());
		if (profile == null) {
			System.err.println("Profile not found: " + task.getProfileId());
			return;
		}

		try {
			FXMLLoader loader = new FXMLLoader(getClass().getResource("/layout/ScheduleTaskDialog.fxml"));
			Parent root = loader.load();
			ScheduleTaskDialogController controller = loader.getController();
			controller.setTask(task);
			root.getStylesheets().add(getClass().getResource("/styles/style.css").toExternalForm());

			Stage dialogStage = createScheduleStage(root);
			dialogStage.showAndWait();
			handleScheduleDialogResult(task, controller);
		} catch (Exception e) {
			e.printStackTrace();
			showErrorAlert("Error", "Could not load schedule dialog: " + e.getMessage());
		}
	}

	private Stage createScheduleStage(Parent root) {
		Stage parentStage = (Stage) taskManagerLayoutController.getSceneNode().getScene().getWindow();
		Stage dialogStage = new Stage();
		dialogStage.setTitle("Schedule Task");
		dialogStage.initModality(Modality.APPLICATION_MODAL);
		dialogStage.initStyle(StageStyle.TRANSPARENT);
		if (parentStage != null) {
			dialogStage.initOwner(parentStage);
		}
		dialogStage.setResizable(false);
		dialogStage.setAlwaysOnTop(true);

		Scene scene = new Scene(root);
		scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
		dialogStage.setScene(scene);
		dialogStage.centerOnScreen();
		return dialogStage;
	}

	private void handleScheduleDialogResult(TaskManagerAux task, ScheduleTaskDialogController controller) {
		if (!controller.isConfirmed()) {
			return;
		}
		if (controller.isImmediate()) {
			executeTaskNow(task, controller.isRecurring());
		} else {
			scheduleTask(task, controller.getScheduledTime(), controller.isRecurring());
		}
	}

	private void scheduleTask(TaskManagerAux task, LocalDateTime scheduledTime, boolean recurring) {
		AccountDescriptor profile = findProfileById(task.getProfileId());
		if (profile == null) {
			System.err.println("Profile not found: " + task.getProfileId());
			return;
		}

		TaskQueue queue = queueFor(profile.getId());
		if (queue == null) {
			showErrorAlert("Error", "No active queue found for profile: " + profile.getName());
			return;
		}

		scheduleTaskInQueue(queue, task, scheduledTime, recurring, profile);
		ScheduleService.obtain().persistDailyCompletion(profile, task.getTaskEnum(), scheduledTime, task.getCustomTaskName());
		showInfoAlert("Success", "Task scheduled successfully for: " + scheduledTime.format(SCHEDULE_FORMAT)
				+ (recurring ? " (recurring)" : " (one-time)"));
	}

	private void scheduleTaskInQueue(TaskQueue queue, TaskManagerAux task, LocalDateTime scheduledTime, boolean recurring, AccountDescriptor profile) {
		TpDailyTaskEnum taskEnum = task.getTaskEnum();
		DelayedTask delayedTask = taskEnum == TpDailyTaskEnum.CUSTOM_TASK
				? prepareCustomTask(queue, profile, task.getCustomTaskName())
				: prepareStandardTask(queue, profile, taskEnum);

		if (delayedTask == null) {
			return;
		}

		delayedTask.reschedule(scheduledTime);
		delayedTask.setRecurring(recurring);
		queue.enqueue(delayedTask);

		TaskManagementService.shared().recordTaskState(profile.getId(), taskStateFor(profile, task, scheduledTime));
		emitTaskLog(profile, "TaskScheduler", "Scheduled " + displayName(task) + " for " + scheduledTime.format(SCHEDULE_FORMAT)
				+ (recurring ? " (recurring)" : " (one-time)"));
	}

	private void enqueueCustomTaskNow(TaskQueue queue, AccountDescriptor profile, TaskManagerAux task, LocalDateTime now, boolean recurring) {
		DelayedTask delayedTask = prepareCustomTask(queue, profile, task.getCustomTaskName());
		if (delayedTask == null) {
			return;
		}

		delayedTask.reschedule(now);
		delayedTask.setRecurring(recurring);
		queue.enqueue(delayedTask);
		TaskManagementService.shared().recordTaskState(profile.getId(), taskStateFor(profile, task, delayedTask.getScheduled()));
	}

	private DelayedTask prepareStandardTask(TaskQueue queue, AccountDescriptor profile, TpDailyTaskEnum taskEnum) {
		DelayedTask delayedTask = DelayedTaskRegistry.create(taskEnum, profile);
		if (delayedTask == null) {
			showErrorAlert("Error", "Task not found: " + taskEnum.getName());
			return null;
		}
		queue.dequeue(taskEnum);
		return delayedTask;
	}

	private DelayedTask prepareCustomTask(TaskQueue queue, AccountDescriptor profile, String className) {
		Optional<CustomTaskService.CustomTaskSettings> settings = customTaskSettings(className);
		if (settings.isEmpty()) {
			showErrorAlert("Error", "Custom task configs not found for: " + className);
			return null;
		}

		DelayedTask delayedTask = CustomTaskService.getInstance().createTaskWithSettings(settings.get(), profile);
		if (delayedTask == null) {
			showErrorAlert("Error", "Task not found/cannot load: " + className);
			return null;
		}
		queue.dequeueByKey(className);
		return delayedTask;
	}

	private Optional<CustomTaskService.CustomTaskSettings> customTaskSettings(String className) {
		return CustomTaskService.getInstance().getEnabledTasks().stream()
				.filter(settings -> settings.getClassName().equals(className))
				.findFirst();
	}

	private TaskStateData taskStateFor(AccountDescriptor profile, TaskManagerAux task, LocalDateTime nextExecution) {
		TaskStateData taskState = new TaskStateData();
		taskState.setProfileId(profile.getId());
		taskState.setTaskId(task.getTaskEnum().getId());
		if (task.getTaskEnum() == TpDailyTaskEnum.CUSTOM_TASK) {
			taskState.setCustomTaskName(task.getCustomTaskName());
		}
		taskState.setScheduled(true);
		taskState.setExecuting(false);
		taskState.setLastExecutionTime(LocalDateTime.now());
		taskState.setNextExecutionTime(nextExecution);
		return taskState;
	}

	private boolean confirmRemoval(TaskManagerAux task, AccountDescriptor profile) {
		Alert confirmDialog = new Alert(Alert.AlertType.CONFIRMATION);
		confirmDialog.setTitle("Remove Task");
		confirmDialog.setHeaderText("Remove task from scheduler");
		confirmDialog.setContentText("Are you sure you want to remove '" + task.getTaskEnum().getName()
				+ "' from the scheduler for profile '" + profile.getName() + "'?");
		Optional<ButtonType> result = confirmDialog.showAndWait();
		return result.isPresent() && result.get() == ButtonType.OK;
	}

	private TaskQueue queueFor(Long profileId) {
		return ScheduleService.obtain().getCoordinator().getQueue(profileId);
	}

	private String displayName(TaskManagerAux task) {
		return task.getTaskEnum() == TpDailyTaskEnum.CUSTOM_TASK ? task.getCustomTaskName() : task.getTaskEnum().getName();
	}

	private void emitTaskLog(AccountDescriptor profile, String source, String message) {
		LoggingService.obtain().emit(TpMessageSeverityEnum.INFO, source, profile.getName(), message);
	}

	private void showErrorAlert(String title, String message) {
		showAlert(Alert.AlertType.ERROR, title, message);
	}

	private void showInfoAlert(String title, String message) {
		showAlert(Alert.AlertType.INFORMATION, title, message);
	}

	private void showAlert(Alert.AlertType type, String title, String message) {
		Alert alert = new Alert(type);
		alert.setTitle(title);
		alert.setHeaderText(null);
		alert.setContentText(message);
		alert.showAndWait();
	}

	public AccountDescriptor findProfileById(Long profileId) {
		List<AccountDescriptor> allProfiles = ProfileService.obtain().fetchAllAccounts();
		return allProfiles.stream()
				.filter(profile -> profile.getId().equals(profileId))
				.findFirst()
				.orElse(null);
	}
}
