package dev.frostguard.app.panel.misc;

import java.util.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.api.domain.ProfilesData;
import dev.frostguard.api.domain.JobMetrics;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class StatisticsLayoutController extends AbstractProfileController {

    // ========================================================================
    // COUNTER CATEGORY MAPPING
    // ========================================================================

    private static final Map<String, String> COUNTER_CATEGORIES = new LinkedHashMap<>();
    static {
        // Combat
        COUNTER_CATEGORIES.put("Arena Battles Won", "Combat");
        COUNTER_CATEGORIES.put("Arena Battles Lost", "Combat");
        COUNTER_CATEGORIES.put("Arena Gems Spent", "Combat");
        COUNTER_CATEGORIES.put("Arena Refreshes", "Combat");
        COUNTER_CATEGORIES.put("Exploration Fights Won", "Combat");
        COUNTER_CATEGORIES.put("Exploration Fights Lost", "Combat");
        // Intel & Exploration
        COUNTER_CATEGORIES.put("Intel Beast", "Intel & Exploration");
        COUNTER_CATEGORIES.put("Intel Survivor Camps", "Intel & Exploration");
        COUNTER_CATEGORIES.put("Intel Journeys", "Intel & Exploration");
        COUNTER_CATEGORIES.put("Beast Attacks Sent", "Intel & Exploration");
        // Economy
        COUNTER_CATEGORIES.put("Mystery Shop Purchases", "Economy");
        COUNTER_CATEGORIES.put("Mystery Shop Free Claims", "Economy");
        COUNTER_CATEGORIES.put("Daily Refreshes Used", "Economy");
        COUNTER_CATEGORIES.put("Alliance Shop Purchases", "Economy");
        COUNTER_CATEGORIES.put("Gather Marches Deployed", "Economy");
        COUNTER_CATEGORIES.put("Nomadic Merchant Free Resources Claimed", "Economy");
        COUNTER_CATEGORIES.put("Nomadic Merchant VIP Points Purchased", "Economy");
        COUNTER_CATEGORIES.put("Nomadic Merchant Daily Refresh Used", "Economy");

        // Training & Research
        COUNTER_CATEGORIES.put("Training Batches Started", "Training & Research");
        COUNTER_CATEGORIES.put("Research Started", "Training & Research");
        // Utility
        COUNTER_CATEGORIES.put("Mail Rewards Claimed", "Utility");
        COUNTER_CATEGORIES.put("Daily Missions Claimed", "Utility");
    }

    // Ordered list of categories for display
    private static final List<String> CATEGORY_ORDER = List.of(
            "Combat", "Intel & Exploration", "Economy", "Training & Research", "Utility", "Other");

    // ========================================================================
    // FXML FIELDS
    // ========================================================================

    @FXML private Button btnRefresh;
    @FXML private Button btnReset;
    @FXML private HBox hboxSummaryCards;
    @FXML private TableView<JobMetrics> tableTasks;
    @FXML private TableColumn<JobMetrics, String> colTaskName;
    @FXML private TableColumn<JobMetrics, Number> colRuns;
    @FXML private TableColumn<JobMetrics, String> colAvgTime;
    @FXML private TableColumn<JobMetrics, String> colTotalTime;
    @FXML private TableColumn<JobMetrics, String> colLastRun;
    @FXML private TableColumn<JobMetrics, String> colAvgOcr;
    @FXML private TableColumn<JobMetrics, String> colAvgImg;
    @FXML private VBox vboxCounterSections;
    @FXML private Label lblNoData;

    private ProfileAux currentProfile;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ========================================================================
    // INITIALIZATION
    // ========================================================================

    @FXML
    private void initialize() {
        colTaskName.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getTaskName()));
        colRuns.setCellValueFactory(cellData -> new SimpleIntegerProperty(cellData.getValue().getNumberOfRuns()));
        colAvgTime.setCellValueFactory(cellData -> new SimpleStringProperty(String.format("%.2f", cellData.getValue().getAverageExecutionTimeMs() / 1000.0)));
        colTotalTime.setCellValueFactory(cellData -> new SimpleStringProperty(String.format("%.2f", cellData.getValue().getTotalExecutionTimeMs() / 1000.0)));
        colAvgOcr.setCellValueFactory(cellData -> new SimpleStringProperty(String.format("%.2f", cellData.getValue().getAverageOcrFailures())));
        colAvgImg.setCellValueFactory(cellData -> new SimpleStringProperty(String.format("%.2f", cellData.getValue().getAverageTemplateFailures())));
        colLastRun.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getLastRunTime()));
    }

    // ========================================================================
    // PROFILE LIFECYCLE
    // ========================================================================

    @Override
    public void onProfileLoad(ProfileAux profile) {
        super.onProfileLoad(profile);
        this.currentProfile = profile;
        refreshStatisticsView();
    }

    @FXML
    private void handleRefresh(ActionEvent event) {
        if (currentProfile != null) {
            refreshStatisticsView();
        }
    }

    @FXML
    private void handleReset(ActionEvent event) {
        if (currentProfile == null) return;

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Reset Statistics");
        alert.setHeaderText("Reset all statistics for this profile?");
        alert.setContentText("This action cannot be undone.");

        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                if (profileObserver != null) {
                    profileObserver.notifyProfileChange(ConfigurationKeyEnum.STATISTICS_JSON_STRING, "{}");
                }
                Platform.runLater(this::refreshStatisticsView);
            }
        });
    }

    // ========================================================================
    // MAIN REFRESH
    // ========================================================================

    private void refreshStatisticsView() {
        if (currentProfile == null) return;

        String json = currentProfile.getConfiguration(ConfigurationKeyEnum.STATISTICS_JSON_STRING);
        ProfilesData stats = parseJsonToStats(json);

        hboxSummaryCards.getChildren().clear();
        tableTasks.getItems().clear();
        vboxCounterSections.getChildren().clear();

        if (stats.getTaskStatistics().isEmpty() && stats.getCustomCounters().isEmpty()) {
            lblNoData.setVisible(true);
            lblNoData.setManaged(true);
            tableTasks.setVisible(false);
            tableTasks.setManaged(false);
        } else {
            lblNoData.setVisible(false);
            lblNoData.setManaged(false);
            tableTasks.setVisible(true);
            tableTasks.setManaged(true);

            // Build summary cards
            buildSummaryCards(stats);

            // Populate task table
            ObservableList<JobMetrics> taskData = FXCollections.observableArrayList(stats.getTaskStatistics().values());
            tableTasks.setItems(taskData);

            // Build grouped counter sections
            buildGroupedCounters(stats.getCustomCounters());
        }
    }

    // ========================================================================
    // SUMMARY CARDS
    // ========================================================================

    private void buildSummaryCards(ProfilesData stats) {
        int totalRuns = 0;
        long totalTimeMs = 0;
        long totalOcrFails = 0;
        long totalImgFails = 0;

        for (JobMetrics task : stats.getTaskStatistics().values()) {
            totalRuns += task.getNumberOfRuns();
            totalTimeMs += task.getTotalExecutionTimeMs();
            totalOcrFails += task.getTotalOcrFailures();
            totalImgFails += task.getTotalTemplateSearchFailures();
        }

        String totalTimeStr;
        double totalHours = totalTimeMs / 3_600_000.0;
        if (totalHours >= 1.0) {
            totalTimeStr = String.format("%.1fh", totalHours);
        } else {
            totalTimeStr = String.format("%.0fm", totalTimeMs / 60_000.0);
        }

        String avgOcr = totalRuns > 0 ? String.format("%.2f", (double) totalOcrFails / totalRuns) : "0";
        String avgImg = totalRuns > 0 ? String.format("%.2f", (double) totalImgFails / totalRuns) : "0";
        int counterTotal = stats.getCustomCounters().values().stream().mapToInt(Integer::intValue).sum();

        hboxSummaryCards.getChildren().addAll(
                createSummaryCard("Total Runs", String.valueOf(totalRuns), "#4fc3f7"),
                createSummaryCard("Total Time", totalTimeStr, "#81c784"),
                createSummaryCard("Avg OCR Fail", avgOcr, "#ffb74d"),
                createSummaryCard("Avg Img Fail", avgImg, "#ff8a65"),
                createSummaryCard("Actions", String.valueOf(counterTotal), "#ba68c8")
        );
    }

    private VBox createSummaryCard(String title, String value, String accentColor) {
        VBox card = new VBox(4);
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("stat-summary-card");
        HBox.setHgrow(card, Priority.ALWAYS);

        Label lblTitle = new Label(title.toUpperCase());
        lblTitle.getStyleClass().add("stat-title");

        Label lblValue = new Label(value);
        lblValue.getStyleClass().add("stat-value");
        lblValue.setStyle("-fx-text-fill: " + accentColor + ";");

        card.getChildren().addAll(lblTitle, lblValue);

        return card;
    }

    // ========================================================================
    // GROUPED COUNTER SECTIONS
    // ========================================================================

    private void buildGroupedCounters(Map<String, Integer> customCounters) {
        if (customCounters.isEmpty()) return;

        // Group counters by category
        Map<String, Map<String, Integer>> grouped = new LinkedHashMap<>();
        for (String cat : CATEGORY_ORDER) {
            grouped.put(cat, new LinkedHashMap<>());
        }

        for (Map.Entry<String, Integer> entry : customCounters.entrySet()) {
            String category = COUNTER_CATEGORIES.getOrDefault(entry.getKey(), "Other");
            grouped.computeIfAbsent(category, k -> new LinkedHashMap<>()).put(entry.getKey(), entry.getValue());
        }

        // Build TitledPanes per category
        for (String category : CATEGORY_ORDER) {
            Map<String, Integer> counters = grouped.get(category);
            if (counters == null || counters.isEmpty()) continue;

            FlowPane flowPane = new FlowPane();
            flowPane.setHgap(12);
            flowPane.setVgap(12);
            flowPane.setPadding(new Insets(8));

            for (Map.Entry<String, Integer> entry : counters.entrySet()) {
                flowPane.getChildren().add(createCounterCard(entry.getKey(), entry.getValue()));
            }

            TitledPane titledPane = new TitledPane(category + " (" + counters.size() + ")", flowPane);
            titledPane.setExpanded(true);
            titledPane.setCollapsible(true);
            titledPane.getStyleClass().add("stat-titled-pane");

            vboxCounterSections.getChildren().add(titledPane);
        }
    }

    private VBox createCounterCard(String name, Integer value) {
        VBox card = new VBox();
        card.setSpacing(6);
        card.getStyleClass().add("stat-counter-card");
        card.setAlignment(Pos.CENTER);

        Label lblName = new Label(name);
        lblName.getStyleClass().add("counter-name");
        lblName.setWrapText(true);
        lblName.setAlignment(Pos.CENTER);

        Label lblValue = new Label(String.valueOf(value));
        lblValue.getStyleClass().add("counter-value");

        card.getChildren().addAll(lblName, lblValue);

        return card;
    }

    // ========================================================================
    // JSON PARSING
    // ========================================================================

    private ProfilesData parseJsonToStats(String json) {
        if (json == null || json.trim().isEmpty() || json.equals("{}")) {
            return new ProfilesData();
        }
        try {
            return objectMapper.readValue(json, ProfilesData.class);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            return new ProfilesData();
        }
    }
}
