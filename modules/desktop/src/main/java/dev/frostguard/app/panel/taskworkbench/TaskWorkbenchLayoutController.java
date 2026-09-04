package dev.frostguard.app.panel.taskworkbench;

import dev.frostguard.api.configs.ControlledExecutionCapability;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.BotStateData;
import dev.frostguard.api.domain.TaskExecutionEventData;
import dev.frostguard.api.domain.TaskExecutionSnapshotData;
import dev.frostguard.api.domain.TaskExecutionState;
import dev.frostguard.engine.listener.TaskExecutionListener;
import dev.frostguard.engine.listener.BotStateListener;
import dev.frostguard.engine.schedule.TaskRegistration;
import dev.frostguard.engine.service.ControlledTaskExecutionService;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.service.ScheduleService;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.util.StringConverter;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.time.format.DateTimeFormatter;
import java.util.List;

public class TaskWorkbenchLayoutController implements TaskExecutionListener, BotStateListener {

    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    @FXML private ComboBox<AccountDescriptor> profileComboBox;
    @FXML private ComboBox<TaskRegistration> taskComboBox;
    @FXML private Button runButton;
    @FXML private Button pauseButton;
    @FXML private Button nextButton;
    @FXML private Button resumeButton;
    @FXML private Button stopButton;
    @FXML private Label stateLabel;
    @FXML private Label capabilityLabel;
    @FXML private Label currentStepLabel;
    @FXML private Label lastStepLabel;
    @FXML private Label messageLabel;
    @FXML private TableView<TaskExecutionEventData> historyTable;
    @FXML private TableColumn<TaskExecutionEventData, String> timeColumn;
    @FXML private TableColumn<TaskExecutionEventData, String> stepColumn;
    @FXML private TableColumn<TaskExecutionEventData, Object> statusColumn;

    private final ControlledTaskExecutionService executionService = ControlledTaskExecutionService.obtain();
    private TaskExecutionSnapshotData snapshot = TaskExecutionSnapshotData.idle();

    @FXML
    public void initialize() {
        configureActionButton(runButton, MaterialDesignP.PLAY, "Start controlled execution");
        configureActionButton(pauseButton, MaterialDesignP.PAUSE, "Pause at the next step boundary");
        configureActionButton(nextButton, MaterialDesignS.STEP_FORWARD, "Execute the next step");
        configureActionButton(resumeButton, MaterialDesignP.PLAY_SPEED, "Resume continuous execution");
        configureActionButton(stopButton, MaterialDesignS.STOP, "Stop controlled execution");
        configureProfiles();
        configureTasks();
        configureHistory();
        executionService.addListener(this);
        ScheduleService.obtain().addEngineObserver(this);
        render(executionService.getSnapshot());
    }

    @FXML
    private void handleRun() {
        try {
            render(executionService.start(profileComboBox.getValue(), taskComboBox.getValue()));
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
            updateControls();
        });
        executionService.getAvailableTasks().stream()
                .filter(registration -> registration.controlledExecutionCapability()
                        == ControlledExecutionCapability.STEP_AWARE)
                .findFirst()
                .ifPresentOrElse(taskComboBox::setValue, () -> taskComboBox.getSelectionModel().selectFirst());
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

    private void configureHistory() {
        timeColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                cell.getValue().occurredAt().format(EVENT_TIME)));
        stepColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().stepName()));
        statusColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().status()));
        historyTable.setPlaceholder(new Label("No execution events"));
    }

    private void render(TaskExecutionSnapshotData newSnapshot) {
        snapshot = newSnapshot == null ? TaskExecutionSnapshotData.idle() : newSnapshot;
        stateLabel.setText(snapshot.state().name().replace('_', ' '));
        stateLabel.getStyleClass().removeIf(style -> style.startsWith("workbench-state-"));
        stateLabel.getStyleClass().add("workbench-state-" + snapshot.state().name().toLowerCase());
        currentStepLabel.setText(formatStep(snapshot.currentStep(), snapshot.currentStepStatus()));
        lastStepLabel.setText(formatStep(snapshot.lastStep(), snapshot.lastStepStatus()));
        messageLabel.setText(snapshot.message() == null ? "" : snapshot.message());
        messageLabel.setManaged(snapshot.message() != null && !snapshot.message().isBlank());
        messageLabel.setVisible(messageLabel.isManaged());
        historyTable.getItems().setAll(snapshot.history());
        if (!historyTable.getItems().isEmpty()) {
            historyTable.scrollTo(historyTable.getItems().size() - 1);
        }
        updateControls();
    }

    private void updateControls() {
        TaskExecutionState state = snapshot.state();
        boolean active = state.isActive();
        boolean selectionReady = profileComboBox.getValue() != null && taskComboBox.getValue() != null;
        runButton.setDisable(active || !selectionReady || ScheduleService.obtain().isEngineRunning());
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
        messageLabel.setText(exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage());
        messageLabel.setManaged(true);
        messageLabel.setVisible(true);
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

    private static String formatStep(String name, Object status) {
        return name == null || name.isBlank() ? "None" : name + (status == null ? "" : "  |  " + status);
    }
}
