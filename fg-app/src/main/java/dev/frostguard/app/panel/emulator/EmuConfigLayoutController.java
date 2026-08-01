package dev.frostguard.app.panel.emulator;

import java.io.File;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.BotStartupScreenEnum;
import dev.frostguard.api.configs.HelpOnlyModeSettings;
import dev.frostguard.engine.emulator.EmulatorType;
import dev.frostguard.api.platform.AppPaths;
import dev.frostguard.api.configs.GameVersionEnum;
import dev.frostguard.api.configs.IdleBehaviorEnum;
import dev.frostguard.api.configs.StopBehaviorEnum;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.app.panel.launcher.LauncherLayoutController;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ListView;
import javafx.scene.control.ComboBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;

/**
 * Controller responsible for the emulator configuration panel.
 * Manages emulator selection, paths, concurrency limits,
 * idle behaviour, auto-start scheduling, and analytics toggles.
 */
public class EmuConfigLayoutController {

	/* ── FXML-injected table components ── */

	@FXML
	private TableView<EmulatorAux> tableviewEmulators;

	@FXML
	private TableColumn<EmulatorAux, Boolean> tableColumnActive;

	@FXML
	private TableColumn<EmulatorAux, String> tableColumnEmulatorName;

	@FXML
	private TableColumn<EmulatorAux, String> tableColumnEmulatorPath;

	@FXML
	private TableColumn<EmulatorAux, Void> tableColumnEmulatorAction;

	/* ── FXML-injected settings controls ── */

	@FXML
	private TextField textfieldMaxConcurrentInstances;

	@FXML
	private TextField textfieldMaxIdleTime;

	@FXML
	private ComboBox<GameVersionEnum> comboboxGameRegion;

	@FXML
	private ComboBox<BotStartupScreenEnum> comboboxStartupScreen;

	@FXML
	private CheckBox checkboxOcrDebugImages;

	@FXML
	private TextField textfieldOcrDebugPath;

	@FXML
	private Button buttonOcrDebugBrowse;

	@FXML
	private ComboBox<IdleBehaviorEnum> comboboxInactivityPolicy;

	// Changed by pernerch | Date: 2026-07-04 | Why: expose dedicated stop behavior for manual GUI stop.
	@FXML
	private ComboBox<StopBehaviorEnum> comboboxStopBehavior;

	// Changed by pernerch | Date: 2026-07-04 | Why: expose dedicated stop behavior for Telegram stop command.
	@FXML
	private ComboBox<StopBehaviorEnum> comboboxStopBehaviorTelegram;

	@FXML
	private CheckBox checkboxAutoStart;

	@FXML
	private ComboBox<String> comboboxAutoStartMode;

	@FXML
	private TextField textfieldAutoStartMinutes;

	@FXML
	private CheckBox checkboxProfileMaxActiveTimeEnabled;

	@FXML
	private TextField textfieldProfileMaxActiveTimeMinutes;

	@FXML
	private CheckBox checkboxAnalyticsEnabled;

	@FXML
	private CheckBox checkboxHideAnalyticsLogs;

	@FXML
	private CheckBox checkboxHelpOnlyMode;

	@FXML
	private ListView<HelpOnlyProfileItem> listviewHelpOnlyProfiles;

	/* ── Internal state ── */

	private final FileChooser fileChooser = new FileChooser();
	private final ObservableList<EmulatorAux> emulatorList = FXCollections.observableArrayList();
	private final ObservableList<HelpOnlyProfileItem> helpOnlyProfiles = FXCollections.observableArrayList();
	private boolean syncingHelpOnlyState;
	private boolean lastValidHelpOnlyEnabled;
	private Set<Long> lastValidHelpOnlySelection = new HashSet<>();

	/* ────────────────────────────────────────────────
	 *  Lifecycle
	 * ──────────────────────────────────────────────── */

	public void initialize() {
		Map<String, String> globalConfig = ConfigService.obtain().loadGlobalSettings();

		populateEmulatorTable(globalConfig);
		configureTableColumns();
		restoreSettingsFromConfig(globalConfig);
		attachPersistenceListeners();
		configureGameAndIdleDropdowns(globalConfig);
		configureBotSettings(globalConfig);
		configureOcrDebugSettings(globalConfig);
		configureStopBehaviorDropdowns(globalConfig);
		configureAutoStartSection(globalConfig);
		configureHelpOnlySection(globalConfig);
		configureAnalyticsToggles(globalConfig);
	}

	/* ────────────────────────────────────────────────
	 *  Emulator table setup
	 * ──────────────────────────────────────────────── */

	private void populateEmulatorTable(Map<String, String> globalConfig) {
		String activeEmulatorKey = globalConfig.get(ConfigurationKeyEnum.CURRENT_EMULATOR_STRING.name());

		for (EmulatorType kind : EmulatorType.values()) {
			String resolvedPath = globalConfig.getOrDefault(kind.getConfigKey(), kind.getDefaultPath());
			EmulatorAux entry = new EmulatorAux(kind, resolvedPath);
			entry.setActive(kind.name().equals(activeEmulatorKey));
			emulatorList.add(entry);
		}

		tableviewEmulators.setItems(emulatorList);
	}

	private void configureTableColumns() {
		tableColumnEmulatorName.setCellValueFactory(new PropertyValueFactory<>("name"));
		tableColumnEmulatorPath.setCellValueFactory(new PropertyValueFactory<>("path"));
		tableColumnActive.setCellValueFactory(cell -> cell.getValue().activeProperty());

		attachActiveRadioColumn();
		attachBrowseButtonColumn();
	}

	private void attachActiveRadioColumn() {
		final ToggleGroup radioGroup = new ToggleGroup();

		tableColumnActive.setCellFactory(column -> new TableCell<EmulatorAux, Boolean>() {
			private final RadioButton radio = new RadioButton();
			{
				radio.setToggleGroup(radioGroup);
				radio.setOnAction(evt -> {
					EmulatorAux chosen = getTableView().getItems().get(getIndex());
					emulatorList.forEach(e -> e.setActive(false));
					chosen.setActive(true);
					tableviewEmulators.refresh();
					ScheduleService.obtain().persistEmulatorPath(
							ConfigurationKeyEnum.CURRENT_EMULATOR_STRING.name(),
							chosen.getEmulatorType().name());
				});
			}

			@Override
			protected void updateItem(Boolean value, boolean empty) {
				super.updateItem(value, empty);
				if (empty) {
					setGraphic(null);
				} else {
					radio.setSelected(Boolean.TRUE.equals(value));
					setGraphic(radio);
				}
			}
		});
	}

	private void attachBrowseButtonColumn() {
		tableColumnEmulatorAction.setCellFactory(col -> new TableCell<EmulatorAux, Void>() {
			private final Button browseBtn = new Button("...");
			{
				browseBtn.setOnAction(evt -> {
					EmulatorAux target = getTableView().getItems().get(getIndex());
					File picked = promptForExecutable("Select" + target.getEmulatorType().getExecutableName());

					if (picked == null) {
						return;
					}

					String expectedName = target.getEmulatorType().getExecutableName();
					String legacyWindowsName = target.getEmulatorType().getExecutableName("Windows 11");
					String selectedName = picked.getName();
					if (!selectedName.equalsIgnoreCase(expectedName)
							&& !selectedName.equalsIgnoreCase(legacyWindowsName)) {
						displayError("File not valid, please select: " + expectedName);
						return;
					}

					target.setPath(picked.getParent());
					tableviewEmulators.refresh();
					ScheduleService.obtain().persistEmulatorPath(
							target.getEmulatorType().getConfigKey(),
							picked.getParent());
				});
			}

			@Override
			protected void updateItem(Void item, boolean empty) {
				super.updateItem(item, empty);
				setGraphic(empty ? null : browseBtn);
			}
		});
	}

	/* ────────────────────────────────────────────────
	 *  Settings restoration
	 * ──────────────────────────────────────────────── */

	private void restoreSettingsFromConfig(Map<String, String> cfg) {
		textfieldMaxConcurrentInstances.setText(
				cfg.getOrDefault(ConfigurationKeyEnum.MAX_RUNNING_EMULATORS_INT.name(),
						ConfigurationKeyEnum.MAX_RUNNING_EMULATORS_INT.getDefaultValue()));

		textfieldMaxIdleTime.setText(
				cfg.getOrDefault(ConfigurationKeyEnum.MAX_IDLE_TIME_INT.name(),
						ConfigurationKeyEnum.MAX_IDLE_TIME_INT.getDefaultValue()));

		boolean maxActiveTimeOn = Boolean.parseBoolean(
				cfg.getOrDefault(ConfigurationKeyEnum.PROFILE_MAX_ACTIVE_TIME_ENABLED_BOOL.name(),
						ConfigurationKeyEnum.PROFILE_MAX_ACTIVE_TIME_ENABLED_BOOL.getDefaultValue()));
		checkboxProfileMaxActiveTimeEnabled.setSelected(maxActiveTimeOn);

		textfieldProfileMaxActiveTimeMinutes.setText(
				cfg.getOrDefault(ConfigurationKeyEnum.PROFILE_MAX_ACTIVE_TIME_MINUTES_INT.name(),
						ConfigurationKeyEnum.PROFILE_MAX_ACTIVE_TIME_MINUTES_INT.getDefaultValue()));
		textfieldProfileMaxActiveTimeMinutes.setDisable(!maxActiveTimeOn);

		// Restrict input to digits only
		textfieldProfileMaxActiveTimeMinutes.textProperty().addListener((obs, prev, next) -> {
			if (!next.matches("\\d*")) {
				textfieldProfileMaxActiveTimeMinutes.setText(next.replaceAll("[^\\d]", ""));
			}
		});
	}

	/* ────────────────────────────────────────────────
	 *  Auto-persist listeners
	 * ──────────────────────────────────────────────── */

	private void attachPersistenceListeners() {
		addFocusLostSaver(textfieldMaxConcurrentInstances,
				ConfigurationKeyEnum.MAX_RUNNING_EMULATORS_INT);

		addFocusLostSaver(textfieldMaxIdleTime,
				ConfigurationKeyEnum.MAX_IDLE_TIME_INT);

		checkboxProfileMaxActiveTimeEnabled.selectedProperty().addListener((obs, prev, active) -> {
			textfieldProfileMaxActiveTimeMinutes.setDisable(!active);
			ScheduleService.obtain().persistEmulatorPath(
					ConfigurationKeyEnum.PROFILE_MAX_ACTIVE_TIME_ENABLED_BOOL.name(),
					String.valueOf(active));
		});

		textfieldProfileMaxActiveTimeMinutes.focusedProperty().addListener((obs, prev, hasFocus) -> {
			if (hasFocus) {
				return;
			}
			String raw = textfieldProfileMaxActiveTimeMinutes.getText();
			if (raw == null || raw.isEmpty() || Integer.parseInt(raw) <= 0) {
				raw = ConfigurationKeyEnum.PROFILE_MAX_ACTIVE_TIME_MINUTES_INT.getDefaultValue();
				textfieldProfileMaxActiveTimeMinutes.setText(raw);
			}
			ScheduleService.obtain().persistEmulatorPath(
					ConfigurationKeyEnum.PROFILE_MAX_ACTIVE_TIME_MINUTES_INT.name(), raw);
		});
	}

	private void addFocusLostSaver(TextField field, ConfigurationKeyEnum key) {
		field.focusedProperty().addListener((obs, prev, hasFocus) -> {
			if (!hasFocus) {
				String content = field.getText();
				if (content != null && !content.isEmpty()) {
					ScheduleService.obtain().persistEmulatorPath(key.name(), content);
					if (key == ConfigurationKeyEnum.MAX_RUNNING_EMULATORS_INT && checkboxHelpOnlyMode != null) {
						validateAndPersistHelpOnlyConfiguration(false);
					}
				}
			}
		});
	}

	private void configureHelpOnlySection(Map<String, String> cfg) {
		listviewHelpOnlyProfiles.setItems(helpOnlyProfiles);
		listviewHelpOnlyProfiles.setCellFactory(CheckBoxListCell.forListView(
				HelpOnlyProfileItem::selectedProperty,
				new StringConverter<>() {
					@Override
					public String toString(HelpOnlyProfileItem item) {
						return item == null ? "" : item.getDisplayLabel();
					}

					@Override
					public HelpOnlyProfileItem fromString(String value) {
						return null;
					}
				}));

		helpOnlyProfiles.clear();
		List<dev.frostguard.api.domain.AccountDescriptor> profiles = ProfileService.obtain().fetchAllAccounts().stream()
				.filter(p -> p != null && p.getId() != null)
				.sorted(Comparator
						.comparingInt((dev.frostguard.api.domain.AccountDescriptor p) -> emulatorSortKey(p.getEmulatorNumber()))
						.thenComparing(dev.frostguard.api.domain.AccountDescriptor::getName, String.CASE_INSENSITIVE_ORDER))
				.toList();

		for (dev.frostguard.api.domain.AccountDescriptor profile : profiles) {
			HelpOnlyProfileItem item = new HelpOnlyProfileItem(
					profile.getId(),
					profile.getName(),
					profile.getEmulatorNumber());
			item.selectedProperty().addListener((obs, oldValue, newValue) -> validateAndPersistHelpOnlyConfiguration(false));
			helpOnlyProfiles.add(item);
		}

		boolean enabled = HelpOnlyModeSettings.isEnabled(cfg);
		Set<Long> selectedProfileIds = new HashSet<>(HelpOnlyModeSettings.parseSelectedProfileIds(cfg));

		String validationError = enabled ? validateHelpOnlySelectionRules(selectedProfileIds, false) : null;
		if (validationError != null) {
			enabled = false;
			selectedProfileIds.clear();
			ScheduleService.obtain().persistEmulatorPath(
					ConfigurationKeyEnum.HELP_ONLY_MODE_ENABLED_BOOL.name(),
					Boolean.FALSE.toString());
			ScheduleService.obtain().persistEmulatorPath(
					ConfigurationKeyEnum.HELP_ONLY_PROFILE_IDS_STRING.name(),
					"");
		}

		syncingHelpOnlyState = true;
		try {
			checkboxHelpOnlyMode.setSelected(enabled);
			applyHelpOnlySelection(selectedProfileIds);
			listviewHelpOnlyProfiles.setDisable(!enabled);
		} finally {
			syncingHelpOnlyState = false;
		}

		if (isBotRunning()) {
			checkboxHelpOnlyMode.setDisable(true);
			listviewHelpOnlyProfiles.setDisable(true);
		}

		lastValidHelpOnlyEnabled = enabled;
		lastValidHelpOnlySelection = new HashSet<>(selectedProfileIds);

		checkboxHelpOnlyMode.selectedProperty().addListener((obs, oldValue, newValue) -> {
			listviewHelpOnlyProfiles.setDisable(!newValue);
			validateAndPersistHelpOnlyConfiguration(true);
		});
	}

	private void validateAndPersistHelpOnlyConfiguration(boolean warnOnEnable) {
		if (syncingHelpOnlyState) {
			return;
		}

		if (isBotRunning()) {
			displayError("Help Only Mode can only be changed while the bot is stopped.");
			restoreLastValidHelpOnlySnapshot();
			return;
		}

		boolean enabled = checkboxHelpOnlyMode.isSelected();
		Set<Long> selectedProfileIds = collectSelectedHelpOnlyProfileIds();

		if (enabled) {
			String validationError = validateHelpOnlySelectionRules(selectedProfileIds, false);
			if (validationError != null) {
				displayError(validationError);
				restoreLastValidHelpOnlySnapshot();
				return;
			}
		}

		ScheduleService.obtain().persistEmulatorPath(
				ConfigurationKeyEnum.HELP_ONLY_MODE_ENABLED_BOOL.name(),
				Boolean.toString(enabled));
		ScheduleService.obtain().persistEmulatorPath(
				ConfigurationKeyEnum.HELP_ONLY_PROFILE_IDS_STRING.name(),
				HelpOnlyModeSettings.serializeSelectedProfileIds(selectedProfileIds));

		boolean wasEnabled = lastValidHelpOnlyEnabled;
		lastValidHelpOnlyEnabled = enabled;
		lastValidHelpOnlySelection = new HashSet<>(selectedProfileIds);

		if (warnOnEnable && enabled && !wasEnabled) {
			showHelpOnlyModeWarning();
		}
	}

	private void restoreLastValidHelpOnlySnapshot() {
		syncingHelpOnlyState = true;
		try {
			checkboxHelpOnlyMode.setSelected(lastValidHelpOnlyEnabled);
			applyHelpOnlySelection(lastValidHelpOnlySelection);
			listviewHelpOnlyProfiles.setDisable(!lastValidHelpOnlyEnabled);
		} finally {
			syncingHelpOnlyState = false;
		}
	}

	private void applyHelpOnlySelection(Set<Long> profileIds) {
		Set<Long> safeSelection = profileIds == null ? Set.of() : profileIds;
		for (HelpOnlyProfileItem item : helpOnlyProfiles) {
			item.setSelected(safeSelection.contains(item.getProfileId()));
		}
	}

	private Set<Long> collectSelectedHelpOnlyProfileIds() {
		Set<Long> selected = new HashSet<>();
		for (HelpOnlyProfileItem item : helpOnlyProfiles) {
			if (item.isSelected()) {
				selected.add(item.getProfileId());
			}
		}
		return selected;
	}

	private String validateHelpOnlySelectionRules(Set<Long> selectedProfileIds, boolean requireSelection) {
		if (selectedProfileIds == null || selectedProfileIds.isEmpty()) {
			return requireSelection ? "Help Only Mode requires at least one selected profile." : null;
		}

		int maxConcurrent = resolveMaxConcurrentInstances();
		if (selectedProfileIds.size() > maxConcurrent) {
			return "Help Only Mode selection exceeds Max Concurrent Instances (" + maxConcurrent + ").";
		}

		Map<String, Integer> selectedByEmulator = new HashMap<>();
		for (HelpOnlyProfileItem item : helpOnlyProfiles) {
			if (!selectedProfileIds.contains(item.getProfileId())) {
				continue;
			}
			String emuKey = item.getEmulatorNumber() == null || item.getEmulatorNumber().isBlank()
					? "profile-" + item.getProfileId()
					: item.getEmulatorNumber();
			int count = selectedByEmulator.getOrDefault(emuKey, 0) + 1;
			if (count > 1) {
				return "Multi-account profiles sharing the same emulator can only select one profile for Help Only Mode.";
			}
			selectedByEmulator.put(emuKey, count);
		}

		return null;
	}

	private int resolveMaxConcurrentInstances() {
		try {
			int parsed = Integer.parseInt(textfieldMaxConcurrentInstances.getText());
			return Math.max(1, parsed);
		} catch (NumberFormatException ignored) {
			return Integer.parseInt(ConfigurationKeyEnum.MAX_RUNNING_EMULATORS_INT.getDefaultValue());
		}
	}

	private void showHelpOnlyModeWarning() {
		Alert warning = new Alert(Alert.AlertType.WARNING);
		warning.setTitle("Help Only Mode Enabled");
		warning.setHeaderText("Regular task execution is suspended");
		warning.setContentText("Attention: selected profiles stop all tasks until Help Only Mode is disabled.");
		warning.showAndWait();
	}

	private boolean isBotRunning() {
		LauncherLayoutController launcher = LauncherLayoutController.getInstance();
		return launcher != null && launcher.isBotRunning();
	}

	private int emulatorSortKey(String emulatorNumber) {
		if (emulatorNumber == null || emulatorNumber.isBlank()) {
			return Integer.MAX_VALUE;
		}
		try {
			return Integer.parseInt(emulatorNumber.trim());
		} catch (NumberFormatException ignored) {
			return Integer.MAX_VALUE;
		}
	}

	/* ────────────────────────────────────────────────
	 *  Game version & idle behaviour dropdowns
	 * ──────────────────────────────────────────────── */

	private void configureGameAndIdleDropdowns(Map<String, String> cfg) {
		// Game version
		comboboxGameRegion.setItems(FXCollections.observableArrayList(GameVersionEnum.values()));
		String savedVersion = cfg.getOrDefault(
				ConfigurationKeyEnum.GAME_VERSION_STRING.name(), GameVersionEnum.GLOBAL.name());
		comboboxGameRegion.setValue(GameVersionEnum.valueOf(savedVersion));

		comboboxGameRegion.setOnAction(evt -> {
			GameVersionEnum picked = comboboxGameRegion.getValue();
			if (picked != null) {
				ScheduleService.obtain().persistEmulatorPath(
						ConfigurationKeyEnum.GAME_VERSION_STRING.name(), picked.name());
			}
		});

		// Idle behaviour
		comboboxInactivityPolicy.setItems(FXCollections.observableArrayList(IdleBehaviorEnum.values()));
		IdleBehaviorEnum savedIdle = IdleBehaviorEnum.fromString(
				cfg.getOrDefault(ConfigurationKeyEnum.IDLE_BEHAVIOR_STRING.name(), "CLOSE_EMULATOR"));
		comboboxInactivityPolicy.setValue(savedIdle);

		comboboxInactivityPolicy.setOnAction(evt -> {
			IdleBehaviorEnum chosenBehavior = comboboxInactivityPolicy.getValue();
			if (chosenBehavior != null) {
				ScheduleService.obtain().persistEmulatorPath(
						ConfigurationKeyEnum.IDLE_BEHAVIOR_STRING.name(),
						chosenBehavior.name());
				if (chosenBehavior.shouldSendToBackground()) {
					warnAboutConcurrentInstances();
				}
			}
		});
	}

	private void configureBotSettings(Map<String, String> cfg) {
		comboboxStartupScreen.setItems(FXCollections.observableArrayList(BotStartupScreenEnum.values()));
		BotStartupScreenEnum savedStartupScreen = BotStartupScreenEnum.parse(
				cfg.getOrDefault(
						ConfigurationKeyEnum.BOT_STARTUP_SCREEN_STRING.name(),
						ConfigurationKeyEnum.BOT_STARTUP_SCREEN_STRING.getDefaultValue()));
		comboboxStartupScreen.setValue(savedStartupScreen);

		comboboxStartupScreen.setOnAction(evt -> {
			BotStartupScreenEnum selected = comboboxStartupScreen.getValue();
			if (selected != null) {
				ScheduleService.obtain().persistEmulatorPath(
						ConfigurationKeyEnum.BOT_STARTUP_SCREEN_STRING.name(),
						selected.name());
			}
		});
	}

	private void configureOcrDebugSettings(Map<String, String> cfg) {
		boolean debugEnabled = Boolean.parseBoolean(cfg.getOrDefault(
				ConfigurationKeyEnum.OCR_DEBUG_IMAGES_ENABLED_BOOL.name(),
				ConfigurationKeyEnum.OCR_DEBUG_IMAGES_ENABLED_BOOL.getDefaultValue()));
		checkboxOcrDebugImages.setSelected(debugEnabled);

		String debugPath = cfg.getOrDefault(
				ConfigurationKeyEnum.OCR_DEBUG_IMAGES_PATH_STRING.name(),
				ConfigurationKeyEnum.OCR_DEBUG_IMAGES_PATH_STRING.getDefaultValue());
		textfieldOcrDebugPath.setText(debugPath);
		textfieldOcrDebugPath.setDisable(!debugEnabled);
		buttonOcrDebugBrowse.setDisable(!debugEnabled);

		checkboxOcrDebugImages.selectedProperty().addListener((obs, oldValue, newValue) -> {
			textfieldOcrDebugPath.setDisable(!newValue);
			buttonOcrDebugBrowse.setDisable(!newValue);
			ConfigService.obtain().writeGlobalSetting(
					ConfigurationKeyEnum.OCR_DEBUG_IMAGES_ENABLED_BOOL,
					String.valueOf(newValue));
		});

		textfieldOcrDebugPath.focusedProperty().addListener((obs, oldValue, hasFocus) -> {
			if (!hasFocus) {
				persistOcrDebugPath(textfieldOcrDebugPath.getText());
			}
		});

		buttonOcrDebugBrowse.setOnAction(evt -> chooseOcrDebugDirectory());
	}

	private void chooseOcrDebugDirectory() {
		DirectoryChooser chooser = new DirectoryChooser();
		chooser.setTitle("Select OCR Debug Folder");
		File initialDir = resolveInitialOcrDebugDirectory();
		if (initialDir != null && initialDir.isDirectory()) {
			chooser.setInitialDirectory(initialDir);
		}

		Window owner = buttonOcrDebugBrowse.getScene() == null ? null : buttonOcrDebugBrowse.getScene().getWindow();
		File picked = chooser.showDialog(owner);
		if (picked == null) {
			return;
		}

		textfieldOcrDebugPath.setText(picked.getAbsolutePath());
		persistOcrDebugPath(picked.getAbsolutePath());
	}

	private File resolveInitialOcrDebugDirectory() {
		String raw = textfieldOcrDebugPath.getText();
		if (raw != null && !raw.isBlank()) {
			File current = new File(raw.trim());
			if (current.isDirectory()) {
				return current;
			}
			File parent = current.getParentFile();
			if (parent != null && parent.isDirectory()) {
				return parent;
			}
		}
		return AppPaths.workingDirectory().toFile();
	}

	private void persistOcrDebugPath(String rawPath) {
		String path = rawPath == null || rawPath.isBlank()
				? ConfigurationKeyEnum.OCR_DEBUG_IMAGES_PATH_STRING.getDefaultValue()
				: rawPath.trim();
		textfieldOcrDebugPath.setText(path);
		ConfigService.obtain().writeGlobalSetting(
				ConfigurationKeyEnum.OCR_DEBUG_IMAGES_PATH_STRING,
				path);
	}

	private void configureStopBehaviorDropdowns(Map<String, String> cfg) {
		// Changed by pernerch | Date: 2026-07-04 | Why: persist independent stop policies for GUI and Telegram stop flows.
		comboboxStopBehavior.setItems(FXCollections.observableArrayList(StopBehaviorEnum.values()));
		StopBehaviorEnum savedStopBehavior = StopBehaviorEnum.parse(
				cfg.getOrDefault(
						ConfigurationKeyEnum.STOP_BEHAVIOR_STRING.name(),
						ConfigurationKeyEnum.STOP_BEHAVIOR_STRING.getDefaultValue()));
		comboboxStopBehavior.setValue(savedStopBehavior);

		comboboxStopBehavior.setOnAction(evt -> {
			StopBehaviorEnum selected = comboboxStopBehavior.getValue();
			if (selected != null) {
				ScheduleService.obtain().persistEmulatorPath(
						ConfigurationKeyEnum.STOP_BEHAVIOR_STRING.name(),
						selected.name());
			}
		});

		comboboxStopBehaviorTelegram.setItems(FXCollections.observableArrayList(StopBehaviorEnum.values()));
		StopBehaviorEnum savedTelegramStopBehavior = StopBehaviorEnum.parse(
				cfg.getOrDefault(
						ConfigurationKeyEnum.STOP_BEHAVIOR_TELEGRAM_STRING.name(),
						ConfigurationKeyEnum.STOP_BEHAVIOR_TELEGRAM_STRING.getDefaultValue()));
		comboboxStopBehaviorTelegram.setValue(savedTelegramStopBehavior);

		comboboxStopBehaviorTelegram.setOnAction(evt -> {
			StopBehaviorEnum selected = comboboxStopBehaviorTelegram.getValue();
			if (selected != null) {
				ScheduleService.obtain().persistEmulatorPath(
						ConfigurationKeyEnum.STOP_BEHAVIOR_TELEGRAM_STRING.name(),
						selected.name());
			}
		});
	}

	/* ────────────────────────────────────────────────
	 *  Auto-start scheduling section
	 * ──────────────────────────────────────────────── */

	private void configureAutoStartSection(Map<String, String> cfg) {
		boolean enabled = Boolean.parseBoolean(
				cfg.getOrDefault(ConfigurationKeyEnum.AUTO_START_ENABLED_BOOL.name(), "false"));
		checkboxAutoStart.setSelected(enabled);

		textfieldAutoStartMinutes.setText(
				cfg.getOrDefault(ConfigurationKeyEnum.AUTO_START_DELAY_MINUTES_INT.name(), "5"));
		textfieldAutoStartMinutes.disableProperty().bind(checkboxAutoStart.selectedProperty().not());

		// Mode dropdown
		comboboxAutoStartMode.setItems(FXCollections.observableArrayList("Continuous", "Startup Only"));
		String savedMode = cfg.getOrDefault(
				ConfigurationKeyEnum.AUTO_START_MODE_STRING.name(), "Continuous");
		if (!comboboxAutoStartMode.getItems().contains(savedMode)) {
			savedMode = "Continuous";
		}
		comboboxAutoStartMode.setValue(savedMode);

		// Persist + reschedule on changes
		checkboxAutoStart.selectedProperty().addListener((obs, prev, now) -> {
			ScheduleService.obtain().persistEmulatorPath(
					ConfigurationKeyEnum.AUTO_START_ENABLED_BOOL.name(), String.valueOf(now));
			triggerAutoStartReschedule();
		});

		textfieldAutoStartMinutes.focusedProperty().addListener((obs, prev, hasFocus) -> {
			if (hasFocus) {
				return;
			}
			String minutes = textfieldAutoStartMinutes.getText();
			if (minutes.isEmpty()) {
				minutes = "5";
				textfieldAutoStartMinutes.setText(minutes);
			}
			ScheduleService.obtain().persistEmulatorPath(
					ConfigurationKeyEnum.AUTO_START_DELAY_MINUTES_INT.name(), minutes);
			triggerAutoStartReschedule();
		});

		comboboxAutoStartMode.setOnAction(evt -> {
			String mode = comboboxAutoStartMode.getValue();
			if (mode != null) {
				ScheduleService.obtain().persistEmulatorPath(
						ConfigurationKeyEnum.AUTO_START_MODE_STRING.name(), mode);
				triggerAutoStartReschedule();
			}
		});
	}

	private void triggerAutoStartReschedule() {
		LauncherLayoutController launcher = LauncherLayoutController.getInstance();
		if (launcher != null) {
			javafx.application.Platform.runLater(launcher::scheduleAutoStart);
		}
	}

	/* ────────────────────────────────────────────────
	 *  Analytics toggles
	 * ──────────────────────────────────────────────── */

	private void configureAnalyticsToggles(Map<String, String> cfg) {
		boolean analyticsOn = Boolean.parseBoolean(
				cfg.getOrDefault(ConfigurationKeyEnum.ANALYTICS_ENABLED_BOOL.name(),
						ConfigurationKeyEnum.ANALYTICS_ENABLED_BOOL.getDefaultValue()));
		checkboxAnalyticsEnabled.setSelected(analyticsOn);

		checkboxAnalyticsEnabled.selectedProperty().addListener((obs, prev, now) ->
			ConfigService.obtain().writeGlobalSetting(
					ConfigurationKeyEnum.ANALYTICS_ENABLED_BOOL, String.valueOf(now)));

		boolean hideLogs = Boolean.parseBoolean(
				cfg.getOrDefault(ConfigurationKeyEnum.HIDE_ANALYTICS_LOGS_BOOL.name(),
						ConfigurationKeyEnum.HIDE_ANALYTICS_LOGS_BOOL.getDefaultValue()));
		checkboxHideAnalyticsLogs.setSelected(hideLogs);

		checkboxHideAnalyticsLogs.selectedProperty().addListener((obs, prev, now) ->
			ConfigService.obtain().writeGlobalSetting(
					ConfigurationKeyEnum.HIDE_ANALYTICS_LOGS_BOOL, String.valueOf(now)));
	}

	/* ────────────────────────────────────────────────
	 *  Utility dialogs
	 * ──────────────────────────────────────────────── */

	private File promptForExecutable(String dialogTitle) {
		fileChooser.setTitle(dialogTitle);
		fileChooser.getExtensionFilters().clear();
		fileChooser.getExtensionFilters().add(
				new FileChooser.ExtensionFilter("Executable Files", "*.exe"));
		return fileChooser.showOpenDialog(null);
	}

	private void warnAboutConcurrentInstances() {
		int instanceLimit = 1;
		try {
			instanceLimit = Integer.parseInt(textfieldMaxConcurrentInstances.getText());
		} catch (NumberFormatException ignored) {
			// fall back to 1 when the field contains non-numeric text
		}

		Alert warning = new Alert(Alert.AlertType.WARNING);
		warning.setTitle("Important: Concurrent Instance Requirement");
		warning.setHeaderText("Close Game Option Selected");
		warning.setContentText(("""
				You have selected 'Close Game' behavior which keeps emulators running during idle periods.

				IMPORTANT: Make sure you have enough concurrent emulator instances (%d) to handle all your active profiles simultaneously. If you have more profiles than concurrent instances, some profiles won't be able to run.

				Consider:
				• Increasing 'Max Concurrent Instances' if needed
				• Using 'Close Emulator' if you have limited system resources
				""").formatted(instanceLimit));
		warning.showAndWait();
	}

	private void displayError(String detail) {
		Alert errorAlert = new Alert(Alert.AlertType.ERROR);
		errorAlert.setTitle("Error");
		errorAlert.setHeaderText(null);
		errorAlert.setContentText(detail);
		errorAlert.showAndWait();
	}

	private static final class HelpOnlyProfileItem {
		private final Long profileId;
		private final String profileName;
		private final String emulatorNumber;
		private final BooleanProperty selected = new SimpleBooleanProperty(false);

		private HelpOnlyProfileItem(Long profileId, String profileName, String emulatorNumber) {
			this.profileId = profileId;
			this.profileName = profileName == null ? "Unnamed" : profileName;
			this.emulatorNumber = emulatorNumber == null ? "-" : emulatorNumber;
		}

		private Long getProfileId() {
			return profileId;
		}

		private String getEmulatorNumber() {
			return emulatorNumber;
		}

		private boolean isSelected() {
			return selected.get();
		}

		private void setSelected(boolean value) {
			selected.set(value);
		}

		private BooleanProperty selectedProperty() {
			return selected;
		}

		private String getDisplayLabel() {
			return profileName + " (Emulator: " + emulatorNumber + ")";
		}
	}
}