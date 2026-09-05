package dev.frostguard.app.panel.taskworkbench;

import dev.frostguard.api.configs.ControlledExecutionCapability;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.BotStateData;
import dev.frostguard.api.domain.LogMessageData;
import dev.frostguard.api.domain.TaskExecutionEventData;
import dev.frostguard.api.domain.TaskExecutionSnapshotData;
import dev.frostguard.api.domain.TaskExecutionState;
import dev.frostguard.api.domain.TaskStepStatus;
import dev.frostguard.engine.listener.BotStateListener;
import dev.frostguard.engine.listener.LogListener;
import dev.frostguard.engine.listener.TaskExecutionListener;
import dev.frostguard.engine.schedule.TaskRegistration;
import dev.frostguard.engine.service.ControlledTaskExecutionService;
import dev.frostguard.engine.service.LoggingService;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.service.ScheduleService;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.text.Text;
import javafx.util.StringConverter;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

public class TaskWorkbenchLayoutController implements TaskExecutionListener, BotStateListener, LogListener {

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int MAX_LOG_ROWS = 600;

    @FXML private ComboBox<AccountDescriptor> profileComboBox;
    @FXML private ComboBox<TaskRegistration> taskComboBox;
    @FXML private Button loadButton;
    @FXML private Button pauseButton;
    @FXML private Button nextButton;
    @FXML private Button resumeButton;
    @FXML private Button stopButton;
    @FXML private Label stateLabel;
    @FXML private Label capabilityLabel;
    @FXML private Label currentStepLabel;
    @FXML private Label nextStepLabel;
    @FXML private Label lastStepLabel;
    @FXML private Label messageLabel;
    @FXML private Pane graphPane;
    @FXML private TableView<WorkbenchLogEntry> logTable;
    @FXML private TableColumn<WorkbenchLogEntry, String> logTimeColumn;
    @FXML private TableColumn<WorkbenchLogEntry, String> logLevelColumn;
    @FXML private TableColumn<WorkbenchLogEntry, String> logSourceColumn;
    @FXML private TableColumn<WorkbenchLogEntry, String> logMessageColumn;

    private final ControlledTaskExecutionService executionService = ControlledTaskExecutionService.obtain();
    private final LoggingService loggingService = LoggingService.obtain();
    private TaskExecutionSnapshotData snapshot = TaskExecutionSnapshotData.idle();
    private TaskRegistration displayedRegistration;
    private String loadedProfileName;
    private String loadedTaskName;
    private LocalDateTime loadedAt;
    private long lastRenderedEventSequence;
    private TaskExecutionState lastLoggedState = TaskExecutionState.IDLE;

    @FXML
    public void initialize() {
        configureLoadButton();
        configureActionButton(pauseButton, MaterialDesignP.PAUSE, "Pause at the next step boundary");
        configureActionButton(nextButton, MaterialDesignS.STEP_FORWARD, "Execute the next step");
        configureActionButton(resumeButton, MaterialDesignP.PLAY_SPEED, "Resume continuous execution");
        configureActionButton(stopButton, MaterialDesignS.STOP, "Stop controlled execution");
        configureProfiles();
        configureTasks();
        configureLogs();
        graphPane.widthProperty().addListener((observable, previous, current) -> renderGraph());
        loggingService.addObserver(this);
        executionService.addListener(this);
        ScheduleService.obtain().addEngineObserver(this);
        render(executionService.getSnapshot());
    }

    @FXML
    private void handleLoad() {
        AccountDescriptor profile = profileComboBox.getValue();
        TaskRegistration registration = taskComboBox.getValue();
        displayedRegistration = registration;
        loadedAt = LocalDateTime.now();
        loadedProfileName = profile == null ? null : profile.getName();
        loadedTaskName = registration == null ? null : registration.taskType().getName();
        lastRenderedEventSequence = 0;
        lastLoggedState = TaskExecutionState.IDLE;
        logTable.getItems().clear();
        try {
            TaskExecutionSnapshotData loadedSnapshot = executionService.start(profile, registration);
            render(loadedSnapshot);
            appendLog(new WorkbenchLogEntry(LocalDateTime.now(), "SESSION", "Workbench",
                    "Task loaded in paused mode"));
        } catch (RuntimeException exception) {
            showCommandFailure(exception);
        }
    }

    @FXML
    private void handlePause() {
        invokeCommand(executionService::pause);
    }

    @FXML
    private void handleNext() {
        invokeCommand(executionService::executeNextStep);
    }

    @FXML
    private void handleResume() {
        invokeCommand(executionService::resume);
    }

    @FXML
    private void handleStop() {
        invokeCommand(executionService::stop);
    }

    @Override
    public void onTaskExecutionChanged(TaskExecutionSnapshotData newSnapshot) {
        Platform.runLater(() -> render(newSnapshot));
    }

    @Override
    public void onEngineStateTransition(BotStateData botState) {
        Platform.runLater(this::updateControls);
    }

    @Override
    public void onLogEntryEmitted(LogMessageData message) {
        if (!matchesLoadedSession(message)) {
            return;
        }
        Platform.runLater(() -> appendLog(new WorkbenchLogEntry(
                message.getTimestamp(), message.getSeverity().name(), message.getSourceTask(), message.getBody())));
    }

    private void configureLoadButton() {
        FontIcon graphic = new FontIcon(MaterialDesignF.FOLDER_DOWNLOAD_OUTLINE);
        graphic.setIconSize(17);
        loadButton.setGraphic(graphic);
        loadButton.setText("Load task");
        loadButton.setTooltip(new Tooltip("Load the selected task in paused mode"));
    }

    private void configureProfiles() {
        profileComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(AccountDescriptor profile) {
                return profile == null ? "" : profile.getName() + " (" + profile.getEmulatorNumber() + ")";
            }

            @Override
            public AccountDescriptor fromString(String value) {
                return null;
            }
        });
        profileComboBox.setOnShowing(event -> refreshProfiles());
        profileComboBox.valueProperty().addListener((observable, previous, selected) -> updateControls());
        refreshProfiles();
    }

    private void refreshProfiles() {
        AccountDescriptor selected = profileComboBox.getValue();
        List<AccountDescriptor> profiles = ProfileService.obtain().fetchAllAccounts();
        profileComboBox.setItems(FXCollections.observableArrayList(profiles));
        if (selected != null) {
            profiles.stream()
                    .filter(profile -> selected.getId().equals(profile.getId()))
                    .findFirst()
                    .ifPresent(profileComboBox::setValue);
        }
        if (profileComboBox.getValue() == null && !profiles.isEmpty()) {
            profileComboBox.getSelectionModel().selectFirst();
        }
    }

    private void configureTasks() {
        taskComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(TaskRegistration registration) {
                return registration == null ? "" : registration.taskType().getName();
            }

            @Override
            public TaskRegistration fromString(String value) {
                return null;
            }
        });
        taskComboBox.setCellFactory(listView -> taskCell());
        taskComboBox.setButtonCell(taskCell());
        taskComboBox.setItems(FXCollections.observableArrayList(executionService.getAvailableTasks()));
        taskComboBox.valueProperty().addListener((observable, previous, selected) -> {
            capabilityLabel.setText(capabilityText(selected));
            if (!snapshot.state().isActive()) {
                renderIdlePreview(selected);
            }
            updateControls();
        });
        executionService.getAvailableTasks().stream()
                .filter(registration -> registration.controlledExecutionCapability()
                        == ControlledExecutionCapability.STEP_AWARE)
                .findFirst()
                .ifPresentOrElse(taskComboBox::setValue, () -> taskComboBox.getSelectionModel().selectFirst());
    }

    private void renderIdlePreview(TaskRegistration registration) {
        displayedRegistration = registration;
        loadedAt = null;
        loadedProfileName = null;
        loadedTaskName = null;
        lastRenderedEventSequence = 0;
        lastLoggedState = TaskExecutionState.IDLE;
        logTable.getItems().clear();
        render(TaskExecutionSnapshotData.idle());
    }

    private ListCell<TaskRegistration> taskCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(TaskRegistration registration, boolean empty) {
                super.updateItem(registration, empty);
                setText(empty || registration == null
                        ? null
                        : registration.taskType().getName() + "  |  " + capabilityText(registration));
            }
        };
    }

    private void configureLogs() {
        logTimeColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                cell.getValue().occurredAt().format(LOG_TIME)));
        logLevelColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().level()));
        logSourceColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().source()));
        logMessageColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().message()));
        logMessageColumn.setCellFactory(column -> wrappingLogCell());
        logTable.setPlaceholder(new Label("No logs for this loaded session"));
    }

    private TableCell<WorkbenchLogEntry, String> wrappingLogCell() {
        return new TableCell<>() {
            private final Text text = new Text();

            {
                setGraphic(text);
                text.wrappingWidthProperty().bind(widthProperty().subtract(16));
                text.fillProperty().bind(textFillProperty());
                setPrefHeight(USE_COMPUTED_SIZE);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                text.setText(empty ? null : item);
            }
        };
    }

    private void render(TaskExecutionSnapshotData newSnapshot) {
        snapshot = newSnapshot == null ? TaskExecutionSnapshotData.idle() : newSnapshot;
        if (snapshot.taskType() != null) {
            displayedRegistration = executionService.getAvailableTasks().stream()
                    .filter(registration -> registration.taskType() == snapshot.taskType())
                    .findFirst()
                    .orElse(displayedRegistration);
        }
        stateLabel.setText(formatState(snapshot.state()));
        stateLabel.getStyleClass().removeIf(style -> style.startsWith("workbench-state-"));
        stateLabel.getStyleClass().add("workbench-state-" + snapshot.state().name().toLowerCase());
        renderStepSummary();
        messageLabel.setText(snapshot.message() == null ? "" : snapshot.message());
        messageLabel.setManaged(snapshot.message() != null && !snapshot.message().isBlank());
        messageLabel.setVisible(messageLabel.isManaged());
        appendStepEvents(snapshot.history());
        appendTerminalState();
        renderGraph();
        updateControls();
    }

    private void renderStepSummary() {
        lastStepLabel.setText(formatStep(snapshot.lastStep(), snapshot.lastStepStatus()));
        currentStepLabel.setText(formatStep(snapshot.currentStep(), snapshot.currentStepStatus()));
        nextStepLabel.setText(formatStep(snapshot.nextStep(), snapshot.nextStepStatus()));
    }

    private void appendStepEvents(List<TaskExecutionEventData> history) {
        for (TaskExecutionEventData event : history) {
            if (event.sequence() <= lastRenderedEventSequence) {
                continue;
            }
            String message = event.stepName();
            if (event.detail() != null && !event.detail().isBlank()) {
                message += ": " + event.detail();
            }
            appendLog(new WorkbenchLogEntry(event.occurredAt(), event.status().name(), "Step", message));
            lastRenderedEventSequence = event.sequence();
        }
    }

    private void appendLog(WorkbenchLogEntry entry) {
        logTable.getItems().add(entry);
        if (logTable.getItems().size() > MAX_LOG_ROWS) {
            logTable.getItems().remove(0, logTable.getItems().size() - MAX_LOG_ROWS);
        }
        logTable.scrollTo(logTable.getItems().size() - 1);
    }

    private void appendTerminalState() {
        TaskExecutionState state = snapshot.state();
        if (state == lastLoggedState) {
            return;
        }
        lastLoggedState = state;
        if (state != TaskExecutionState.COMPLETED
                && state != TaskExecutionState.FAILED
                && state != TaskExecutionState.STOPPED) {
            return;
        }
        String message = "Execution " + state.name().toLowerCase();
        if (snapshot.message() != null && !snapshot.message().isBlank()) {
            message += ": " + snapshot.message();
        }
        appendLog(new WorkbenchLogEntry(LocalDateTime.now(),
                state == TaskExecutionState.FAILED ? "ERROR" : "SESSION", "Workbench", message));
    }

    private boolean matchesLoadedSession(LogMessageData message) {
        return loadedAt != null
                && message != null
                && message.getTimestamp() != null
                && !message.getTimestamp().isBefore(loadedAt)
                && Objects.equals(loadedProfileName, message.getAccountTag())
                && Objects.equals(loadedTaskName, message.getSourceTask());
    }

    private void renderGraph() {
        if (displayedRegistration == null) {
            graphPane.getChildren().clear();
            return;
        }
        TaskFlowGraphRenderer.render(
                graphPane, displayedRegistration.flowDefinition(), snapshot);
    }

    private void updateControls() {
        TaskExecutionState state = snapshot.state();
        boolean active = state.isActive();
        boolean selectionReady = profileComboBox.getValue() != null && taskComboBox.getValue() != null;
        loadButton.setDisable(active || !selectionReady || ScheduleService.obtain().isEngineRunning());
        profileComboBox.setDisable(active);
        taskComboBox.setDisable(active);
        pauseButton.setDisable(state != TaskExecutionState.RUNNING);
        nextButton.setDisable(state != TaskExecutionState.PAUSED);
        resumeButton.setDisable(state != TaskExecutionState.PAUSED
                && state != TaskExecutionState.PAUSE_REQUESTED);
        stopButton.setDisable(!active || state == TaskExecutionState.STOPPING);
    }

    private void invokeCommand(Runnable command) {
        try {
            command.run();
        } catch (RuntimeException exception) {
            showCommandFailure(exception);
        }
    }

    private void showCommandFailure(RuntimeException exception) {
        String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        messageLabel.setText(message);
        messageLabel.setManaged(true);
        messageLabel.setVisible(true);
        appendLog(new WorkbenchLogEntry(LocalDateTime.now(), "ERROR", "Workbench", message));
    }

    private static void configureActionButton(Button button, Ikon icon, String tooltip) {
        FontIcon graphic = new FontIcon(icon);
        graphic.setIconSize(17);
        button.setGraphic(graphic);
        button.setTooltip(new Tooltip(tooltip));
    }

    private static String capabilityText(TaskRegistration registration) {
        if (registration == null) {
            return "No task selected";
        }
        return registration.controlledExecutionCapability() == ControlledExecutionCapability.STEP_AWARE
                ? "Step-aware"
                : "Coarse";
    }

    private static String formatStep(String name, TaskStepStatus status) {
        if (name == null || name.isBlank()) {
            return "None";
        }
        String visibleStatus = status == null
                ? ""
                : status == TaskStepStatus.STARTED ? "RUNNING" : status.name();
        if (visibleStatus.isBlank()) {
            return name;
        }
        return name + "  |  " + visibleStatus;
    }

    private static String formatState(TaskExecutionState state) {
        return state.name().replace('_', ' ');
    }

    private record WorkbenchLogEntry(LocalDateTime occurredAt, String level, String source, String message) {
    }
}
