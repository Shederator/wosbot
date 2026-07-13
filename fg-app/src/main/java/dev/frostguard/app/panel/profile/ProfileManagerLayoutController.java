package dev.frostguard.app.panel.profile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.kordamp.ikonli.javafx.FontIcon;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ProfileStatusData;
import dev.frostguard.engine.service.LoggingService;
import dev.frostguard.engine.service.ProfileService;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.Popup;
import javafx.util.Duration;

public class ProfileManagerLayoutController implements IProfileChangeObserver {

	private static final String SORT_NAME = "Name";
	private static final String SORT_PRIORITY = "Priority";
	private static final String SORT_STATUS = "Status";
	private static final String SORT_EMULATOR = "Emulator";

	private final ExecutorService profileQueueExecutor = Executors.newSingleThreadExecutor();
	private final List<IProfileLoadListener> profileLoadListeners = new ArrayList<>();
	private ProfileManagerActionController profileManagerActionController;
	private ObservableList<ProfileAux> profiles;
	private FilteredList<ProfileAux> filteredProfiles;
	private SortedList<ProfileAux> sortedProfiles;
	private Long loadedProfileId;

	@FXML
	private TableView<ProfileAux> tableviewLogMessages;
	@FXML
	private TableColumn<ProfileAux, Void> columnDelete;
	@FXML
	private TableColumn<ProfileAux, String> columnEmulatorNumber;
	@FXML
	private TableColumn<ProfileAux, Boolean> columnEnabled;
	@FXML
	private TableColumn<ProfileAux, String> columnProfileName;
	@FXML
	private TableColumn<ProfileAux, Long> columnPriority;
	@FXML
	private TableColumn<ProfileAux, String> columnStatus;
	@FXML
	private TableColumn<ProfileAux, String> columnFurnaceLevel;
	@FXML
	private TableColumn<ProfileAux, String> columnStamina;
	@FXML
	private Button btnBulkUpdate;
	@FXML
	private TextField txtSearchProfiles;
	@FXML
	private ComboBox<String> comboBoxSortBy;
	@FXML
	private Button btnColumnSettings;

	@FXML
	private void initialize() {
		profileManagerActionController = new ProfileManagerActionController(this);
		initializeTableView();
		initializeSearchAndSort();
		loadProfiles();
		ProfileService.obtain().registerDataObserver(dto -> Platform.runLater(() -> handleProfileDataChange(dto)));
	}

	private void initializeSearchAndSort() {
		comboBoxSortBy.setItems(FXCollections.observableArrayList(SORT_NAME, SORT_PRIORITY, SORT_STATUS, SORT_EMULATOR));
		comboBoxSortBy.getSelectionModel().selectFirst();
		if (txtSearchProfiles != null) {
			txtSearchProfiles.textProperty().addListener((obs, oldVal, newVal) -> applyFilter(newVal));
		}
		comboBoxSortBy.valueProperty().addListener((obs, oldVal, newVal) -> applySort(newVal));
	}

	@FXML
	private void handleClearSearch(ActionEvent event) {
		if (txtSearchProfiles != null) {
			txtSearchProfiles.clear();
		}
	}

	private void applyFilter(String searchText) {
		if (filteredProfiles == null) {
			return;
		}
		String needle = searchText == null ? "" : searchText.toLowerCase().trim();
		filteredProfiles.setPredicate(profile -> needle.isEmpty()
				|| (profile.getName() != null && profile.getName().toLowerCase().contains(needle)));
	}

	private void applySort(String sortBy) {
		if (sortedProfiles == null || sortBy == null) {
			return;
		}
		if (SORT_NAME.equals(sortBy)) {
			bindToTableComparator();
			return;
		}
		if (sortedProfiles.comparatorProperty().isBound()) {
			sortedProfiles.comparatorProperty().unbind();
		}
		sortedProfiles.setComparator(comparatorFor(sortBy));
	}

	private Comparator<ProfileAux> comparatorFor(String sortBy) {
		return switch (sortBy) {
			case SORT_PRIORITY -> Comparator.comparingLong(profile ->
					profile.getPriority() == null ? Long.MAX_VALUE : profile.getPriority());
			case SORT_STATUS -> Comparator.comparing(profile -> profile.getStatus() == null ? "" : profile.getStatus());
			case SORT_EMULATOR -> Comparator.comparing(profile ->
					profile.getEmulatorNumber() == null ? "" : String.valueOf(profile.getEmulatorNumber()));
			default -> Comparator.comparing(profile -> profile.getName() == null ? "" : profile.getName());
		};
	}

	private void bindToTableComparator() {
		if (!sortedProfiles.comparatorProperty().isBound()) {
			sortedProfiles.comparatorProperty().bind(tableviewLogMessages.comparatorProperty());
		}
	}

	@FXML
	private void handleColumnSettings(ActionEvent event) {
		ContextMenu menu = new ContextMenu();
		addColumnToggle(menu, "Enabled", columnEnabled);
		addColumnToggle(menu, "Emulator", columnEmulatorNumber);
		addColumnToggle(menu, "Furnace Level", columnFurnaceLevel);
		addColumnToggle(menu, "Name", columnProfileName);
		addColumnToggle(menu, "Priority", columnPriority);
		addColumnToggle(menu, "Status", columnStatus);
		addColumnToggle(menu, "Stamina", columnStamina);
		addColumnToggle(menu, "Actions", columnDelete);
		menu.show(btnColumnSettings, javafx.geometry.Side.BOTTOM, 0, 4);
	}

	private void addColumnToggle(ContextMenu menu, String label, TableColumn<?, ?> column) {
		if (column == null) {
			return;
		}
		CheckMenuItem item = new CheckMenuItem(label);
		item.setSelected(column.isVisible());
		item.setOnAction(event -> column.setVisible(item.isSelected()));
		menu.getItems().add(item);
	}

	private void initializeTableView() {
		profiles = FXCollections.observableArrayList();
		filteredProfiles = new FilteredList<>(profiles);
		sortedProfiles = new SortedList<>(filteredProfiles);

		columnProfileName.setCellValueFactory(cellData -> cellData.getValue().nameProperty());
		columnEmulatorNumber.setCellValueFactory(cellData -> cellData.getValue().emulatorNumberProperty());
		columnPriority.setCellValueFactory(cellData -> cellData.getValue().priorityProperty().asObject());
		columnStatus.setCellValueFactory(cellData -> cellData.getValue().statusProperty());
		columnEnabled.setCellValueFactory(cellData -> cellData.getValue().enabledProperty());

		if (columnFurnaceLevel != null) {
			columnFurnaceLevel.setCellFactory(col -> placeholderCell());
		}
		if (columnStamina != null) {
			columnStamina.setCellFactory(col -> placeholderCell());
		}
		tableviewLogMessages.setRowFactory(table -> editableProfileRow());
		columnDelete.setCellFactory(col -> new ProfileActionsCell());
		columnEnabled.setCellFactory(col -> new EnabledSwitchCell());

		bindToTableComparator();
		tableviewLogMessages.setItems(sortedProfiles);
	}

	private TableCell<ProfileAux, String> placeholderCell() {
		return new TableCell<>() {
			@Override
			protected void updateItem(String item, boolean empty) {
				super.updateItem(item, empty);
				setText(empty ? null : "-");
				setAlignment(Pos.CENTER);
			}
		};
	}

	private TableRow<ProfileAux> editableProfileRow() {
		TableRow<ProfileAux> row = new TableRow<>();
		row.setOnMouseClicked(event -> {
			if (event.getClickCount() == 2 && !row.isEmpty()) {
				profileManagerActionController.showEditProfileDialog(row.getItem(), tableviewLogMessages);
			}
		});
		return row;
	}

	@FXML
	void handleButtonAddProfile(ActionEvent event) {
		profileManagerActionController.showNewProfileDialog();
	}

	@FXML
	void handleButtonBulkUpdateProfiles(ActionEvent event) {
		profileManagerActionController.showBulkUpdateDialog(loadedProfileId, profiles, btnBulkUpdate);
	}

	private void showTasksPopup(ProfileAux profile, Node ownerNode) {
		Popup popup = new Popup();
		popup.setAutoHide(true);

		VBox root = new VBox(10);
		root.getStyleClass().add("tasks-popup-surface");
		Label title = new Label("Enabled Tasks: " + profile.getName());
		title.getStyleClass().add("tasks-popup-title");
		root.getChildren().add(title);

		FlowPane flowPane = enabledTasksFlow(profile);
		if (flowPane.getChildren().isEmpty()) {
			Label empty = new Label("No tasks enabled.");
			empty.getStyleClass().add("tasks-popup-empty");
			root.getChildren().add(empty);
		} else {
			root.getChildren().add(flowPane);
		}

		popup.getContent().add(root);
		javafx.geometry.Bounds bounds = ownerNode.localToScreen(ownerNode.getBoundsInLocal());
		if (bounds != null) {
			popup.show(ownerNode, bounds.getMinX(), bounds.getMaxY() + 5);
		}
	}

	private FlowPane enabledTasksFlow(ProfileAux profile) {
		FlowPane flowPane = new FlowPane(8, 8);
		flowPane.setPrefWidth(280);
		for (ConfigAux cfg : profile.getConfigs()) {
			if (isEnabledTaskConfig(cfg)) {
				flowPane.getChildren().add(taskChip(formatTaskName(cfg.getName())));
			}
		}
		return flowPane;
	}

	private boolean isEnabledTaskConfig(ConfigAux cfg) {
		String key = cfg.getName();
		return "true".equalsIgnoreCase(cfg.getValue())
				&& key != null
				&& key.endsWith("_BOOL")
				&& !key.equals("BOOL_DEBUG")
				&& !key.equals("TELEGRAM_BOT_ENABLED_BOOL")
				&& !key.equals("AUTO_START_ENABLED_BOOL");
	}

	private Label taskChip(String label) {
		Label chip = new Label(label);
		chip.getStyleClass().add("task-chip");
		FontIcon check = new FontIcon("mdi2c-check-circle");
		check.setIconSize(12);
		check.setIconColor(Color.WHITE);
		chip.setGraphic(check);
		return chip;
	}

	private String formatTaskName(String key) {
		String name = key;
		if (name.endsWith("_BOOL")) {
			name = name.substring(0, name.length() - 5);
		}
		if (name.startsWith("BOOL_")) {
			name = name.substring(5);
		}

		StringBuilder display = new StringBuilder();
		for (String word : name.replace("_", " ").toLowerCase().split(" ")) {
			if (!word.isEmpty()) {
				display.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
			}
		}
		return display.toString().trim();
	}

	public void loadProfiles() {
		profileManagerActionController.loadProfiles(accounts -> Platform.runLater(() -> {
			profiles.setAll(accounts.stream().map(this::toProfileAux).toList());
			selectLoadedProfile();
			reapplyCurrentSort();
		}));
	}

	private ProfileAux toProfileAux(AccountDescriptor account) {
		ProfileAux profileAux = new ProfileAux(
				account.getId(),
				account.getName(),
				account.getEmulatorNumber(),
				account.getEnabled(),
				account.getPriority(),
				"NOT RUNNING",
				account.getReconnectionTime(),
				account.getCharacterId(),
				account.getCharacterName(),
				account.getCharacterAllianceCode(),
				account.getCharacterServer());
		account.getConfigs().forEach(config ->
				profileAux.getConfigs().add(new ConfigAux(config.getConfigurationName(), config.getValue())));
		return profileAux;
	}

	private void selectLoadedProfile() {
		if (profiles.isEmpty()) {
			return;
		}
		ProfileAux selectedProfile = profiles.stream()
				.filter(profile -> Objects.equals(profile.getId(), loadedProfileId))
				.findFirst()
				.orElse(profiles.get(0));
		loadedProfileId = selectedProfile.getId();
		notifyProfileLoadListeners(selectedProfile);
	}

	private void reapplyCurrentSort() {
		String currentSort = comboBoxSortBy == null ? null : comboBoxSortBy.getValue();
		if (currentSort != null && !SORT_NAME.equals(currentSort)) {
			applySort(currentSort);
		}
	}

	private void handleProfileDataChange(AccountDescriptor dto) {
		try {
			if (dto == null || profiles == null || profiles.isEmpty()) {
				loadProfiles();
				return;
			}

			ProfileAux target = profiles.stream()
					.filter(profile -> Objects.equals(profile.getId(), dto.getId()))
					.findFirst()
					.orElse(null);
			if (target == null) {
				loadProfiles();
				return;
			}

			mergeProfileFields(target, dto);
			if (dto.getConfigs() == null || dto.getConfigs().isEmpty()) {
				loadProfiles();
				return;
			}
			mergeProfileConfigs(target, dto);

			tableviewLogMessages.refresh();
			if (Objects.equals(target.getId(), loadedProfileId)) {
				notifyProfileLoadListeners(target);
			}
		} catch (Exception ex) {
			loadProfiles();
		}
	}

	private void mergeProfileFields(ProfileAux target, AccountDescriptor dto) {
		if (dto.getName() != null) {
			target.setName(dto.getName());
		}
		if (dto.getEmulatorNumber() != null) {
			target.setEmulatorNumber(dto.getEmulatorNumber());
		}
		if (dto.getPriority() != null) {
			target.setPriority(dto.getPriority());
		}
		if (dto.getEnabled() != null) {
			target.setEnabled(dto.getEnabled());
		}
		if (dto.getReconnectionTime() != null) {
			target.setReconnectionTime(dto.getReconnectionTime());
		}
		if (dto.getCharacterId() != null) {
			target.setCharacterId(dto.getCharacterId());
		}
		if (dto.getCharacterName() != null) {
			target.setCharacterName(dto.getCharacterName());
		}
		if (dto.getCharacterAllianceCode() != null) {
			target.setCharacterAllianceCode(dto.getCharacterAllianceCode());
		}
		if (dto.getCharacterServer() != null) {
			target.setCharacterServer(dto.getCharacterServer());
		}
	}

	private void mergeProfileConfigs(ProfileAux target, AccountDescriptor dto) {
		dto.getConfigs().forEach(cfgDto -> {
			ConfigAux existing = target.getConfigs().stream()
					.filter(config -> config.getName().equals(cfgDto.getConfigurationName()))
					.findFirst()
					.orElse(null);
			if (existing == null) {
				target.getConfigs().add(new ConfigAux(cfgDto.getConfigurationName(), cfgDto.getValue()));
			} else {
				existing.setValue(cfgDto.getValue());
			}
		});
	}

	public void addProfileLoadListener(IProfileLoadListener moduleController) {
		profileLoadListeners.add(moduleController);
	}

	public javafx.collections.ObservableList<ProfileAux> getProfiles() {
		return profiles;
	}

	public void setLoadedProfileId(Long profileId) {
		this.loadedProfileId = profileId;
	}

	public Long getLoadedProfileId() {
		return loadedProfileId;
	}

	public void notifyProfileLoadListeners(ProfileAux currentProfile) {
		profileLoadListeners.forEach(listener -> listener.onProfileLoad(currentProfile));
	}

	public void handleProfileStatusChange(ProfileStatusData status) {
		Platform.runLater(() -> {
			if (profiles == null) {
				return;
			}
			profiles.stream()
					.filter(profile -> Objects.equals(profile.getId(), status.getId()))
					.forEach(profile -> profile.setStatus(status.getStatus()));
			tableviewLogMessages.refresh();
			tableviewLogMessages.sort();
		});
	}

	@Override
	public void notifyProfileChange(ConfigurationKeyEnum key, Object value) {
		try {
			ProfileAux loadedProfile = profiles.stream()
					.filter(profile -> Objects.equals(profile.getId(), loadedProfileId))
					.findFirst()
					.orElse(null);
			if (loadedProfile == null) {
				return;
			}

			loadedProfile.setConfig(key, value);
			profileQueueExecutor.submit(() -> profileManagerActionController.saveProfile(loadedProfile));
		} catch (Exception e) {
			e.printStackTrace();
			LoggingService.obtain().emit(
					TpMessageSeverityEnum.ERROR,
					"Profile Manager",
					"-",
					"Error while saving profile: " + e.getMessage());
		}
	}

	private void showAlert(Alert.AlertType type, String title, String content) {
		Alert alert = new Alert(type);
		alert.setTitle(title);
		alert.setHeaderText(null);
		alert.setContentText(content);
		alert.showAndWait();
	}

	private ProfileAux rowProfile(TableCell<ProfileAux, ?> cell) {
		TableRow<ProfileAux> row = cell.getTableRow();
		return row == null ? null : row.getItem();
	}

	private Button iconButton(String iconLiteral, int size, String color, String tooltip) {
		Button button = new Button();
		FontIcon icon = new FontIcon(iconLiteral);
		icon.setIconSize(size);
		icon.setIconColor(Color.web(color));
		button.setGraphic(icon);
		button.getStyleClass().add("action-icon-button");
		button.setTooltip(new Tooltip(tooltip));
		return button;
	}

	private final class ProfileActionsCell extends TableCell<ProfileAux, Void> {
		private final Button btnDelete = iconButton("mdi2c-close", 16, "#f85149", "Delete Profile");
		private final Button btnViewTasks = iconButton("mdi2f-format-list-checks", 18, "#388bfd", "View Enabled Tasks");
		private final Button btnLoad = iconButton("mdi2p-play", 22, "#2ea043", "Load Profile");
		private final HBox buttonContainer = new HBox(5, btnLoad, btnViewTasks, btnDelete);

		private ProfileActionsCell() {
			buttonContainer.setAlignment(Pos.CENTER);
			btnViewTasks.setOnAction(event -> {
				ProfileAux currentProfile = rowProfile(this);
				if (currentProfile != null) {
					showTasksPopup(currentProfile, btnViewTasks);
				}
			});
			btnDelete.setOnAction(this::deleteCurrentProfile);
			btnLoad.setOnAction(event -> {
				ProfileAux currentProfile = rowProfile(this);
				if (currentProfile != null) {
					loadedProfileId = currentProfile.getId();
					notifyProfileLoadListeners(currentProfile);
				}
			});
		}

		@Override
		protected void updateItem(Void item, boolean empty) {
			super.updateItem(item, empty);
			setGraphic(empty ? null : buttonContainer);
		}

		private void deleteCurrentProfile(ActionEvent event) {
			if (getTableView().getItems().size() <= 1) {
				showAlert(Alert.AlertType.WARNING, "WARNING", "You must have at least one profile.");
				return;
			}

			ProfileAux currentProfile = rowProfile(this);
			if (currentProfile == null) {
				return;
			}
			boolean deleted = profileManagerActionController.deleteProfile(new AccountDescriptor(currentProfile.getId()));
			if (deleted) {
				showAlert(Alert.AlertType.INFORMATION, "SUCCESS", "Profile deleted successfully.");
				loadProfiles();
			} else {
				showAlert(Alert.AlertType.ERROR, "ERROR", "Error deleting profile.");
			}
		}
	}

	private final class EnabledSwitchCell extends TableCell<ProfileAux, Boolean> {
		private final ToggleButton toggleButton = new ToggleButton();
		private final Rectangle background = new Rectangle(32, 16, Color.web("#3b3f4c"));
		private final Circle knob = new Circle(6, Color.web("#1a1c24"));
		private final StackPane switchContainer = new StackPane(background, knob);

		private EnabledSwitchCell() {
			background.setArcWidth(16);
			background.setArcHeight(16);
			knob.setTranslateX(-8);
			switchContainer.setMinSize(40, 20);
			switchContainer.setMaxSize(40, 20);
			switchContainer.setAlignment(Pos.CENTER);
			switchContainer.setOnMouseClicked(event -> toggleSwitch());
			toggleButton.setOnAction(event -> applySwitchValue(toggleButton.isSelected(), false));
		}

		@Override
		protected void updateItem(Boolean item, boolean empty) {
			super.updateItem(item, empty);
			if (empty || item == null) {
				setGraphic(null);
				return;
			}
			toggleButton.setSelected(item);
			paintSwitch(item);
			setGraphic(switchContainer);
			setAlignment(Pos.CENTER);
		}

		private void toggleSwitch() {
			applySwitchValue(!toggleButton.isSelected(), true);
		}

		private void applySwitchValue(boolean enabled, boolean persist) {
			toggleButton.setSelected(enabled);
			animateSwitch(enabled);
			ProfileAux currentProfile = rowProfile(this);
			if (currentProfile != null) {
				currentProfile.setEnabled(enabled);
				if (persist) {
					profileManagerActionController.saveProfile(currentProfile);
				}
			}
		}

		private void animateSwitch(boolean enabled) {
			TranslateTransition slide = new TranslateTransition(Duration.millis(180), knob);
			slide.setToX(enabled ? 8 : -8);
			background.setFill(enabled ? Color.web("#ffcd53") : Color.web("#3b3f4c"));
			slide.play();
		}

		private void paintSwitch(boolean enabled) {
			background.setFill(enabled ? Color.web("#ffcd53") : Color.web("#3b3f4c"));
			knob.setTranslateX(enabled ? 8 : -8);
		}
	}
}
