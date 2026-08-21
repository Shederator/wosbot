package dev.frostguard.app.panel.notification;

import dev.frostguard.api.domain.ActionRequiredIncidentData;
import dev.frostguard.api.domain.ActionRequiredIncidentState;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.service.ActionRequiredIncidentService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class NotificationCenterController {

    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @FXML
    private Button buttonClose;
    @FXML
    private ComboBox<NotificationFilter> filterIncidents;
    @FXML
    private VBox incidentCards;
    @FXML
    private Label labelEmpty;
    @FXML
    private Label labelActionStatus;

    private final ActionRequiredIncidentService incidentService = ActionRequiredIncidentService.obtain();
    private final Consumer<List<ActionRequiredIncidentData>> incidentListener = this::onIncidentSnapshot;
    private Runnable closeAction = () -> { };
    private List<ActionRequiredIncidentData> incidents = List.of();

    @FXML
    private void initialize() {
        filterIncidents.setItems(FXCollections.observableArrayList(NotificationFilter.values()));
        filterIncidents.setValue(NotificationFilter.ACTION_REQUIRED);
        incidentService.registerListener(incidentListener);
        refresh(incidentService.findAll());
    }

    public void setCloseAction(Runnable closeAction) {
        this.closeAction = closeAction == null ? () -> { } : closeAction;
    }

    public void refreshFromStore() {
        refresh(incidentService.findAll());
    }

    @FXML
    private void handleClose(ActionEvent event) {
        closeAction.run();
    }

    @FXML
    private void handleFilterChanged(ActionEvent event) {
        renderCards();
    }

    private void onIncidentSnapshot(List<ActionRequiredIncidentData> snapshot) {
        if (Platform.isFxApplicationThread()) {
            refresh(snapshot);
        } else {
            Platform.runLater(() -> refresh(snapshot));
        }
    }

    private void refresh(List<ActionRequiredIncidentData> snapshot) {
        incidents = List.copyOf(snapshot);
        renderCards();
    }

    private void renderCards() {
        NotificationFilter filter = filterIncidents.getValue() == null
                ? NotificationFilter.ACTION_REQUIRED
                : filterIncidents.getValue();
        List<ActionRequiredIncidentData> visible = visibleIncidents(incidents, filter);
        incidentCards.getChildren().setAll(visible.stream().map(this::incidentCard).toList());
        labelEmpty.setVisible(visible.isEmpty());
        labelEmpty.setManaged(visible.isEmpty());
    }

    private VBox incidentCard(ActionRequiredIncidentData incident) {
        VBox card = new VBox(10);
        card.getStyleClass().add("notification-card");
        if (incident.state() == ActionRequiredIncidentState.RECOVERED) {
            card.getStyleClass().add("recovered");
        }

        Label state = new Label(stateLabel(incident));
        state.getStyleClass().add(incident.state() == ActionRequiredIncidentState.ACTIVE
                ? "notification-state-active" : "notification-state-recovered");
        Label title = wrappingLabel(incident.title(), "notification-card-title");
        HBox header = new HBox(8, state, spacer(), occurrenceLabel(incident));
        header.setFillHeight(true);

        Label context = wrappingLabel(
                incident.profileName() + "  ·  " + incident.taskName(), "notification-card-context");
        Label cause = wrappingLabel(incident.cause(), "notification-card-cause");
        GridPane times = timestampGrid(incident);
        Label diagnostics = wrappingLabel(diagnosticPreview(incident), "notification-diagnostics-preview");

        Button copy = new Button("Copy diagnostics");
        copy.getStyleClass().add("notification-copy-button");
        copy.setMaxWidth(Double.MAX_VALUE);
        copy.setOnAction(event -> copyDiagnostics(incident));

        Button logs = new Button("Open logs");
        logs.getStyleClass().add("notification-secondary-button");
        logs.setMaxWidth(Double.MAX_VALUE);
        logs.setOnAction(event -> openLogs(incident));

        VBox buttons = new VBox(8, copy, logs);
        if (incident.isUnread()) {
            Button acknowledge = new Button("Acknowledge");
            acknowledge.getStyleClass().add("notification-acknowledge-button");
            acknowledge.setMaxWidth(Double.MAX_VALUE);
            acknowledge.setOnAction(event -> incidentService.acknowledge(incident.id()));
            buttons.getChildren().add(acknowledge);
        }

        card.getChildren().addAll(header, title, context, cause, times, diagnostics, buttons);
        return card;
    }

    private static Label occurrenceLabel(ActionRequiredIncidentData incident) {
        Label occurrence = new Label(incident.occurrenceCount() + (incident.occurrenceCount() == 1
                ? " occurrence" : " occurrences"));
        occurrence.getStyleClass().add("notification-occurrences");
        return occurrence;
    }

    private static GridPane timestampGrid(ActionRequiredIncidentData incident) {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(5);
        addTimestampRow(grid, 0, "First seen", incident.firstSeenAt());
        addTimestampRow(grid, 1, "Last seen", incident.lastSeenAt());
        addTimestampRow(grid, 2, "Retry", incident.retryAt());
        if (incident.recoveredAt() != null) {
            addTimestampRow(grid, 3, "Recovered", incident.recoveredAt());
        }
        return grid;
    }

    private static void addTimestampRow(GridPane grid, int row, String key, LocalDateTime value) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("notification-time-key");
        Label valueLabel = new Label(formatTime(value));
        valueLabel.getStyleClass().add("notification-time-value");
        GridPane.setHalignment(valueLabel, HPos.RIGHT);
        GridPane.setHgrow(valueLabel, Priority.ALWAYS);
        grid.addRow(row, keyLabel, valueLabel);
    }

    private static String diagnosticPreview(ActionRequiredIncidentData incident) {
        return "Expected: " + displayValue(incident.expectedState())
                + "\nObserved: " + displayValue(incident.observedState())
                + "\nLast action: " + displayValue(incident.lastAction())
                + "\nRetry/fallback: " + displayValue(incident.retryOrFallback())
                + "\nResources: " + displayValue(incident.resourceOutcome());
    }

    private void copyDiagnostics(ActionRequiredIncidentData incident) {
        ClipboardContent content = new ClipboardContent();
        content.putString(ActionRequiredIncidentService.formatDiagnostics(incident));
        Clipboard.getSystemClipboard().setContent(content);
        showActionStatus("Diagnostics copied to clipboard.");
    }

    private void openLogs(ActionRequiredIncidentData incident) {
        try {
            Path target = resolveLogTarget(WorkspacePaths.current(), incident);
            Files.createDirectories(WorkspacePaths.current().logs());
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("Desktop integration is unavailable");
            }
            Desktop.getDesktop().open(target.toFile());
            showActionStatus("Opened " + target.getFileName() + ".");
        } catch (IOException exception) {
            showActionStatus("Could not open logs: " + exception.getMessage());
        }
    }

    static Path resolveLogTarget(WorkspacePaths workspace, ActionRequiredIncidentData incident) throws IOException {
        Path expected = workspace.accountLog(incident.profileName(), incident.profileId());
        if (Files.isRegularFile(expected)) {
            return expected;
        }
        if (Files.isDirectory(workspace.logs())) {
            String suffix = "_" + incident.profileId() + ".log";
            try (Stream<Path> files = Files.list(workspace.logs())) {
                Path renamedProfileLog = files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().startsWith("account_"))
                        .filter(path -> path.getFileName().toString().endsWith(suffix))
                        .findFirst()
                        .orElse(null);
                if (renamedProfileLog != null) {
                    return renamedProfileLog;
                }
            }
        }
        return workspace.logs();
    }

    static List<ActionRequiredIncidentData> visibleIncidents(
            List<ActionRequiredIncidentData> incidents, NotificationFilter filter) {
        return incidents.stream().filter(filter::includes).toList();
    }

    private static String stateLabel(ActionRequiredIncidentData incident) {
        if (incident.state() == ActionRequiredIncidentState.RECOVERED) {
            return "RECOVERED";
        }
        return incident.isUnread() ? "ACTION REQUIRED" : "ACKNOWLEDGED";
    }

    private void showActionStatus(String message) {
        labelActionStatus.setText(message);
        labelActionStatus.setVisible(true);
        labelActionStatus.setManaged(true);
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private static Label wrappingLabel(String text, String styleClass) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static String displayValue(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String formatTime(LocalDateTime value) {
        return value == null ? "-" : value.format(DISPLAY_TIME);
    }

    enum NotificationFilter {
        ACTION_REQUIRED("Action required") {
            @Override
            boolean includes(ActionRequiredIncidentData incident) {
                return incident.isUnread();
            }
        },
        ACKNOWLEDGED("Acknowledged") {
            @Override
            boolean includes(ActionRequiredIncidentData incident) {
                return incident.state() == ActionRequiredIncidentState.ACTIVE
                        && incident.acknowledgedAt() != null;
            }
        },
        RECOVERED("Recovered") {
            @Override
            boolean includes(ActionRequiredIncidentData incident) {
                return incident.state() == ActionRequiredIncidentState.RECOVERED;
            }
        },
        ALL("All notifications") {
            @Override
            boolean includes(ActionRequiredIncidentData incident) {
                return true;
            }
        };

        private final String label;

        NotificationFilter(String label) {
            this.label = label;
        }

        abstract boolean includes(ActionRequiredIncidentData incident);

        @Override
        public String toString() {
            return label;
        }
    }
}
