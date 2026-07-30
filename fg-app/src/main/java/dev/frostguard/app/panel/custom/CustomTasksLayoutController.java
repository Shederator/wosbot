package dev.frostguard.app.panel.custom;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.service.CustomTaskService;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.engine.service.TaskManagementService;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.api.domain.TaskStateData;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Controller for the Custom Tasks tab.
 * <p>
 * Allows users to import .java files exported by the Task Builder,
 * compile them at runtime, and schedule them for execution in the bot's TaskQueue.
 */
public class CustomTasksLayoutController {

    @FXML private VBox taskCardsContainer;
    @FXML private VBox emptyState;
    @FXML private ScrollPane tasksScrollPane;
    @FXML private Label statusLabel;
    @FXML private Button btnImportTask;

    private final CustomTaskService customTaskService = CustomTaskService.getInstance();
    private final Map<String, VBox> taskCards = new LinkedHashMap<>();
    private final Map<String, Integer> taskOffsets = new HashMap<>();
    private final Map<String, Integer> taskPriorities = new HashMap<>();
    private final Map<String, Boolean> taskEnabled = new HashMap<>();
    private final Map<String, String> taskFirstExecutionUtc = new HashMap<>();
    private final Map<String, Integer> taskFollowUpDelayHours = new HashMap<>();

    private static final DateTimeFormatter UTC_INPUT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String DEFAULT_SHIELD_FIRST_EXECUTION_UTC = "2026-06-20 09:00";
    private static final int DEFAULT_FOLLOW_UP_DELAY_HOURS = 8;

    @FXML
    public void initialize() {
        restoreSavedTasks();
        updateEmptyState();
    }

    /**
     * Restores previously imported/enabled custom tasks from CustomTaskService
     * persistence so the UI reflects the saved state on app restart.
     */
    private void restoreSavedTasks() {
        List<CustomTaskService.SavedTaskEntry> saved = customTaskService.getAllSavedEntries();
        for (CustomTaskService.SavedTaskEntry entry : saved) {
            if (taskCards.containsKey(entry.getClassName())) {
                continue; // already shown
            }
            taskEnabled.put(entry.getClassName(), entry.isEnabled());
            taskOffsets.put(entry.getClassName(), entry.getOffsetMinutes());
            taskPriorities.put(entry.getClassName(), entry.getPriority());
            taskFirstExecutionUtc.put(entry.getClassName(), getInitialExecutionUtcValue(entry));
            taskFollowUpDelayHours.put(entry.getClassName(), getFollowUpDelayValue(entry));

            addTaskCard(entry.getClassName(), new java.io.File(entry.getSourcePath()), entry.isEnabled(),
                    entry.getOffsetMinutes(), entry.getPriority(), getInitialExecutionUtcValue(entry),
                    getFollowUpDelayValue(entry));
        }
    }

    @FXML
    private void handleImportTask() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Import Custom Task (.java)");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Java Source Files", "*.java")
        );

        // Default to user home
        fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));

        File selectedFile = fileChooser.showOpenDialog(
                taskCardsContainer.getScene().getWindow()
        );

        if (selectedFile != null) {
            importTask(selectedFile);
        }
    }

    private void importTask(File javaFile) {
        setStatus("⏳ Compiling " + javaFile.getName() + "...");
        btnImportTask.setDisable(true);

        Thread compileThread = new Thread(() -> {
            String className = customTaskService.compileAndLoad(javaFile);
            Platform.runLater(() -> {
                btnImportTask.setDisable(false);
                if (className != null) {
                    if (taskCards.containsKey(className)) {
                        // Already imported — just refresh
                        setStatus("✅ Reloaded: " + className);
                    } else {
                        taskOffsets.put(className, 60);
                        taskPriorities.put(className, 0);
                        taskEnabled.put(className, false);
                        taskFirstExecutionUtc.put(className, getDefaultFirstExecutionUtc(className));
                        taskFollowUpDelayHours.put(className, DEFAULT_FOLLOW_UP_DELAY_HOURS);
                        customTaskService.saveTaskSettings(className, className, 60, 0,
                                taskFirstExecutionUtc.get(className), taskFollowUpDelayHours.get(className));
                        addTaskCard(className, javaFile, false, 60, 0,
                                taskFirstExecutionUtc.get(className), taskFollowUpDelayHours.get(className));
                        setStatus("✅ Imported: " + className);
                    }
                    updateEmptyState();
                } else {
                    setStatus("❌ Compilation failed — check logs");
                    showAlert("Compilation Failed",
                            "Could not compile " + javaFile.getName() + ".\n\n" +
                                    "Make sure the file extends DelayedTask and the project " +
                                    "is running on a JDK (not JRE).");
                }
            });
        });
        compileThread.setDaemon(true);
        compileThread.start();
    }

    private void addTaskCard(String className, File sourceFile, boolean initialEnabled, int initialOffset,
                             int initialPriority, String initialFirstExecutionUtc, int initialFollowUpDelayHours) {
        VBox card = new VBox(0);
        card.getStyleClass().add("custom-task-card");
        card.setMaxWidth(Double.MAX_VALUE);

        // ── Card Header ──
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("custom-task-card-header");
        header.setPadding(new Insets(12, 16, 12, 16));

        FontIcon taskIcon = new FontIcon("mdi2c-code-braces");
        taskIcon.setIconSize(20);
        taskIcon.setIconColor(Color.web("#a78bfa"));

        VBox titleBlock = new VBox(2);
        Label nameLabel = new Label(className);
        nameLabel.getStyleClass().add("custom-task-card-name");
        Label pathLabel = new Label(sourceFile.getAbsolutePath());
        pathLabel.getStyleClass().add("custom-task-card-path");
        pathLabel.setMaxWidth(350);
        titleBlock.getChildren().addAll(nameLabel, pathLabel);
        HBox.setHgrow(titleBlock, Priority.ALWAYS);

        // Initialize maps
        taskOffsets.put(className, initialOffset);
        taskPriorities.put(className, initialPriority);
        taskEnabled.put(className, initialEnabled);
        taskFirstExecutionUtc.put(className, initialFirstExecutionUtc);
        taskFollowUpDelayHours.put(className, initialFollowUpDelayHours);

        // Status badge
        Label statusBadge = new Label(initialEnabled ? "Enabled" : "Ready");
        statusBadge.getStyleClass().add(initialEnabled ? "custom-task-badge-enabled" : "custom-task-badge-ready");

        header.getChildren().addAll(taskIcon, titleBlock, statusBadge);

        // ── Divider ──
        Separator divider = new Separator();
        divider.getStyleClass().add("custom-task-card-divider");

        // ── Card Body ──
        HBox body = new HBox(16);
        body.setAlignment(Pos.CENTER_LEFT);
        body.setPadding(new Insets(12, 16, 12, 16));
        body.getStyleClass().add("custom-task-card-body");

        // Enable checkbox
        CheckBox enableCheck = new CheckBox("Enable");
        enableCheck.getStyleClass().add("custom-task-checkbox");
        enableCheck.setSelected(initialEnabled);
        enableCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            taskEnabled.put(className, newVal);
            statusBadge.setText(newVal ? "Enabled" : "Ready");
            statusBadge.getStyleClass().removeAll("custom-task-badge-ready", "custom-task-badge-enabled");
            statusBadge.getStyleClass().add(newVal ? "custom-task-badge-enabled" : "custom-task-badge-ready");

            if (newVal) {
                if (!scheduleTask(className)) {
                    enableCheck.setSelected(false);
                }
            } else {
                unscheduleTask(className);
            }
        });

        VBox enableBox = new VBox(8);
        enableBox.getChildren().add(enableCheck);

        if (customTaskService.supportsCustomTaskConfiguration(className)) {
            VBox firstExecutionBox = new VBox(4);
            Label firstExecutionLabel = new Label("First execution (UTC)");
            firstExecutionLabel.getStyleClass().add("custom-task-field-label");
            TextField firstExecutionField = new TextField(initialFirstExecutionUtc);
            firstExecutionField.getStyleClass().add("custom-task-field");
            firstExecutionField.setPrefWidth(170);
            firstExecutionField.setMaxWidth(170);
            firstExecutionField.setPromptText("yyyy-MM-dd HH:mm");

            TextField followUpDelayField = new TextField(String.valueOf(initialFollowUpDelayHours));
            Runnable commitTimingChange = () -> {
                if (!commitTimingSettings(className, firstExecutionField.getText(), followUpDelayField.getText())) {
                    firstExecutionField.setText(taskFirstExecutionUtc.getOrDefault(
                            className, getDefaultFirstExecutionUtc(className)));
                }
            };
            firstExecutionField.setOnAction(e -> commitTimingChange.run());
            firstExecutionField.focusedProperty().addListener((obs, oldVal, newVal) -> {
                if (!newVal) {
                    commitTimingChange.run();
                }
            });
            firstExecutionBox.getChildren().addAll(firstExecutionLabel, firstExecutionField);

            VBox followUpDelayBox = new VBox(4);
            Label followUpDelayLabel = new Label("Follow-up delay (hours)");
            followUpDelayLabel.getStyleClass().add("custom-task-field-label");
            followUpDelayField.getStyleClass().add("custom-task-field");
            followUpDelayField.setPrefWidth(120);
            followUpDelayField.setMaxWidth(120);
            followUpDelayField.setPromptText(String.valueOf(DEFAULT_FOLLOW_UP_DELAY_HOURS));
            Runnable commitDelayChange = () -> {
                if (!commitTimingSettings(className, firstExecutionField.getText(), followUpDelayField.getText())) {
                    followUpDelayField.setText(String.valueOf(taskFollowUpDelayHours.getOrDefault(
                            className, DEFAULT_FOLLOW_UP_DELAY_HOURS)));
                }
            };
            followUpDelayField.setOnAction(e -> commitDelayChange.run());
            followUpDelayField.focusedProperty().addListener((obs, oldVal, newVal) -> {
                if (!newVal) {
                    commitDelayChange.run();
                }
            });
            followUpDelayBox.getChildren().addAll(followUpDelayLabel, followUpDelayField);

            enableBox.getChildren().addAll(firstExecutionBox, followUpDelayBox);
        }

        // Offset field
        VBox offsetBox = new VBox(4);
        Label offsetLabel = new Label("Offset (minutes)");
        offsetLabel.getStyleClass().add("custom-task-field-label");
        TextField offsetField = new TextField(String.valueOf(initialOffset));
        offsetField.getStyleClass().add("custom-task-field");
        offsetField.setPrefWidth(100);
        offsetField.setMaxWidth(100);
        offsetField.textProperty().addListener((obs, oldVal, newVal) -> {
            try {
                int offset = Integer.parseInt(newVal.trim());
                if (offset >= 0) {
                    taskOffsets.put(className, offset);
                }
            } catch (NumberFormatException ignored) {}
        });
        offsetField.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal) {
                persistCurrentSettings(className);
            }
        });
        offsetBox.getChildren().addAll(offsetLabel, offsetField);

        // Priority field
        VBox priorityBox = new VBox(4);
        Label priorityLabel = new Label("Priority");
        priorityLabel.getStyleClass().add("custom-task-field-label");
        TextField priorityField = new TextField(String.valueOf(initialPriority));
        priorityField.getStyleClass().add("custom-task-field");
        priorityField.setPrefWidth(80);
        priorityField.setMaxWidth(80);
        priorityField.setPromptText("0");
        priorityField.textProperty().addListener((obs, oldVal, newVal) -> {
            try {
                int priority = Integer.parseInt(newVal.trim());
                taskPriorities.put(className, priority);
            } catch (NumberFormatException ignored) {}
        });
        priorityField.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal) {
                persistCurrentSettings(className);
            }
        });
        priorityBox.getChildren().addAll(priorityLabel, priorityField);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Action buttons (only Remove)
        HBox actionsBox = new HBox(8);
        actionsBox.setAlignment(Pos.CENTER_RIGHT);

        Button btnRemove = new Button();
        FontIcon removeIcon = new FontIcon("mdi2d-delete-outline");
        removeIcon.setIconSize(16);
        removeIcon.setIconColor(Color.web("#f85149"));
        btnRemove.setGraphic(removeIcon);
        btnRemove.getStyleClass().add("custom-task-action-btn");
        btnRemove.setTooltip(new Tooltip("Remove Task"));
        btnRemove.setOnAction(e -> removeTask(className));

        actionsBox.getChildren().addAll(btnRemove);

        body.getChildren().addAll(enableBox, offsetBox, priorityBox, spacer, actionsBox);

        card.getChildren().addAll(header, divider, body);
        taskCards.put(className, card);

        // Add the card before the empty state (which will be hidden)
        int insertIndex = taskCardsContainer.getChildren().indexOf(emptyState);
        if (insertIndex < 0) insertIndex = taskCardsContainer.getChildren().size();
        taskCardsContainer.getChildren().add(insertIndex, card);

        // Animate card appearance
        card.setOpacity(0);
        card.setTranslateY(12);
        javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(
                javafx.util.Duration.millis(200), card);
        fade.setFromValue(0); fade.setToValue(1);
        javafx.animation.TranslateTransition slide = new javafx.animation.TranslateTransition(
                javafx.util.Duration.millis(250), card);
        slide.setFromY(12); slide.setToY(0);
        slide.setInterpolator(javafx.animation.Interpolator.EASE_OUT);
        new javafx.animation.ParallelTransition(fade, slide).play();
    }

    private void removeTask(String className) {
        VBox card = taskCards.remove(className);
        if (card != null) {
            taskCardsContainer.getChildren().remove(card);
        }
        // Unschedule before removing
        if (Boolean.TRUE.equals(taskEnabled.get(className))) {
            unscheduleTask(className);
        }
        taskOffsets.remove(className);
        taskPriorities.remove(className);
        taskEnabled.remove(className);
        taskFirstExecutionUtc.remove(className);
        taskFollowUpDelayHours.remove(className);
        customTaskService.removeTask(className);
        updateEmptyState();
        setStatus("Removed: " + className);
    }

    private boolean scheduleTask(String className) {
        int offsetMin = taskOffsets.getOrDefault(className, 60);
        int priority = taskPriorities.getOrDefault(className, 0);
        String firstExecutionUtc = taskFirstExecutionUtc.getOrDefault(className, getDefaultFirstExecutionUtc(className));
        int followUpDelayHours = taskFollowUpDelayHours.getOrDefault(className, DEFAULT_FOLLOW_UP_DELAY_HOURS);
        if (customTaskService.supportsCustomTaskConfiguration(className)
                && (!isValidUtcDateTime(firstExecutionUtc) || followUpDelayHours <= 0)) {
            taskEnabled.put(className, false);
            setStatus("❌ Invalid custom timing settings for " + className);
            showAlert("Invalid Custom Task Settings",
                    "Use UTC format yyyy-MM-dd HH:mm and a positive follow-up delay in hours.");
            return false;
        }

        // Register with CustomTaskService so bot startup picks it up
        customTaskService.enableTask(className, className, offsetMin, priority, firstExecutionUtc, followUpDelayHours);

        // Also try to add to live queues if bot is already running
        List<AccountDescriptor> profiles = ProfileService.obtain().fetchAllAccounts();
        if (profiles == null || profiles.isEmpty()) {
            setStatus("📋 " + className + " enabled — will be scheduled when bot starts");
            return true;
        }

        boolean addedToAny = false;
        CustomTaskService.CustomTaskSettings configs =
                new CustomTaskService.CustomTaskSettings(className, className, offsetMin, priority,
                        firstExecutionUtc, followUpDelayHours);
        for (AccountDescriptor profile : profiles) {
            TaskQueue queue = ScheduleService.obtain().getCoordinator().getQueue(profile.getId());
            if (queue != null) {
                DelayedTask task = ScheduleService.obtain()
                        .scheduleCustomTask(profile, queue, configs, null, false, true);
                if (task != null) {
                    addedToAny = true;
                    setStatus("📋 Scheduled " + className + " for " + profile.getName()
                            + " (offset: " + offsetMin + "m, priority: " + priority + ")");
                }
            }
        }

        if (!addedToAny) {
            setStatus("📋 " + className + " enabled — will be scheduled when bot starts");
        }
        return true;
    }

    private void unscheduleTask(String className) {
        // Unregister from CustomTaskService
        customTaskService.disableTask(className);

        // Also try to remove from live queues
        List<AccountDescriptor> profiles = ProfileService.obtain().fetchAllAccounts();
        if (profiles == null || profiles.isEmpty()) {
            return;
        }

        for (AccountDescriptor profile : profiles) {
            TaskQueue queue = ScheduleService.obtain().getCoordinator().getQueue(profile.getId());
            if (queue != null) {
                queue.dequeueByKey(className);
                
                // Update task state for Task Manager UI
                TaskStateData taskState = TaskManagementService.shared().lookupTaskState(profile.getId(), TpDailyTaskEnum.CUSTOM_TASK.getId(), className);
                if (taskState != null) {
                    taskState.setScheduled(false);
                    taskState.setNextExecutionTime(null);
                    TaskManagementService.shared().recordTaskState(profile.getId(), taskState);
                }
            }
        }
        setStatus("⏹ Unscheduled " + className);
    }

    private boolean commitTimingSettings(String className, String firstExecutionUtcText, String followUpDelayHoursText) {
        String normalizedFirstExecutionUtc = normalizeFirstExecutionUtc(firstExecutionUtcText, className);
        Integer normalizedFollowUpDelayHours = parsePositiveHours(followUpDelayHoursText);
        if (normalizedFirstExecutionUtc == null || normalizedFollowUpDelayHours == null) {
            setStatus("❌ Invalid UTC date or follow-up delay for " + className);
            return false;
        }

        taskFirstExecutionUtc.put(className, normalizedFirstExecutionUtc);
        taskFollowUpDelayHours.put(className, normalizedFollowUpDelayHours);
        customTaskService.saveTaskSettings(className, className,
                taskOffsets.getOrDefault(className, 60),
                taskPriorities.getOrDefault(className, 0),
                normalizedFirstExecutionUtc,
                normalizedFollowUpDelayHours);

        if (Boolean.TRUE.equals(taskEnabled.get(className))) {
            return scheduleTask(className);
        }
        return true;
    }

    private void persistCurrentSettings(String className) {
        customTaskService.saveTaskSettings(className, className,
                taskOffsets.getOrDefault(className, 60),
                taskPriorities.getOrDefault(className, 0),
                taskFirstExecutionUtc.getOrDefault(className, getDefaultFirstExecutionUtc(className)),
                taskFollowUpDelayHours.getOrDefault(className, DEFAULT_FOLLOW_UP_DELAY_HOURS));
    }

    private String normalizeFirstExecutionUtc(String value, String className) {
        String candidate = value != null && !value.trim().isEmpty() ? value.trim() : getDefaultFirstExecutionUtc(className);
        try {
            return UTC_INPUT_FORMATTER.format(LocalDateTime.parse(candidate, UTC_INPUT_FORMATTER));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private Integer parsePositiveHours(String value) {
        String candidate = value != null && !value.trim().isEmpty() ? value.trim() : String.valueOf(DEFAULT_FOLLOW_UP_DELAY_HOURS);
        try {
            int hours = Integer.parseInt(candidate);
            return hours > 0 ? hours : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isValidUtcDateTime(String value) {
        return normalizeFirstExecutionUtc(value, "shield") != null;
    }

    private String getDefaultFirstExecutionUtc(String className) {
        return "shield".equals(className) ? DEFAULT_SHIELD_FIRST_EXECUTION_UTC : "";
    }

    private String getInitialExecutionUtcValue(CustomTaskService.SavedTaskEntry entry) {
        String savedValue = entry.getFirstExecutionUtc();
        return savedValue != null && !savedValue.isBlank() ? savedValue : getDefaultFirstExecutionUtc(entry.getClassName());
    }

    private int getFollowUpDelayValue(CustomTaskService.SavedTaskEntry entry) {
        Integer savedValue = entry.getFollowUpDelayHours();
        return savedValue != null && savedValue > 0 ? savedValue : DEFAULT_FOLLOW_UP_DELAY_HOURS;
    }

    private void updateEmptyState() {
        boolean hasCards = !taskCards.isEmpty();
        if (emptyState != null) {
            emptyState.setVisible(!hasCards);
            emptyState.setManaged(!hasCards);
        }
    }

    private void setStatus(String message) {
        if (statusLabel != null) {
            statusLabel.setText(message);
        }
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
