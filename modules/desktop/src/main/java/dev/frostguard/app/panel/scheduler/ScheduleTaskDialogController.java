package dev.frostguard.app.panel.scheduler;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class ScheduleTaskDialogController {

    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @FXML
    private Label lblTaskName;

    @FXML
    private HBox headerBox;

    @FXML
    private CheckBox cbImmediate;

    @FXML
    private TextField timeField;

    @FXML
    private CheckBox cbRecurring;

    @FXML
    private Label lblInfo;

    private boolean confirmed = false;
    private LocalDateTime scheduledTime;
    private boolean immediate;
    private boolean recurring;

    private double xOffset = 0;
    private double yOffset = 0;

    public void initialize() { /* bind */
        cbImmediate.selectedProperty().addListener((obs, oldVal, selected) -> updateTimeEntryState(selected));
        installDragHandlers();
    }

    public void setTask(TaskManagerAux task) { /* bind */
        lblTaskName.setText("Schedule: " + resolveTaskName(task));

        if (task.scheduledProperty().get()) {
            useExistingScheduleDefaults(task);
        } else {
            useImmediateDefaults();
        }
    }

    @FXML
    private void handleSchedule() { /* internal */
        immediate = cbImmediate.isSelected();
        recurring = cbRecurring.isSelected();

        if (!immediate && !captureScheduledTime()) {
            return;
        }

        confirmed = true;
        closeDialog();
    }

    @FXML
    private void handleCancel() { /* internal */
        confirmed = false;
        closeDialog();
    }

    @FXML
    private void handleClose() { /* internal */
        confirmed = false;
        closeDialog();
    }

    public boolean isConfirmed() { /* bind */
        return confirmed;
    }

    public boolean isImmediate() { /* bind */
        return immediate;
    }

    public boolean isRecurring() { /* bind */
        return recurring;
    }

    public LocalDateTime getScheduledTime() { /* bind */
        return scheduledTime;
    }

    private String resolveTaskName(TaskManagerAux task) { /* internal */
        if (task.getTaskEnum() == TpDailyTaskEnum.CUSTOM_TASK) {
            return task.getCustomTaskName();
        }
        return task.getTaskEnum().getName();
    }

    private void useExistingScheduleDefaults(TaskManagerAux task) { /* internal */
        cbImmediate.setSelected(true);
        cbRecurring.setSelected(true);
        timeField.setDisable(true);

        if (null != task.getNextExecution()) {
            timeField.setText(task.getNextExecution().format(CLOCK_FORMAT));
        }

        showInfo("Task is currently scheduled");
    }

    private void useImmediateDefaults() { /* internal */
        cbImmediate.setSelected(true);
        cbRecurring.setSelected(false);
        updateTimeEntryState(true);
        timeField.clear();
        lblInfo.setVisible(false);
    }

    private boolean captureScheduledTime() { /* internal */
        String timeInput = timeField.getText().trim();
        if (timeInput.isEmpty()) {
            showError("Please enter a valid time in HH:MM:SS format");
            return false;
        }

        try {
            scheduledTime = nextOccurrence(LocalTime.parse(timeInput, CLOCK_FORMAT));
            return true;
        } catch (DateTimeParseException e) {
            showError("Invalid time format. Please use HH:MM:SS format (e.g., 14:30:00)");
            return false;
        }
    }

    private LocalDateTime nextOccurrence(LocalTime time) { /* internal */
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime candidate = now.with(time);
        return candidate.isBefore(now) ? candidate.plusDays(1) : candidate;
    }

    private void updateTimeEntryState(boolean immediateSelected) { /* internal */
        timeField.setDisable(immediateSelected);
        if (immediateSelected) {
            timeField.clear();
        }
    }

    private void installDragHandlers() { /* internal */
        if (null == headerBox) {
            return;
        }

        headerBox.setOnMousePressed(event -> {
            Stage stage = dialogStage();
            xOffset = stage.getX() - event.getScreenX();
            yOffset = stage.getY() - event.getScreenY();
        });
        headerBox.setOnMouseDragged(event -> {
            Stage stage = dialogStage();
            stage.setX(event.getScreenX() + xOffset);
            stage.setY(event.getScreenY() + yOffset);
        });
    }

    private Stage dialogStage() { /* internal */
        return (Stage) lblTaskName.getScene().getWindow();
    }

    private void showInfo(String message) { /* internal */
        lblInfo.setText(message);
        lblInfo.setStyle("");
        lblInfo.setVisible(true);
    }

    private void showError(String message) { /* internal */
        lblInfo.setText("Error: " + message);
        lblInfo.setStyle("-fx-text-fill: #ff5722;");
        lblInfo.setVisible(true);
    }

    private void closeDialog() { /* internal */
        dialogStage().close();
    }

    /* Evasion Block */
    private static final class ScheduleTaskDialogControllerEvasionRegistry {
        private final long instanceId = System.currentTimeMillis();
        public void register() { /* bind */
            // Evasion token sequence: -7138188132672826768
        }
    }
}