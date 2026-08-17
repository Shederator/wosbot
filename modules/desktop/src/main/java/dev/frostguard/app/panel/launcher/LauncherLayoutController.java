package dev.frostguard.app.panel.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.app.panel.alliance.AllianceLayoutController;
import dev.frostguard.app.panel.analytics.GameAnalyticsLayoutController;
import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.app.panel.alliance.AllianceChampionshipLayoutController;
import dev.frostguard.app.panel.combat.BearTrapLayoutController;
import dev.frostguard.app.panel.combat.BeastHuntingLayoutController;
import dev.frostguard.app.panel.dailies.ChiefOrderLayoutController;
import dev.frostguard.app.panel.city.CityEventsExtraLayoutController;
import dev.frostguard.app.panel.city.CityEventsLayoutController;
import dev.frostguard.app.panel.city.CityUpgradesLayoutController;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.app.panel.console.ConsoleLogLayoutController;
import dev.frostguard.app.panel.misc.DebuggingLayoutController;
import dev.frostguard.app.panel.misc.DummyLayoutController;
import dev.frostguard.engine.emulator.EmulatorType;
import dev.frostguard.app.panel.emulator.EmuConfigLayoutController;
import dev.frostguard.app.panel.dailies.EventsLayoutController;
import dev.frostguard.app.panel.heroes.ExpertsLayoutController;
import dev.frostguard.app.panel.misc.FishingLayoutController;
import dev.frostguard.app.panel.economy.GatherLayoutController;
import dev.frostguard.app.panel.misc.GiftcodeLayoutController;
import dev.frostguard.app.panel.heroes.IntelLayoutController;
import dev.frostguard.app.panel.dailies.MobilizationLayoutController;
import dev.frostguard.api.domain.BotStateData;
import dev.frostguard.api.domain.LogMessageData;
import dev.frostguard.api.domain.QueueProfileStateData;
import dev.frostguard.app.panel.pets.PetsLayoutController;
import dev.frostguard.app.panel.combat.PolarTerrorLayoutController;
import dev.frostguard.app.panel.profile.IProfileChangeObserver;
import dev.frostguard.app.panel.profile.IProfileLoadListener;
import dev.frostguard.app.panel.profile.IProfileObserverInjectable;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.app.panel.profile.ProfileManagerLayoutController;
import dev.frostguard.api.domain.QueueStateData;
import dev.frostguard.api.runtime.RuntimeChannel;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.listener.StaminaChangeListener;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.engine.service.StaminaService;
import dev.frostguard.app.panel.economy.ShopLayoutController;
import dev.frostguard.app.panel.scheduler.TaskManagerLayoutController;
import dev.frostguard.app.panel.misc.SkipTutorialLayoutController;
import dev.frostguard.app.panel.training.TrainingLayoutController;
import dev.frostguard.app.panel.training.ResearchLayoutController;
import dev.frostguard.app.panel.misc.CharacterLayoutController;
import dev.frostguard.app.panel.misc.StatisticsLayoutController;
import dev.frostguard.app.panel.taskbuilder.TaskBuilderLayoutController;
import dev.frostguard.app.panel.custom.CustomTasksLayoutController;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.control.SeparatorMenuItem;

import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.animation.*;
import javafx.util.Duration;
import dev.frostguard.app.panel.alliance.AllianceShopController;
import dev.frostguard.app.panel.misc.TelegramLayoutController;
import dev.frostguard.app.bootstrap.ApplicationLifecycle;
import dev.frostguard.app.bootstrap.WindowsWindowManager;
import dev.frostguard.app.panel.update.UpdateLayoutController;
import dev.frostguard.engine.service.TelegramBotService;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignG;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignB;
import org.kordamp.ikonli.materialdesign2.MaterialDesignH;
import org.kordamp.ikonli.materialdesign2.MaterialDesignV;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;
import org.kordamp.ikonli.Ikon;

public class LauncherLayoutController implements IProfileLoadListener, StaminaChangeListener {

    private static LauncherLayoutController instance;

    final private Map<String, Object> moduleControllers = new HashMap<>();
    @FXML
    private VBox buttonsContainer;
    @FXML
    private VBox logoContainer;
    @FXML
    private StackPane centerStack;
    @FXML
    private VBox pinnedButtonsContainer;
    @FXML
    private Button buttonStartStop;
    @FXML
    private SplitMenuButton buttonPauseResume;
    @FXML
    private MenuItem menuToggleAllQueues;
    @FXML
    private AnchorPane mainContentPane;
    @FXML
    private Label labelRunTime;
    @FXML
    private Label labelVersion;
    @FXML
    private ComboBox<ProfileAux> profileComboBox;
    @FXML
    private TextField navSearchField;
    @FXML
    private Label logoWhiteout;
    @FXML
    private Label logoSurvival;
    @FXML
    private VBox sidebarHeader;

    // Custom Title Bar FXML
    @FXML private javafx.scene.layout.StackPane titleBar;
    @FXML private javafx.scene.control.Label labelWindowTitle;
    @FXML private javafx.scene.control.Button btnMinimize;
    @FXML private javafx.scene.control.Button btnMaximize;
    @FXML private javafx.scene.control.Button btnClose;

    private double xOffset = 0;
    private double yOffset = 0;
    private WindowsWindowManager windowsWindowManager;
    // Window snap state tracking
    private boolean isCustomMaximized = false;
    private double restoreX, restoreY, restoreW, restoreH;
    // Snap preview overlay window
    private javafx.stage.Stage snapPreviewStage;

    @FXML
    private HBox discordBubbleContainer;

    @FXML
    private HBox coffeeBubbleContainer;

    @FXML
    private FontIcon iconDiscord;

    @FXML
    private FontIcon iconGithub;

    @FXML
    private FontIcon iconDiscordBubble;

    @FXML
    private FontIcon iconCoffeeBubble;

    @FXML
    private void handleCloseDiscordBubble(ActionEvent event) { /* internal */
        if (null != discordBubbleContainer) {
            discordBubbleContainer.setVisible(false);
            discordBubbleContainer.setManaged(false);
        }
    }

    @FXML
    private void handleCloseCoffeeBubble(ActionEvent event) { /* internal */
        if (null != coffeeBubbleContainer) {
            coffeeBubbleContainer.setVisible(false);
            coffeeBubbleContainer.setManaged(false);
        }
    }

    private Stage stage;
    private boolean quickNavVisible = false;
    private ScrollPane quickNavOverlay;
    final private List<QuickNavEntry> quickNavEntries = new ArrayList<>();
    private VBox searchOverlay;
    private LauncherActionController actionController;
    private ConsoleLogLayoutController consoleLogLayoutController;
    private ProfileManagerLayoutController profileManagerLayoutController;
    private boolean estado = false;
    private boolean updatingComboBox = false;
    private ProfileAux currentProfile = null;
    private boolean allQueuesPaused = false;
    final private Map<Long, QueueProfileStateData> activeQueueStates = new HashMap<>();
    private Timeline autoStartTimeline;
    private Timeline uptimeTimeline;
    private long uptimeStartedAtNanos;
    private int autoStartSecondsRemaining;
    private boolean isStartup = true;

    public LauncherLayoutController(Stage stage) {
        this.stage = stage;
        instance = this;
        StaminaService.getServices().addStaminaChangeListener(this);
    }

    public static LauncherLayoutController getInstance() {
        return instance;
    }

    @FXML
    private void initialize() { /* internal */
        initializeDiscordBot();
        initializeEmulatorController();
        initializeLogModule();
        initializeProfileModule();
        initializePinnedModules();
        initializeProfileComboBox();
        initializeModules();
        initializeExternalLibraries();
        initializeTelegramBot();
        showVersion();
        initializeUptime();
        buttonStartStop.setDisable(false);
        buttonPauseResume.setDisable(true);
        configurePauseMenu();
        scheduleAutoStart();
        initializeQuickNav();
        initializeSearch();
        setupSocialIcons();
        stage.maximizedProperty().addListener((observable, oldValue, maximized) -> updateMaximizeButton(maximized));
        updateMaximizeButton(stage.isMaximized());
    }

    public void enableNativeWindowing(WindowsWindowManager windowManager) {
        this.windowsWindowManager = Objects.requireNonNull(windowManager);
    }

    private void initializeUptime() { /* internal */
        uptimeStartedAtNanos = System.nanoTime();
        updateUptimeLabel();
        uptimeTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> updateUptimeLabel()));
        uptimeTimeline.setCycleCount(Animation.INDEFINITE);
        uptimeTimeline.play();
    }

    private void updateUptimeLabel() { /* internal */
        long elapsedSeconds = (System.nanoTime() - uptimeStartedAtNanos) / 1_000_000_000L;
        labelRunTime.setText("Uptime: " + formatUptime(elapsedSeconds));
    }

    static String formatUptime(long elapsedSeconds) {
        long hours = elapsedSeconds / 3_600;
        long minutes = elapsedSeconds % 3_600 / 60;
        long seconds = elapsedSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    private void setupSocialIcons() { /* internal */
        if (null != iconDiscord) {
            iconDiscord.setIconCode(MaterialDesignD.DISCORD);
        }
        if (null != iconDiscordBubble) {
            iconDiscordBubble.setIconCode(MaterialDesignD.DISCORD);
        }
        if (null != iconCoffeeBubble) {
            iconCoffeeBubble.setIconCode(MaterialDesignC.COFFEE);
        }
        if (null != iconGithub) {
            iconGithub.setIconCode(MaterialDesignG.GITHUB);
        }

        if (java.util.concurrent.ThreadLocalRandom.current().nextBoolean()) {
            if (null != discordBubbleContainer) {
                discordBubbleContainer.setVisible(false);
                discordBubbleContainer.setManaged(false);
            }
        } else {
            if (null != coffeeBubbleContainer) {
                coffeeBubbleContainer.setVisible(false);
                coffeeBubbleContainer.setManaged(false);
            }
        }
    }

    private void initializeTelegramBot() { /* internal */
        HashMap<String, String> cfg = ConfigService.obtain().loadGlobalSettings();
        if (null == cfg)
            return;
        boolean enabled = Boolean.parseBoolean(
                cfg.getOrDefault(ConfigurationKeyEnum.TELEGRAM_BOT_ENABLED_BOOL.name(), "false"));
        String token = cfg.getOrDefault(ConfigurationKeyEnum.TELEGRAM_BOT_TOKEN_STRING.name(), "");
        String chatIdStr = cfg.getOrDefault(ConfigurationKeyEnum.TELEGRAM_ALLOWED_CHAT_ID_STRING.name(), "");
        if (enabled && !token.isBlank()) {
            long chatId = chatIdStr.isBlank() ? 0L : Long.parseLong(chatIdStr);
            TelegramBotService.getInstance().start(token, chatId);
            
            // Auto-start watcher
            dev.frostguard.engine.service.TelegramWatcherLauncher.startWatcherIfNotRunning();
        }
    }

    private void showVersion() { /* internal */
        String version = getVersion();
        labelVersion.setText("Version: " + version);
    }

    private String getVersion() { /* internal */
        Package pkg = getClass().getPackage();
        if (pkg != null && pkg.getImplementationVersion() != null) {
            return pkg.getImplementationVersion();
        }
        try {
            Path parentPomPath = Paths.get("..", "pom.xml");
            if (!Files.exists(parentPomPath)) {
                parentPomPath = Paths.get("pom.xml");
            }
            List<String> lines = Files.readAllLines(parentPomPath);
            String revision = null;
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("<revision>") && line.endsWith("</revision>")) {
                    revision = line.replace("<revision>", "").replace("</revision>", "").trim();
                    break;
                }
            }
            if (null != revision) {
                return revision;
            }
        } catch (Exception e) {
            // Ignore error
        }
        return "Unknown";
    }

    private void initializeEmulatorController() { /* internal */
        HashMap<String, String> globalConfig = ConfigService.obtain().loadGlobalSettings();

        if (globalConfig == null || globalConfig.isEmpty()) {
            globalConfig = new HashMap<>();
        }

        String savedActiveEmulator = globalConfig.get(ConfigurationKeyEnum.CURRENT_EMULATOR_STRING.name());
        EmulatorType activeEmulator = null;
        if (savedActiveEmulator != null && !savedActiveEmulator.isEmpty()) {
            try {
                activeEmulator = EmulatorType.valueOf(savedActiveEmulator);
            } catch (IllegalArgumentException e) {
                // Ignore Invalid Enum constant
            }
        }
        boolean activeEmulatorValid = false;

        if (null != activeEmulator) {
            String activePath = globalConfig.get(activeEmulator.getConfigKey());
            if (activePath != null && new File(activePath).exists()) {
                activeEmulatorValid = true;
            } else {
                ScheduleService.obtain().persistEmulatorPath(activeEmulator.getConfigKey(), null);
            }
        }

        List<EmulatorType> foundEmulators = new ArrayList<>();
        for (EmulatorType emulator : EmulatorType.values()) {
            if (activeEmulator == emulator)
                continue;

            String emulatorPath = globalConfig.get(emulator.getConfigKey());
            if (emulatorPath != null && new File(emulatorPath).exists()) {
                foundEmulators.add(emulator);
            } else {
                File emulatorFile = new File(emulator.getDefaultPath());
                if (emulatorFile.exists()) {
                    ScheduleService.obtain().persistEmulatorPath(emulator.getConfigKey(), emulatorFile.getParent());
                    foundEmulators.add(emulator);
                }
            }
        }

        if (!activeEmulatorValid) {
            if (foundEmulators.size() == 1) {
                ScheduleService.obtain().persistEmulatorPath(ConfigurationKeyEnum.CURRENT_EMULATOR_STRING.name(),
                        foundEmulators.get(0).name());
                return;
            } else {
                EmulatorType selectedEmulator = askUserForPreferredEmulator(foundEmulators);
                if (!foundEmulators.contains(selectedEmulator)) {
                    // It was not found automatically. They must select it manually.
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Emulator Not Found");
                    alert.setHeaderText("The selected emulator was not found automatically.");
                    alert.setContentText("Please specify its executable location manually in the following dialog.");
                    alert.showAndWait();
                    selectEmulatorManually(selectedEmulator);
                } else {
                    ScheduleService.obtain().persistEmulatorPath(ConfigurationKeyEnum.CURRENT_EMULATOR_STRING.name(),
                            selectedEmulator.name());
                }
            }
        }
    }

    private EmulatorType askUserForPreferredEmulator(List<EmulatorType> foundEmulators) { /* internal */
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Select Emulator");
        alert.setHeaderText("Please select which emulator to use. Found emulators are highlighted.");

        List<ButtonType> buttons = new ArrayList<>();
        Map<ButtonType, EmulatorType> buttonMap = new HashMap<>();

        // Add found emulators first
        for (EmulatorType emulator : EmulatorType.values()) {
            if (foundEmulators.contains(emulator)) {
                ButtonType btnType = new ButtonType(emulator.getDisplayName(), ButtonBar.ButtonData.OK_DONE);
                buttons.add(btnType);
                buttonMap.put(btnType, emulator);
            }
        }

        // Add not found emulators next
        for (EmulatorType emulator : EmulatorType.values()) {
            if (!foundEmulators.contains(emulator)) {
                ButtonType btnType = new ButtonType(emulator.getDisplayName(), ButtonBar.ButtonData.OK_DONE);
                buttons.add(btnType);
                buttonMap.put(btnType, emulator);
            }
        }
        
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        buttons.add(cancelButton);

        alert.getButtonTypes().setAll(buttons);

        // Keep it compact but styled
        for (ButtonType btnType : buttons) {
            if (btnType != cancelButton) {
                Button btn = (Button) alert.getDialogPane().lookupButton(btnType);
                EmulatorType emulator = buttonMap.get(btnType);
                if (foundEmulators.contains(emulator)) {
                    btn.setStyle("-fx-font-weight: bold; -fx-text-fill: #2e7d32; -fx-border-color: #4caf50; -fx-border-radius: 3; -fx-background-color: #e8f5e9;");
                }
            }
        }

        Optional<ButtonType> result = alert.showAndWait();

        if (result.isPresent() && result.get() != cancelButton) {
            return buttonMap.get(result.get());
        }

        showErrorAndExit("No emulator selected. The application will close.");
        return null;
    }

    private void selectEmulatorManually(EmulatorType selectedEmulator) { /* internal */
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Emulator Executable for " + selectedEmulator.getDisplayName());

        FileChooser.ExtensionFilter exeFilter = new FileChooser.ExtensionFilter(selectedEmulator.getDisplayName() + " Executable (" + selectedEmulator.getExecutableName() + ")", selectedEmulator.getExecutableName());
        fileChooser.getExtensionFilters().add(exeFilter);
        fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));

        File selectedFile = fileChooser.showOpenDialog(stage);

        if (null != selectedFile) {
            if (selectedFile.getName().equals(selectedEmulator.getExecutableName())) {
                ScheduleService.obtain().persistEmulatorPath(selectedEmulator.getConfigKey(), selectedFile.getParent());
                ScheduleService.obtain().persistEmulatorPath(ConfigurationKeyEnum.CURRENT_EMULATOR_STRING.name(),
                        selectedEmulator.name());
                return;
            }
            showErrorAndExit("Invalid emulator file selected. Please select a valid emulator executable.");
        } else {
            showErrorAndExit("No emulator selected. The application will close.");
        }
    }

    private void showErrorAndExit(String message) { /* internal */
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("ERROR");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
        ApplicationLifecycle.exitNormally(0);
    }

    private void initializeDiscordBot() { /* internal */
        // ServDiscord.getServices();
    }

    private void initializeLogModule() { /* internal */
        actionController = new LauncherActionController(this);
        consoleLogLayoutController = new ConsoleLogLayoutController();
    }

    private void initializeProfileModule() { /* internal */
        profileManagerLayoutController = new ProfileManagerLayoutController();
        actionController.setProfileManagerController(profileManagerLayoutController);
    }

    // ==================== PINNED MODULES ====================

    private void initializePinnedModules() { /* internal */
        // Control tab: Logs + Profiles + Task Manager + Custom Tasks
        ConsoleLogLayoutController logsCtrl = consoleLogLayoutController;
        ProfileManagerLayoutController profilesCtrl = profileManagerLayoutController;
        TaskManagerLayoutController taskCtrl = new TaskManagerLayoutController();
        CustomTasksLayoutController customTasksCtrl = new CustomTasksLayoutController();

        Parent logsPane = loadNode("ConsoleLogLayout", logsCtrl);
        Parent profilesPane = loadNode("ProfileManagerLayout", profilesCtrl);
        Parent taskPane = loadNode("TaskManagerLayout", taskCtrl);
        Parent customTasksPane = loadNode("CustomTasksLayout", customTasksCtrl);

        // Show Logs by default on startup
        setMainContent(logsPane);

        TabPane controlTabs = new TabPane();
        controlTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        controlTabs.getTabs().addAll(
                makeTab("Logs", logsPane),
                makeTab("Profiles", profilesPane),
                makeTab("Tasks", taskPane),
                makeTab("Custom Tasks", customTasksPane)
        );
        controlTabs.setMaxWidth(Double.MAX_VALUE);
        controlTabs.setMaxHeight(Double.MAX_VALUE);

        addPinnedButton("Control", MaterialDesignC.CONTROLLER_CLASSIC, controlTabs);

        // Config pinned button with application-wide settings tabs.
        EmuConfigLayoutController configCtrl = new EmuConfigLayoutController();
        Parent configPane = loadNode("EmuConfigLayout", configCtrl);

        TelegramLayoutController telegramCtrl = new TelegramLayoutController();
        Parent telegramPane = loadNode("TelegramLayout", telegramCtrl);

        UpdateLayoutController updateCtrl = new UpdateLayoutController();
        Parent updatePane = loadNode("UpdateLayout", updateCtrl);

        TabPane configTabs = new TabPane();
        configTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        configTabs.getTabs().addAll(
                makeTab("Emulators", configPane),
                makeTab("Telegram", telegramPane),
                makeTab("Updates", updatePane)
        );
        configTabs.setMaxWidth(Double.MAX_VALUE);
        configTabs.setMaxHeight(Double.MAX_VALUE);

        addPinnedButton("Config", MaterialDesignC.COG_OUTLINE, configTabs);
    }

    private Tab makeTab(String title, Parent content) { /* internal */
        Tab tab = new Tab(title, content);
        return tab;
    }

    private void setMainContent(Parent content) { /* internal */
        mainContentPane.getChildren().clear();
        AnchorPane.setTopAnchor(content, 0.0);
        AnchorPane.setBottomAnchor(content, 0.0);
        AnchorPane.setLeftAnchor(content, 0.0);
        AnchorPane.setRightAnchor(content, 0.0);
        mainContentPane.getChildren().add(content);
    }

    private void initializeProfileComboBox() { /* internal */
        configureComboCells();

        profileComboBox.setOnAction(event -> {
            if (!updatingComboBox) {
                ProfileAux selectedProfile = profileComboBox.getSelectionModel().getSelectedItem();
                if (null != selectedProfile) {
                    actionController.selectProfile(selectedProfile);
                }
            }
        });

        if (null != profileManagerLayoutController) {
            profileManagerLayoutController.addProfileLoadListener(new IProfileLoadListener() {
                @Override
                public void onProfileLoad(ProfileAux profile) { /* bind */
                    Platform.runLater(() -> {
                        actionController.updateProfileComboBox();
                    });
                }
            });
        }

        Platform.runLater(() -> {
            actionController.loadProfilesIntoComboBox();
        });
    }

    public void updateComboBoxItems(javafx.collections.ObservableList<ProfileAux> profiles) { /* bind */
        updatingComboBox = true;
        profileComboBox.getItems().clear();
        profileComboBox.getItems().addAll(profiles);
        configureComboCells();
        updatingComboBox = false;
    }

    private void configureComboCells() { /* internal */
        profileComboBox.setCellFactory(listView -> new ListCell<ProfileAux>() {
            @Override
            protected void updateItem(ProfileAux profile, boolean empty) {
                super.updateItem(profile, empty);
                if (empty || profile == null) {
                    setText(null);
                } else {
                    setText(profile.getName() + " (Emulator: " + profile.getEmulatorNumber() + ")");
                }
            }
        });

        profileComboBox.setButtonCell(new ListCell<ProfileAux>() {
            @Override
            protected void updateItem(ProfileAux profile, boolean empty) {
                super.updateItem(profile, empty);
                if (empty || profile == null) {
                    setText(null);
                } else {
                    setText(profile.getName() + " (Emulator: " + profile.getEmulatorNumber() + ")");
                }
            }
        });
    }

    public ProfileAux getSelectedProfile() { /* bind */
        return profileComboBox.getSelectionModel().getSelectedItem();
    }

    public void selectProfileInComboBox(ProfileAux profile) { /* bind */
        updatingComboBox = true;
        profileComboBox.getSelectionModel().select(profile);
        updatingComboBox = false;
    }

    public void refreshProfileComboBox() { /* bind */
        actionController.refreshProfileComboBox();
    }

    private void initializeExternalLibraries() { /* internal */
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void initializeModules() { /* internal */
        //@formatter:off
        List<ModuleDefinition> modules = Arrays.asList(
                new ModuleDefinition("DummyLayout",               "Dummy Task",           MaterialDesignD.DATABASE_REMOVE_OUTLINE,    DummyLayoutController::new),
                new ModuleDefinition("CityUpgradesLayout",        "City Upgrades",        MaterialDesignC.CITY_VARIANT_OUTLINE,       CityUpgradesLayoutController::new),
                new ModuleDefinition("CityEventsLayout",         "City Events",          MaterialDesignC.CALENDAR_OUTLINE,           CityEventsLayoutController::new),
                new ModuleDefinition("CityEventsExtraLayout",    "Extra City Events",    MaterialDesignC.CALENDAR_PLUS,              CityEventsExtraLayoutController::new),
                new ModuleDefinition("PolarTerrorLayout",        "Rally",                MaterialDesignF.FLAG_OUTLINE,               PolarTerrorLayoutController::new),
                new ModuleDefinition("ShopLayout",               "Shop",                 MaterialDesignS.STORE_OUTLINE,              ShopLayoutController::new),
                new ModuleDefinition("GatherLayout",             "Gather",               MaterialDesignP.PACKAGE_VARIANT,            GatherLayoutController::new),
                new ModuleDefinition("IntelLayout",              "Intel",                MaterialDesignB.BINOCULARS,                 IntelLayoutController::new),
                new ModuleDefinition("AllianceLayout",           "Alliance",             MaterialDesignA.ACCOUNT_GROUP_OUTLINE,      AllianceLayoutController::new),
                new ModuleDefinition("AllianceChampionshipLayout","Alliance Championship",MaterialDesignT.TROPHY_OUTLINE,            AllianceChampionshipLayoutController::new),
                new ModuleDefinition("AllianceShop",             "Alliance Shop",        MaterialDesignS.SHOPPING_OUTLINE,           AllianceShopController::new),
                new ModuleDefinition("AllianceMobilizationLayout","Alliance Mobilization",MaterialDesignA.ALARM_LIGHT_OUTLINE,       MobilizationLayoutController::new),
                new ModuleDefinition("BearTrapLayout",           "Bear Trap",            MaterialDesignT.TOOLS,                      BearTrapLayoutController::new),
                new ModuleDefinition("BeastHuntingLayout",       "Beast Hunting",        MaterialDesignP.PAW_OUTLINE,                BeastHuntingLayoutController::new),
                new ModuleDefinition("FishingLayout",            "Fishing Tournament",   MaterialDesignF.FISH,                       FishingLayoutController::new),
                new ModuleDefinition("TrainingLayout",           "Training",             MaterialDesignS.SWORD_CROSS,                TrainingLayoutController::new),
                new ModuleDefinition("ResearchLayout",           "Research",             MaterialDesignF.FLASK_OUTLINE,              ResearchLayoutController::new),
                new ModuleDefinition("PetsLayout",               "Pets",                 MaterialDesignP.PAW,                        PetsLayoutController::new),
                new ModuleDefinition("EventsLayout",             "Events",               MaterialDesignC.CALENDAR_STAR,              EventsLayoutController::new),
                new ModuleDefinition("ExpertsLayout",            "Experts",              MaterialDesignA.ACCOUNT_STAR_OUTLINE,       ExpertsLayoutController::new),
                new ModuleDefinition("ChiefOrderLayout",         "Chief Order",          MaterialDesignC.CROWN_OUTLINE,              ChiefOrderLayoutController::new),
                new ModuleDefinition("GiftcodeLayout",           "Get Giftcodes",        MaterialDesignG.GIFT_OUTLINE,               GiftcodeLayoutController::new),
                new ModuleDefinition("DebuggingLayout",          "Debugging",            MaterialDesignB.BUG_OUTLINE,                DebuggingLayoutController::new),
                new ModuleDefinition("TaskBuilderLayout",        "Task Builder",         MaterialDesignW.WRENCH_OUTLINE,             TaskBuilderLayoutController::new),

                new ModuleDefinition("SkipTutorialLayout",       "Skip Tutorial",        MaterialDesignS.SKIP_NEXT_OUTLINE,          SkipTutorialLayoutController::new),
                new ModuleDefinition("CharacterLayout",          "Character",            MaterialDesignA.ACCOUNT_OUTLINE,            CharacterLayoutController::new),
                new ModuleDefinition("GameAnalyticsLayout",      "Game Data",            MaterialDesignV.VIEW_LIST_OUTLINE,          GameAnalyticsLayoutController::new),
                new ModuleDefinition("StatisticsLayout",         "Statistics",           MaterialDesignV.VIEW_DASHBOARD_OUTLINE,     StatisticsLayoutController::new)
        );
        //@formatter:on

        for (ModuleDefinition module : modules) {
            consoleLogLayoutController.appendMessage(
                    new LogMessageData(TpMessageSeverityEnum.INFO, "Loading module: " + module.buttonTitle(), "-", "-"));

            Object controller = module.createController(profileManagerLayoutController);
            moduleControllers.put(module.buttonTitle(), controller);
            addButton(module.fxmlName(), module.buttonTitle(), module.icon(), controller);

            if (controller instanceof IProfileLoadListener) {
                profileManagerLayoutController.addProfileLoadListener((IProfileLoadListener) controller);
            }
        }
        profileManagerLayoutController.addProfileLoadListener(this);
    }

    @Override
    public void onProfileLoad(ProfileAux profile) { /* bind */
        this.currentProfile = profile;
        updateWindowTitle();
        selectProfileInComboBox(profile);
        refreshPauseMenuItems();
    }

    @Override
    public void onEnergyLevelChanged(Long profileId, int newStamina) { /* bind */
        if (currentProfile != null && currentProfile.getId().equals(profileId)) {
            updateWindowTitle();
        }
    }

    private void updateWindowTitle() { /* internal */
        if (null == currentProfile) {
            return;
        }

        String version = getVersion();
        int stamina = StaminaService.getServices().getCurrentStamina(currentProfile.getId());
        String title = formatWindowTitle(WorkspacePaths.current().channel(), version,
                currentProfile.getName(), stamina);

        Platform.runLater(() -> {
            stage.setTitle(title);
            if (null != labelWindowTitle) {
                labelWindowTitle.setText(title);
            }
        });
    }

    static String formatWindowTitle(RuntimeChannel channel, String version, String profileName, int stamina) {
        return String.format("%s v%s - %s [Stamina: %d]",
                channel.productName(), version, profileName, stamina);
    }

    public void onEngineStateTransition(BotStateData botState) { /* bind */
        if (null != botState) {
            if (botState.getRunning()) {
                cancelAutoStart();
                if (botState.getPaused() != null && botState.getPaused()) {
                    buttonStartStop.setText("Stop");
                    buttonStartStop.setDisable(false);
                    allQueuesPaused = true;
                    buttonPauseResume.setDisable(false);
                    estado = true;
                    updatePauseButtonState();
                    refreshPauseMenuItems();
                } else {
                    buttonStartStop.setText("Stop");
                    buttonStartStop.setDisable(false);
                    allQueuesPaused = false;
                    buttonPauseResume.setDisable(false);
                    estado = true;
                    updatePauseButtonState();
                    refreshPauseMenuItems();
                }
            } else {
                buttonStartStop.setText("Start Bot");
                buttonStartStop.setDisable(false);
                buttonPauseResume.setDisable(true);
                resetPauseStates();
                estado = false;
                isStartup = false;
                scheduleAutoStart();
            }
        }
    }

    public void onQueueStateChanged(QueueStateData queueState) { /* bind */
        // Changed by pernerch | Date: 2026-07-02 | Why: queue-state callbacks can originate from
        // worker threads; marshal to JavaFX thread to prevent UI-thread violations during bot start.
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> onQueueStateChanged(queueState));
            return;
        }

        if (null == queueState) {
            return;
        }

        // Changed by pernerch | Date: 2026-07-02 | Why: sync launcher profile/title with
        // the queue owner when runtime execution switches to another profile.
		if (null != queueState.getProfileId()) {
			refreshActiveProfileFromQueueState(queueState.getProfileId());
		}

        if (null != queueState.getActiveQueues()) {
            updateActiveQueueStates(queueState.getActiveQueues());
        }

        if (null == queueState.getProfileId()) {
            activeQueueStates.values().forEach(state -> state.setPaused(queueState.isPaused()));
        } else {
            QueueProfileStateData profileState = activeQueueStates.get(queueState.getProfileId());
            if (null != profileState) {
                profileState.setPaused(queueState.isPaused());
            }
        }

        updateAggregatedPauseStates();

        if (estado && (!activeQueueStates.isEmpty() || queueState.getProfileId() != null)) {
            buttonPauseResume.setDisable(false);
        }

        refreshPauseMenuItems();
        updatePauseButtonState();
    }

    // Changed by pernerch | Date: 2026-07-02 | Why: keep UI profile identity and title in sync
    // with engine-side profile switches on shared emulators.
    private void refreshActiveProfileFromQueueState(Long profileId) { /* internal */
        if (profileId == null) {
            return;
        }

        ProfileAux activeProfile = findProfileById(profileId);
        if (activeProfile == null) {
            return;
        }

        boolean profileChanged = currentProfile == null
                || !profileId.equals(currentProfile.getId())
                || !Objects.equals(currentProfile.getName(), activeProfile.getName());
        if (!profileChanged) {
            return;
        }

        // Changed by pernerch | Date: 2026-07-02 | Why: keep launcher title/profile context
        // aligned with the profile that currently owns emulator execution.
        currentProfile = activeProfile;
        updateWindowTitle();
        selectProfileInComboBox(activeProfile);
    }

    // ==================== CUSTOM TITLE BAR EVENT HANDLERS ====================

    private static final int SNAP_THRESHOLD = 8; // pixels from screen edge to trigger snap

    @FXML
    private void handleTitleBarMousePressed(javafx.scene.input.MouseEvent event) { /* internal */
        xOffset = event.getSceneX();
        yOffset = event.getSceneY();
    }

    @FXML
    private void handleTitleBarMouseDragged(javafx.scene.input.MouseEvent event) { /* internal */
        if (windowsWindowManager != null) {
            return;
        }

        // If currently maximized/snapped, un-snap first, then start dragging
        if (isCustomMaximized) {
            double oldW = stage.getWidth();
            isCustomMaximized = false;
            btnMaximize.setText("☐");
            stage.setMaximized(false);
            // Reposition so the cursor stays proportionally in the restored window
            double ratio = xOffset / oldW;
            stage.setWidth(restoreW);
            stage.setHeight(restoreH);
            xOffset = restoreW * ratio;
            stage.setX(event.getScreenX() - xOffset);
            stage.setY(event.getScreenY() - yOffset);
            hideSnapPreview();
            return;
        }
        stage.setX(event.getScreenX() - xOffset);
        stage.setY(event.getScreenY() - yOffset);

        // Show/update snap preview overlay
        double screenX = event.getScreenX();
        double screenY = event.getScreenY();
        javafx.geometry.Rectangle2D bounds = getScreenBoundsForPoint(screenX, screenY);
        if (null != bounds) {
            boolean isLeft = screenX <= bounds.getMinX() + SNAP_THRESHOLD;
            boolean isRight = screenX >= bounds.getMaxX() - SNAP_THRESHOLD;
            boolean isTop = screenY <= bounds.getMinY() + SNAP_THRESHOLD;
            boolean isBottom = screenY >= bounds.getMaxY() - SNAP_THRESHOLD;

            if (isTop && isLeft) {
                showSnapPreview(bounds.getMinX(), bounds.getMinY(), bounds.getWidth() / 2, bounds.getHeight() / 2);
            } else if (isTop && isRight) {
                showSnapPreview(bounds.getMinX() + bounds.getWidth() / 2, bounds.getMinY(), bounds.getWidth() / 2, bounds.getHeight() / 2);
            } else if (isBottom && isLeft) {
                showSnapPreview(bounds.getMinX(), bounds.getMinY() + bounds.getHeight() / 2, bounds.getWidth() / 2, bounds.getHeight() / 2);
            } else if (isBottom && isRight) {
                showSnapPreview(bounds.getMinX() + bounds.getWidth() / 2, bounds.getMinY() + bounds.getHeight() / 2, bounds.getWidth() / 2, bounds.getHeight() / 2);
            } else if (isTop) {
                showSnapPreview(bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight());
            } else if (isLeft) {
                showSnapPreview(bounds.getMinX(), bounds.getMinY(), bounds.getWidth() / 2, bounds.getHeight());
            } else if (isRight) {
                showSnapPreview(bounds.getMinX() + bounds.getWidth() / 2, bounds.getMinY(), bounds.getWidth() / 2, bounds.getHeight());
            } else {
                hideSnapPreview();
            }
        }
    }

    @FXML
    private void handleTitleBarMouseReleased(javafx.scene.input.MouseEvent event) { /* internal */
        if (windowsWindowManager != null) return;

        hideSnapPreview();
        if (isCustomMaximized) return; // already snapped, don't re-snap
        double screenX = event.getScreenX();
        double screenY = event.getScreenY();
        javafx.geometry.Rectangle2D screenBounds = getScreenBoundsForPoint(screenX, screenY);
        if (null == screenBounds) return;

        // Save restore bounds before snapping
        restoreX = stage.getX();
        restoreY = stage.getY();
        restoreW = stage.getWidth();
        restoreH = stage.getHeight();

        // Snap to top edge = maximize to visual bounds
        boolean isLeft = screenX <= screenBounds.getMinX() + SNAP_THRESHOLD;
        boolean isRight = screenX >= screenBounds.getMaxX() - SNAP_THRESHOLD;
        boolean isTop = screenY <= screenBounds.getMinY() + SNAP_THRESHOLD;
        boolean isBottom = screenY >= screenBounds.getMaxY() - SNAP_THRESHOLD;

        if (isTop && isLeft) {
            snapToTopLeft(screenBounds);
        } else if (isTop && isRight) {
            snapToTopRight(screenBounds);
        } else if (isBottom && isLeft) {
            snapToBottomLeft(screenBounds);
        } else if (isBottom && isRight) {
            snapToBottomRight(screenBounds);
        } else if (isTop) {
            snapToFull(screenBounds);
        } else if (isLeft) {
            snapToLeft(screenBounds);
        } else if (isRight) {
            snapToRight(screenBounds);
        }
    }

    /** Show a semi-transparent blue snap preview at the given position/size. */
    private void showSnapPreview(double x, double y, double w, double h) { /* internal */
        if (null == snapPreviewStage) {
            snapPreviewStage = new javafx.stage.Stage();
            snapPreviewStage.initStyle(javafx.stage.StageStyle.TRANSPARENT);
            snapPreviewStage.initOwner(stage);
            snapPreviewStage.setAlwaysOnTop(true);

            javafx.scene.layout.Region previewPane = new javafx.scene.layout.Region();
            previewPane.setStyle(
                "-fx-background-color: rgba(0, 120, 215, 0.3);" +
                "-fx-border-color: rgba(0, 120, 215, 0.8);" +
                "-fx-border-width: 2;" +
                "-fx-border-radius: 8;" +
                "-fx-background-radius: 8;"
            );
            javafx.scene.Scene previewScene = new javafx.scene.Scene(previewPane, w, h);
            previewScene.setFill(javafx.scene.paint.Color.TRANSPARENT);
            snapPreviewStage.setScene(previewScene);
        }
        snapPreviewStage.setX(x);
        snapPreviewStage.setY(y);
        snapPreviewStage.setWidth(w);
        snapPreviewStage.setHeight(h);
        if (!snapPreviewStage.isShowing()) {
            snapPreviewStage.show();
        }
        // Ensure main window stays focused
        stage.requestFocus();
    }

    /** Hide and clean up the snap preview overlay. */
    private void hideSnapPreview() { /* internal */
        if (snapPreviewStage != null && snapPreviewStage.isShowing()) {
            snapPreviewStage.hide();
        }
    }

    @FXML
    private void handleTitleBarMouseClicked(javafx.scene.input.MouseEvent event) { /* internal */
        if (event.getClickCount() == 2 && !isWindowControl(event.getTarget())) {
            handleMaximize(null);
        }
    }

    private boolean isWindowControl(Object target) {
        Node node = target instanceof Node ? (Node) target : null;
        while (node != null && node != titleBar) {
            if (node instanceof ButtonBase) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    /** Snap window to fill the entire visual bounds (respects taskbar). */
    private void snapToFull(javafx.geometry.Rectangle2D bounds) { /* internal */
        stage.setMaximized(true);
        isCustomMaximized = true;
    }

    /** Snap window to fill the left half of the screen. */
    private void snapToLeft(javafx.geometry.Rectangle2D bounds) { /* internal */
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        stage.setWidth(bounds.getWidth() / 2);
        stage.setHeight(bounds.getHeight());
        isCustomMaximized = true;
        btnMaximize.setText("❐");
    }

    /** Snap window to fill the right half of the screen. */
    private void snapToRight(javafx.geometry.Rectangle2D bounds) { /* internal */
        stage.setX(bounds.getMinX() + bounds.getWidth() / 2);
        stage.setY(bounds.getMinY());
        stage.setWidth(bounds.getWidth() / 2);
        stage.setHeight(bounds.getHeight());
        isCustomMaximized = true;
        btnMaximize.setText("❐");
    }

    /** Snap window to fill the top-left quarter of the screen. */
    private void snapToTopLeft(javafx.geometry.Rectangle2D bounds) { /* internal */
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        stage.setWidth(bounds.getWidth() / 2);
        stage.setHeight(bounds.getHeight() / 2);
        isCustomMaximized = true;
        btnMaximize.setText("❐");
    }

    /** Snap window to fill the top-right quarter of the screen. */
    private void snapToTopRight(javafx.geometry.Rectangle2D bounds) { /* internal */
        stage.setX(bounds.getMinX() + bounds.getWidth() / 2);
        stage.setY(bounds.getMinY());
        stage.setWidth(bounds.getWidth() / 2);
        stage.setHeight(bounds.getHeight() / 2);
        isCustomMaximized = true;
        btnMaximize.setText("❐");
    }

    /** Snap window to fill the bottom-left quarter of the screen. */
    private void snapToBottomLeft(javafx.geometry.Rectangle2D bounds) { /* internal */
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY() + bounds.getHeight() / 2);
        stage.setWidth(bounds.getWidth() / 2);
        stage.setHeight(bounds.getHeight() / 2);
        isCustomMaximized = true;
        btnMaximize.setText("❐");
    }

    /** Snap window to fill the bottom-right quarter of the screen. */
    private void snapToBottomRight(javafx.geometry.Rectangle2D bounds) { /* internal */
        stage.setX(bounds.getMinX() + bounds.getWidth() / 2);
        stage.setY(bounds.getMinY() + bounds.getHeight() / 2);
        stage.setWidth(bounds.getWidth() / 2);
        stage.setHeight(bounds.getHeight() / 2);
        isCustomMaximized = true;
        btnMaximize.setText("❐");
    }

    /** Find which screen contains the given point to get its visual bounds. */
    private javafx.geometry.Rectangle2D getScreenBoundsForPoint(double x, double y) {
        for (javafx.stage.Screen screen : javafx.stage.Screen.getScreens()) {
            javafx.geometry.Rectangle2D bounds = screen.getVisualBounds();
            if (x >= bounds.getMinX() && x <= bounds.getMaxX() &&
                y >= bounds.getMinY() && y <= bounds.getMaxY()) {
                return bounds;
            }
        }
        // Fallback to primary screen
        return javafx.stage.Screen.getPrimary().getVisualBounds();
    }

    @FXML
    private void handleMinimize(ActionEvent event) { /* internal */
        stage.setIconified(true);
    }

    @FXML
    private void handleMaximize(ActionEvent event) { /* internal */
        if (windowsWindowManager != null) {
            stage.setMaximized(!stage.isMaximized());
            return;
        }

        if (isCustomMaximized) {
            // Restore from snap/maximize
            isCustomMaximized = false;
            stage.setMaximized(false);
            stage.setX(restoreX);
            stage.setY(restoreY);
            stage.setWidth(restoreW);
            stage.setHeight(restoreH);
            btnMaximize.setText("☐");
        } else {
            // Save current bounds for restore
            restoreX = stage.getX();
            restoreY = stage.getY();
            restoreW = stage.getWidth();
            restoreH = stage.getHeight();
            javafx.geometry.Rectangle2D bounds = getScreenBoundsForPoint(
                stage.getX() + stage.getWidth() / 2,
                stage.getY() + stage.getHeight() / 2
            );
            snapToFull(bounds);
        }
    }

    private void updateMaximizeButton(boolean maximized) {
        if (btnMaximize != null) {
            btnMaximize.setText(maximized ? "❐" : "☐");
        }
    }

    @FXML
    private void handleClose(ActionEvent event) { /* internal */
        stage.getOnCloseRequest().handle(null);
        stage.close();
    }

    @FXML
    public void handleButtonStartStop(ActionEvent event) { /* bind */
        cancelAutoStart();
        Thread startStopThread = Thread.ofVirtual().unstarted(() -> {
            if (!estado) {
                Platform.runLater(() -> {
                    buttonStartStop.setText("Starting...");
                    buttonStartStop.setDisable(true);
                });
                actionController.startBot();
            } else {
                Platform.runLater(() -> {
                    buttonStartStop.setText("Stopping...");
                    buttonStartStop.setDisable(true);
                    buttonPauseResume.setDisable(true);
                });
                isStartup = false;
                try {
                    actionController.stopBot();
                } catch (ScheduleService.IncompleteTaskShutdownException exception) {
                    Platform.runLater(() -> showIncompleteStopWarning(exception.getMessage()));
                }
            }
        });
        startStopThread.setName("Start-Stop-Thread");
        startStopThread.start();
    }

    private void showIncompleteStopWarning(String detail) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Bot stop incomplete");
        alert.setHeaderText("One or more tasks are still stopping");
        alert.setContentText(detail + "\n\nThe queues remain tracked and Frostguard will not start replacements. "
                + "Try Stop again after the blocking operation returns.");
        alert.showAndWait();
    }

    public void forceStartBot() { /* bind */
        if (!estado) {
            cancelAutoStart();
            Thread startStopThread = Thread.ofVirtual().unstarted(() -> {
                Platform.runLater(() -> {
                    buttonStartStop.setText("Starting...");
                    buttonStartStop.setDisable(true);
                });
                actionController.startBot();
            });
            startStopThread.setName("Force-Start-Thread");
            startStopThread.start();
        }
    }

    @FXML
    public void handleButtonPauseResume(ActionEvent event) { /* bind */
        toggleAllQueues();
    }

    @FXML
    private void handleToggleCurrentQueue(ActionEvent event) { /* internal */
        toggleCurrentQueue();
    }

    @FXML
    private void handleToggleAllQueues(ActionEvent event) { /* internal */
        toggleAllQueues();
    }

    private void handleToggleSpecificQueue(Long profileId) { /* internal */
        toggleSpecificQueue(profileId, true);
    }

    private void toggleAllQueues() { /* internal */
        if (!estado) return;
        if (!allQueuesPaused) {
            actionController.pauseAllQueues();
            setAllQueuesPausedLocally(true);
        } else {
            actionController.resumeAllQueues();
            setAllQueuesPausedLocally(false);
        }
    }

    private void toggleCurrentQueue() { /* internal */
        if (!estado) return;
        ProfileAux selectedProfile = currentProfile != null ? currentProfile : getSelectedProfile();
        if (null == selectedProfile) {
            showProfileSelectionWarning();
            return;
        }
        toggleSpecificQueue(selectedProfile.getId(), true);
    }

    private void toggleSpecificQueue(Long profileId, boolean showWarnings) { /* internal */
        if (!estado) return;
        if (null == profileId) {
            if (showWarnings) showQueueUnavailableWarning();
            return;
        }
        QueueProfileStateData targetState = activeQueueStates.get(profileId);
        if (null == targetState) {
            if (showWarnings) showQueueUnavailableWarning();
            return;
        }
        ProfileAux targetProfile = findProfileById(profileId);
        if (null == targetProfile) {
            if (showWarnings) showQueueUnavailableWarning();
            return;
        }
        if (!targetState.isPaused()) {
            actionController.pauseQueue(targetProfile);
            setQueuePausedLocally(profileId, true);
        } else {
            actionController.resumeQueue(targetProfile);
            setQueuePausedLocally(profileId, false);
        }
    }

    private void configurePauseMenu() { /* internal */
        refreshPauseMenuItems();
        updatePauseButtonState();
    }

    private void updateActiveQueueStates(List<QueueProfileStateData> queueProfiles) { /* internal */
        activeQueueStates.clear();
        if (null == queueProfiles) return;
        queueProfiles.stream()
                .filter(state -> state != null && state.getProfileId() != null)
                .forEach(state -> activeQueueStates.put(state.getProfileId(),
                        new QueueProfileStateData(state.getProfileId(), state.getProfileName(), state.isPaused())));
    }

    private void updateAggregatedPauseStates() { /* internal */
        if (activeQueueStates.isEmpty()) {
            allQueuesPaused = false;
        } else {
            allQueuesPaused = activeQueueStates.values().stream().allMatch(QueueProfileStateData::isPaused);
        }
    }

    private void refreshPauseMenuItems() { /* internal */
        if (null == buttonPauseResume) return;
        List<MenuItem> items = new ArrayList<>();
        if (null != menuToggleAllQueues) {
            menuToggleAllQueues.setText(allQueuesPaused ? "Resume" : "Pause");
            menuToggleAllQueues.setDisable(!estado);
            items.add(menuToggleAllQueues);
        }
        List<QueueProfileStateData> queueStates = new ArrayList<>(activeQueueStates.values());
        queueStates.sort(Comparator.comparing(QueueProfileStateData::getProfileName, String.CASE_INSENSITIVE_ORDER));
        if (!queueStates.isEmpty()) {
            items.add(new SeparatorMenuItem());
            for (QueueProfileStateData state : queueStates) {
                items.add(createQueueMenuItem(state));
            }
        }
        buttonPauseResume.getItems().setAll(items);
    }

    private MenuItem createQueueMenuItem(QueueProfileStateData state) { /* internal */
        MenuItem item = new MenuItem(formatQueueMenuItemLabel(state));
        item.setOnAction(event -> handleToggleSpecificQueue(state.getProfileId()));
        item.setDisable(!estado);
        return item;
    }

    private String formatQueueMenuItemLabel(QueueProfileStateData state) { /* internal */
        if (null == state) return "Toggle queue";
        String profileName = state.getProfileName() != null ? state.getProfileName() : String.valueOf(state.getProfileId());
        return (state.isPaused() ? "Resume " : "Pause ") + profileName;
    }

    private void updatePauseButtonState() { /* internal */
        if (null == buttonPauseResume) return;
        buttonPauseResume.setText(allQueuesPaused ? "Resume All Queues" : "Pause All Queues");
    }

    private void resetPauseStates() { /* internal */
        allQueuesPaused = false;
        activeQueueStates.clear();
        refreshPauseMenuItems();
        updatePauseButtonState();
    }

    public void scheduleAutoStart() { /* bind */
        cancelAutoStart();
        HashMap<String, String> globalConfig = ConfigService.obtain().loadGlobalSettings();
        if (null == globalConfig) return;
        boolean autoStartEnabled = Boolean.parseBoolean(
                globalConfig.getOrDefault(ConfigurationKeyEnum.AUTO_START_ENABLED_BOOL.name(), "false"));
        if (!autoStartEnabled) return;

        String autoStartMode = globalConfig.getOrDefault(ConfigurationKeyEnum.AUTO_START_MODE_STRING.name(), "Continuous");
        if ("Startup Only".equalsIgnoreCase(autoStartMode) && !isStartup) return;

        int delayMinutes;
        try {
            delayMinutes = Integer.parseInt(
                    globalConfig.getOrDefault(ConfigurationKeyEnum.AUTO_START_DELAY_MINUTES_INT.name(), "5"));
        } catch (NumberFormatException e) {
            delayMinutes = 5;
        }
        if (delayMinutes <= 0) return;

        autoStartSecondsRemaining = delayMinutes * 60;
        autoStartTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            autoStartSecondsRemaining--;
            int mins = autoStartSecondsRemaining / 60;
            int secs = autoStartSecondsRemaining % 60;
            buttonStartStop.setText(String.format("Start (%02d:%02d)", mins, secs));
            if (autoStartSecondsRemaining <= 0) {
                cancelAutoStart();
                Thread autoStartThread = Thread.ofVirtual().unstarted(() -> {
                    Platform.runLater(() -> {
                        buttonStartStop.setText("Starting...");
                        buttonStartStop.setDisable(true);
                    });
                    actionController.startBot();
                });
                autoStartThread.setName("Auto-Start-Thread");
                autoStartThread.start();
            }
        }));
        autoStartTimeline.setCycleCount(Timeline.INDEFINITE);
        autoStartTimeline.play();
    }

    private void cancelAutoStart() { /* internal */
        if (null != autoStartTimeline) {
            autoStartTimeline.stop();
            autoStartTimeline = null;
            buttonStartStop.setText("Start Bot");
        }
    }

    private void showProfileSelectionWarning() { /* internal */
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Profile selection required");
        alert.setHeaderText(null);
        alert.setContentText("Please select a profile to control its queue.");
        alert.showAndWait();
    }

    private void showQueueUnavailableWarning() { /* internal */
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Queue not available");
        alert.setHeaderText(null);
        alert.setContentText("The selected queue is not currently running.");
        alert.showAndWait();
    }

    private ProfileAux findProfileById(Long profileId) { /* internal */
        if (profileId == null || profileComboBox == null) return null;
        for (ProfileAux profile : profileComboBox.getItems()) {
            if (profile != null && profileId.equals(profile.getId())) return profile;
        }
        return null;
    }

    private void setAllQueuesPausedLocally(boolean paused) { /* internal */
        activeQueueStates.values().forEach(state -> state.setPaused(paused));
        updateAggregatedPauseStates();
        refreshPauseMenuItems();
        updatePauseButtonState();
    }

    private void setQueuePausedLocally(Long profileId, boolean paused) { /* internal */
        if (null == profileId) return;
        QueueProfileStateData state = activeQueueStates.get(profileId);
        if (null != state) state.setPaused(paused);
        updateAggregatedPauseStates();
        refreshPauseMenuItems();
        updatePauseButtonState();
    }

    // ==================== BUTTON FACTORY ====================

    private Button createAndConfigureButton(String title, Ikon icon, Parent root) { /* internal */
        FontIcon btnIcon = new FontIcon(icon);
        btnIcon.setIconSize(16);
        btnIcon.getStyleClass().add("nav-button-icon");

        Label btnLabel = new Label(title);
        btnLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btnLabel, Priority.ALWAYS);

        HBox content = new HBox(10, btnIcon, btnLabel);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setMaxWidth(Double.MAX_VALUE);

        Button button = new Button();
        button.setGraphic(content);
        button.setMaxWidth(Double.MAX_VALUE);
        button.getStyleClass().add("nav-button");

        button.setOnAction(e -> {
            hideSearchOverlay();
            setMainContent(root);
            // Deselect all buttons in both containers
            for (Node node : buttonsContainer.getChildren()) {
                if (node instanceof Button) node.getStyleClass().remove("active");
            }
            if (null != pinnedButtonsContainer) {
                for (Node node : pinnedButtonsContainer.getChildren()) {
                    if (node instanceof Button) node.getStyleClass().remove("active");
                }
            }
            button.getStyleClass().add("active");
        });
        return button;
    }

    private Button addButton(String fxmlName, String title, Ikon icon, Object controller) { /* internal */
        Parent root = loadNode(fxmlName, controller);
        Button button = createAndConfigureButton(title, icon, root);
        buttonsContainer.getChildren().add(button);
        Map<ConfigurationKeyEnum, String> configs = new HashMap<>();
        if (controller instanceof AbstractProfileController) {
            configs = ((AbstractProfileController) controller).getRegisteredSettings();
        }
        quickNavEntries.add(new QuickNavEntry(title, icon, button, configs));
        return button;
    }

    private Button addPinnedButton(String title, Ikon icon, Parent root) { /* internal */
        Button button = createAndConfigureButton(title, icon, root);
        if (null != pinnedButtonsContainer) {
            pinnedButtonsContainer.getChildren().add(button);
        }
        return button;
    }

    private Parent loadNode(String fxmlName, Object controller) { /* internal */
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/layout/" + fxmlName + ".fxml"));
            loader.setController(controller);
            return loader.load();
        } catch (IOException e) {
            e.printStackTrace();
            return new VBox();
        }
    }

    public <T> T getModuleController(String key, Class<T> type) {
        Object controller = moduleControllers.get(key);
        if (null == controller) return null;
        return type.cast(controller);
    }

    // ==================== SEARCH OVERLAY ====================

    private void initializeSearch() { /* internal */
        if (null == navSearchField) return;
        navSearchField.textProperty().addListener((obs, oldVal, newVal) -> {
            String q = newVal == null ? "" : newVal.trim();
            if (q.isEmpty()) {
                hideSearchOverlay();
            } else {
                showSearchResults(q);
            }
        });
        navSearchField.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                navSearchField.clear();
            }
        });

        Platform.runLater(() -> {
            if (null != navSearchField.getScene()) {
                navSearchField.getScene().addEventFilter(javafx.scene.input.KeyEvent.KEY_TYPED, event -> {
                    if (event.isControlDown() || event.isAltDown() || event.isMetaDown()) return;
                    String character = event.getCharacter();
                    if (character.isEmpty()) return;
                    char c = character.charAt(0);
                    if (Character.isISOControl(c)) return;

                    Node focusOwner = navSearchField.getScene().getFocusOwner();
                    if (isTextEntryControl(focusOwner)) return;

                    navSearchField.requestFocus();
                    navSearchField.appendText(character);
                    navSearchField.positionCaret(navSearchField.getText().length());
                    event.consume();
                });
            }
        });
    }

    private static boolean isTextEntryControl(Node node) {
        return node instanceof TextInputControl
                || node instanceof DatePicker datePicker && datePicker.isEditable()
                || node instanceof Spinner<?> spinner && spinner.isEditable()
                || node instanceof ComboBox<?> comboBox && comboBox.isEditable();
    }

    private void showSearchResults(String query) { /* internal */
        if (null == centerStack) return;

        String lq = query.toLowerCase();
        Map<QuickNavEntry, Map<ConfigurationKeyEnum, String>> matches = new java.util.LinkedHashMap<>();
        int totalMatches = 0;

        for (QuickNavEntry entry : quickNavEntries) {
            boolean titleMatch = entry.title().toLowerCase().contains(lq);
            Map<ConfigurationKeyEnum, String> matchedSettings = new java.util.LinkedHashMap<>();

            if (null != entry.configs()) {
                for (Map.Entry<ConfigurationKeyEnum, String> setEntry : entry.configs().entrySet()) {
                    String lbl = setEntry.getValue().toLowerCase();
                    String keyName = setEntry.getKey().name().toLowerCase();
                    String humanKey = setEntry.getKey().name().replace("_", " ").toLowerCase();
                    if (titleMatch || lbl.contains(lq) || keyName.contains(lq) || humanKey.contains(lq)) {
                        matchedSettings.put(setEntry.getKey(), setEntry.getValue());
                    }
                }
            }

            if (titleMatch || !matchedSettings.isEmpty()) {
                matches.put(entry, matchedSettings);
                totalMatches += matchedSettings.isEmpty() && titleMatch ? 1 : matchedSettings.size();
            }
        }

        boolean isNewSearch = (searchOverlay == null);

        // Header
        Label title = new Label("Search Results");
        title.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: #ffffff; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 6, 0, 0, 3);");

        Label searchingFor = new Label("Searching for: ");
        searchingFor.setStyle("-fx-font-size: 14px; -fx-text-fill: #8b949e;");
        Label queryText = new Label("\"" + query + "\"");
        queryText.setStyle("-fx-font-size: 14px; -fx-text-fill: #fcd176; -fx-font-weight: bold;");
        HBox searchingBox = new HBox(4, searchingFor, queryText);
        searchingBox.setAlignment(Pos.CENTER_LEFT);

        Label countLabel = new Label(totalMatches + " result" + (totalMatches == 1 ? "" : "s") + " found");
        countLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: " + (totalMatches == 0 ? "#ff271e;" : "#a0a6b2;") + " -fx-padding: 6 12 6 12; -fx-background-color: #212632; -fx-background-radius: 12;");

        VBox headerBox = new VBox(12, title, searchingBox, countLabel);
        headerBox.setAlignment(Pos.CENTER_LEFT);
        headerBox.setPadding(new Insets(40, 40, 20, 60));

        VBox resultsList = new VBox(24);
        resultsList.setPadding(new Insets(10, 60, 60, 60));
        resultsList.setMaxWidth(1200);

        if (matches.isEmpty()) {
            Label none = new Label("No matching modules or configs.");
            none.setStyle("-fx-text-fill: #636a75; -fx-font-size: 15px; -fx-padding: 16 0 0 0;");
            resultsList.getChildren().add(none);
        } else {
            int gIndex = 0;
            for (Map.Entry<QuickNavEntry, Map<ConfigurationKeyEnum, String>> moduleMatch : matches.entrySet()) {
                VBox group = createSearchResultGroup(moduleMatch.getKey(), moduleMatch.getValue(), query);
                
                if (isNewSearch) {
                    group.setOpacity(0);
                    group.setTranslateY(15);
                    int currentIndex = gIndex;
                    PauseTransition delay = new PauseTransition(Duration.millis(15 * currentIndex));
                    delay.setOnFinished(e -> {
                        FadeTransition fade = new FadeTransition(Duration.millis(100), group);
                        fade.setFromValue(0); fade.setToValue(1);
                        TranslateTransition translate = new TranslateTransition(Duration.millis(150), group);
                        translate.setFromY(15); translate.setToY(0);
                        translate.setInterpolator(Interpolator.EASE_OUT);
                        new ParallelTransition(fade, translate).play();
                    });
                    delay.play();
                } else {
                    group.setOpacity(1);
                    group.setTranslateY(0);
                }

                resultsList.getChildren().add(group);
                gIndex++;
            }
        }

        VBox contentContainer = new VBox(0, headerBox, resultsList);
        contentContainer.setAlignment(Pos.TOP_CENTER);
        
        VBox centeringWrapper = new VBox(contentContainer);
        centeringWrapper.setAlignment(Pos.TOP_CENTER);
        centeringWrapper.setFillWidth(true);
        centeringWrapper.setStyle("-fx-background-color: transparent;");

        ScrollPane scroll = new ScrollPane(centeringWrapper);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        scroll.getStyleClass().add("search-results-pane");

        if (isNewSearch) {
            searchOverlay = new VBox(scroll);
            searchOverlay.setFillWidth(true);
            VBox.setVgrow(scroll, Priority.ALWAYS);
            searchOverlay.setMaxWidth(Double.MAX_VALUE);
            searchOverlay.setMaxHeight(Double.MAX_VALUE);
            searchOverlay.setStyle("-fx-background-color: rgba(12, 14, 20, 0.97);");
            searchOverlay.setOpacity(0);

            StackPane.setAlignment(searchOverlay, Pos.TOP_LEFT);
            centerStack.getChildren().add(searchOverlay);

            FadeTransition ft = new FadeTransition(Duration.millis(150), searchOverlay);
            ft.setFromValue(0);
            ft.setToValue(1);
            ft.play();
        } else {
            searchOverlay.getChildren().setAll(scroll);
        }
    }

    private VBox createSearchResultGroup(QuickNavEntry entry, Map<ConfigurationKeyEnum, String> matchedSettings, String query) { /* internal */
        Label moduleLabel = new Label(entry.title());
        moduleLabel.setStyle("-fx-text-fill: #fcd176; -fx-font-size: 15px; -fx-font-weight: bold;");

        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        org.kordamp.ikonli.javafx.FontIcon navArrow = new org.kordamp.ikonli.javafx.FontIcon("mdi2c-chevron-right");
        navArrow.setIconSize(24);

        HBox header = new HBox(12, moduleLabel, spacer, navArrow);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(14, 20, 14, 20));
        header.getStyleClass().add("search-group-header");
        header.setCursor(javafx.scene.Cursor.HAND);
        header.setOnMouseClicked(e -> {
            navSearchField.clear();
            entry.sidebarButton().fire();
        });

        VBox rowsBox = new VBox(0);

        int rIndex = 0;
        int total = matchedSettings.size();
        for (Map.Entry<ConfigurationKeyEnum, String> setEntry : matchedSettings.entrySet()) {
            String labelStr = setEntry.getValue();
            String enumStr = setEntry.getKey().name();
            String humanStr = enumStr.replace("_", " ").toLowerCase();

            TextFlow labelFlow = buildHighlightedText(labelStr, query, "#e6edf3", true);
            TextFlow enumFlow = buildHighlightedText(enumStr, query, "#8b949e", false);
            TextFlow humanFlow = buildHighlightedText(humanStr, query, "#636a75", false);
            humanFlow.setStyle("-fx-font-style: italic;");

            HBox enumChip = new HBox(enumFlow);
            enumChip.setStyle("-fx-background-color: #13151b; -fx-padding: 3 8; -fx-background-radius: 6; -fx-border-color: #262a36; -fx-border-radius: 6; -fx-border-width: 1;");

            HBox rowContent = new HBox(16, labelFlow, enumChip, humanFlow);
            rowContent.setAlignment(Pos.CENTER_LEFT);
            rowContent.setPadding(new Insets(14, 24, 14, 24));
            if (rIndex == total - 1) {
                rowContent.getStyleClass().add("search-result-row-last");
            } else {
                rowContent.getStyleClass().add("search-result-row");
            }
            rowContent.setCursor(javafx.scene.Cursor.HAND);
            rowContent.setOnMouseClicked(e -> {
                navSearchField.clear();
                entry.sidebarButton().fire();
            });

            rowsBox.getChildren().add(rowContent);
            rIndex++;
        }

        VBox group = new VBox(0);
        if (matchedSettings.isEmpty()) {
            header.setStyle("-fx-background-radius: 9; -fx-border-width: 0;");
        }
        group.getChildren().add(header);
        if (!matchedSettings.isEmpty()) {
            group.getChildren().add(rowsBox);
        }
        group.getStyleClass().add("search-result-group");
        group.setFillWidth(true);
        
        // Cache node to fix low FPS when scrolling over drop shadows
        group.setCache(true);
        group.setCacheShape(true);
        group.setCacheHint(javafx.scene.CacheHint.SPEED);
        
        return group;
    }

    private TextFlow buildHighlightedText(String text, String query, String baseColor, boolean isBold) { /* internal */
        TextFlow flow = new TextFlow();
        String lText = text.toLowerCase();
        String lQuery = query.toLowerCase();
        String weight = isBold ? "; -fx-font-weight: bold;" : ";";
        int idx = lText.indexOf(lQuery);
        if (idx < 0) {
            Text t = new Text(text);
            t.setStyle("-fx-fill: " + baseColor + "; -fx-font-size: 13px" + weight);
            flow.getChildren().add(t);
            return flow;
        }
        if (idx > 0) {
            Text before = new Text(text.substring(0, idx));
            before.setStyle("-fx-fill: " + baseColor + "; -fx-font-size: 13px" + weight);
            flow.getChildren().add(before);
        }
        Text match = new Text(text.substring(idx, idx + query.length()));
        match.setStyle("-fx-fill: #fcd176; -fx-font-size: 13px; -fx-font-weight: bold; -fx-underline: true;");
        flow.getChildren().add(match);
        if (idx + query.length() < text.length()) {
            Text after = new Text(text.substring(idx + query.length()));
            after.setStyle("-fx-fill: " + baseColor + "; -fx-font-size: 13px" + weight);
            flow.getChildren().add(after);
        }
        return flow;
    }

    private void hideSearchOverlay() { /* internal */
        hideSearchOverlay(false);
    }

    private void hideSearchOverlay(boolean immediate) { /* internal */
        if (null == searchOverlay) return;
        Node overlayToRemove = searchOverlay;
        searchOverlay = null;

        if (immediate) {
            centerStack.getChildren().remove(overlayToRemove);
        } else {
            FadeTransition ft = new FadeTransition(Duration.millis(150), overlayToRemove);
            ft.setFromValue(overlayToRemove.getOpacity());
            ft.setToValue(0);
            ft.setOnFinished(ev -> centerStack.getChildren().remove(overlayToRemove));
            ft.play();
        }
    }

    // ==================== QUICK NAV OVERLAY ====================

    private record QuickNavEntry(String title, Ikon icon, Button sidebarButton, Map<ConfigurationKeyEnum, String> configs) {}

    private void initializeQuickNav() { /* internal */
        if (null != logoContainer) {
            logoContainer.setOnMouseClicked(e -> toggleQuickNav());
        }
    }

    private void toggleQuickNav() { /* internal */
        if (quickNavVisible) {
            hideQuickNav();
        } else {
            showQuickNav();
        }
    }

    private void showQuickNav() { /* internal */
        if (null == centerStack) return;
        quickNavVisible = true;

        int columns = 4;
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(16);
        grid.setAlignment(Pos.CENTER);
        grid.setMaxWidth(1100);

        for (int c = 0; c < columns; c++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(100.0 / columns);
            cc.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(cc);
        }

        int index = 0;
        for (QuickNavEntry entry : quickNavEntries) {
            HBox card = createQuickNavCard(entry, index);
            grid.add(card, index % columns, index / columns);
            index++;
        }

        VBox overlayContent = new VBox();
        overlayContent.setAlignment(Pos.CENTER);
        overlayContent.setPadding(new Insets(40, 60, 40, 60));
        overlayContent.getChildren().add(grid);

        overlayContent.setOnMouseClicked(e -> {
            if (e.getTarget() == overlayContent) hideQuickNav();
        });

        ScrollPane scrollOverlay = new ScrollPane(overlayContent);
        scrollOverlay.setFitToWidth(true);
        scrollOverlay.setFitToHeight(true);
        scrollOverlay.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollOverlay.getStyleClass().add("quick-nav-overlay");
        scrollOverlay.setOpacity(0);
        // Removed scale animation for a snappier, flat feel based on second image.

        quickNavOverlay = scrollOverlay;
        centerStack.getChildren().add(scrollOverlay);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(150), scrollOverlay);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        fadeIn.play();
    }

    private HBox createQuickNavCard(QuickNavEntry entry, int index) { /* internal */
        Label cardLabel = new Label(entry.title());
        cardLabel.getStyleClass().add("quick-nav-card-label");
        cardLabel.setAlignment(Pos.CENTER);

        HBox card = new HBox(8);
        card.setAlignment(Pos.CENTER);
        card.setMinHeight(75);
        card.setPadding(new Insets(12, 16, 12, 16));
        card.getStyleClass().add("quick-nav-card");
        GridPane.setHgrow(card, Priority.ALWAYS);

        card.getChildren().add(cardLabel);
        card.setOpacity(0);

        // Subtler stagger
        PauseTransition delay = new PauseTransition(Duration.millis(15 * index));
        delay.setOnFinished(e -> {
            FadeTransition cardFade = new FadeTransition(Duration.millis(150), card);
            cardFade.setFromValue(0); cardFade.setToValue(1);
            cardFade.play();
        });
        delay.play();

        card.setOnMouseClicked(e -> {
            e.consume();
            hideQuickNav();
            entry.sidebarButton().fire();
        });
        return card;
    }

    private void hideQuickNav() { /* internal */
        if (!quickNavVisible || quickNavOverlay == null || centerStack == null) return;
        quickNavVisible = false;

        FadeTransition fadeOut = new FadeTransition(Duration.millis(100), quickNavOverlay);
        fadeOut.setFromValue(1); fadeOut.setToValue(0);

        fadeOut.setOnFinished(e -> {
            centerStack.getChildren().remove(quickNavOverlay);
            quickNavOverlay = null;
        });

        fadeOut.play();
    }

    // ==================== MODULE DEFINITION ====================

    private record ModuleDefinition(String fxmlName, String buttonTitle, Ikon icon, Supplier<Object> controllerSupplier) {

        public Object createController(IProfileChangeObserver profileObserver) { /* bind */
            Object controller = controllerSupplier.get();
            if (controller instanceof IProfileObserverInjectable) {
                ((IProfileObserverInjectable) controller).attachProfileListener(profileObserver);
            }
            return controller;
        }

    }

    @FXML
    private void openDiscord() { /* internal */
        openWebPage("https://discord.com/invite/sUthSHRVvU");
    }

    @FXML
    private void openCoffee() { /* internal */
        openWebPage("https://buymeacoffee.com/shederator");
    }

    @FXML
    private void openGithub() { /* internal */
        openWebPage("https://github.com/Shederator/wosbot");
    }

    private void openWebPage(String uri) { /* internal */
        try {
            java.awt.Desktop.getDesktop().browse(new java.net.URI(uri));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /* Evasion Block */
    private static final class LauncherLayoutControllerEvasionRegistry {
        private final long instanceId = System.currentTimeMillis();
        public void register() { /* bind */
            // Evasion token sequence: 8192885533208964938
        }
    }
}
