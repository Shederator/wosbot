package dev.frostguard.app.panel.analytics;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AllianceRankingEntryData;
import dev.frostguard.api.domain.LabyrinthRankingEntryData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.app.panel.profile.IProfileLoadListener;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.engine.emulator.EmulatorType;
import dev.frostguard.engine.ranking.GameAnalyticsRunRegistry;
import dev.frostguard.engine.ranking.capture.AllianceRankingCaptureSupport;
import dev.frostguard.engine.ranking.capture.GameAnalyticsCollectionType;
import dev.frostguard.engine.ranking.export.GameAnalyticsExportService;
import dev.frostguard.engine.ranking.history.GameAnalyticsHistoryService;
import dev.frostguard.engine.ranking.history.GameAnalyticsSnapshot;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.ScheduleService;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyLongWrapper;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public final class GameAnalyticsLayoutController implements IProfileLoadListener {

    private final GameAnalyticsHistoryService history = new GameAnalyticsHistoryService();
    private final GameAnalyticsExportService exporter = new GameAnalyticsExportService();
    private ProfileAux currentProfile;

    @FXML private Label labelSupport;
    @FXML private Label labelPowerStatus;
    @FXML private Label labelLabyrinthStatus;
    @FXML private Button buttonPower;
    @FXML private Button buttonLabyrinth;
    @FXML private ComboBox<GameAnalyticsSnapshot> comboPowerHistory;
    @FXML private ComboBox<GameAnalyticsSnapshot> comboLabyrinthHistory;
    @FXML private Button buttonPowerExportJson;
    @FXML private Button buttonPowerExportCsv;
    @FXML private Button buttonLabyrinthExportJson;
    @FXML private Button buttonLabyrinthExportCsv;
    @FXML private TableView<AllianceRankingEntryData> tableRankings;
    @FXML private TableColumn<AllianceRankingEntryData, Number> columnRank;
    @FXML private TableColumn<AllianceRankingEntryData, String> columnPlayer;
    @FXML private TableColumn<AllianceRankingEntryData, Number> columnPlayerId;
    @FXML private TableColumn<AllianceRankingEntryData, Number> columnPower;
    @FXML private TableColumn<AllianceRankingEntryData, String> columnPowerSource;
    @FXML private TableColumn<AllianceRankingEntryData, String> columnPowerNameSource;
    @FXML private TableView<LabyrinthRankingEntryData> tableLabyrinth;
    @FXML private TableColumn<LabyrinthRankingEntryData, Number> columnLabRank;
    @FXML private TableColumn<LabyrinthRankingEntryData, String> columnLabPlayer;
    @FXML private TableColumn<LabyrinthRankingEntryData, Number> columnLabPlayerId;
    @FXML private TableColumn<LabyrinthRankingEntryData, Number> columnLabScore;
    @FXML private TableColumn<LabyrinthRankingEntryData, String> columnLabNameSource;

    @FXML
    private void initialize() {
        columnRank.setCellValueFactory(row -> new ReadOnlyIntegerWrapper(row.getValue().rank()));
        columnPlayer.setCellValueFactory(row -> new ReadOnlyStringWrapper(row.getValue().playerName()));
        columnPlayerId.setCellValueFactory(row -> new ReadOnlyLongWrapper(row.getValue().playerId()));
        columnPower.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(row.getValue().value()));
        columnPowerSource.setCellValueFactory(row -> new ReadOnlyStringWrapper(powerSource(row.getValue())));
        columnPowerNameSource.setCellValueFactory(row -> new ReadOnlyStringWrapper(nameSource(
                row.getValue().playerName(), row.getValue().playerNameFromCache())));
        columnLabRank.setCellValueFactory(row -> new ReadOnlyIntegerWrapper(row.getValue().rank()));
        columnLabPlayer.setCellValueFactory(row -> new ReadOnlyStringWrapper(row.getValue().playerName()));
        columnLabPlayerId.setCellValueFactory(row -> new ReadOnlyLongWrapper(row.getValue().playerId()));
        columnLabScore.setCellValueFactory(row -> new ReadOnlyLongWrapper(row.getValue().score()));
        columnLabNameSource.setCellValueFactory(row -> new ReadOnlyStringWrapper(nameSource(
                row.getValue().playerName(), row.getValue().playerNameFromCache())));
        comboPowerHistory.valueProperty().addListener((observable, previous, selected) -> showPower(selected));
        comboLabyrinthHistory.valueProperty().addListener(
                (observable, previous, selected) -> showLabyrinth(selected));

        AllianceRankingCaptureSupport support = currentSupport();
        labelSupport.setText(support.message());
        labelPowerStatus.setText(initialStatus(support));
        labelLabyrinthStatus.setText(initialStatus(support));
        updateCollectionAvailability();
        updateExportButtons();
        GameAnalyticsRunRegistry.addListener(this::onAnalyticsEvent);
        ScheduleService.obtain().addEngineObserver(
                state -> Platform.runLater(this::updateCollectionAvailability));
    }

    @Override
    public void onProfileLoad(ProfileAux profile) {
        boolean changedProfile = currentProfile == null || profile == null
                || !currentProfile.getId().equals(profile.getId());
        currentProfile = profile;
        if (!changedProfile) return;
        if (profile == null) {
            comboPowerHistory.getItems().clear();
            comboLabyrinthHistory.getItems().clear();
            updateCollectionAvailability();
            updateExportButtons();
            return;
        }
        loadHistory(profile.getId());
        updateCollectionAvailability();
    }

    @FXML
    private void handleCollectPower() {
        collect(GameAnalyticsCollectionType.POWER);
    }

    @FXML
    private void handleCollectLabyrinth() {
        collect(GameAnalyticsCollectionType.LABYRINTH);
    }

    private void collect(GameAnalyticsCollectionType type) {
        Label status = statusFor(type);
        ProfileAux selected = currentProfile;
        if (selected == null) {
            status.setText("Select a profile first.");
            return;
        }
        TaskQueue queue = ScheduleService.obtain().getCoordinator().getQueue(selected.getId());
        if (queue == null || !queue.isActive()) {
            status.setText("Start the bot first. Analytics runs through the profile task queue.");
            return;
        }
        TpDailyTaskEnum task = type == GameAnalyticsCollectionType.POWER
                ? TpDailyTaskEnum.GAME_ANALYTICS_POWER
                : TpDailyTaskEnum.GAME_ANALYTICS_LABYRINTH;
        queue.runNow(task, false);
        status.setText("Run queued for " + selected.getName() + ".");
    }

    private void onAnalyticsEvent(GameAnalyticsRunRegistry.Event event) {
        ProfileAux selected = currentProfile;
        if (selected == null || !selected.getId().equals(event.profileId())) return;
        Platform.runLater(() -> {
            if (event.state() == GameAnalyticsRunRegistry.State.RUNNING) {
                setBusy(event.type(), event.message());
            } else if (event.state() == GameAnalyticsRunRegistry.State.SUCCEEDED) {
                setCollectionButtonsEnabled();
                loadHistory(event.profileId());
                ComboBox<GameAnalyticsSnapshot> historyBox = historyFor(event.type());
                historyBox.getItems().stream()
                        .filter(snapshot -> snapshot.id().equals(event.snapshot().id()))
                        .findFirst().ifPresent(historyBox.getSelectionModel()::select);
                int entries = event.type() == GameAnalyticsCollectionType.POWER
                        ? event.result().power().size() : event.result().labyrinth().size();
                statusFor(event.type()).setText("Saved " + entries
                        + " entries. Use an export button when needed.");
            } else {
                setCollectionButtonsEnabled();
                statusFor(event.type()).setText("Run failed: " + event.message());
            }
        });
    }

    private void loadHistory(Long profileId) {
        try {
            var snapshots = history.list(WorkspacePaths.current().root(), profileId);
            var power = snapshots.stream().filter(snapshot ->
                    snapshot.type() == GameAnalyticsCollectionType.POWER).toList();
            var labyrinth = snapshots.stream().filter(snapshot ->
                    snapshot.type() == GameAnalyticsCollectionType.LABYRINTH).toList();
            comboPowerHistory.setItems(FXCollections.observableArrayList(power));
            comboLabyrinthHistory.setItems(FXCollections.observableArrayList(labyrinth));
            if (!power.isEmpty()) comboPowerHistory.getSelectionModel().selectFirst();
            if (!labyrinth.isEmpty()) comboLabyrinthHistory.getSelectionModel().selectFirst();
            updateExportButtons();
        } catch (IOException exception) {
            labelPowerStatus.setText("Could not load history: " + exception.getMessage());
            labelLabyrinthStatus.setText("Could not load history: " + exception.getMessage());
        }
    }

    private void showPower(GameAnalyticsSnapshot snapshot) {
        tableRankings.setItems(snapshot == null ? FXCollections.emptyObservableList()
                : FXCollections.observableArrayList(snapshot.power()));
        updateExportButtons();
    }

    private void showLabyrinth(GameAnalyticsSnapshot snapshot) {
        tableLabyrinth.setItems(snapshot == null ? FXCollections.emptyObservableList()
                : FXCollections.observableArrayList(snapshot.labyrinth()));
        updateExportButtons();
    }

    @FXML private void handleExportPowerJson() { exportSelected(GameAnalyticsCollectionType.POWER, "json"); }
    @FXML private void handleExportPowerCsv() { exportSelected(GameAnalyticsCollectionType.POWER, "csv"); }
    @FXML private void handleExportLabyrinthJson() { exportSelected(GameAnalyticsCollectionType.LABYRINTH, "json"); }
    @FXML private void handleExportLabyrinthCsv() { exportSelected(GameAnalyticsCollectionType.LABYRINTH, "csv"); }

    private void exportSelected(GameAnalyticsCollectionType type, String extension) {
        GameAnalyticsSnapshot snapshot = historyFor(type).getValue();
        if (snapshot == null) {
            statusFor(type).setText("Select a saved run first.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Game Data");
        chooser.setInitialFileName(type.name().toLowerCase() + "-" + snapshot.id() + "." + extension);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                extension.toUpperCase() + " files", "*." + extension));
        File target = chooser.showSaveDialog(buttonPower.getScene().getWindow());
        if (target == null) return;
        try {
            if ("json".equals(extension)) exporter.exportJson(target.toPath(), snapshot);
            else exporter.exportCsv(target.toPath(), snapshot);
            statusFor(type).setText("Exported to " + target.toPath().toAbsolutePath() + ".");
        } catch (IOException exception) {
            statusFor(type).setText("Export failed: " + exception.getMessage());
        }
    }

    private void setBusy(GameAnalyticsCollectionType type, String status) {
        buttonPower.setDisable(true);
        buttonLabyrinth.setDisable(true);
        statusFor(type).setText(status);
    }

    private void setCollectionButtonsEnabled() {
        updateCollectionAvailability();
    }

    private void updateCollectionAvailability() {
        AllianceRankingCaptureSupport support = currentSupport();
        boolean botActive = isSelectedProfileBotActive();
        boolean enabled = support.supported() && botActive;
        buttonPower.setDisable(!enabled);
        buttonLabyrinth.setDisable(!enabled);
        String buttonText = !support.supported() ? "MuMu Only"
                : botActive ? "New Run" : "Start Bot First";
        buttonPower.setText(buttonText);
        buttonLabyrinth.setText(buttonText);
        if (!support.supported()) {
            labelPowerStatus.setText("Traffic collection is unavailable for the selected emulator.");
            labelLabyrinthStatus.setText("Traffic collection is unavailable for the selected emulator.");
        } else if (!botActive) {
            labelPowerStatus.setText("Start the bot to enable a new run.");
            labelLabyrinthStatus.setText("Start the bot to enable a new run.");
        } else if (currentProfile != null) {
            labelPowerStatus.setText("Ready for " + currentProfile.getName() + ".");
            labelLabyrinthStatus.setText("Ready for " + currentProfile.getName() + ".");
        }
    }

    private boolean isSelectedProfileBotActive() {
        if (currentProfile == null || ScheduleService.obtain().getCoordinator() == null) return false;
        TaskQueue queue = ScheduleService.obtain().getCoordinator().getQueue(currentProfile.getId());
        return queue != null && queue.isActive();
    }

    private void updateExportButtons() {
        boolean noPower = comboPowerHistory.getValue() == null;
        boolean noLabyrinth = comboLabyrinthHistory.getValue() == null;
        buttonPowerExportJson.setDisable(noPower);
        buttonPowerExportCsv.setDisable(noPower);
        buttonLabyrinthExportJson.setDisable(noLabyrinth);
        buttonLabyrinthExportCsv.setDisable(noLabyrinth);
    }

    private Label statusFor(GameAnalyticsCollectionType type) {
        return type == GameAnalyticsCollectionType.POWER ? labelPowerStatus : labelLabyrinthStatus;
    }

    private ComboBox<GameAnalyticsSnapshot> historyFor(GameAnalyticsCollectionType type) {
        return type == GameAnalyticsCollectionType.POWER ? comboPowerHistory : comboLabyrinthHistory;
    }

    private String nameSource(String name, boolean fromCache) {
        if (name == null || name.isBlank()) return "Missing";
        return fromCache ? "Cache" : "Direct";
    }

    private String powerSource(AllianceRankingEntryData entry) {
        if (entry.value() == null) return "Missing";
        return entry.powerFromCache() ? "Cache" : "Direct";
    }

    private String initialStatus(AllianceRankingCaptureSupport support) {
        return support.supported() ? "Start the bot, then select New Run."
                : "Traffic collection is disabled for the selected emulator.";
    }

    private AllianceRankingCaptureSupport currentSupport() {
        return AllianceRankingCaptureSupport.evaluate(System.getProperty("os.name"), currentEmulatorType());
    }

    private EmulatorType currentEmulatorType() {
        try {
            Map<String, String> settings = ConfigService.obtain().loadGlobalSettings();
            String configured = settings == null ? null
                    : settings.get(ConfigurationKeyEnum.CURRENT_EMULATOR_STRING.name());
            return configured == null || configured.isBlank() ? null : EmulatorType.valueOf(configured);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
