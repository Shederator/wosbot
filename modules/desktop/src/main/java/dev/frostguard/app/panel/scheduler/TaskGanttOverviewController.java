package dev.frostguard.app.panel.scheduler;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.DailyTaskStatusData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.TaskStateData;
import dev.frostguard.app.panel.scheduler.TaskManagerAux;
import dev.frostguard.engine.listener.StaminaChangeListener;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.engine.service.TaskManagementService;
import dev.frostguard.engine.service.StaminaService;
import dev.frostguard.app.panel.scheduler.TaskManagerActionController;
import dev.frostguard.engine.listener.TaskStatusChangeListener;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.control.Label;
import javafx.scene.shape.Rectangle;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Renders a Gantt-style timeline of scheduled and running tasks
 * across all enabled profiles.  Supports three zoom levels
 * (two-hour, twenty-four-hour, and week) and live-refreshes
 * via listener callbacks.
 */
public class TaskGanttOverviewController implements TaskStatusChangeListener, StaminaChangeListener {

    /* ── FXML bindings ── */

    @FXML
    private VBox vboxAccounts;

    @FXML
    private ScrollPane scrollPane;

    @FXML
    private HBox timeAxisHeader;

    @FXML
    private ToggleButton toggleViewButton;

    @FXML
    private ToggleButton toggleInactiveTasksButton;

    /* ── Internal state ── */

    private TaskManagerActionController taskManagerActionController;
    private javafx.animation.Timeline periodicRefreshTimer;
    private String activeNameFilter = "";
    private List<AccountDescriptor> cachedProfiles = new ArrayList<>();
    private final Map<Long, Label> staminaLabelRegistry = new HashMap<>();

    /* ── Zoom presets ── */

    private enum TimeScale {
        SHORT("2 Hours",  120,   5),
        DAY("24 Hours",   1500,  5),
        FULL_WEEK("Week", 11520, 60);

        private final String displayName;
        private final int spanMinutes;
        private final int minimumBarMinutes;

        TimeScale(String displayName, int spanMinutes, int minimumBarMinutes) {
            this.displayName = displayName;
            this.spanMinutes = spanMinutes;
            this.minimumBarMinutes = minimumBarMinutes;
        }

        String displayName()        { return displayName; }
        int spanMinutes()           { return spanMinutes; }
        double minimumBarMinutes()  { return minimumBarMinutes; }

        TimeScale advance() {
            return switch (this) {
                case SHORT     -> DAY;
                case DAY       -> FULL_WEEK;
                case FULL_WEEK -> SHORT;
            };
        }
    }

    /* ── Layout constants ── */

    private static final double PIVOT_FRACTION = 30.0 / 120.0;
    private static final double TIMELINE_MIN_PX = 400;
    private static final double TIMELINE_MAX_PX = 2000;
    private static final int LANE_HEIGHT_PX    = 24;
    private static final int LANE_Y_OFFSET     = 4;
    private static final int ROW_GAP           = 12;
    private static final int ROW_MIN_HEIGHT    = 48;
    private static final int BAR_HEIGHT_PX     = 18;
    private static final double LABEL_COL_PX   = 128;
    private static final double TZ_COL_PX      = 60;

    /* ── Short-name lookup for well-known tasks ── */

    private static final Map<String, String> SHORT_NAMES = Map.ofEntries(
        Map.entry("Hero Recruitment",               "Hero"),
        Map.entry("Nomadic Merchant",               "Nomad"),
        Map.entry("War Academy Shards",             "WAcad"),
        Map.entry("Crystal Laboratory",             "CrLab"),
        Map.entry("VIP Points",                     "VIP"),
        Map.entry("Pet Adventure",                  "PetAdv"),
        Map.entry("Exploration Chest",              "ExpCh"),
        Map.entry("Trek Supplies",                  "TrekS"),
        Map.entry("Life Essence",                   "LifeE"),
        Map.entry("Life Essence Caring",            "LfCar"),
        Map.entry("Labyrinth",                      "Laby"),
        Map.entry("Tundra Trek Automation",         "TrekA"),
        Map.entry("Bank",                           "Bank"),
        Map.entry("Arena",                          "Arena"),
        Map.entry("Mail Rewards",                   "Mail"),
        Map.entry("Daily Missions",                 "Daily"),
        Map.entry("Storehouse Chest",               "Store"),
        Map.entry("Intel",                          "Intel"),
        Map.entry("Expert Agnes Intel",             "Agnes"),
        Map.entry("Expert Romulus Tag",             "RomT"),
        Map.entry("Expert Romulus Troops",          "RomTr"),
        Map.entry("Expert Skill Training",          "SkTrn"),
        Map.entry("Alliance Autojoin",              "AlJoin"),
        Map.entry("Alliance Tech",                  "AlTch"),
        Map.entry("Alliance Pet Treasure",          "AlPet"),
        Map.entry("Alliance Chests",                "AlCh"),
        Map.entry("Alliance Triumph",               "AlTri"),
        Map.entry("Alliance Mobilization",          "AlMob"),
        Map.entry("Alliance Shop",                  "AlShp"),
        Map.entry("Alliance Championship",          "AlChmp"),
        Map.entry("Bear Trap Event",                "Bear"),
        Map.entry("Pet Skill Stamina",              "PetSt"),
        Map.entry("Pet Skill Food",                 "PetFd"),
        Map.entry("Pet Skill Treasure",             "PetTr"),
        Map.entry("Pet Skill Gathering",            "PetGa"),
        Map.entry("Training Infantry",              "TrnInf"),
        Map.entry("Training Lancer",                "TrnLnc"),
        Map.entry("Training Marksman",              "TrnMrk"),
        Map.entry("City Upgrade Furnace",           "Furn"),
        Map.entry("City Survivors",                 "Surv"),
        Map.entry("Shop Mystery",                   "Myst"),
        Map.entry("Chief Order: Rush Job",          "CoRush"),
        Map.entry("Chief Order: Urgent Mobilization", "CoMob"),
        Map.entry("Chief Order: Productivity Day",  "CoProd"),
        Map.entry("Initialize",                     "Init"),
        Map.entry("Gather Speed Boost",             "GthSpd"),
        Map.entry("Gather Resources",               "GthRes"),
        Map.entry("Gather Meat",                    "GthMt"),
        Map.entry("Gather Wood",                    "GthWd"),
        Map.entry("Gather Coal",                    "GthCl"),
        Map.entry("Gather Iron",                    "GthIr"),
        Map.entry("Tundra Truck Event",             "TrkEv"),
        Map.entry("Hero Mission Event",             "HeroEv"),
        Map.entry("Mercenary Event",                "Merc"),
        Map.entry("Journey of Light Event",         "JoL"),
        Map.entry("Polar Terror Hunting",           "Polar"),
        Map.entry("Myriad Bazaar Event",            "Bazaar")
    );

    /* ════════════════════════════════════════════════
     *  Coordinate-mapping helper
     * ════════════════════════════════════════════════ */

    private static final class AxisMapper {
        private enum Scaling { LINEAR, LOG_ONLY, SPLIT_LOG_LINEAR }

        private final Scaling scaling;
        private final LocalDateTime windowStart;
        private final LocalDateTime windowEnd;
        private final double totalPx;
        private final double totalMinutes;

        // LINEAR fields
        private final double pxPerMinute;

        // LOG_ONLY fields
        private final double logDivisor;

        // SPLIT_LOG_LINEAR fields
        private final double leftSpanMinutes;
        private final double pivotPx;
        private final double leftLogDivisor;
        private final double rightPxPerMinute;

        private AxisMapper(Scaling scaling,
                           LocalDateTime windowStart, LocalDateTime windowEnd,
                           double totalPx, double totalMinutes,
                           double pxPerMinute, double logDivisor,
                           double leftSpanMinutes, double pivotPx,
                           double leftLogDivisor, double rightPxPerMinute) {
            this.scaling         = scaling;
            this.windowStart     = windowStart;
            this.windowEnd       = windowEnd;
            this.totalPx         = totalPx;
            this.totalMinutes    = totalMinutes;
            this.pxPerMinute     = pxPerMinute;
            this.logDivisor      = logDivisor;
            this.leftSpanMinutes = leftSpanMinutes;
            this.pivotPx         = pivotPx;
            this.leftLogDivisor  = leftLogDivisor;
            this.rightPxPerMinute = rightPxPerMinute;
        }

        static AxisMapper forLinear(LocalDateTime start, double px, int minutes) {
            LocalDateTime end = start.plusMinutes(minutes);
            double effectiveMin = Math.max(1.0, ChronoUnit.SECONDS.between(start, end) / 60.0);
            double perMin = px / effectiveMin;
            return new AxisMapper(Scaling.LINEAR, start, end, px, effectiveMin,
                    perMin, 0, 0, 0, 0, perMin);
        }

        static AxisMapper forSplitLogLinear(LocalDateTime start, LocalDateTime pivot,
                                            LocalDateTime end, double px, double pivotRatio) {
            double mins = Math.max(1.0, ChronoUnit.SECONDS.between(start, end) / 60.0);
            double leftMins  = Math.max(0, ChronoUnit.SECONDS.between(start, pivot) / 60.0);
            double rightMins = Math.max(0, mins - leftMins);
            double clamped   = Math.max(0, Math.min(1, pivotRatio));
            double pPx       = px * clamped;
            double lLog      = leftMins > 0 ? Math.log1p(leftMins) : 1.0;
            double rPpm      = rightMins > 0 ? (px - pPx) / rightMins : 0;
            return new AxisMapper(Scaling.SPLIT_LOG_LINEAR, start, end, px, mins,
                    rPpm, 0, leftMins, pPx, lLog, rPpm);
        }

        double mapToX(LocalDateTime time) {
            double mins = ChronoUnit.SECONDS.between(windowStart, time) / 60.0;
            mins = Math.max(0, Math.min(mins, totalMinutes));

            switch (scaling) {
                case LINEAR:
                    return mins * pxPerMinute;
                case LOG_ONLY:
                    return logDivisor == 0 ? 0 : totalPx * Math.log1p(mins) / logDivisor;
                case SPLIT_LOG_LINEAR:
                    if (mins <= leftSpanMinutes) {
                        if (leftSpanMinutes <= 0 || leftLogDivisor == 0) {
                            return 0;
                        }
                        double gap = Math.max(0, leftSpanMinutes - mins);
                        double norm = Math.log1p(gap) / leftLogDivisor;
                        return pivotPx * (1.0 - norm);
                    }
                    return pivotPx + (mins - leftSpanMinutes) * rightPxPerMinute;
                default:
                    throw new IllegalStateException("Unknown scaling: " + scaling);
            }
        }

        double pixelSpan(LocalDateTime origin, double durationMinutes) {
            double clamped = Math.max(0, durationMinutes);
            Duration dur = Duration.ofMillis(Math.round(clamped * 60_000));
            LocalDateTime terminus = origin.plus(dur);
            if (terminus.isAfter(windowEnd)) {
                terminus = windowEnd;
            }
            return Math.max(0, mapToX(terminus) - mapToX(origin));
        }
    }

    /* ════════════════════════════════════════════════
     *  Active zoom level
     * ════════════════════════════════════════════════ */

    private TimeScale activeScale = TimeScale.SHORT;
    private Map<Long, List<TaskManagerAux>> tasksByProfile = new LinkedHashMap<>();
    private double computedWidth = 720;
    private boolean includeInactiveTasks = false;

    /* ════════════════════════════════════════════════
     *  Initialisation
     * ════════════════════════════════════════════════ */

    public void initialize() {
        taskManagerActionController = new TaskManagerActionController(null);

        TaskManagementService.shared().subscribeStatusChanges(this);
        StaminaService.getServices().addStaminaChangeListener(this);

        if (vboxAccounts != null) {
            vboxAccounts.setSpacing(0);
            vboxAccounts.setFillWidth(true);
        }

        if (toggleViewButton != null) {
            toggleViewButton.setText(activeScale.displayName());
        }

        if (toggleInactiveTasksButton != null) {
            toggleInactiveTasksButton.setSelected(true);
            toggleInactiveTasksButton.setText("Show inactive");
        }

        if (scrollPane != null) {
            scrollPane.widthProperty().addListener((obs, oldW, newW) -> {
                recalculateWidth(newW.doubleValue());
                Platform.runLater(() -> { renderTimeAxis(); refreshProfiles(); });
            });
        }

        Platform.runLater(() -> {
            recalculateWidth(scrollPane != null ? scrollPane.getWidth() : 800);
            renderTimeAxis();
            refreshProfiles();
        });

        periodicRefreshTimer = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.seconds(5), e -> {
                renderTimeAxis();
                refreshProfiles();
            })
        );
        periodicRefreshTimer.setCycleCount(javafx.animation.Animation.INDEFINITE);
        periodicRefreshTimer.play();
    }

    /* ════════════════════════════════════════════════
     *  Public API
     * ════════════════════════════════════════════════ */

    /** Applies the search filter from the table view to the timeline. */
    public void setTaskFilter(String text) {
        String normalized = text == null ? "" : text.trim().toLowerCase(Locale.ENGLISH);
        if (Objects.equals(normalized, activeNameFilter)) {
            return;
        }
        activeNameFilter = normalized;

        if (!tasksByProfile.isEmpty() && !cachedProfiles.isEmpty()) {
            List<AccountDescriptor> snapshot = new ArrayList<>(cachedProfiles);
            Platform.runLater(() -> reconstructView(snapshot));
        }
    }

    public void stopAutoRefresh() {
        if (periodicRefreshTimer != null) {
            periodicRefreshTimer.stop();
        }
        StaminaService.getServices().removeStaminaChangeListener(this);
    }

    /* ════════════════════════════════════════════════
     *  Listener callbacks
     * ════════════════════════════════════════════════ */

    @Override
    public void onTaskStatusTransition(Long profileId, int taskNameId, TaskStateData state) {
        Platform.runLater(() -> {
            List<TaskManagerAux> profileTasks = tasksByProfile.get(profileId);
            if (profileTasks == null) {
                return;
            }
            for (TaskManagerAux t : profileTasks) {
                if (t.getTaskEnum().getId() == taskNameId) {
                    t.setExecuting(state.isExecuting());
                    t.setLastExecution(state.getLastExecutionTime());
                    t.setNextExecution(state.getNextExecutionTime());
                    t.setScheduled(state.isScheduled());

                    List<AccountDescriptor> allEnabled = ProfileService.obtain().fetchAllAccounts()
                        .stream()
                        .filter(p -> Boolean.TRUE.equals(p.getEnabled()))
                        .sorted(Comparator.comparing(AccountDescriptor::getName))
                        .collect(Collectors.toList());

                    if (!allEnabled.isEmpty()) {
                        reconstructView(allEnabled);
                    }
                    break;
                }
            }
        });
    }

    @Override
    public void onEnergyLevelChanged(Long profileId, int newStamina) {
        if (profileId == null) {
            return;
        }
        Platform.runLater(() -> {
            Label lbl = staminaLabelRegistry.get(profileId);
            if (lbl != null) {
                lbl.setText(renderStamina(newStamina));
            }
        });
    }

    /* ════════════════════════════════════════════════
     *  Toggle handlers
     * ════════════════════════════════════════════════ */

    @FXML
    private void toggleTimeView() {
        activeScale = activeScale.advance();
        if (toggleViewButton != null) {
            toggleViewButton.setText(activeScale.displayName());
        }
        renderTimeAxis();
        refreshProfiles();
    }

    @FXML
    private void toggleInactiveTasks() {
        boolean hideInactive = toggleInactiveTasksButton != null && toggleInactiveTasksButton.isSelected();
        includeInactiveTasks = !hideInactive;
        if (toggleInactiveTasksButton != null) {
            toggleInactiveTasksButton.setText(hideInactive ? "Show inactive" : "Hide inactive");
        }

        if (!cachedProfiles.isEmpty()) {
            reconstructView(new ArrayList<>(cachedProfiles));
        } else {
            refreshProfiles();
        }
    }

    /* ════════════════════════════════════════════════
     *  Width management
     * ════════════════════════════════════════════════ */

    private void recalculateWidth(double containerPx) {
        double candidate = containerPx - LABEL_COL_PX - 80;
        computedWidth = Math.max(TIMELINE_MIN_PX, Math.min(TIMELINE_MAX_PX, candidate));
    }

    /* ════════════════════════════════════════════════
     *  Time axis rendering
     * ════════════════════════════════════════════════ */

    private void renderTimeAxis() {
        if (timeAxisHeader == null) {
            return;
        }
        timeAxisHeader.getChildren().clear();
        LocalDateTime now = LocalDateTime.now();

        timeAxisHeader.getChildren().add(createTimezoneLabel());

        javafx.scene.layout.Pane ruler = new javafx.scene.layout.Pane();
        ruler.setPrefWidth(computedWidth);
        ruler.setMinWidth(computedWidth);
        ruler.setMaxWidth(computedWidth);
        timeAxisHeader.getChildren().add(ruler);

        switch (activeScale) {
            case DAY       -> drawDayRuler(ruler, now);
            case FULL_WEEK -> drawWeekRuler(ruler, now);
            case SHORT     -> drawShortRuler(ruler, now);
        }

        timeAxisHeader.setPrefWidth(TZ_COL_PX + computedWidth);
    }

    private javafx.scene.layout.VBox createTimezoneLabel() {
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(0);
        box.setAlignment(javafx.geometry.Pos.CENTER);
        box.setPrefWidth(60);
        box.setMinWidth(60);
        box.setMaxWidth(60);

        Label local = new Label("Local");
        local.setStyle("-fx-text-fill: #ffb347; -fx-font-size: 9; -fx-font-weight: bold;");
        local.setAlignment(javafx.geometry.Pos.CENTER);

        Label utc = new Label("UTC");
        utc.setStyle("-fx-text-fill: #ffb347; -fx-font-size: 8; -fx-font-weight: bold;");
        utc.setAlignment(javafx.geometry.Pos.CENTER);

        box.getChildren().addAll(local, utc);
        return box;
    }

    /* ── Day ruler ── */

    private void drawDayRuler(javafx.scene.layout.Pane pane, LocalDateTime now) {
        LocalDateTime origin = viewWindowStart(TimeScale.DAY, now);
        double hourSpan = computedWidth / 25.0;
        double lblW = Math.max(48, Math.min(120, hourSpan + 24));

        for (int h = 0; h < 25; h++) {
            LocalDateTime tick = origin.plusHours(h);
            ZonedDateTime utcTick = tick.atZone(ZoneId.systemDefault())
                    .withZoneSameInstant(ZoneOffset.UTC);

            javafx.scene.layout.VBox col = buildAxisCell(lblW);

            Label lLocal = new Label(String.format("%02d", tick.getHour()));
            lLocal.setStyle(String.format("-fx-text-fill: %s; -fx-font-size: %d;",
                    h % 6 == 0 ? "#888888" : "#666666",
                    h % 6 == 0 ? 10 : 9));

            Label lUtc = new Label(String.format("%02d", utcTick.getHour()));
            lUtc.setStyle(String.format("-fx-text-fill: %s; -fx-font-size: 8;",
                    h % 6 == 0 ? "#666666" : "#555555"));

            col.getChildren().addAll(lLocal, lUtc);
            positionAxisCell(col, h * hourSpan, lblW, computedWidth);
            pane.getChildren().add(col);
        }
    }

    /* ── Week ruler ── */

    private void drawWeekRuler(javafx.scene.layout.Pane pane, LocalDateTime now) {
        LocalDateTime origin = viewWindowStart(TimeScale.FULL_WEEK, now);
        double daySpan = computedWidth / 8.0;
        double lblW = Math.max(100, Math.min(160, daySpan + 40));

        for (int d = 0; d < 8; d++) {
            LocalDateTime dayAt = origin.plusDays(d);
            ZonedDateTime utcDay = dayAt.withHour(12).atZone(ZoneId.systemDefault())
                    .withZoneSameInstant(ZoneOffset.UTC);

            javafx.scene.layout.VBox col = buildAxisCell(lblW);

            String localTxt = d == 0 ? "Today" : String.format("%02d.%02d",
                    dayAt.getDayOfMonth(), dayAt.getMonthValue());

            String utcTxt;
            int ld = dayAt.getDayOfMonth(), ud = utcDay.getDayOfMonth();
            int lm = dayAt.getMonthValue(),  um = utcDay.getMonthValue();

            if (ld == ud && lm == um) {
                utcTxt = String.format("%02d.%02d", ud, um);
            } else if (utcDay.toLocalDate().isBefore(dayAt.toLocalDate())) {
                utcTxt = String.format("%02d.%02d(-1)", ud, um);
            } else {
                utcTxt = String.format("%02d.%02d(+1)", ud, um);
            }

            Label lLocal = new Label(localTxt);
            lLocal.setStyle(String.format("-fx-text-fill: %s; -fx-font-size: %d; -fx-font-weight: %s;",
                    d == 0 ? "#ffb347" : "#888888", d == 0 ? 11 : 10, d == 0 ? "bold" : "normal"));

            Label lUtc = new Label(utcTxt);
            lUtc.setStyle(String.format("-fx-text-fill: %s; -fx-font-size: 7;",
                    d == 0 ? "#ffaa44" : "#666666"));

            col.getChildren().addAll(lLocal, lUtc);
            positionAxisCell(col, d * daySpan, lblW, computedWidth);
            pane.getChildren().add(col);
        }
    }

    /* ── Short (2h) ruler ── */

    private void drawShortRuler(javafx.scene.layout.Pane pane, LocalDateTime now) {
        LocalDateTime start = viewWindowStart(TimeScale.SHORT, now);
        LocalDateTime end   = viewWindowEnd(TimeScale.SHORT, now);
        AxisMapper mapper   = AxisMapper.forSplitLogLinear(start, now, end, computedWidth, PIVOT_FRACTION);

        List<LocalDateTime> ticks = computeShortTicks(start, now, end);
        if (ticks.isEmpty()) {
            return;
        }

        double lblW = 104;

        for (int i = 0; i < ticks.size(); i++) {
            LocalDateTime t = ticks.get(i);
            ZonedDateTime lz = t.atZone(ZoneId.systemDefault());
            ZonedDateTime uz = lz.withZoneSameInstant(ZoneOffset.UTC);

            boolean isNow  = t.isEqual(now);
            boolean isPast = t.isBefore(now);

            String localTxt = String.format("%02d:%02d", lz.getHour(), lz.getMinute());
            String utcTxt   = String.format("%02d:%02d", uz.getHour(), uz.getMinute());

            String lColor = isNow ? "#ffb347" : (isPast ? "#888888" : "#666666");
            String uColor = isNow ? "#ffb347" : (isPast ? "#666666" : "#555555");

            Label lLocal = new Label(localTxt);
            lLocal.setStyle(String.format("-fx-text-fill: %s; -fx-font-size: 10; -fx-font-weight: %s;",
                    lColor, isNow ? "bold" : "normal"));

            Label lUtc = new Label(utcTxt);
            lUtc.setStyle(String.format("-fx-text-fill: %s; -fx-font-size: 8; -fx-font-weight: %s;",
                    uColor, isNow ? "bold" : "normal"));

            javafx.scene.layout.VBox col = new javafx.scene.layout.VBox(lLocal, lUtc);
            col.setAlignment(javafx.geometry.Pos.CENTER);
            col.setPrefWidth(lblW);
            col.setMinWidth(lblW);
            col.setMaxWidth(lblW);

            double cx = mapper.mapToX(t);
            double lx = cx - lblW / 2.0;
            double maxX = computedWidth - lblW;
            javafx.geometry.Pos align = javafx.geometry.Pos.CENTER;
            javafx.geometry.Insets pad = javafx.geometry.Insets.EMPTY;

            if (i == 0) {
                lx = 0;
                align = javafx.geometry.Pos.CENTER_LEFT;
                pad = new javafx.geometry.Insets(0, 0, 0, 2);
            } else if (i == ticks.size() - 1) {
                lx = maxX;
                align = javafx.geometry.Pos.CENTER_RIGHT;
                pad = new javafx.geometry.Insets(0, 2, 0, 0);
            } else {
                lx = Math.max(0, Math.min(lx, maxX));
            }

            col.setAlignment(align);
            lLocal.setAlignment(align);
            lUtc.setAlignment(align);
            col.setPadding(pad);
            col.setLayoutX(lx);
            pane.getChildren().add(col);
        }
    }

    /* ── Axis cell helpers ── */

    private javafx.scene.layout.VBox buildAxisCell(double w) {
        javafx.scene.layout.VBox cell = new javafx.scene.layout.VBox(0);
        cell.setAlignment(javafx.geometry.Pos.CENTER);
        cell.setPrefWidth(w);
        cell.setMinWidth(w);
        cell.setMaxWidth(w);
        return cell;
    }

    private void positionAxisCell(javafx.scene.layout.VBox cell, double centerX,
                                   double cellW, double totalW) {
        double desired = centerX - cellW / 2.0;
        double cap     = totalW - cellW;
        double lx      = Math.max(0, Math.min(desired, cap));

        final javafx.geometry.Pos align;
        final javafx.geometry.Insets pad;

        if (lx <= 0.5) {
            align = javafx.geometry.Pos.CENTER_LEFT;
            pad = new javafx.geometry.Insets(0, 0, 0, 2);
        } else if (lx >= cap - 0.5) {
            align = javafx.geometry.Pos.CENTER_RIGHT;
            pad = new javafx.geometry.Insets(0, 2, 0, 0);
        } else {
            align = javafx.geometry.Pos.CENTER;
            pad = javafx.geometry.Insets.EMPTY;
        }
        cell.setAlignment(align);
        cell.getChildren().forEach(n -> { if (n instanceof Label l) l.setAlignment(align); });
        cell.setPadding(pad);
        cell.setLayoutX(lx);
    }

    /* ════════════════════════════════════════════════
     *  View window helpers
     * ════════════════════════════════════════════════ */

    private LocalDateTime viewWindowStart(TimeScale scale, LocalDateTime now) {
        return switch (scale) {
            case SHORT -> {
                ZonedDateTime utcNow   = now.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC);
                ZonedDateTime midnight = utcNow.toLocalDate().atStartOfDay(ZoneOffset.UTC);
                yield midnight.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
            }
            case DAY       -> now.minusHours(1).withMinute(0).withSecond(0).withNano(0);
            case FULL_WEEK -> now.withHour(0).withMinute(0).withSecond(0).withNano(0);
        };
    }

    private LocalDateTime viewWindowEnd(TimeScale scale, LocalDateTime now) {
        return switch (scale) {
            case SHORT         -> now.plusMinutes(scale.spanMinutes());
            case DAY, FULL_WEEK -> viewWindowStart(scale, now).plusMinutes(scale.spanMinutes());
        };
    }

    private List<LocalDateTime> computeShortTicks(LocalDateTime start, LocalDateTime now, LocalDateTime end) {
        NavigableSet<LocalDateTime> pts = new TreeSet<>();
        pts.add(start);

        for (int hr = 6; hr <= 18; hr += 6) {
            LocalDateTime c = start.plusHours(hr);
            if (!c.isBefore(now)) break;
            pts.add(c);
        }

        pts.add(now);

        int[] futureOffsets = {15, 30, 45, 60, 75, 90, 105, 120};
        for (int m : futureOffsets) {
            LocalDateTime c = now.plusMinutes(m);
            if (!c.isAfter(end)) pts.add(c);
        }

        if (!pts.contains(end)) pts.add(end);
        return new ArrayList<>(pts);
    }

    /* ════════════════════════════════════════════════
     *  Timeline background + grid lines
     * ════════════════════════════════════════════════ */

    private AxisMapper paintTimelineGrid(javafx.scene.layout.Pane canvas, TimeScale scale,
                                          double width, int rowH, LocalDateTime now) {
        switch (scale) {
            case DAY -> {
                double hourPx = width / 25.0;
                for (int h = 0; h < 25; h++) {
                    double x = h * hourPx;
                    javafx.scene.shape.Line ln = new javafx.scene.shape.Line(x, 0, x, 36);
                    if (h % 6 == 0) {
                        ln.setStroke(javafx.scene.paint.Color.web("#555555"));
                        ln.setStrokeWidth(1.5);
                    } else {
                        ln.setStroke(javafx.scene.paint.Color.web("#3a3a3a"));
                        ln.setStrokeWidth(0.5);
                    }
                    canvas.getChildren().add(ln);
                }
                LocalDateTime ws = viewWindowStart(TimeScale.DAY, now);
                AxisMapper mapper = AxisMapper.forLinear(ws, width, scale.spanMinutes());
                addNowIndicator(canvas, mapper.mapToX(now), rowH);
                return mapper;
            }
            case FULL_WEEK -> {
                double dayPx = width / 8.0;
                for (int d = 0; d < 8; d++) {
                    double x = d * dayPx;
                    javafx.scene.shape.Line ln = new javafx.scene.shape.Line(x, 0, x, rowH);
                    if (d == 0) {
                        ln.setStroke(javafx.scene.paint.Color.web("#666666"));
                        ln.setStrokeWidth(2.0);
                    } else if (d % 2 == 0) {
                        ln.setStroke(javafx.scene.paint.Color.web("#555555"));
                        ln.setStrokeWidth(1.0);
                    } else {
                        ln.setStroke(javafx.scene.paint.Color.web("#3a3a3a"));
                        ln.setStrokeWidth(0.5);
                    }
                    canvas.getChildren().add(ln);
                }
                LocalDateTime ws = viewWindowStart(TimeScale.FULL_WEEK, now);
                AxisMapper mapper = AxisMapper.forLinear(ws, width, scale.spanMinutes());
                addNowIndicator(canvas, mapper.mapToX(now), rowH);
                return mapper;
            }
            case SHORT -> {
                LocalDateTime ws = viewWindowStart(scale, now);
                LocalDateTime we = viewWindowEnd(scale, now);
                AxisMapper mapper = AxisMapper.forSplitLogLinear(ws, now, we, width, PIVOT_FRACTION);
                List<LocalDateTime> ticks = computeShortTicks(ws, now, we);
                for (LocalDateTime tick : ticks) {
                    if (tick.isEqual(now)) continue;
                    double x = mapper.mapToX(tick);
                    javafx.scene.shape.Line ln = new javafx.scene.shape.Line(x, 0, x, rowH);
                    boolean major;
                    if (tick.isBefore(now)) {
                        long hrs = Math.max(0, ChronoUnit.HOURS.between(ws, tick));
                        major = hrs == 0 || hrs % 6 == 0;
                    } else {
                        long mins = ChronoUnit.MINUTES.between(now, tick);
                        major = mins % 60 == 0;
                    }
                    ln.setStroke(javafx.scene.paint.Color.web(major ? "#555555" : "#3a3a3a"));
                    ln.setStrokeWidth(major ? 1.5 : 0.5);
                    canvas.getChildren().add(ln);
                }
                addNowIndicator(canvas, mapper.mapToX(now), rowH);
                return mapper;
            }
            default -> throw new IllegalStateException("Unsupported scale: " + scale);
        }
    }

    private void addNowIndicator(javafx.scene.layout.Pane canvas, double x, int height) {
        javafx.scene.shape.Line marker = new javafx.scene.shape.Line(x, 0, x, height);
        marker.setStroke(javafx.scene.paint.Color.web("#ff4444"));
        marker.setStrokeWidth(2);
        marker.getStrokeDashArray().addAll(5.0, 3.0);
        canvas.getChildren().add(marker);
    }

    /* ════════════════════════════════════════════════
     *  Profile loading
     * ════════════════════════════════════════════════ */

    private void refreshProfiles() {
        List<AccountDescriptor> all = ProfileService.obtain().fetchAllAccounts();

        List<AccountDescriptor> enabled = all.stream()
            .filter(p -> Boolean.TRUE.equals(p.getEnabled()))
            .sorted(Comparator.comparing(AccountDescriptor::getName))
            .collect(Collectors.toList());

        tasksByProfile.clear();

        final int[] completedCount = {0};
        final int total = enabled.size();

        for (AccountDescriptor profile : enabled) {
            fetchTasksForProfile(profile, () -> {
                completedCount[0]++;
                if (completedCount[0] == total) {
                    Platform.runLater(() -> {
                        cachedProfiles = new ArrayList<>(enabled);
                        reconstructView(enabled);
                    });
                }
            });
        }
    }

    private void reconstructView(List<AccountDescriptor> profiles) {
        vboxAccounts.getChildren().clear();
        staminaLabelRegistry.clear();
        cachedProfiles = new ArrayList<>(profiles);

        for (AccountDescriptor profile : profiles) {
            List<TaskManagerAux> tasks = tasksByProfile.get(profile.getId());
            if (tasks == null) continue;

            List<TaskManagerAux> visible = tasks.stream()
                .filter(this::passesNameFilter)
                .filter(t -> includeInactiveTasks || !isIdle(t))
                .collect(Collectors.toList());

            if (!activeNameFilter.isEmpty() && visible.isEmpty()) {
                continue;
            }

            buildProfileRow(profile, visible, computedWidth);
        }
    }

    /* ════════════════════════════════════════════════
     *  Task fetching
     * ════════════════════════════════════════════════ */

    private void fetchTasksForProfile(AccountDescriptor profile, Runnable whenDone) {
        taskManagerActionController.loadDailyTaskStatus(profile.getId(), (List<DailyTaskStatusData> statuses) -> {
            List<TaskManagerAux> prior = tasksByProfile.get(profile.getId());
            Map<Integer, TaskManagerAux> priorMap = new HashMap<>();
            if (prior != null) {
                prior.forEach(t -> priorMap.put(t.getTaskEnum().getId(), t));
            }

            List<TaskManagerAux> collected = new ArrayList<>();

            dev.frostguard.engine.service.CustomTaskService cts = dev.frostguard.engine.service.CustomTaskService.getInstance();
            Collection<dev.frostguard.engine.service.CustomTaskService.CustomTaskSettings> customs = cts.getEnabledTasks();

            for (TpDailyTaskEnum task : TpDailyTaskEnum.values()) {
                if (task == TpDailyTaskEnum.CUSTOM_TASK) continue;
                collected.add(buildTaskAux(profile.getId(), task, task.getName(), null, statuses, priorMap));
            }

            for (dev.frostguard.engine.service.CustomTaskService.CustomTaskSettings ct : customs) {
                collected.add(buildTaskAux(profile.getId(), TpDailyTaskEnum.CUSTOM_TASK,
                        ct.getCustomName(), ct.getClassName(), statuses, priorMap));
            }

            collected = collected.stream().filter(this::isTimelineWorthy).collect(Collectors.toList());
            tasksByProfile.put(profile.getId(), collected);

            if (whenDone != null) {
                whenDone.run();
            }
        });
    }

    private TaskManagerAux buildTaskAux(Long profileId, TpDailyTaskEnum task, String taskName,
                                        String customName, List<DailyTaskStatusData> statuses,
                                        Map<Integer, TaskManagerAux> priorMap) {
        DailyTaskStatusData match = statuses.stream()
            .filter(s -> s.getIdTpDailyTask() == task.getId())
            .findFirst().orElse(null);

        if (match == null) {
            TaskManagerAux cached = priorMap.get(task.getId());
            if (cached != null) return cached;
            return new TaskManagerAux(taskName, null, null, task, profileId,
                    Long.MAX_VALUE, false, false, false, customName);
        }

        long secsRemaining = Long.MAX_VALUE;
        boolean ready = false;
        if (match.getNextSchedule() != null) {
            secsRemaining = ChronoUnit.SECONDS.between(LocalDateTime.now(), match.getNextSchedule());
            if (secsRemaining <= 0) {
                ready = true;
                secsRemaining = 0;
            }
        }

        boolean queued = false;
        dev.frostguard.engine.schedule.TaskQueue queue =
                ScheduleService.obtain().getCoordinator().getQueue(profileId);
        if (queue != null) {
            queued = customName != null ? queue.isTaskQueued(customName) : queue.isTaskQueued(task);
        }

        boolean running = false;
        TaskStateData liveState = TaskManagementService.shared()
                .lookupTaskState(profileId, task.getId(), customName);
        if (liveState != null) {
            running = liveState.isExecuting();
        }

        TaskManagerAux existing = priorMap.get(task.getId());
        if (existing != null) {
            existing.setLastExecution(match.getLastExecution());
            existing.setNextExecution(match.getNextSchedule());
            existing.setNearestMinutesUntilExecution(secsRemaining);
            existing.setHasReadyTask(ready);
            existing.setScheduled(queued);
            if (existing.isExecuting() != running) {
                existing.setExecuting(running);
            }
            return existing;
        }

        return new TaskManagerAux(taskName, match.getLastExecution(), match.getNextSchedule(),
                task, profileId, secsRemaining, ready, queued, running, customName);
    }

    /* ════════════════════════════════════════════════
     *  Profile row rendering
     * ════════════════════════════════════════════════ */

    private void buildProfileRow(AccountDescriptor profile, List<TaskManagerAux> tasks, double width) {
        LocalDateTime now = LocalDateTime.now();
        List<List<TaskManagerAux>> lanes = computeLanes(tasks, now);
        int rowH = laneCountToRowHeight(lanes.size());

        HBox row = createRow(rowH);
        row.getChildren().addAll(
            createProfileLabel(profile),
            fixedSpacer(TZ_COL_PX)
        );

        javafx.scene.layout.Pane canvas = createCanvas(width, rowH);
        AxisMapper mapper = paintTimelineGrid(canvas, activeScale, width, rowH, now);
        drawTaskBars(canvas, lanes, mapper, width, now);

        row.getChildren().add(canvas);
        vboxAccounts.getChildren().add(row);
    }

    private int laneCountToRowHeight(int lanes) {
        int content = lanes == 0 ? ROW_MIN_HEIGHT : LANE_HEIGHT_PX * lanes + ROW_GAP;
        return Math.max(ROW_MIN_HEIGHT, content);
    }

    private HBox createRow(int height) {
        HBox row = new HBox(8);
        row.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        row.setPrefHeight(height);
        row.setMinHeight(height);
        row.setMaxHeight(height);
        row.setStyle("-fx-padding: 0; -fx-spacing: 8;");
        return row;
    }

    private VBox createProfileLabel(AccountDescriptor profile) {
        Label nameLabel = new Label(profile.getName());
        nameLabel.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 12; -fx-font-weight: bold;");
        nameLabel.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        int stamina = StaminaService.getServices().getCurrentStamina(profile.getId());
        Label staminaLabel = new Label(renderStamina(stamina));
        staminaLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 10;");
        staminaLabel.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        staminaLabelRegistry.put(profile.getId(), staminaLabel);

        VBox box = new VBox(0, nameLabel, staminaLabel);
        box.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        box.setPrefWidth(LABEL_COL_PX);
        box.setMinWidth(LABEL_COL_PX);
        box.setMaxWidth(LABEL_COL_PX);
        return box;
    }

    private javafx.scene.layout.Region fixedSpacer(double w) {
        javafx.scene.layout.Region r = new javafx.scene.layout.Region();
        r.setPrefWidth(w);
        r.setMinWidth(w);
        r.setMaxWidth(w);
        return r;
    }

    private javafx.scene.layout.Pane createCanvas(double w, int h) {
        javafx.scene.layout.Pane pane = new javafx.scene.layout.Pane();
        pane.setPrefWidth(w);
        pane.setMinWidth(w);
        pane.setMaxWidth(w);
        pane.setPrefHeight(h);
        pane.setMinHeight(h);
        pane.setMaxHeight(h);
        pane.setStyle("-fx-background-color: #2a2a2a; -fx-border-color: #3a3a3a; -fx-border-width: 1;");
        return pane;
    }

    /* ════════════════════════════════════════════════
     *  Lane computation
     * ════════════════════════════════════════════════ */

    private List<List<TaskManagerAux>> computeLanes(List<TaskManagerAux> tasks, LocalDateTime now) {
        List<List<Span>> lanes = new ArrayList<>();

        visibleSpans(tasks, now).forEach(span -> {
            Optional<List<Span>> free = lanes.stream()
                .filter(lane -> lane.stream().noneMatch(span::collidesWith))
                .findFirst();

            free.ifPresentOrElse(
                lane -> lane.add(span),
                () -> lanes.add(new ArrayList<>(List.of(span)))
            );
        });

        return lanes.stream()
            .map(lane -> lane.stream().map(Span::source).collect(Collectors.toList()))
            .collect(Collectors.toList());
    }

    private List<Span> visibleSpans(List<TaskManagerAux> tasks, LocalDateTime now) {
        return tasks.stream()
            .filter(this::isTimelineWorthy)
            .map(t -> Span.of(t, resolveTime(t, now)))
            .flatMap(Optional::stream)
            .filter(span -> isInsideWindow(span.from(), now))
            .sorted(Comparator.comparing(Span::from))
            .collect(Collectors.toList());
    }

    private record Span(TaskManagerAux source, LocalDateTime from, LocalDateTime to) {
        static Optional<Span> of(TaskManagerAux task, LocalDateTime start) {
            if (start == null) return Optional.empty();
            return Optional.of(new Span(task, start, start.plusMinutes(5)));
        }

        boolean collidesWith(Span other) {
            return !(to.isBefore(other.from) || from.isAfter(other.to));
        }
    }

    /* ════════════════════════════════════════════════
     *  Task bar drawing
     * ════════════════════════════════════════════════ */

    private void drawTaskBars(javafx.scene.layout.Pane canvas, List<List<TaskManagerAux>> lanes,
                               AxisMapper mapper, double totalPx, LocalDateTime now) {
        for (int lane = 0; lane < lanes.size(); lane++) {
            int laneIdx = lane;
            for (TaskManagerAux task : lanes.get(lane)) {
                computeBarLayout(task, mapper, totalPx, now)
                    .map(layout -> assembleBarNode(layout, laneIdx))
                    .ifPresent(canvas.getChildren()::add);
            }
        }
    }

    private Optional<BarLayout> computeBarLayout(TaskManagerAux task, AxisMapper mapper,
                                                  double totalPx, LocalDateTime now) {
        LocalDateTime display = resolveTime(task, now);
        if (!isInsideWindow(display, now)) return Optional.empty();

        LocalDateTime aligned = quantise(activeScale, display);
        if (aligned == null) return Optional.empty();

        String shortName = abbreviate(task.getTaskName());
        double textPx  = shortName.length() * 9 + 12;
        double timePx  = Math.max(0, mapper.pixelSpan(aligned, activeScale.minimumBarMinutes()));
        double barPx   = Math.max(textPx, Math.max(12, timePx));
        double xPos    = constrain(mapper.mapToX(aligned), 0, Math.max(0, totalPx - barPx));

        return Optional.of(new BarLayout(task, shortName, display, xPos, barPx, resolveAppearance(task)));
    }

    private javafx.scene.layout.StackPane assembleBarNode(BarLayout layout, int laneIndex) {
        javafx.scene.layout.StackPane stack = new javafx.scene.layout.StackPane();
        stack.setLayoutX(layout.x());
        stack.setLayoutY(LANE_Y_OFFSET + laneIndex * LANE_HEIGHT_PX);

        Rectangle bg = new Rectangle(layout.width(), BAR_HEIGHT_PX);
        bg.setArcWidth(4);
        bg.setArcHeight(4);
        bg.setFill(javafx.scene.paint.Color.web(layout.look().fill()));
        bg.setStroke(javafx.scene.paint.Color.web(layout.look().border()));
        bg.setStrokeWidth(layout.look().borderWidth());

        Label lbl = new Label(layout.shortName());
        lbl.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 8; -fx-font-weight: bold;");
        lbl.setMaxWidth(layout.width() - 4);

        wireInteractions(stack, layout);
        stack.getChildren().addAll(bg, lbl);
        return stack;
    }

    private void wireInteractions(javafx.scene.layout.StackPane node, BarLayout layout) {
        javafx.scene.control.Tooltip tip = new javafx.scene.control.Tooltip(buildTooltipText(layout));
        tip.setShowDelay(javafx.util.Duration.millis(200));
        tip.setHideDelay(javafx.util.Duration.INDEFINITE);
        tip.setShowDuration(javafx.util.Duration.INDEFINITE);
        tip.setAutoHide(false);
        javafx.scene.control.Tooltip.install(node, tip);

        node.setOnMouseEntered(e -> {
            javafx.geometry.Bounds bounds = node.localToScreen(node.getBoundsInLocal());
            if (bounds != null) {
                tip.show(node, bounds.getMinX(), bounds.getMaxY() + 5);
            }
        });
        node.setOnMouseExited(e -> tip.hide());
        node.setOnMouseClicked(e -> {
            if (!layout.task().isExecuting()) {
                taskManagerActionController.executeTaskDirectly(layout.task());
            }
        });
        if (!layout.task().isExecuting()) {
            node.setStyle("-fx-cursor: hand;");
        }
    }

    private String buildTooltipText(BarLayout layout) {
        TaskManagerAux task = layout.task();
        LocalDateTime tipTime = task.getNextExecution() != null
            ? task.getNextExecution()
            : (task.getLastExecution() != null ? task.getLastExecution() : layout.displayTime());
        String heading   = task.isExecuting() ? "Running since" : "Time";
        String timeStr   = tipTime != null
            ? String.format("%02d:%02d", tipTime.getHour(), tipTime.getMinute()) : "N/A";
        String clickHint = task.isExecuting() ? "" : "\n\n[Click to Execute]";
        return String.format("%s\n%s: %s\nStatus: %s%s",
                task.getTaskName(), heading, timeStr, layout.look().label(), clickHint);
    }

    /* ── Visual state resolution ── */

    private BarAppearance resolveAppearance(TaskManagerAux task) {
        if (task.isExecuting()) {
            return new BarAppearance("#FF9800", "#fff200", 2.5, "RUNNING");
        }
        if (task.isScheduled() && task.hasReadyTask()) {
            return new BarAppearance("#4CAF50", "#2e7d32", 1.5, "READY");
        }
        if (isIdle(task)) {
            return new BarAppearance("#616161", "#8a8a8a", 1,
                    task.hasReadyTask() ? "INACTIVE (READY)" : "INACTIVE");
        }
        return new BarAppearance("#47d9ff", "#0288d1", 1, "SCHEDULED");
    }

    /* ── Records ── */

    private record BarLayout(TaskManagerAux task, String shortName,
                             LocalDateTime displayTime, double x, double width,
                             BarAppearance look) {}

    private record BarAppearance(String fill, String border, double borderWidth, String label) {}

    /* ════════════════════════════════════════════════
     *  Time helpers
     * ════════════════════════════════════════════════ */

    private LocalDateTime resolveTime(TaskManagerAux task, LocalDateTime now) {
        if (task.isExecuting()) return now;
        if (task.getNextExecution() != null) return task.getNextExecution();
        return task.getLastExecution();
    }

    private LocalDateTime quantise(TimeScale scale, LocalDateTime time) {
        if (time == null) return null;
        if (scale != TimeScale.DAY) return time;

        int sec = time.getSecond();
        if (sec <= 30 && time.getMinute() == 0) {
            return time.withSecond(0).withNano(0);
        }
        if (sec >= 30 && time.getMinute() == 59) {
            return time.plusMinutes(1).withMinute(0).withSecond(0).withNano(0);
        }
        return time;
    }

    /* ════════════════════════════════════════════════
     *  Predicate helpers
     * ════════════════════════════════════════════════ */

    private boolean isInsideWindow(LocalDateTime time, LocalDateTime now) {
        if (time == null) return false;
        LocalDateTime ws = viewWindowStart(activeScale, now);
        LocalDateTime we = viewWindowEnd(activeScale, now);
        return !time.isBefore(ws) && !time.isAfter(we);
    }

    private boolean isTimelineWorthy(TaskManagerAux task) {
        if (task == null) return false;
        return task.isExecuting()
            || task.hasReadyTask()
            || task.isScheduled()
            || task.getNextExecution() != null
            || task.getLastExecution() != null;
    }

    private boolean isIdle(TaskManagerAux task) {
        if (task == null) return true;
        if (task.isExecuting()) return false;
        return !task.isScheduled();
    }

    private boolean passesNameFilter(TaskManagerAux task) {
        if (activeNameFilter.isEmpty()) return true;
        String name = task.getTaskName();
        return name != null && name.toLowerCase(Locale.ENGLISH).contains(activeNameFilter);
    }

    /* ════════════════════════════════════════════════
     *  Abbreviation helpers
     * ════════════════════════════════════════════════ */

    private String abbreviate(String fullName) {
        if (fullName == null || fullName.isBlank()) return "";
        return SHORT_NAMES.getOrDefault(fullName, fallbackAbbreviation(fullName));
    }

    private String fallbackAbbreviation(String fullName) {
        String[] tokens = fullName.trim().split("\\s+");
        if (tokens.length == 1) {
            return fullName.length() > 5 ? fullName.substring(0, 5) : fullName;
        }
        StringBuilder sb = new StringBuilder(tokens.length);
        for (String w : tokens) {
            if (!w.isEmpty()) sb.append(w.charAt(0));
        }
        return sb.toString();
    }

    /* ── Stamina display ── */

    private String renderStamina(int value) {
        return String.format("Stamina: %d", Math.max(0, value));
    }

    /* ── Math ── */

    private double constrain(double v, double lo, double hi) {
        return Math.max(lo, Math.min(v, hi));
    }
}