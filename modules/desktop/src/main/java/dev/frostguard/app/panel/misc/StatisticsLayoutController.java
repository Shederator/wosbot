package dev.frostguard.app.panel.misc;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignG;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
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
        COUNTER_CATEGORIES.put("Storehouse Chests Opened", "Economy");
        COUNTER_CATEGORIES.put("Alliance Gifts Collected", "Economy");
        COUNTER_CATEGORIES.put("Pet Adventure Chests", "Economy");
        COUNTER_CATEGORIES.put("Alliance Triumph Rewards", "Economy");

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
    @FXML private ComboBox<Integer> cmbTelemetryInterval;
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

    // "What the bot did for you" section
    @FXML private FlowPane flowEarnings;
    @FXML private FlowPane flowActivity;
    @FXML private Label lblWindow;
    @FXML private Label lblEarningsEmpty;
    @FXML private ComboBox<String> comboReportWindow;

    /** Display names + accent colours for the six telemetry metrics. */
    private static final Map<String, String> METRIC_LABELS = new LinkedHashMap<>();
    private static final Map<String, String> METRIC_COLORS = new LinkedHashMap<>();
    static {
        METRIC_LABELS.put("power", "Power");   METRIC_COLORS.put("power", "#4fc3f7");
        METRIC_LABELS.put("gems", "Gems");     METRIC_COLORS.put("gems", "#ba68c8");
        METRIC_LABELS.put("meat", "Meat");     METRIC_COLORS.put("meat", "#ff8a65");
        METRIC_LABELS.put("wood", "Wood");     METRIC_COLORS.put("wood", "#81c784");
        METRIC_LABELS.put("coal", "Coal");     METRIC_COLORS.put("coal", "#90a4ae");
        METRIC_LABELS.put("iron", "Iron");     METRIC_COLORS.put("iron", "#ffb74d");
        METRIC_LABELS.put("steel", "Steel");   METRIC_COLORS.put("steel", "#b0bec5");
        // Speedup durations (minutes) — formatted as "6d 3h" for display.
        METRIC_LABELS.put("sp_general", "Gen. speedup");        METRIC_COLORS.put("sp_general", "#4dd0e1");
        METRIC_LABELS.put("sp_training", "Training spd");       METRIC_COLORS.put("sp_training", "#7986cb");
        METRIC_LABELS.put("sp_construction", "Construction spd"); METRIC_COLORS.put("sp_construction", "#a1887f");
        METRIC_LABELS.put("sp_research", "Research spd");       METRIC_COLORS.put("sp_research", "#4db6ac");
        METRIC_LABELS.put("sp_healing", "Healing spd");         METRIC_COLORS.put("sp_healing", "#e57373");
    }

    /**
     * Icon key per resource metric. Drop <key>.png into <cwd>/stat-icons/ to set any of these.
     */
    private static final Map<String, String> METRIC_ICONS = new LinkedHashMap<>();
    static {
        METRIC_ICONS.put("power", "power");
        METRIC_ICONS.put("gems", "gems");
        METRIC_ICONS.put("meat", "meat");
        METRIC_ICONS.put("wood", "wood");
        METRIC_ICONS.put("coal", "coal");
        METRIC_ICONS.put("iron", "iron");
        METRIC_ICONS.put("steel", "steel");
        METRIC_ICONS.put("sp_general", "sp_general");
        METRIC_ICONS.put("sp_training", "sp_training");
        METRIC_ICONS.put("sp_construction", "sp_construction");
        METRIC_ICONS.put("sp_research", "sp_research");
        METRIC_ICONS.put("sp_healing", "sp_healing");
    }
    /** Icon key per activity tile. Drop <key>.png into <cwd>/stat-icons/ to set any of these. */
    private static final Map<String, String> ACTIVITY_ICONS = new LinkedHashMap<>();
    static {
        ACTIVITY_ICONS.put("Beasts hunted", "beasts");
        ACTIVITY_ICONS.put("Journeys scouted", "journeys");
        ACTIVITY_ICONS.put("Survivor camps", "survivor_camps");
        ACTIVITY_ICONS.put("Gather marches", "gather");
        ACTIVITY_ICONS.put("Daily missions", "daily_missions");
        ACTIVITY_ICONS.put("Growth missions", "growth_missions");
        ACTIVITY_ICONS.put("Mail rewards", "mail");
        ACTIVITY_ICONS.put("Exploration wins", "exploration_win");
        ACTIVITY_ICONS.put("Arena wins", "arena_win");
        ACTIVITY_ICONS.put("Beast attacks", "beast_attacks");
        ACTIVITY_ICONS.put("Storehouse chests", "storehouse");
        ACTIVITY_ICONS.put("Alliance chests", "alliance_chest");
        ACTIVITY_ICONS.put("Pet chests", "pet");
        ACTIVITY_ICONS.put("Triumph rewards", "triumph");
    }

    /** Clean flat glyphs per icon key — the default look. Overridable by a dropped-in PNG. */
    private static final Map<String, Ikon> GLYPHS = new LinkedHashMap<>();
    static {
        GLYPHS.put("power", MaterialDesignA.ARM_FLEX);
        GLYPHS.put("gems", MaterialDesignD.DIAMOND_STONE);
        GLYPHS.put("meat", MaterialDesignF.FOOD_STEAK);
        GLYPHS.put("wood", MaterialDesignP.PINE_TREE);
        GLYPHS.put("coal", MaterialDesignG.GRAIN);
        GLYPHS.put("iron", MaterialDesignA.ANVIL);
        // Steel → steel-mill; speedups → fast-forward / clock / timer family.
        GLYPHS.put("steel", MaterialDesignF.FACTORY);
        GLYPHS.put("sp_general", MaterialDesignF.FAST_FORWARD);
        GLYPHS.put("sp_training", MaterialDesignC.CLOCK_FAST);
        GLYPHS.put("sp_construction", MaterialDesignT.TIMER_SAND);
        GLYPHS.put("sp_research", MaterialDesignF.FLASK);
        GLYPHS.put("sp_healing", MaterialDesignM.MEDICAL_BAG);
        GLYPHS.put("intel", MaterialDesignC.CHART_BAR);
        GLYPHS.put("beasts", MaterialDesignT.TARGET);
        GLYPHS.put("survivor_camps", MaterialDesignT.TENT);
        GLYPHS.put("journeys", MaterialDesignC.COMPASS_OUTLINE);
        GLYPHS.put("exploration_win", MaterialDesignT.TROPHY);
        GLYPHS.put("training", MaterialDesignS.SWORD_CROSS);
        GLYPHS.put("research", MaterialDesignF.FLASK);
        GLYPHS.put("gather", MaterialDesignA.AXE);
        GLYPHS.put("storehouse", MaterialDesignW.WAREHOUSE);
        GLYPHS.put("alliance_chest", MaterialDesignS.SHIELD);
        GLYPHS.put("pet", MaterialDesignP.PAW);
        GLYPHS.put("exploration", MaterialDesignC.COMPASS);
        GLYPHS.put("exploration_chest", MaterialDesignT.TREASURE_CHEST);
        GLYPHS.put("daily_missions", MaterialDesignC.CLIPBOARD_CHECK);
        GLYPHS.put("growth_missions", MaterialDesignT.TRENDING_UP);
        GLYPHS.put("mail", MaterialDesignE.EMAIL);
        GLYPHS.put("life_essence", MaterialDesignS.SPROUT);
        GLYPHS.put("labyrinth", MaterialDesignM.MAP_MARKER_PATH);
        GLYPHS.put("arena_win", MaterialDesignT.TROPHY_VARIANT);
        GLYPHS.put("beast_attacks", MaterialDesignS.SWORD);
        GLYPHS.put("triumph", MaterialDesignT.TROPHY_AWARD);
    }

    /**
     * The tile icon for a key: a user-supplied PNG at {@code <cwd>/stat-icons/<key>.png} if present
     * (drop one in and hit Refresh — no rebuild), otherwise a clean flat glyph. Returns a JavaFX
     * Node either way.
     */
    private static javafx.scene.Node iconNodeFor(String key, String accent) {
        if (key == null) return null;
        try {
            java.io.File ext = new java.io.File(System.getProperty("user.dir"), "stat-icons/" + key + ".png");
            if (ext.isFile()) {
                ImageView iv = new ImageView(new Image(ext.toURI().toString(), 72, 72, true, true));
                iv.setFitWidth(72); iv.setFitHeight(72); iv.setPreserveRatio(true);
                return iv;
            }
        } catch (Exception ignored) { }
        Ikon glyph = GLYPHS.get(key);
        if (glyph == null) return null;
        // Size via a CSS STYLE CLASS, not setIconSize(): ikonli's CSS pass overrides the
        // programmatic value, so every setIconSize() number rendered at the default. The app's
        // own nav icons size themselves through CSS (.nav-button .ikonli-font-icon) and render
        // real glyphs — so .stat-tile-glyph { -fx-icon-size } is the mechanism that actually takes.
        // Colour still works programmatically (it varies per tile, so it can't be a static class).
        FontIcon fi = new FontIcon(glyph);
        fi.getStyleClass().add("stat-tile-glyph");
        fi.setIconColor(javafx.scene.paint.Color.web(accent == null ? "#9eaab6" : accent));
        return fi;
    }

    /** The sleep window for the "last night" report (11pm-8:30am local) -- matches
     *  bg_telemetry's wake-snapshot anchor, which documents an observed real capture at
     *  08:30:43; keeping the two in sync matters, a drift between them excludes real data. */
    private static final LocalTime SLEEP_START = LocalTime.of(23, 0);
    private static final LocalTime WAKE_END = LocalTime.of(8, 30);

    // No profile is selected yet at field-init time, so there's nothing to load -- an ID no real
    // profile will ever have resolves to a not-yet-existing file, which TelemetryReport.load()
    // already treats as "empty" by design. refreshEarnings() replaces this the moment a profile
    // is actually selected.
    private static final long NO_PROFILE_SELECTED_ID = -1L;

    private ProfileAux currentProfile;
    private TelemetryReport telemetry = TelemetryReport.load(NO_PROFILE_SELECTED_ID);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** The "what the bot did" timeframe segments, driven by a dropdown rather than a button row.
     *  Past Hour and This Month are new; This Week is the same rolling-7-day math as Last 7 Days,
     *  just a friendlier label. A custom date-range picker is deliberately out of scope. */
    private enum ReportWindow { PAST_HOUR, NIGHT, LAST_24H, THIS_WEEK, THIS_MONTH, TOTAL }

    private static final Map<String, ReportWindow> WINDOW_LABELS = new LinkedHashMap<>();
    static {
        WINDOW_LABELS.put("Past hour", ReportWindow.PAST_HOUR);
        WINDOW_LABELS.put("Last night", ReportWindow.NIGHT);
        WINDOW_LABELS.put("Last 24 hours", ReportWindow.LAST_24H);
        WINDOW_LABELS.put("This week", ReportWindow.THIS_WEEK);
        WINDOW_LABELS.put("This month", ReportWindow.THIS_MONTH);
        WINDOW_LABELS.put("All time", ReportWindow.TOTAL);
    }

    /**
     * Which timeframe is currently shown. Defaults to All time so the page never opens blank, but
     * once the user picks a segment, Refresh must stay on it instead of snapping back to All time.
     */
    private ReportWindow activeWindow = ReportWindow.TOTAL;

    // ========================================================================
    // INITIALIZATION
    // ========================================================================

    @FXML
    private void initialize() {
        cmbTelemetryInterval.setItems(FXCollections.observableArrayList(1, 2, 3, 4, 6, 8, 12, 24));
        registerComboBox(cmbTelemetryInterval, ConfigurationKeyEnum.TELEMETRY_INTERVAL_HOURS_INT);
        initializeChangeEvents();
        colTaskName.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getTaskName()));
        colRuns.setCellValueFactory(cellData -> new SimpleIntegerProperty(cellData.getValue().getNumberOfRuns()));
        colAvgTime.setCellValueFactory(cellData -> new SimpleStringProperty(String.format("%.2f", cellData.getValue().getAverageExecutionTimeMs() / 1000.0)));
        colTotalTime.setCellValueFactory(cellData -> new SimpleStringProperty(String.format("%.2f", cellData.getValue().getTotalExecutionTimeMs() / 1000.0)));
        colAvgOcr.setCellValueFactory(cellData -> new SimpleStringProperty(String.format("%.2f", cellData.getValue().getAverageOcrFailures())));
        colAvgImg.setCellValueFactory(cellData -> new SimpleStringProperty(String.format("%.2f", cellData.getValue().getAverageTemplateFailures())));
        colLastRun.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getLastRunTime()));

        // Selecting a ComboBox value fires its valueProperty listener immediately, so wiring the
        // listener BEFORE this initial select would make showActiveWindow() run during
        // initialize() itself -- before the real profile/data exists, rendering a blank page.
        // Listener attaches AFTER the initial select so it stays silent here and only fires on an
        // actual user pick; showActiveWindow() only runs once onProfileLoad() has set
        // currentProfile and reloaded telemetry.
        comboReportWindow.getItems().setAll(WINDOW_LABELS.keySet());
        comboReportWindow.getSelectionModel().select("All time");
        comboReportWindow.valueProperty().addListener((obs, oldV, newV) -> {
            ReportWindow selected = WINDOW_LABELS.get(newV);
            if (selected != null) {
                activeWindow = selected;
                showActiveWindow();
            }
        });
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

        refreshEarnings();

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
        VBox card = new VBox(3);
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
    // EARNINGS (telemetry)
    // ========================================================================

    private void refreshEarnings() {
        // Loads by the profile's stable numeric ID rather than its name (mutable/non-unique),
        // from that profile's own workspace-local file -- one profile's telemetry can't satisfy
        // another's.
        long profileId = currentProfile != null && currentProfile.getId() != null
                ? currentProfile.getId() : NO_PROFILE_SELECTED_ID;
        telemetry = TelemetryReport.load(profileId);
        // Reload the data but stay on whatever timeframe the user last selected (defaults to All
        // time on first open). Refresh must never yank the view back to All time.
        showActiveWindow();
    }

    /** Renders the currently-selected timeframe and keeps the dropdown's shown value in sync. */
    private void showActiveWindow() {
        switch (activeWindow) {
            case PAST_HOUR -> {
                setActiveSegment("Past hour");
                showWindow("Past hour",
                        telemetry.last(1, ChronoUnit.HOURS), telemetry.activityLast(1, ChronoUnit.HOURS),
                        telemetry.coverageForLast(1, ChronoUnit.HOURS));
            }
            case NIGHT -> {
                setActiveSegment("Last night");
                showWindow("Last night (" + SLEEP_START + "–" + WAKE_END + ")",
                        telemetry.lastNight(ZoneId.systemDefault(), SLEEP_START, WAKE_END),
                        telemetry.activityLastNight(ZoneId.systemDefault(), SLEEP_START, WAKE_END),
                        telemetry.coverageForLastNight(ZoneId.systemDefault(), SLEEP_START, WAKE_END));
            }
            case LAST_24H -> {
                setActiveSegment("Last 24 hours");
                showWindow("Last 24 hours",
                        telemetry.last(24, ChronoUnit.HOURS), telemetry.activityLast(24, ChronoUnit.HOURS),
                        telemetry.coverageForLast(24, ChronoUnit.HOURS));
            }
            case THIS_WEEK -> {
                setActiveSegment("This week");
                showWindow("This week (last 7 days)",
                        telemetry.last(7, ChronoUnit.DAYS), telemetry.activityLast(7, ChronoUnit.DAYS),
                        telemetry.coverageForLast(7, ChronoUnit.DAYS));
            }
            case THIS_MONTH -> {
                setActiveSegment("This month");
                showWindow("This month (last 30 days)",
                        telemetry.last(30, ChronoUnit.DAYS), telemetry.activityLast(30, ChronoUnit.DAYS),
                        telemetry.coverageForLast(30, ChronoUnit.DAYS));
            }
            case TOTAL -> {
                setActiveSegment("All time");
                showWindow("All recorded time", telemetry.total(), telemetry.activityTotal(),
                        telemetry.coverageForTotal());
            }
        }
    }

    // A window label like "Last night (23:00-08:30)" is the INTENDED window, not proof of what
    // was actually captured -- this formats the REAL first/last sample timestamps a window's
    // numbers were built from, so a gapped or stale telemetry run is visible right at the top
    // instead of hidden behind a label that always looks the same.
    private static final DateTimeFormatter COVERAGE_FORMATTER = DateTimeFormatter.ofPattern("M/d h:mm a");

    /** Package-visible and static purely so it's directly testable without standing up a full
     *  FXML-loaded controller instance -- no instance state is actually used here. */
    static String formatCoverage(TelemetryReport.Coverage coverage) {
        if (coverage == null) {
            return null;
        }
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime from = ZonedDateTime.ofInstant(coverage.actualFrom(), zone);
        ZonedDateTime to = ZonedDateTime.ofInstant(coverage.actualTo(), zone);
        return "recorded " + from.format(COVERAGE_FORMATTER) + " → " + to.format(COVERAGE_FORMATTER);
    }

    /** Keeps the dropdown showing the active window without re-firing its change listener
     *  (e.g. after Refresh reloads data but the user's selection hasn't changed). */
    private void setActiveSegment(String label) {
        if (comboReportWindow != null && !label.equals(comboReportWindow.getValue())) {
            comboReportWindow.setValue(label);
        }
    }

    /** One earnings tile's computed content -- metric key kept alongside so {@link #applyViewState}
     *  can still look up its icon/label/color, without those lookups happening inside the (now
     *  testable) decision logic itself. */
    record EarningsCard(String metricKey, String value, String sub) {}

    /**
     * "a real FXML-loaded Statistics controller test covering timeframe
     * selection, refresh/profile changes, and the zero-change versus insufficient-data
     * presentation" was missing -- the decision logic lived entirely inside a method that also
     * mutated live FXML nodes, so testing it meant either standing up a real JavaFX scene graph
     * (needs a headless-JavaFX test harness this module has never set up, with real CI-fragility
     * risk) or not testing it at all.
     *
     * <p>Splits {@code showWindow}'s old body into this pure decision function -- everything about
     * WHAT to show, entirely computable from plain data, no JavaFX types involved -- and
     * {@link #applyViewState}, a thin wrapper that just paints the result onto FXML nodes.
     * {@code StatisticsLayoutControllerTest} now exercises the exact timeframe-selection /
     * zero-change / insufficient-data / metric-card logic the review asked for, directly, with no scene
     * graph needed. Behavior is unchanged from the original {@code showWindow} -- this is a pure
     * extraction, not a rewrite of what it decides.</p>
     */
    static ViewState computeViewState(String windowLabel, List<TelemetryReport.Delta> earned,
                                       List<TelemetryReport.Activity> did, TelemetryReport.Coverage coverage,
                                       TelemetryReport telemetry) {
        // Decide on whether anything actually CHANGED, not on whether the lists are empty.
        //
        // Emptiness stopped meaning "no change" once a genuinely unchanged metric began emitting a
        // zero-valued Delta instead of being omitted: the earned list is then non-empty, bothEmpty is
        // false, and the zero-change branch below could never be reached on real data. The report's
        // shape and the UI's reading of it had drifted apart, so "no measurable change" was
        // effectively dead code while real unchanged windows fell through to the normal branch.
        //
        // The same now holds for activity, which also emits measured zeros -- see
        // TelemetryReport.activityOverWindow. So: something happened if any earned delta or any
        // activity count is non-zero. Coverage still answers the separate question of whether the
        // window could be measured at all, which is what tells "nothing happened" apart from
        // "nothing is known yet".
        boolean anyEarnedChange = earned != null
                && earned.stream().anyMatch(d -> d != null && d.change() != 0);
        boolean anyActivity = did != null
                && did.stream().anyMatch(a -> a != null && a.change() != 0);
        boolean nothingChanged = !anyEarnedChange && !anyActivity;

        boolean insufficientData = nothingChanged && coverage == null;
        boolean zeroChange = nothingChanged && coverage != null;

        String coverageSuffix = formatCoverage(coverage);
        String windowLabelText;
        if (insufficientData) {
            windowLabelText = windowLabel + " — not enough samples yet to measure a change.";
        } else if (zeroChange) {
            windowLabelText = windowLabel + " — no measurable change in this window."
                    + (coverageSuffix != null ? "  (" + coverageSuffix + ")" : "");
        } else {
            windowLabelText = coverageSuffix != null ? windowLabel + "  (" + coverageSuffix + ")" : windowLabel;
        }

        // Always show every resource the bot captured (power, gems, meat, wood, coal, iron) —
        // never drop one just because its delta is zero or it has a single data point. Value is
        // the gain when we can measure it, otherwise the current amount.
        Map<String, TelemetryReport.Delta> byMetric = new HashMap<>();
        if (earned != null) {
            for (TelemetryReport.Delta d : earned) byMetric.put(d.metric(), d);
        }
        List<EarningsCard> earningsCards = new ArrayList<>();
        for (String metric : TelemetryReport.METRICS) {
            // latestValueOf() finds each metric's own most recent value independently, rather
            // than reading the single latest overall sample (telemetry.latest()) for
            // "current" -- a metric missing from just that one row would otherwise vanish
            // from its card entirely even with a perfectly good earlier value.
            Long current = latestValueOf(telemetry, metric);
            if (current == null) continue; // metric never captured
            TelemetryReport.Delta d = byMetric.get(metric);
            boolean changed = d != null && d.change() != 0;
            // Speedup metrics (sp_*) are DURATIONS in minutes, so they format as "6d 3h",
            // "+3h 12m" (gained) / "-1d" (spent) — not the M/K resource formatter.
            boolean isSpeedup = metric.startsWith("sp_");
            String value;
            String sub;
            if (isSpeedup) {
                value = changed ? fmtMinutesSigned(d.change()) : fmtMinutes(current);
                sub = changed ? fmtMinutes(d.start()) + " → " + fmtMinutes(d.end()) : "on hand now";
            } else {
                // Measured change → show the before→after range (headline already carries the +/- gain).
                // Single data point → the headline IS the current stockpile, so label it plainly.
                value = changed ? fmtSigned(d.change()) : fmt(current);
                sub = changed ? fmt(d.start()) + " → " + fmt(d.end()) : "on hand now";
            }
            earningsCards.add(new EarningsCard(metric, value, sub));
        }

        // "Filling in" must mean NOT MEASURABLE, not "nothing happened". An entry exists for every
        // counter that had a usable start/end pair, so an empty list is the real insufficient-data
        // case; a list of zeros is a measured quiet window and should not nag for more cycles.
        // Only the non-zero entries are worth a card, so the two are separated here.
        boolean activityMeasurable = did != null && !did.isEmpty();
        List<TelemetryReport.Activity> activityToShow = did == null ? List.of()
                : did.stream().filter(a -> a != null && a.change() != 0).toList();
        boolean activityFillingIn = !activityMeasurable;

        return new ViewState(insufficientData, zeroChange, windowLabelText, earningsCards,
                activityToShow, activityFillingIn);
    }

    /** Everything {@link #showWindow} used to decide, computed independently of any FXML node.
     *  Package-visible so it's directly testable without a live FXML-loaded controller instance. */
    record ViewState(boolean insufficientData, boolean zeroChange, String windowLabelText,
                      List<EarningsCard> earningsCards, List<TelemetryReport.Activity> activity,
                      boolean activityFillingIn) {}

    /** Rebuilds both the "earned" and "did" sections for the chosen window. Thin glue: computes
     *  the decision once via {@link #computeViewState}, then paints it onto FXML nodes. */
    private void showWindow(String windowLabel, List<TelemetryReport.Delta> earned,
                            List<TelemetryReport.Activity> did, TelemetryReport.Coverage coverage) {
        applyViewState(computeViewState(windowLabel, earned, did, coverage, telemetry));
    }

    private void applyViewState(ViewState vs) {
        if (lblEarningsEmpty != null) {
            lblEarningsEmpty.setVisible(vs.insufficientData());
            lblEarningsEmpty.setManaged(vs.insufficientData());
        }
        if (lblWindow != null) {
            lblWindow.setText(vs.windowLabelText());
        }

        if (flowEarnings != null) {
            flowEarnings.getChildren().clear();
            for (EarningsCard c : vs.earningsCards()) {
                flowEarnings.getChildren().add(createStatCard(
                        METRIC_ICONS.get(c.metricKey()),
                        METRIC_LABELS.getOrDefault(c.metricKey(), c.metricKey()),
                        c.value(), c.sub(),
                        METRIC_COLORS.getOrDefault(c.metricKey(), "#4fc3f7")));
            }
        }

        if (flowActivity != null) {
            flowActivity.getChildren().clear();
            if (!vs.activityFillingIn()) {
                for (TelemetryReport.Activity a : vs.activity()) {
                    flowActivity.getChildren().add(createStatCard(
                            ACTIVITY_ICONS.get(a.label()),
                            a.label(), String.valueOf(a.change()), "", "#81c784"));
                }
            } else {
                // Windowed views need two activity snapshots (~2h apart) before they can show a
                // delta. Say so instead of leaving a blank section that looks broken.
                Label hint = new Label("Filling in — this window needs a couple more sample cycles"
                        + " (the bot snapshots roughly every 2 hours). “All time” shows totals now.");
                hint.getStyleClass().add("label-muted");
                hint.setWrapText(true);
                flowActivity.getChildren().add(hint);
            }
        }
    }

    private static final double CARD_W = 140;
    private static final double CARD_H = 176;

    private VBox createStatCard(String iconKey, String title, String value, String subtitle, String accent) {
        VBox card = new VBox(3);
        card.setAlignment(Pos.TOP_CENTER);
        card.getStyleClass().add("stat-summary-card");
        card.setPadding(new Insets(9, 8, 9, 8));
        // Every card is exactly the same size — no more ragged rows.
        card.setPrefSize(CARD_W, CARD_H);
        card.setMinSize(CARD_W, CARD_H);
        card.setMaxSize(CARD_W, CARD_H);

        // Fixed-height icon slot, present on every card (empty when there's no icon) so heights match.
        StackPane iconSlot = new StackPane();
        javafx.scene.Node iconNode = iconNodeFor(iconKey, accent);
        if (iconNode != null) iconSlot.getChildren().add(iconNode);
        iconSlot.setPrefHeight(72);
        iconSlot.setMinHeight(72);
        iconSlot.setMaxHeight(72);

        Label lblValue = new Label(value);
        lblValue.getStyleClass().add("stat-value");
        lblValue.setStyle("-fx-text-fill: " + accent + ";");

        Label lblTitle = new Label(title);
        lblTitle.getStyleClass().add("stat-title");
        lblTitle.setWrapText(true);
        lblTitle.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        lblTitle.setAlignment(Pos.CENTER);
        lblTitle.setMaxWidth(CARD_W - 20);

        card.getChildren().addAll(iconSlot, lblValue, lblTitle);
        if (subtitle != null && !subtitle.isBlank()) {
            Label lblSub = new Label(subtitle);
            lblSub.getStyleClass().add("label-muted");
            lblSub.setWrapText(true);
            lblSub.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
            lblSub.setAlignment(Pos.CENTER);
            lblSub.setMaxWidth(CARD_W - 12);
            card.getChildren().add(lblSub);
        }
        return card;
    }

    /** Abbreviates a large number for display: 15685506 → "15.69M". */
    static String fmt(Long value) {
        if (value == null) return "—";
        long v = value;
        long abs = Math.abs(v);
        if (abs >= 1_000_000_000L) return trim(v / 1_000_000_000.0) + "B";
        if (abs >= 1_000_000L) return trim(v / 1_000_000.0) + "M";
        // Below a million, show the real number with commas — no "K". the operator: "million makes
        // sense, the K does not." 76944 → "76,944", not "76.04K".
        return String.format(Locale.US, "%,d", v);
    }

    static String fmtSigned(Long change) {
        if (change == null) return "—";
        String body = fmt(Math.abs(change));
        if (change > 0) return "+" + body;
        if (change < 0) return "-" + body;
        return "0";
    }

    /** Formats a speedup duration in minutes as "6d 3h", "1d 22h 31m", "45m". */
    static String fmtMinutes(Long minutes) {
        if (minutes == null) return "—";
        long m = Math.abs(minutes);
        long days = m / 1440; m %= 1440;
        long hours = m / 60; long mins = m % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append('d');
        if (hours > 0) { if (sb.length() > 0) sb.append(' '); sb.append(hours).append('h'); }
        // Always show minutes when nothing bigger applies, so "0" renders as "0m" not "".
        if (mins > 0 || sb.length() == 0) { if (sb.length() > 0) sb.append(' '); sb.append(mins).append('m'); }
        return sb.toString();
    }

    /** Signed speedup delta: "+3h 12m" gained, "-1d" spent, "0" unchanged. */
    static String fmtMinutesSigned(Long change) {
        if (change == null) return "—";
        if (change == 0) return "0";
        return (change > 0 ? "+" : "-") + fmtMinutes(Math.abs(change));
    }

    private static String trim(double d) {
        return String.format(Locale.US, "%.2f", d);
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
    /**
     * Each metric's own most recent recorded value, searched backwards independently.
     *
     * <p>Deliberately not {@code telemetry.latest().get(metric)}. That reads the single newest
     * sample, so a metric absent from just that one row disappears from its card even when a
     * perfectly good earlier value exists -- the reading is missing, not the metric.
     *
     * <p>Lives here rather than on TelemetryReport: #250 owns that class, and this is a
     * presentation concern of the dashboard rather than part of the collector's contract.
     */
    private static Long latestValueOf(TelemetryReport telemetry, String metric) {
        List<TelemetryReport.Sample> samples = telemetry.samples();
        for (int i = samples.size() - 1; i >= 0; i--) {
            Long value = samples.get(i).get(metric);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

}
