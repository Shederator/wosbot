package dev.frostguard.app.panel.emulator;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.engine.emulator.EmulatorType;
import dev.frostguard.api.configs.GameVersionEnum;
import dev.frostguard.api.configs.IdleBehaviorEnum;
import dev.frostguard.api.configs.StopBehaviorEnum;
import dev.frostguard.app.panel.emulator.EmulatorAux;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.app.panel.launcher.LauncherLayoutController;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.FileChooser;

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

	/* ── Internal state ── */

	private final FileChooser fileChooser = new FileChooser();
	private final ObservableList<EmulatorAux> emulatorList = FXCollections.observableArrayList();

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
		configureStopBehaviorDropdowns(globalConfig);
		configureAutoStartSection(globalConfig);
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

					if (!picked.getName().equalsIgnoreCase(target.getEmulatorType().getExecutableName())) {
						displayError("File not valid, please select: " + target.getEmulatorType().getExecutableName());
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

		String maxIdleTime = normalizePositiveMinutes(
				cfg.get(ConfigurationKeyEnum.MAX_IDLE_TIME_INT.name()),
				ConfigurationKeyEnum.MAX_IDLE_TIME_INT.getDefaultValue());
		textfieldMaxIdleTime.setText(maxIdleTime);

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

		textfieldMaxIdleTime.focusedProperty().addListener((obs, prev, hasFocus) -> {
			if (hasFocus || textfieldMaxIdleTime.isDisabled()) {
				return;
			}
			String rawValue = textfieldMaxIdleTime.getText();
			String value = normalizePositiveMinutes(
					rawValue,
					ConfigurationKeyEnum.MAX_IDLE_TIME_INT.getDefaultValue());
			textfieldMaxIdleTime.setText(value);
			ScheduleService.obtain().persistEmulatorPath(
					ConfigurationKeyEnum.MAX_IDLE_TIME_INT.name(), value);
			if (!isPositiveMinutes(rawValue)) {
				displayInvalidMaxIdleTimeWarning(rawValue, value);
			}
		});

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
				}
			}
		});
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
		updateMaxIdleTimeAvailability(savedIdle);

		comboboxInactivityPolicy.setOnAction(evt -> {
			IdleBehaviorEnum chosenBehavior = comboboxInactivityPolicy.getValue();
			if (chosenBehavior != null) {
				updateMaxIdleTimeAvailability(chosenBehavior);
				ScheduleService.obtain().persistEmulatorPath(
						ConfigurationKeyEnum.IDLE_BEHAVIOR_STRING.name(),
						chosenBehavior.name());
				if (chosenBehavior.shouldSendToBackground()) {
					warnAboutConcurrentInstances();
				}
			}
		});
	}

	private void updateMaxIdleTimeAvailability(IdleBehaviorEnum behavior) {
		textfieldMaxIdleTime.setDisable(!behavior.requiresIdleTimeout());
	}

	static String normalizePositiveMinutes(String rawValue, String defaultValue) {
		try {
			int value = Integer.parseInt(rawValue == null ? "" : rawValue.trim());
			return value > 0 ? String.valueOf(value) : defaultValue;
		} catch (NumberFormatException ex) {
			return defaultValue;
		}
	}

	static boolean isPositiveMinutes(String rawValue) {
		try {
			return Integer.parseInt(rawValue == null ? "" : rawValue.trim()) > 0;
		} catch (NumberFormatException ex) {
			return false;
		}
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
		warning.setContentText(
				"You have selected 'Close Game' behavior which keeps emulators running during idle periods.\n\n"
				+ "IMPORTANT: Make sure you have enough concurrent emulator instances (" + instanceLimit + ") "
				+ "to handle all your active profiles simultaneously. If you have more profiles than concurrent "
				+ "instances, some profiles won't be able to run.\n\n"
				+ "Consider:\n"
				+ "• Increasing 'Max Concurrent Instances' if needed\n"
				+ "• Using 'Close Emulator' if you have limited system resources");
		warning.showAndWait();
	}

	private void displayError(String detail) {
		Alert errorAlert = new Alert(Alert.AlertType.ERROR);
		errorAlert.setTitle("Error");
		errorAlert.setHeaderText(null);
		errorAlert.setContentText(detail);
		errorAlert.showAndWait();
	}

	private void displayInvalidMaxIdleTimeWarning(String rawValue, String fallbackValue) {
		String enteredValue = rawValue == null || rawValue.isBlank() ? "an empty value" : "'" + rawValue + "'";
		Alert warning = new Alert(Alert.AlertType.WARNING);
		warning.setTitle("Invalid Max Idle Time");
		warning.setHeaderText("Enter a positive whole number of minutes");
		warning.setContentText(
				enteredValue + " is not a valid maximum idle time. The value was reset to "
				+ fallbackValue + " minutes. Select 'Keep Running' if no idle action should occur.");
		warning.showAndWait();
	}
}
