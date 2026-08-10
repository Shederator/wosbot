package dev.frostguard.app.panel.console;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.LogMessageData;
import dev.frostguard.engine.listener.ProfileDataChangeListener;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.service.ScheduleService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.text.Text;

public class ConsoleLogLayoutController implements ProfileDataChangeListener {

	private static final String ALL_PROFILES = "All profiles";
	private static final String ALL_LEVELS = "All levels";
	private static final int MAX_LOG_ROWS = 600;
	private static final DateTimeFormatter LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy");

	@FXML
	private Button buttonClearLogs;

	@FXML
	private Button buttonOpenLogFolder;

	@FXML
	private CheckBox checkboxDebug;

	@FXML
	private ComboBox<String> comboBoxProfileFilter;

	@FXML
	private ComboBox<String> comboBoxLevelFilter;

	@FXML
	private TextField txtSearchLogs;

	@FXML
	private Button btnClearSearch;

	@FXML
	private TableView<LogMessageAux> tableviewLogMessages;

	@FXML
	private TableColumn<LogMessageAux, String> columnMessage;

	@FXML
	private TableColumn<LogMessageAux, String> columnTimeStamp;

	@FXML
	private TableColumn<LogMessageAux, String> columnProfile;

	@FXML
	private TableColumn<LogMessageAux, String> columnTask;

	@FXML
	private TableColumn<LogMessageAux, String> columnLevel;

	private ObservableList<LogMessageAux> logMessages;
	private FilteredList<LogMessageAux> filteredLogMessages;

	@FXML
	private void initialize() {
		new ConsoleLogActionController(this);
		logMessages = FXCollections.observableArrayList();
		filteredLogMessages = new FilteredList<>(logMessages);

		configureDebugToggle();
		configureTableColumns();
		configureFilters();
		tableviewLogMessages.setItems(filteredLogMessages);
		tableviewLogMessages.setPlaceholder(new Label("NO LOGS"));
		ProfileService.obtain().registerDataObserver(this);
	}

	@FXML
	void handleButtonClearLogs(ActionEvent event) {
		Platform.runLater(logMessages::clear);
	}

	@FXML
	void handleClearSearch(ActionEvent event) {
		if (txtSearchLogs != null) {
			txtSearchLogs.clear();
		}
	}

	@FXML
	void handleButtonOpenLogFolder(ActionEvent event) {
		try {
			Path logsDir = Path.of("log");
			Files.createDirectories(logsDir);
			Desktop.getDesktop().open(logsDir.toFile());
		} catch (IOException e) {
			System.err.println("Error opening logs folder: " + e.getMessage());
			e.printStackTrace();
		}
	}

	private void configureDebugToggle() {
		boolean defaultDebug = Boolean.parseBoolean(ConfigurationKeyEnum.BOOL_DEBUG.getDefaultValue());
		boolean debugEnabled = Optional.ofNullable(ConfigService.obtain().loadGlobalSettings())
				.map(cfg -> cfg.get(ConfigurationKeyEnum.BOOL_DEBUG.name()))
				.map(Boolean::parseBoolean)
				.orElse(defaultDebug);
		checkboxDebug.setSelected(debugEnabled);
		checkboxDebug.setOnAction(event -> ScheduleService.obtain().persistEmulatorPath(
				ConfigurationKeyEnum.BOOL_DEBUG.name(),
				String.valueOf(checkboxDebug.isSelected())));
	}

	private void configureTableColumns() {
		columnTimeStamp.setCellValueFactory(cellData -> cellData.getValue().timeStampProperty());
		columnMessage.setCellValueFactory(cellData -> cellData.getValue().messageProperty());
		columnLevel.setCellValueFactory(cellData -> cellData.getValue().severityProperty());
		columnTask.setCellValueFactory(cellData -> cellData.getValue().taskProperty());
		columnProfile.setCellValueFactory(cellData -> cellData.getValue().profileProperty());

		columnMessage.setCellFactory(column -> wrappingMessageCell());
		columnLevel.setCellFactory(column -> levelCell());
		columnTask.setCellFactory(column -> styledTextCell("log-module-text"));
		columnProfile.setCellFactory(column -> styledTextCell("log-module-text"));
	}

	private TableCell<LogMessageAux, String> wrappingMessageCell() {
		return new TableCell<>() {
			private final Text text = new Text();

			{
				setGraphic(text);
				text.wrappingWidthProperty().bind(widthProperty());
				text.fillProperty().bind(textFillProperty());
				setPrefHeight(USE_COMPUTED_SIZE);
			}

			@Override
			protected void updateItem(String item, boolean empty) {
				super.updateItem(item, empty);
				text.setText(empty ? null : item);
			}
		};
	}

	private TableCell<LogMessageAux, String> levelCell() {
		return new TableCell<>() {
			@Override
			protected void updateItem(String item, boolean empty) {
				super.updateItem(item, empty);
				getStyleClass().removeAll("log-level-info", "status-stopped");
				if (empty || item == null) {
					setText(null);
					return;
				}
				setText(item);
				if ("INFO".equalsIgnoreCase(item)) {
					getStyleClass().add("log-level-info");
				} else if ("ERROR".equalsIgnoreCase(item)) {
					getStyleClass().add("status-stopped");
				}
			}
		};
	}

	private TableCell<LogMessageAux, String> styledTextCell(String styleClass) {
		return new TableCell<>() {
			@Override
			protected void updateItem(String item, boolean empty) {
				super.updateItem(item, empty);
				getStyleClass().remove(styleClass);
				if (empty || item == null) {
					setText(null);
					return;
				}
				setText(item);
				getStyleClass().add(styleClass);
			}
		};
	}

	private void configureFilters() {
		comboBoxLevelFilter.setItems(FXCollections.observableArrayList(ALL_LEVELS, "INFO", "DEBUG", "WARNING", "ERROR"));
		comboBoxLevelFilter.getSelectionModel().selectFirst();
		refreshProfileFilter();

		comboBoxProfileFilter.valueProperty().addListener((obs, oldVal, newVal) -> updateLogFilter());
		comboBoxLevelFilter.valueProperty().addListener((obs, oldVal, newVal) -> updateLogFilter());
		if (txtSearchLogs != null) {
			txtSearchLogs.textProperty().addListener((obs, oldVal, newVal) -> updateLogFilter());
		}
	}

	private void refreshProfileFilter() {
		try {
			List<AccountDescriptor> profiles = ProfileService.obtain().fetchAllAccounts();
			if (profiles == null) {
				return;
			}

			String previousSelection = comboBoxProfileFilter.getSelectionModel().getSelectedItem();
			ObservableList<String> profileNames = FXCollections.observableArrayList();
			profileNames.add(ALL_PROFILES);
			profiles.forEach(profile -> profileNames.add(profile.getName()));
			comboBoxProfileFilter.setItems(profileNames);

			if (previousSelection != null && profileNames.contains(previousSelection)) {
				comboBoxProfileFilter.getSelectionModel().select(previousSelection);
			} else {
				comboBoxProfileFilter.getSelectionModel().selectFirst();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void updateLogFilter() {
		filteredLogMessages.setPredicate(logMessage -> matchesSearch(logMessage)
				&& matchesProfile(logMessage)
				&& matchesLevel(logMessage));
	}

	private boolean matchesSearch(LogMessageAux logMessage) {
		if (txtSearchLogs == null || txtSearchLogs.getText() == null || txtSearchLogs.getText().isEmpty()) {
			return true;
		}
		String search = txtSearchLogs.getText().toLowerCase();
		return containsIgnoreCase(logMessage.messageProperty().get(), search)
				|| containsIgnoreCase(logMessage.taskProperty().get(), search)
				|| containsIgnoreCase(logMessage.profileProperty().get(), search);
	}

	private boolean matchesProfile(LogMessageAux logMessage) {
		String selectedProfile = comboBoxProfileFilter.getValue();
		if (selectedProfile == null || selectedProfile.isEmpty() || ALL_PROFILES.equals(selectedProfile)) {
			return true;
		}
		return selectedProfile.equals(logMessage.profileProperty().get());
	}

	private boolean matchesLevel(LogMessageAux logMessage) {
		String selectedLevel = comboBoxLevelFilter.getValue();
		if (selectedLevel == null || selectedLevel.isEmpty() || ALL_LEVELS.equals(selectedLevel)) {
			return true;
		}
		String messageLevel = logMessage.severityProperty().get();
		return messageLevel != null && messageLevel.equalsIgnoreCase(selectedLevel);
	}

	private boolean containsIgnoreCase(String value, String lowercaseNeedle) {
		return value != null && value.toLowerCase().contains(lowercaseNeedle);
	}

	public void appendMessage(LogMessageData dtoMessage) {
		if (!checkboxDebug.isSelected() && dtoMessage.getSeverity() == TpMessageSeverityEnum.DEBUG) {
			return;
		}

		String formattedDate = LocalDateTime.now().format(LOG_TIME_FORMAT);
		LogMessageAux row = new LogMessageAux(
				formattedDate,
				dtoMessage.getSeverity().name(),
				dtoMessage.getBody(),
				dtoMessage.getSourceTask(),
				dtoMessage.getAccountTag());

		Platform.runLater(() -> {
			logMessages.add(0, row);
			if (logMessages.size() > MAX_LOG_ROWS) {
				logMessages.remove(logMessages.size() - 1);
			}
		});
	}

	@Override
	public void onAccountDataModified(AccountDescriptor profile) {
		Platform.runLater(this::refreshProfileFilter);
	}
}
