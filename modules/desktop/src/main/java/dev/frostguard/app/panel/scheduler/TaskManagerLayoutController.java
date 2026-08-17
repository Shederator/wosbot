package dev.frostguard.app.panel.scheduler;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.kordamp.ikonli.javafx.FontIcon;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.DailyTaskStatusData;
import dev.frostguard.api.domain.TaskStateData;
import dev.frostguard.engine.listener.ProfileDataChangeListener;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.service.CustomTaskService;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.engine.service.TaskManagementService;
import dev.frostguard.vision.convert.GameTimeUtils;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

public class TaskManagerLayoutController implements ProfileDataChangeListener {

	private static final List<String> TIME_STATUS_STYLES = List.of(
			"next-execution-ready",
			"next-execution-never",
			"next-execution-executing",
			"next-execution-seconds",
			"next-execution-minutes-short",
			"next-execution-minutes-medium",
			"next-execution-minutes-long",
			"next-execution-hours",
			"next-execution-days");

	private static final Comparator<TaskManagerAux> TASK_AUX_COMPARATOR = Comparator
			.comparing(TaskManagerAux::isScheduled).reversed()
			.thenComparing(TaskManagerAux::isExecuting, Comparator.reverseOrder())
			.thenComparing(TaskManagerAux::hasReadyTask, Comparator.reverseOrder())
			.thenComparingLong(TaskManagerAux::getNearestMinutesUntilExecution);

	private final Image iconTrue = loadIndicator("green.png");
	private final Image iconFalse = loadIndicator("red.png");
	private final Image iconWaiting = loadIndicator("yellow.png");
	private final Image iconIdle = loadIndicator("grey.png");
	private final ObjectProperty<LocalDateTime> globalClock = new SimpleObjectProperty<>(LocalDateTime.now());
	private final Map<Long, Tab> profileTabsMap = new HashMap<>();
	private final Map<Long, ObservableList<TaskManagerAux>> tasks = new HashMap<>();
	private final Map<Long, FilteredList<TaskManagerAux>> filteredTasks = new HashMap<>();
	private final TaskManagerActionController taskManagerActionController = new TaskManagerActionController(this);

	@FXML
	private TabPane tabPaneProfiles;

	@FXML
	private TextField txtFilterTaskName;

	@FXML
	private Button btnToggleView;

	@FXML
	private javafx.scene.layout.VBox ganttViewContainer;

	private boolean showingGanttView = false;
	private Node ganttViewNode = null;
	private TaskGanttOverviewController ganttViewController = null;

	public Node getSceneNode() {
		return tabPaneProfiles;
	}

	@FXML
	public void initialize() {
		ProfileService.obtain().registerDataObserver(this);
		configureFilter();
		setToggleButtonLabel();
		loadProfiles();
		startClock();
	}

	@FXML
	private void handleToggleView() {
		showingGanttView = !showingGanttView;
		if (showingGanttView) {
			showGanttView();
		} else {
			showTableView();
		}
		setToggleButtonLabel();
	}

	@FXML
	private void handleClearTaskFilter() {
		if (txtFilterTaskName != null) {
			txtFilterTaskName.clear();
		}
	}

	private void configureFilter() {
		if (txtFilterTaskName != null) {
			txtFilterTaskName.textProperty().addListener((observable, oldValue, newValue) -> applyFilter(newValue));
		}
	}

	private void setToggleButtonLabel() {
		if (btnToggleView != null) {
			btnToggleView.setText(showingGanttView ? "Table View" : "Timeline View");
		}
	}

	private void startClock() {
		Timeline ticker = new Timeline(new KeyFrame(Duration.seconds(1), evt -> updateTimeValues()));
		ticker.setCycleCount(Animation.INDEFINITE);
		ticker.play();
	}

	private void showGanttView() {
		tabPaneProfiles.setVisible(false);
		tabPaneProfiles.setManaged(false);

		if (ganttViewNode == null && !loadGanttView()) {
			showingGanttView = false;
			showTableView();
			return;
		}

		if (ganttViewController != null && txtFilterTaskName != null) {
			ganttViewController.setTaskFilter(txtFilterTaskName.getText());
		}
		if (!ganttViewContainer.getChildren().contains(ganttViewNode)) {
			ganttViewContainer.getChildren().setAll(ganttViewNode);
		}
		ganttViewContainer.setVisible(true);
		ganttViewContainer.setManaged(true);
	}

	private boolean loadGanttView() {
		try {
			FXMLLoader loader = new FXMLLoader(getClass().getResource("/layout/TaskGanttOverview.fxml"));
			ganttViewNode = loader.load();
			ganttViewController = loader.getController();
			return true;
		} catch (java.io.IOException e) {
			e.printStackTrace();
			return false;
		}
	}

	private void showTableView() {
		ganttViewContainer.setVisible(false);
		ganttViewContainer.setManaged(false);
		tabPaneProfiles.setVisible(true);
		tabPaneProfiles.setManaged(true);
	}

	private void applyFilter(String filterText) {
		String filter = filterText == null ? "" : filterText.toLowerCase().trim();
		filteredTasks.values().forEach(filteredList -> filteredList.setPredicate(task -> filter.isEmpty()
				|| task.getTaskName().toLowerCase().contains(filter)));

		if (ganttViewController != null) {
			ganttViewController.setTaskFilter(filterText);
		}
	}

	private void updateTimeValues() {
		Platform.runLater(() -> {
			LocalDateTime now = LocalDateTime.now();
			globalClock.set(now);
			tasks.values().forEach(dataList -> refreshCountdowns(dataList, now));
		});
	}

	private void refreshCountdowns(ObservableList<TaskManagerAux> dataList, LocalDateTime now) {
		boolean needsReorder = false;
		for (TaskManagerAux task : dataList) {
			if (task.getNextExecution() != null && updateCountdown(task, now)) {
				needsReorder = true;
			}
		}
		if (needsReorder) {
			FXCollections.sort(dataList, TASK_AUX_COMPARATOR);
		}
	}

	private boolean updateCountdown(TaskManagerAux task, LocalDateTime now) {
		long newSeconds = ChronoUnit.SECONDS.between(now, task.getNextExecution());
		long oldSeconds = task.getNearestMinutesUntilExecution();
		boolean ready = newSeconds <= 0;
		boolean wasReady = task.hasReadyTask();
		if (newSeconds == oldSeconds && ready == wasReady) {
			return false;
		}
		task.setNearestMinutesUntilExecution(Math.max(0, newSeconds));
		task.setHasReadyTask(ready);
		return true;
	}

	private void loadProfiles() {
		taskManagerActionController.loadProfiles(accounts -> Platform.runLater(() -> {
			if (tabPaneProfiles == null) {
				return;
			}
			reconcileProfileTabs(accounts);
			selectFallbackTab();
		}));
	}

	private void reconcileProfileTabs(List<AccountDescriptor> accounts) {
		Set<Long> currentProfileIds = accounts.stream()
				.map(AccountDescriptor::getId)
				.collect(Collectors.toSet());
		removeMissingTabs(currentProfileIds);
		accounts.forEach(this::upsertProfileTab);
	}

	private void removeMissingTabs(Set<Long> currentProfileIds) {
		profileTabsMap.entrySet().removeIf(entry -> {
			if (currentProfileIds.contains(entry.getKey())) {
				return false;
			}
			tabPaneProfiles.getTabs().remove(entry.getValue());
			tasks.remove(entry.getKey());
			filteredTasks.remove(entry.getKey());
			return true;
		});
	}

	private void upsertProfileTab(AccountDescriptor profile) {
		Tab existingTab = profileTabsMap.get(profile.getId());
		if (existingTab == null) {
			Tab newTab = createProfileTab(profile);
			profileTabsMap.put(profile.getId(), newTab);
			tabPaneProfiles.getTabs().add(newTab);
			return;
		}
		if (!existingTab.getText().equals(profile.getName())) {
			existingTab.setText(profile.getName());
		}
	}

	private void selectFallbackTab() {
		if (tabPaneProfiles.getSelectionModel().getSelectedItem() == null && !tabPaneProfiles.getTabs().isEmpty()) {
			tabPaneProfiles.getSelectionModel().selectFirst();
		}
	}

	private Tab createProfileTab(AccountDescriptor profile) {
		ObservableList<TaskManagerAux> dataList = FXCollections.observableArrayList();
		FilteredList<TaskManagerAux> filteredList = new FilteredList<>(dataList);
		TableView<TaskManagerAux> table = createTaskTable();
		table.setItems(filteredList);

		tasks.put(profile.getId(), dataList);
		filteredTasks.put(profile.getId(), filteredList);

		Tab tab = new Tab(profile.getName(), table);
		tab.setClosable(false);
		tab.setUserData(profile.getId());

		reloadTaskRows(profile, list -> {
			dataList.setAll(list);
			FXCollections.sort(dataList, TASK_AUX_COMPARATOR);
			if (txtFilterTaskName != null && !txtFilterTaskName.getText().isEmpty()) {
				applyFilter(txtFilterTaskName.getText());
			}
		});
		return tab;
	}

	@Override
	public void onAccountDataModified(AccountDescriptor profile) {
		Platform.runLater(this::loadProfiles);
	}

	private void reloadTaskRows(AccountDescriptor profile, Consumer<List<TaskManagerAux>> onListReady) {
		taskManagerActionController.loadDailyTaskStatus(profile.getId(), statuses -> {
			List<TaskManagerAux> list = new ArrayList<>();
			for (TpDailyTaskEnum task : TpDailyTaskEnum.values()) {
				if (task != TpDailyTaskEnum.CUSTOM_TASK) {
					list.add(createTaskAux(profile.getId(), task, task.getName(), null, statuses));
				}
			}
			CustomTaskService.getInstance().getEnabledTasks().forEach(customTask -> list.add(createTaskAux(
					profile.getId(),
					TpDailyTaskEnum.CUSTOM_TASK,
					customTask.getCustomName(),
					customTask.getClassName(),
					statuses)));
			list.sort(TASK_AUX_COMPARATOR);
			Platform.runLater(() -> onListReady.accept(list));
		});
	}

	private TaskManagerAux createTaskAux(Long profileId, TpDailyTaskEnum task, String taskName, String customTaskName,
			List<DailyTaskStatusData> statuses) {
		TaskQueue queue = ScheduleService.obtain().getCoordinator().getQueue(profileId);
		TaskStateData liveState = TaskManagementService.shared().lookupTaskState(profileId, task.getId(), customTaskName);
		if (liveState != null && queue != null) {
			return fromLiveState(profileId, task, taskName, customTaskName, liveState);
		}

		DailyTaskStatusData storedState = statuses.stream()
				.filter(status -> status.getIdTpDailyTask() == task.getId())
				.findFirst()
				.orElse(null);
		if (storedState == null) {
			return new TaskManagerAux(taskName, null, null, task, profileId, Long.MAX_VALUE, false, false, false, customTaskName);
		}

		Timing timing = timingUntil(storedState.getNextSchedule());
		return new TaskManagerAux(
				taskName,
				storedState.getLastExecution(),
				storedState.getNextSchedule(),
				task,
				profileId,
				timing.secondsUntil(),
				timing.ready(),
				isQueued(profileId, task, customTaskName),
				false,
				customTaskName);
	}

	private TaskManagerAux fromLiveState(Long profileId, TpDailyTaskEnum task, String taskName, String customTaskName,
			TaskStateData liveState) {
		Timing timing = timingUntil(liveState.getNextExecutionTime());
		return new TaskManagerAux(
				taskName,
				liveState.getLastExecutionTime(),
				liveState.getNextExecutionTime(),
				task,
				profileId,
				timing.secondsUntil(),
				timing.ready(),
				liveState.isScheduled(),
				liveState.isExecuting(),
				customTaskName);
	}

	private boolean isQueued(Long profileId, TpDailyTaskEnum task, String customTaskName) {
		TaskQueue queue = ScheduleService.obtain().getCoordinator().getQueue(profileId);
		if (queue == null) {
			return false;
		}
		return customTaskName == null ? queue.isTaskQueued(task) : queue.isTaskQueued(customTaskName);
	}

	private Timing timingUntil(LocalDateTime nextExecution) {
		if (nextExecution == null) {
			return new Timing(Long.MAX_VALUE, false);
		}
		long seconds = ChronoUnit.SECONDS.between(LocalDateTime.now(), nextExecution);
		return seconds <= 0 ? new Timing(0, true) : new Timing(seconds, false);
	}

	private TableView<TaskManagerAux> createTaskTable() {
		TableView<TaskManagerAux> table = new TableView<>();
		table.getStyleClass().add("table-view");
		table.getColumns().add(taskNameColumn());
		table.getColumns().add(lastExecutionColumn());
		table.getColumns().add(nextExecutionColumn());
		table.getColumns().add(actionsColumn());
		table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		return table;
	}

	private TableColumn<TaskManagerAux, String> taskNameColumn() {
		TableColumn<TaskManagerAux, String> column = new TableColumn<>("Task Name");
		column.setPrefWidth(200);
		column.setCellValueFactory(cellData -> cellData.getValue().taskNameProperty());
		column.setCellFactory(col -> new TableCell<>() {
			@Override
			protected void updateItem(String item, boolean empty) {
				super.updateItem(item, empty);
				graphicProperty().unbind();
				if (empty || item == null) {
					setText(null);
					setGraphic(null);
					setStyle("");
					return;
				}
				setText(item);
				TaskManagerAux task = rowTask(this);
				if (task != null) {
					graphicProperty().bind(Bindings.createObjectBinding(() -> statusIconFor(task),
							task.scheduledProperty(), task.executingProperty(), task.hasReadyTaskProperty(), globalClock));
					setContentDisplay(ContentDisplay.LEFT);
				}
				setStyle("-fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold;");
			}
		});
		return column;
	}

	private TableColumn<TaskManagerAux, String> lastExecutionColumn() {
		TableColumn<TaskManagerAux, String> column = new TableColumn<>("Last Execution");
		column.setPrefWidth(150);
		column.setCellValueFactory(cellData -> {
			TaskManagerAux task = cellData.getValue();
			return Bindings.createStringBinding(() -> GameTimeUtils.formatElapsed(task.getLastExecution()),
					task.nextExecutionProperty(), task.executingProperty(), globalClock);
		});
		column.setCellFactory(col -> plainTextCell("-fx-text-fill: white; -fx-font-size: 14px;"));
		return column;
	}

	private TableColumn<TaskManagerAux, String> nextExecutionColumn() {
		TableColumn<TaskManagerAux, String> column = new TableColumn<>("Next Execution");
		column.setPrefWidth(150);
		column.setCellValueFactory(cellData -> {
			TaskManagerAux task = cellData.getValue();
			return Bindings.createStringBinding(() -> describeNextExecution(task),
					task.nextExecutionProperty(), task.executingProperty(), globalClock);
		});
		column.setCellFactory(col -> new TableCell<>() {
			@Override
			protected void updateItem(String item, boolean empty) {
				super.updateItem(item, empty);
				getStyleClass().removeAll(TIME_STATUS_STYLES);
				if (empty || item == null) {
					setText(null);
					setStyle("");
					return;
				}
				setText(item);
				styleNextExecutionCell(this, item);
			}
		});
		return column;
	}

	private TableColumn<TaskManagerAux, Void> actionsColumn() {
		TableColumn<TaskManagerAux, Void> column = new TableColumn<>("Actions");
		column.setPrefWidth(200);
		column.setCellFactory(col -> new ActionCell());
		return column;
	}

	private TableCell<TaskManagerAux, String> plainTextCell(String style) {
		return new TableCell<>() {
			@Override
			protected void updateItem(String item, boolean empty) {
				super.updateItem(item, empty);
				if (empty || item == null) {
					setText(null);
					setStyle("");
				} else {
					setText(item);
					setStyle(style);
				}
			}
		};
	}

	private String describeNextExecution(TaskManagerAux task) {
		if (task.executingProperty().get()) {
			return "Executing";
		}
		LocalDateTime next = task.getNextExecution();
		if (next == null) {
			return "Never";
		}
		long seconds = java.time.Duration.between(globalClock.get(), next).getSeconds();
		if (seconds <= 0) {
			return "Ready";
		}
		if (seconds < 60) {
			return seconds + "s";
		}
		if (seconds < 3600) {
			return (seconds / 60) + "m";
		}
		if (seconds < 86400) {
			long hours = seconds / 3600;
			long minutes = (seconds % 3600) / 60;
			return hours + "h " + minutes + "m";
		}
		long days = seconds / 86400;
		long hours = (seconds % 86400) / 3600;
		long minutes = (seconds % 3600) / 60;
		return days + "d " + hours + "h " + minutes + "m";
	}

	private void styleNextExecutionCell(TableCell<TaskManagerAux, String> cell, String value) {
		if ("Ready".equals(value)) {
			cell.getStyleClass().add("next-execution-ready");
		} else if ("Never".equals(value) || "--".equals(value)) {
			cell.getStyleClass().add("next-execution-never");
		} else if ("Executing".equals(value)) {
			cell.getStyleClass().add("next-execution-executing");
		} else if (value.endsWith("s")) {
			cell.getStyleClass().add("next-execution-seconds");
		} else if (value.matches("\\d+m")) {
			cell.getStyleClass().add(minuteStyle(value));
		} else if (value.matches("\\d+h \\d+m")) {
			cell.getStyleClass().add("next-execution-hours");
		} else if (value.matches("\\d+d \\d+h \\d+m")) {
			cell.getStyleClass().add("next-execution-days");
		}
	}

	private String minuteStyle(String value) {
		int minutes = Integer.parseInt(value.replace("m", ""));
		if (minutes <= 15) {
			return "next-execution-minutes-short";
		}
		return minutes <= 60 ? "next-execution-minutes-medium" : "next-execution-minutes-long";
	}

	public void updateTabOrder() {
		profileTabsMap.forEach((profileId, tab) -> {
			TaskQueue queue = ScheduleService.obtain().getCoordinator().getQueue(profileId);
			if (queue != null) {
				tab.setGraphic(tabIconFor(queue.getProfile()));
			}
		});

		List<Tab> sortedTabs = profileTabsMap.entrySet().stream()
				.sorted((left, right) -> compareProfileTabs(left.getKey(), right.getKey()))
				.map(Map.Entry::getValue)
				.collect(Collectors.toList());
		tabPaneProfiles.getTabs().setAll(sortedTabs);
	}

	private int compareProfileTabs(Long leftProfileId, Long rightProfileId) {
		if (Objects.equals(leftProfileId, rightProfileId)) {
			return 0;
		}
		TaskQueue leftQueue = ScheduleService.obtain().getCoordinator().getQueue(leftProfileId);
		TaskQueue rightQueue = ScheduleService.obtain().getCoordinator().getQueue(rightProfileId);
		if (leftQueue == null || rightQueue == null) {
			return leftQueue == null ? 1 : -1;
		}

		int leftPosition = leftQueue.getProfile().getQueuePosition();
		int rightPosition = rightQueue.getProfile().getQueuePosition();
		if (leftPosition == Integer.MAX_VALUE && rightPosition == Integer.MAX_VALUE) {
			return leftQueue.getScheduledUntil().isAfter(rightQueue.getScheduledUntil()) ? 1 : -1;
		}
		return Integer.compare(leftPosition, rightPosition);
	}

	private ImageView tabIconFor(AccountDescriptor profile) {
		Image image;
		if (profile.getQueuePosition() == 0) {
			image = iconTrue;
		} else if (profile.getQueuePosition() == Integer.MAX_VALUE && profile.getEnabled()) {
			image = iconIdle;
		} else if (!profile.getEnabled()) {
			image = iconFalse;
		} else {
			image = iconWaiting;
		}
		return iconView(image);
	}

	private ImageView statusIconFor(TaskManagerAux task) {
		boolean waiting = !task.isExecuting()
				&& !task.hasReadyTask()
				&& task.isScheduled()
				&& task.getNextExecution() != null
				&& ChronoUnit.SECONDS.between(LocalDateTime.now(), task.getNextExecution()) >= 60;
		if (waiting) {
			return iconView(iconWaiting);
		}
		if (task.scheduledProperty().get()) {
			return iconView(iconTrue);
		}
		return iconView(iconIdle);
	}

	public void updateTaskStatus(Long profileId, int taskNameId, TaskStateData taskState) {
		Platform.runLater(() -> {
			ObservableList<TaskManagerAux> dataList = tasks.get(profileId);
			if (dataList == null) {
				return;
			}

			TaskManagerAux taskAux = findTask(dataList, taskNameId, taskState)
					.orElseGet(() -> createMissingCustomTask(profileId, taskNameId, taskState, dataList));
			if (taskAux == null) {
				return;
			}

			applyTaskState(taskAux, taskState);
			FXCollections.sort(dataList, TASK_AUX_COMPARATOR);
			updateTabOrder();
		});
	}

	private Optional<TaskManagerAux> findTask(List<TaskManagerAux> dataList, int taskNameId, TaskStateData taskState) {
		return dataList.stream()
				.filter(aux -> aux.getTaskEnum().getId() == taskNameId
						&& (taskState.getCustomTaskName() == null
								|| Objects.equals(aux.getCustomTaskName(), taskState.getCustomTaskName())))
				.findFirst();
	}

	private TaskManagerAux createMissingCustomTask(Long profileId, int taskNameId, TaskStateData taskState,
			ObservableList<TaskManagerAux> dataList) {
		if (taskNameId != TpDailyTaskEnum.CUSTOM_TASK.getId() || taskState.getCustomTaskName() == null) {
			return null;
		}

		Timing timing = timingUntil(taskState.getNextExecutionTime());
		TaskManagerAux taskAux = new TaskManagerAux(
				taskState.getCustomTaskName(),
				taskState.getLastExecutionTime(),
				taskState.getNextExecutionTime(),
				TpDailyTaskEnum.CUSTOM_TASK,
				profileId,
				timing.secondsUntil(),
				timing.ready(),
				taskState.isScheduled(),
				taskState.isExecuting(),
				taskState.getCustomTaskName());
		dataList.add(taskAux);
		return taskAux;
	}

	private void applyTaskState(TaskManagerAux taskAux, TaskStateData taskState) {
		Timing timing = timingUntil(taskState.getNextExecutionTime());
		taskAux.setLastExecution(taskState.getLastExecutionTime());
		taskAux.setNextExecution(taskState.getNextExecutionTime());
		taskAux.setScheduled(taskState.isScheduled());
		taskAux.setExecuting(taskState.isExecuting());
		taskAux.setHasReadyTask(timing.ready());
		taskAux.setNearestMinutesUntilExecution(timing.secondsUntil());
	}

	private void refreshProfileRows(AccountDescriptor profile) {
		if (profile == null) {
			return;
		}
		reloadTaskRows(profile, list -> {
			ObservableList<TaskManagerAux> dataList = tasks.get(profile.getId());
			if (dataList != null) {
				dataList.setAll(list);
				FXCollections.sort(dataList, TASK_AUX_COMPARATOR);
			}
		});
	}

	private Image loadIndicator(String fileName) {
		return new Image(Objects.requireNonNull(getClass().getResourceAsStream("/icons/indicators/" + fileName)));
	}

	private ImageView iconView(Image image) {
		ImageView view = new ImageView(image);
		view.setFitWidth(10);
		view.setFitHeight(10);
		return view;
	}

	private TaskManagerAux rowTask(TableCell<TaskManagerAux, ?> cell) {
		TableRow<TaskManagerAux> row = cell.getTableRow();
		return row == null ? null : row.getItem();
	}

	private record Timing(long secondsUntil, boolean ready) {
	}

	private final class ActionCell extends TableCell<TaskManagerAux, Void> {
		private final Button btnSchedule = iconButton("mdi2h-history", 16, "#ffce54", "Schedule Task");
		private final Button btnRemove = iconButton("mdi2c-close", 16, "#f85149", "Remove Task");
		private final Button btnExecute = iconButton("mdi2p-play", 22, "#2ea043", "Execute Task");
		private final HBox actionBox = new HBox(12, btnSchedule, btnRemove, btnExecute);

		private ActionCell() {
			actionBox.setAlignment(Pos.CENTER);
			btnSchedule.setOnAction(event -> {
				TaskManagerAux item = rowTask(this);
				if (item != null) {
					taskManagerActionController.showScheduleDialog(item);
					AccountDescriptor profile = taskManagerActionController.findProfileById(item.getProfileId());
					refreshProfileRows(profile);
				}
			});
			btnRemove.setOnAction(event -> {
				TaskManagerAux item = rowTask(this);
				if (item != null) {
					AccountDescriptor profile = taskManagerActionController.findProfileById(item.getProfileId());
					taskManagerActionController.removeTask(item, () -> refreshProfileRows(profile));
				}
			});
			btnExecute.setOnAction(event -> {
				TaskManagerAux item = rowTask(this);
				if (item != null) {
					AccountDescriptor profile = taskManagerActionController.findProfileById(item.getProfileId());
					taskManagerActionController.executeTaskDirectly(item);
					refreshProfileRows(profile);
				}
			});
		}

		@Override
		protected void updateItem(Void item, boolean empty) {
			super.updateItem(item, empty);
			unbindButtons();
			TaskManagerAux task = rowTask(this);
			if (empty || task == null) {
				setGraphic(null);
				return;
			}
			bindButtons(task);
			setGraphic(actionBox);
		}

		private void bindButtons(TaskManagerAux task) {
			btnSchedule.disableProperty().bind(Bindings.createBooleanBinding(
					() -> ScheduleService.obtain().getCoordinator().getQueue(task.getProfileId()) == null,
					globalClock));
			btnRemove.disableProperty().bind(Bindings.createBooleanBinding(
					() -> !taskManagerActionController.canRemoveTask(task),
					task.scheduledProperty(),
					task.executingProperty()));
			btnExecute.disableProperty().bind(Bindings.createBooleanBinding(
					() -> !taskManagerActionController.isQueueActive(task.getProfileId()) || task.executingProperty().get(),
					task.executingProperty(),
					globalClock));
			paintIcon(btnSchedule, "#ffce54");
			paintIcon(btnRemove, "#f85149");
			paintIcon(btnExecute, "#2ea043");
		}

		private void unbindButtons() {
			btnSchedule.disableProperty().unbind();
			btnRemove.disableProperty().unbind();
			btnExecute.disableProperty().unbind();
		}

		private Button iconButton(String iconLiteral, int size, String color, String tooltip) {
			Button button = new Button();
			FontIcon icon = new FontIcon(iconLiteral);
			icon.setIconSize(size);
			icon.setIconColor(Color.web(color));
			button.setGraphic(icon);
			button.getStyleClass().add("action-icon-button");
			button.setTooltip(new Tooltip(tooltip));
			return button;
		}

		private void paintIcon(Button button, String enabledColor) {
			FontIcon icon = (FontIcon) button.getGraphic();
			icon.setIconColor(Color.web(button.isDisabled() ? "#3b3f4c" : enabledColor));
			button.disableProperty().addListener((obs, oldValue, disabled) ->
					icon.setIconColor(Color.web(disabled ? "#3b3f4c" : enabledColor)));
		}
	}
}
