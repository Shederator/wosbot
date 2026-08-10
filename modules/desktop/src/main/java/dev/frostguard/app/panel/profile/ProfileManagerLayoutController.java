package dev.frostguard.app.panel.profile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;
import java.io.File;

import org.kordamp.ikonli.javafx.FontIcon;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ProfileStatusData;
import dev.frostguard.api.domain.ProfileTagData;
import dev.frostguard.engine.service.LoggingService;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.service.ScheduleService;
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
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.ListView;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.Separator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
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
import javafx.stage.FileChooser;
import javafx.util.Duration;

public class ProfileManagerLayoutController implements IProfileChangeObserver {

	private static final java.util.Set<ConfigurationKeyEnum> BEAR_PARTICIPATION_SCHEDULE_KEYS = java.util.Set.of(
			ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL,
			ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT,
			ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT,
			ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING,
			ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_SCHEDULE_DATETIME_STRING);

	private static final String SORT_NAME = "Name";
	private static final String SORT_PRIORITY = "Priority";
	private static final String SORT_STATUS = "Status";
	private static final String SORT_EMULATOR = "Emulator";
	private static final String ALL_PROFILES_VIEW = "All profiles";

	private final ExecutorService profileQueueExecutor = Executors.newSingleThreadExecutor();
	private final List<IProfileLoadListener> profileLoadListeners = new ArrayList<>();
	private ProfileManagerActionController profileManagerActionController;
	private ObservableList<ProfileAux> profiles;
	private FilteredList<ProfileAux> filteredProfiles;
	private SortedList<ProfileAux> sortedProfiles;
	private Long loadedProfileId;
	private final Set<Long> selectedProfileIds = new HashSet<>();
	private final CheckBox selectAllVisible = new CheckBox();
	private final List<String> structuredFilters = new ArrayList<>();
	private final ContextMenu searchSuggestions = new ContextMenu();
	private Map<String, String> tagColors = Map.of();
	private final Preferences savedViewPreferences = Preferences.userNodeForPackage(
			ProfileManagerLayoutController.class).node("profile-search-views");

	@FXML
	private TableView<ProfileAux> tableviewLogMessages;
	@FXML
	private TableColumn<ProfileAux, Void> columnDelete;
	@FXML
	private TableColumn<ProfileAux, String> columnEmulatorNumber;
	@FXML
	private TableColumn<ProfileAux, Boolean> columnEnabled;
	@FXML
	private TableColumn<ProfileAux, Void> columnSelected;
	@FXML
	private TableColumn<ProfileAux, String> columnProfileName;
	@FXML
	private TableColumn<ProfileAux, String> columnServer;
	@FXML
	private TableColumn<ProfileAux, String> columnAlliance;
	@FXML
	private TableColumn<ProfileAux, String> columnTags;
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
	private Button btnImportProfiles;
	@FXML
	private Button btnExportProfiles;
	@FXML
	private Button btnEnableSelected;
	@FXML
	private Button btnDisableSelected;
	@FXML
	private Button btnTagsSelected;
	@FXML
	private Label lblSelectionCount;
	@FXML
	private Separator selectionSeparator;
	@FXML
	private FlowPane structuredFilterPane;
	@FXML
	private Button btnAddFilter;
	@FXML
	private TextField txtSearchProfiles;
	@FXML
	private ComboBox<String> comboBoxSortBy;
	@FXML
	private ComboBox<String> comboSavedViews;
	@FXML
	private Button btnColumnSettings;

	@FXML
	private void initialize() {
		profileManagerActionController = new ProfileManagerActionController(this);
		initializeTableView();
		initializeSearchAndSort();
		initializeSavedViews();
		initializeBulkSelection();
		loadProfiles();
		ProfileService.obtain().registerDataObserver(dto -> Platform.runLater(() -> handleProfileDataChange(dto)));
	}

	private void initializeSavedViews() {
		try {
			List<String> viewNames = new ArrayList<>(List.of(savedViewPreferences.keys()));
			viewNames.sort(String.CASE_INSENSITIVE_ORDER);
			viewNames.addFirst(ALL_PROFILES_VIEW);
			comboSavedViews.setItems(FXCollections.observableArrayList(viewNames));
			comboSavedViews.setOnAction(event -> {
				String name = comboSavedViews.getValue();
				if (name != null) {
					structuredFilters.clear();
					refreshStructuredFilterChips();
					txtSearchProfiles.setText(ALL_PROFILES_VIEW.equals(name) ? "" : savedViewPreferences.get(name, ""));
				}
			});
			if (comboSavedViews.getValue() == null) comboSavedViews.setValue(ALL_PROFILES_VIEW);
		} catch (Exception ex) {
			comboSavedViews.setDisable(true);
		}
	}

	@FXML
	private void handleSaveView(ActionEvent event) {
		String query = (String.join(" ", structuredFilters) + " " + txtSearchProfiles.getText()).trim();
		if (query.isBlank()) {
			showAlert(Alert.AlertType.WARNING, "Empty search", "Add at least one search or filter first.");
			return;
		}
		TextInputDialog prompt = new TextInputDialog(comboSavedViews.getValue());
		prompt.setTitle("Save profile view");
		prompt.setHeaderText("Save the current search");
		prompt.setContentText("View name:");
		prompt.showAndWait().map(String::trim).filter(name -> !name.isBlank()).ifPresent(name -> {
			if (ALL_PROFILES_VIEW.equalsIgnoreCase(name)) {
				showAlert(Alert.AlertType.WARNING, "Reserved name", "Choose a different name for this view.");
				return;
			}
			savedViewPreferences.put(name, query);
			initializeSavedViews();
			comboSavedViews.setValue(name);
		});
	}

	@FXML
	private void handleDeleteView(ActionEvent event) {
		String name = comboSavedViews.getValue();
		if (name == null || ALL_PROFILES_VIEW.equals(name)) return;
		savedViewPreferences.remove(name);
		comboSavedViews.getItems().remove(name);
		comboSavedViews.setValue(ALL_PROFILES_VIEW);
	}

	private void initializeSearchAndSort() {
		comboBoxSortBy.setItems(FXCollections.observableArrayList(SORT_NAME, SORT_PRIORITY, SORT_STATUS, SORT_EMULATOR));
		comboBoxSortBy.getSelectionModel().selectFirst();
		if (txtSearchProfiles != null) {
			txtSearchProfiles.textProperty().addListener((obs, oldVal, newVal) -> {
				applyFilter(newVal);
				refreshSearchSuggestions();
			});
			txtSearchProfiles.setOnMouseClicked(event -> refreshSearchSuggestions());
			txtSearchProfiles.focusedProperty().addListener((obs, oldVal, focused) -> {
				if (!focused) searchSuggestions.hide();
			});
		}
		comboBoxSortBy.valueProperty().addListener((obs, oldVal, newVal) -> applySort(newVal));
	}

	private void refreshSearchSuggestions() {
		if (txtSearchProfiles == null || !txtSearchProfiles.isFocused()) return;
		String token = currentSearchToken();
		searchSuggestions.getItems().clear();
		int separator = token.indexOf(':');
		if (separator < 0) {
			addQualifierSuggestion("server:", "Server number", token);
			addQualifierSuggestion("alliance:", "Alliance code", token);
			addQualifierSuggestion("tag:", "Profile tag", token);
			addQualifierSuggestion("status:", "Current profile status", token);
			addQualifierSuggestion("enabled:", "Enabled or disabled", token);
			addQualifierSuggestion("character:", "Character ID or name", token);
			addQualifierSuggestion("emulator:", "Emulator number", token);
			addQualifierSuggestion("name:", "Profile name", token);
		} else {
			String qualifier = token.substring(0, separator).toLowerCase();
			String value = token.substring(separator + 1).toLowerCase();
			searchValues(qualifier).stream().filter(candidate -> candidate.toLowerCase().contains(value))
					.limit(10).forEach(candidate -> addSearchSuggestion(qualifier + ":" + candidate, null));
		}
		if (searchSuggestions.getItems().isEmpty()) {
			searchSuggestions.hide();
		} else if (!searchSuggestions.isShowing()) {
			searchSuggestions.show(txtSearchProfiles, javafx.geometry.Side.BOTTOM, 0, 2);
		}
	}

	private void addQualifierSuggestion(String qualifier, String description, String typedToken) {
		if (typedToken.isBlank() || qualifier.startsWith(typedToken.toLowerCase())) {
			addSearchSuggestion(qualifier, description);
		}
	}

	private void addSearchSuggestion(String replacement, String description) {
		MenuItem item = new MenuItem(description == null ? replacement : replacement + "   " + description);
		item.setOnAction(event -> replaceCurrentSearchToken(replacement));
		searchSuggestions.getItems().add(item);
	}

	private List<String> searchValues(String qualifier) {
		if ("enabled".equals(qualifier)) return List.of("true", "false");
		if (profiles == null) return List.of();
		return profiles.stream().flatMap(profile -> switch (qualifier) {
			case "server" -> java.util.stream.Stream.of(profile.getCharacterServer());
			case "alliance" -> java.util.stream.Stream.of(profile.getCharacterAllianceCode());
			case "tag" -> profile.getTags().stream();
			case "status" -> java.util.stream.Stream.of(profile.getStatus());
			case "character" -> java.util.stream.Stream.of(profile.getCharacterId(), profile.getCharacterName());
			case "emulator" -> java.util.stream.Stream.of(profile.getEmulatorNumber());
			case "name" -> java.util.stream.Stream.of(profile.getName());
			default -> java.util.stream.Stream.empty();
		}).filter(Objects::nonNull).map(String::valueOf).filter(value -> !value.isBlank())
				.distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
	}

	private String currentSearchToken() {
		String text = txtSearchProfiles.getText();
		int caret = Math.min(txtSearchProfiles.getCaretPosition(), text.length());
		int start = text.lastIndexOf(' ', Math.max(0, caret - 1)) + 1;
		return text.substring(start, caret);
	}

	private void replaceCurrentSearchToken(String replacement) {
		String text = txtSearchProfiles.getText();
		int caret = Math.min(txtSearchProfiles.getCaretPosition(), text.length());
		int start = text.lastIndexOf(' ', Math.max(0, caret - 1)) + 1;
		int end = text.indexOf(' ', caret);
		if (end < 0) end = text.length();
		String suffix = replacement.endsWith(":") ? "" : " ";
		txtSearchProfiles.replaceText(start, end, replacement + suffix);
		txtSearchProfiles.positionCaret(start + replacement.length() + suffix.length());
		Platform.runLater(this::refreshSearchSuggestions);
	}

	@FXML
	private void handleClearSearch(ActionEvent event) {
		structuredFilters.clear();
		refreshStructuredFilterChips();
		comboSavedViews.setValue(ALL_PROFILES_VIEW);
		if (txtSearchProfiles != null) {
			txtSearchProfiles.clear();
		}
	}

	private void applyFilter(String searchText) {
		if (filteredProfiles == null) {
			return;
		}
		String combinedQuery = String.join(" ", structuredFilters) + " " + (searchText == null ? "" : searchText);
		filteredProfiles.setPredicate(profile -> ProfileSearchMatcher.matches(profile, combinedQuery.trim()));
		updateBulkSelectionBar();
	}

	@FXML
	private void handleAddFilter(ActionEvent event) {
		ContextMenu menu = new ContextMenu();
		addFilterOption(menu, "Server", "server");
		addFilterOption(menu, "Alliance", "alliance");
		addFilterOption(menu, "Tag", "tag");
		addFilterOption(menu, "Character", "character");
		addFilterOption(menu, "Emulator", "emulator");
		addFilterOption(menu, "Status", "status");
		MenuItem enabled = new MenuItem("Enabled profiles");
		enabled.setOnAction(action -> addStructuredFilter("enabled:true"));
		MenuItem disabled = new MenuItem("Disabled profiles");
		disabled.setOnAction(action -> addStructuredFilter("enabled:false"));
		menu.getItems().addAll(new javafx.scene.control.SeparatorMenuItem(), enabled, disabled);
		menu.show(btnAddFilter, javafx.geometry.Side.BOTTOM, 0, 4);
	}

	@FXML
	private void handleManageTags(ActionEvent event) {
		Dialog<ButtonType> dialog = new Dialog<>();
		dialog.setTitle("Manage profile tags");
		dialog.setHeaderText("Rename, recolor or delete tags");
		ListView<ProfileTagData> list = new ListView<>();
		list.setPrefSize(380, 260);
		list.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
			@Override protected void updateItem(ProfileTagData tag, boolean empty) {
				super.updateItem(tag, empty);
				setText(empty || tag == null ? null : tag.name());
				setStyle(empty || tag == null ? "" : "-fx-border-color: " + tag.color()
						+ "; -fx-border-width: 0 0 0 6; -fx-padding: 6 10;");
			}
		});
		list.setItems(FXCollections.observableArrayList(profileManagerActionController.loadTagDefinitions()));
		Button edit = new Button("Edit");
		Button delete = new Button("Delete");
		edit.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
		delete.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
		edit.setOnAction(action -> editTag(list));
		delete.setOnAction(action -> deleteTag(list));
		dialog.getDialogPane().setContent(new VBox(10, list, new HBox(8, edit, delete)));
		dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
		dialog.showAndWait();
		loadProfiles();
	}

	private void editTag(ListView<ProfileTagData> list) {
		ProfileTagData selected = list.getSelectionModel().getSelectedItem();
		if (selected == null) return;
		TextField name = new TextField(selected.name());
		TextField color = new TextField(selected.color());
		color.setPromptText("#388bfd");
		HBox presets = new HBox(6);
		for (String preset : List.of("#388bfd", "#2ea043", "#d29922", "#f85149", "#a371f7", "#db61a2")) {
			Button swatch = new Button();
			swatch.setMinSize(24, 24); swatch.setPrefSize(24, 24);
			swatch.setStyle("-fx-background-color: " + preset + "; -fx-background-radius: 12; -fx-cursor: hand;");
			swatch.setTooltip(new Tooltip(preset));
			swatch.setOnAction(event -> color.setText(preset));
			presets.getChildren().add(swatch);
		}
		javafx.scene.layout.GridPane fields = new javafx.scene.layout.GridPane();
		fields.setHgap(10); fields.setVgap(10);
		fields.addRow(0, new Label("Name:"), name);
		fields.addRow(1, new Label("Color:"), presets);
		fields.addRow(2, new Label("Hex:"), color);
		Dialog<ButtonType> editor = new Dialog<>();
		editor.setTitle("Edit tag");
		editor.getDialogPane().setContent(fields);
		editor.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
		if (editor.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK && !name.getText().isBlank()) {
			String hex = color.getText().trim();
			if (!hex.matches("#[0-9a-fA-F]{6}")) {
				showAlert(Alert.AlertType.ERROR, "Invalid color", "Use a six-digit hex color such as #388bfd.");
				return;
			}
			if (!profileManagerActionController.updateTag(selected.name(), name.getText().trim(), hex)) {
				showAlert(Alert.AlertType.ERROR, "Tag update failed", "The name may already be in use.");
			}
			list.setItems(FXCollections.observableArrayList(profileManagerActionController.loadTagDefinitions()));
		}
	}

	private void deleteTag(ListView<ProfileTagData> list) {
		ProfileTagData selected = list.getSelectionModel().getSelectedItem();
		if (selected == null) return;
		Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
				"Delete tag '" + selected.name() + "' from every profile?", ButtonType.YES, ButtonType.CANCEL);
		if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.YES) {
			profileManagerActionController.deleteTag(selected.name());
			list.setItems(FXCollections.observableArrayList(profileManagerActionController.loadTagDefinitions()));
		}
	}

	private void addFilterOption(ContextMenu menu, String label, String key) {
		Menu choices = new Menu(label);
		searchValues(key).stream().limit(30).forEach(value -> {
			MenuItem suggestion = new MenuItem(value);
			suggestion.setOnAction(action -> addStructuredFilter(
					key + ":" + (value.contains(" ") ? "\"" + value + "\"" : value)));
			choices.getItems().add(suggestion);
		});
		if (!choices.getItems().isEmpty()) choices.getItems().add(new javafx.scene.control.SeparatorMenuItem());
		MenuItem custom = new MenuItem("Custom…");
		custom.setOnAction(action -> {
			TextInputDialog dialog = new TextInputDialog();
			dialog.setTitle("Filter profiles");
			dialog.setHeaderText("Filter by " + label.toLowerCase());
			dialog.setContentText(label + ":");
			dialog.showAndWait().map(String::trim).filter(value -> !value.isBlank())
					.ifPresent(value -> addStructuredFilter(key + ":"
							+ (value.contains(" ") ? "\"" + value + "\"" : value)));
		});
		choices.getItems().add(custom);
		menu.getItems().add(choices);
	}

	private void addStructuredFilter(String filter) {
		if (structuredFilters.stream().noneMatch(existing -> existing.equalsIgnoreCase(filter))) {
			structuredFilters.add(filter);
		}
		refreshStructuredFilterChips();
		applyFilter(txtSearchProfiles.getText());
	}

	private void refreshStructuredFilterChips() {
		structuredFilterPane.getChildren().clear();
		for (String filter : List.copyOf(structuredFilters)) {
			Button chip = new Button(filter + "  ×");
			chip.getStyleClass().add("profile-filter-chip");
			chip.setOnAction(event -> {
				structuredFilters.remove(filter);
				refreshStructuredFilterChips();
				applyFilter(txtSearchProfiles.getText());
			});
			structuredFilterPane.getChildren().add(chip);
		}
		setVisibleAndManaged(structuredFilterPane, !structuredFilters.isEmpty());
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
		addColumnToggle(menu, "Server", columnServer);
		addColumnToggle(menu, "Alliance", columnAlliance);
		addColumnToggle(menu, "Tags", columnTags);
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
		columnServer.setCellValueFactory(cellData -> cellData.getValue().characterServerProperty());
		columnAlliance.setCellValueFactory(cellData -> cellData.getValue().characterAllianceCodeProperty());
		columnTags.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(
				String.join(", ", cellData.getValue().getTags())));
		columnTags.setCellFactory(column -> new TableCell<>() {
			private final HBox tags = new HBox(5);
			{
				tags.setAlignment(Pos.CENTER_LEFT);
				setAlignment(Pos.CENTER_LEFT);
			}

			@Override
			protected void updateItem(String value, boolean empty) {
				super.updateItem(value, empty);
				tags.getChildren().clear();
				if (empty || getTableRow() == null || getTableRow().getItem() == null) {
					setGraphic(null);
					return;
				}
				List<String> profileTags = getTableRow().getItem().getTags();
				for (String tag : profileTags.stream().limit(3).toList()) {
					Button chip = new Button(tag);
					chip.getStyleClass().add("profile-tag-choice");
					String color = tagColors.getOrDefault(tag.toLowerCase(), "#388bfd");
					chip.setStyle("-fx-border-color: " + color + "; -fx-border-radius: 12;");
					chip.setTooltip(new Tooltip("Filter by tag " + tag));
					chip.setOnAction(event -> addSearchFilter("tag", tag));
					tags.getChildren().add(chip);
				}
				if (profileTags.size() > 3) {
					Label overflow = new Label("+" + (profileTags.size() - 3));
					overflow.getStyleClass().add("profile-tag-overflow");
					overflow.setTooltip(new Tooltip(String.join(", ", profileTags.subList(3, profileTags.size()))));
					tags.getChildren().add(overflow);
				}
				setGraphic(tags);
				setText(null);
			}
		});
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
		columnSelected.setCellFactory(col -> new ProfileSelectionCell());
		tableviewLogMessages.setFixedCellSize(54);

		bindToTableComparator();
		tableviewLogMessages.setItems(sortedProfiles);
	}

	private void initializeBulkSelection() {
		selectAllVisible.setAccessibleText("Select all visible profiles");
		selectAllVisible.setTooltip(new Tooltip("Select all visible profiles"));
		selectAllVisible.setOnAction(event -> setAllVisibleSelected(selectAllVisible.isSelected()));
		columnSelected.setGraphic(selectAllVisible);
		updateBulkSelectionBar();
	}

	private void setAllVisibleSelected(boolean selected) {
		if (!selected) {
			selectedProfileIds.clear();
		} else {
			for (ProfileAux profile : sortedProfiles) {
				selectedProfileIds.add(profile.getId());
			}
		}
		tableviewLogMessages.refresh();
		updateBulkSelectionBar();
	}

	private void setProfileSelected(ProfileAux profile, boolean selected) {
		if (profile == null || profile.getId() == null) {
			return;
		}
		if (selected) {
			selectedProfileIds.add(profile.getId());
		} else {
			selectedProfileIds.remove(profile.getId());
		}
		updateBulkSelectionBar();
	}

	private void updateBulkSelectionBar() {
		int count = selectedProfileIds.size();
		boolean hasSelection = count > 0;
		lblSelectionCount.setText(count + (count == 1 ? " profile selected" : " profiles selected"));
		setVisibleAndManaged(lblSelectionCount, hasSelection);
		setVisibleAndManaged(btnEnableSelected, hasSelection);
		setVisibleAndManaged(btnDisableSelected, hasSelection);
		setVisibleAndManaged(btnTagsSelected, hasSelection);
		setVisibleAndManaged(selectionSeparator, hasSelection);

		long visibleCount = sortedProfiles.stream().filter(profile -> profile.getId() != null).count();
		long visibleSelected = sortedProfiles.stream()
				.filter(profile -> selectedProfileIds.contains(profile.getId())).count();
		selectAllVisible.setIndeterminate(visibleSelected > 0 && visibleSelected < visibleCount);
		selectAllVisible.setSelected(visibleCount > 0 && visibleSelected == visibleCount);
	}

	private void setVisibleAndManaged(Node node, boolean visible) {
		node.setVisible(visible);
		node.setManaged(visible);
	}

	@FXML
	private void handleEnableSelected(ActionEvent event) {
		applyEnabledToSelection(true);
	}

	@FXML
	private void handleDisableSelected(ActionEvent event) {
		List<ProfileAux> selected = selectedProfiles();
		long running = profileManagerActionController.countRunningProfiles(selected);
		Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
		confirmation.setTitle("Disable profiles");
		confirmation.setHeaderText("Disable " + selected.size() + (selected.size() == 1 ? " profile?" : " profiles?"));
		String runtimeNote = running == 0 ? "" : " " + running
				+ (running == 1 ? " running queue will" : " running queues will") + " be paused immediately.";
		confirmation.setContentText("They will remain disabled after the next restart." + runtimeNote);
		if (confirmation.showAndWait().filter(ButtonType.OK::equals).isPresent()) {
			applyEnabledToSelection(false);
		}
	}

	@FXML
	private void handleTagsSelected(ActionEvent event) {
		List<ProfileAux> selected = selectedProfiles();
		ContextMenu menu = new ContextMenu();
		for (String tag : profileManagerActionController.loadTags()) {
			CheckBox checkBox = new CheckBox(tag);
			long tagged = selected.stream().filter(profile -> profile.getTags().stream()
					.anyMatch(current -> current.equalsIgnoreCase(tag))).count();
			checkBox.setAllowIndeterminate(true);
			checkBox.setIndeterminate(tagged > 0 && tagged < selected.size());
			checkBox.setSelected(tagged == selected.size());
			checkBox.setOnAction(toggle -> applyTagToSelection(tag, checkBox.isSelected()));
			javafx.scene.control.CustomMenuItem item = new javafx.scene.control.CustomMenuItem(checkBox, false);
			menu.getItems().add(item);
		}
		if (!menu.getItems().isEmpty()) menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
		MenuItem create = new MenuItem("+ Create tag");
		create.setOnAction(action -> createTagForSelection());
		menu.getItems().add(create);
		menu.show(btnTagsSelected, javafx.geometry.Side.TOP, 0, -4);
	}

	private void createTagForSelection() {
		TextInputDialog dialog = new TextInputDialog();
		dialog.setTitle("Create tag");
		dialog.setHeaderText("Create and assign a tag to the selected profiles");
		dialog.setContentText("Tag name:");
		dialog.showAndWait().map(String::trim).filter(name -> !name.isBlank())
				.ifPresent(name -> applyTagToSelection(name, true));
	}

	private void applyTagToSelection(String tag, boolean add) {
		var result = profileManagerActionController.updateTag(selectedProfiles(), tag, add);
		tableviewLogMessages.refresh();
		applyFilter(txtSearchProfiles.getText());
		if (!result.successful()) {
			showAlert(Alert.AlertType.WARNING, "Tags partially updated",
					result.updated() + " updated; " + result.failed() + " failed.");
		}
	}

	private void addSearchFilter(String qualifier, String value) {
		String filter = qualifier + ":" + (value.contains(" ") ? "\"" + value + "\"" : value);
		String current = txtSearchProfiles.getText().trim();
		if (!current.toLowerCase().contains(filter.toLowerCase())) {
			txtSearchProfiles.setText(current.isBlank() ? filter : current + " " + filter);
		}
		txtSearchProfiles.requestFocus();
		txtSearchProfiles.positionCaret(txtSearchProfiles.getText().length());
	}

	private void applyEnabledToSelection(boolean enabled) {
		List<ProfileAux> selected = selectedProfiles();
		if (selected.isEmpty()) {
			return;
		}
		var result = profileManagerActionController.setProfilesEnabled(selected, enabled);
		tableviewLogMessages.refresh();
		if (result.successful()) {
			showAlert(Alert.AlertType.INFORMATION, "Profiles updated",
					result.updated() + (result.updated() == 1 ? " profile " : " profiles ")
							+ (enabled ? "enabled." : "disabled."));
		} else {
			showAlert(Alert.AlertType.WARNING, "Profiles partially updated",
					result.updated() + " updated; " + result.failed() + " failed.");
		}
	}

	private List<ProfileAux> selectedProfiles() {
		return profiles.stream().filter(profile -> selectedProfileIds.contains(profile.getId())).toList();
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

	@FXML
	private void handleExportProfiles(ActionEvent event) {
		List<ProfileAux> selected = selectedProfiles();
		if (selected.isEmpty()) {
			showAlert(Alert.AlertType.WARNING, "No profiles selected",
					"Select one or more profiles to export.");
			return;
		}
		FileChooser chooser = profileJsonChooser("Export Profiles");
		chooser.setInitialFileName(selected.size() == 1
				? safeFileName(selected.get(0).getName()) + ".json" : "frostguard-profiles.json");
		File destination = chooser.showSaveDialog(btnExportProfiles.getScene().getWindow());
		if (destination == null) return;
		try {
			profileManagerActionController.exportProfiles(destination.toPath(), selected);
			showAlert(Alert.AlertType.INFORMATION, "Profiles exported",
					selected.size() + (selected.size() == 1 ? " profile exported." : " profiles exported."));
		} catch (Exception ex) {
			showAlert(Alert.AlertType.ERROR, "Export failed", ex.getMessage());
		}
	}

	@FXML
	private void handleImportProfiles(ActionEvent event) {
		FileChooser chooser = profileJsonChooser("Import Profiles");
		List<File> sources = chooser.showOpenMultipleDialog(btnImportProfiles.getScene().getWindow());
		if (sources == null || sources.isEmpty()) return;
		var preview = profileManagerActionController.prepareImport(
				sources.stream().map(File::toPath).toList(), new ArrayList<>(profiles));
		List<AccountDescriptor> selected = showImportPreview(preview);
		if (selected == null) return;
		var result = profileManagerActionController.importPrepared(selected);
		loadProfiles();
		int failed = result.failed() + preview.errors().size();
		Alert.AlertType type = failed == 0 ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING;
		showAlert(type, "Profile import complete",
				result.imported() + " imported; " + failed + " failed."
						+ (preview.errors().isEmpty() ? "" : "\n" + String.join("\n", preview.errors())));
	}

	private List<AccountDescriptor> showImportPreview(ProfileManagerActionController.ImportPreview preview) {
		Dialog<ButtonType> dialog = new Dialog<>();
		dialog.setTitle("Import profiles");
		dialog.setHeaderText("Select profiles and resolve names before importing");
		VBox rows = new VBox(8);
		List<java.util.Map.Entry<CheckBox, AccountDescriptor>> choices = new ArrayList<>();
		for (AccountDescriptor descriptor : preview.profiles()) {
			CheckBox selected = new CheckBox(); selected.setSelected(true);
			TextField name = new TextField(descriptor.getName()); name.setPrefWidth(260);
			Label details = new Label("Server " + Objects.toString(descriptor.getCharacterServer(), "—")
					+ "  •  " + descriptor.getConfigs().size() + " settings");
			details.setStyle("-fx-text-fill: #8b949e;");
			name.textProperty().addListener((obs, old, value) -> descriptor.setName(value.trim()));
			rows.getChildren().add(new HBox(10, selected, new VBox(3, name, details)));
			choices.add(new java.util.AbstractMap.SimpleEntry<>(selected, descriptor));
		}
		if (!preview.errors().isEmpty()) {
			Label errors = new Label(String.join("\n", preview.errors()));
			errors.setStyle("-fx-text-fill: #f85149;"); rows.getChildren().add(errors);
		}
		ScrollPane scroll = new ScrollPane(rows); scroll.setFitToWidth(true); scroll.setPrefViewportHeight(360);
		dialog.getDialogPane().setContent(scroll);
		dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
		if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return null;
		List<AccountDescriptor> result = choices.stream().filter(entry -> entry.getKey().isSelected())
				.map(java.util.Map.Entry::getValue).filter(profile -> !profile.getName().isBlank()).toList();
		Set<String> names = new HashSet<>();
		Set<String> existingNames = profiles.stream().map(ProfileAux::getName).filter(Objects::nonNull)
				.map(String::toLowerCase).collect(java.util.stream.Collectors.toSet());
		if (result.stream().anyMatch(profile -> existingNames.contains(profile.getName().toLowerCase())
				|| !names.add(profile.getName().toLowerCase()))) {
			showAlert(Alert.AlertType.ERROR, "Duplicate names", "Imported profile names must be unique.");
			return null;
		}
		return result;
	}

	private FileChooser profileJsonChooser(String title) {
		FileChooser chooser = new FileChooser();
		chooser.setTitle(title);
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Frostguard profile files", "*.json"));
		return chooser;
	}

	private String safeFileName(String raw) {
		String safe = raw == null ? "profile" : raw.replaceAll("[^A-Za-z0-9._-]+", "-");
		return safe.isBlank() ? "profile" : safe;
	}

	private void showTasksPopup(ProfileAux profile, Node ownerNode) {
		Popup popup = new Popup();
		popup.setAutoHide(true);

		VBox root = new VBox(10);
		root.setStyle("-fx-background-color: #1e1e2e; -fx-padding: 15; -fx-border-color: #388bfd; -fx-border-width: 1; -fx-border-radius: 5; -fx-background-radius: 5;");
		Label title = new Label("Enabled Tasks: " + profile.getName());
		title.setStyle("-fx-text-fill: #a8dadc; -fx-font-size: 14px; -fx-font-weight: bold;");
		root.getChildren().add(title);

		FlowPane flowPane = enabledTasksFlow(profile);
		if (flowPane.getChildren().isEmpty()) {
			Label empty = new Label("No tasks enabled.");
			empty.setStyle("-fx-text-fill: #8b949e; -fx-font-style: italic;");
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
		chip.setStyle("-fx-background-color: #2ea043; -fx-text-fill: white; -fx-padding: 4 8; -fx-background-radius: 4; -fx-font-size: 11px;");
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
			tagColors = profileManagerActionController.loadTagDefinitions().stream().collect(
					java.util.stream.Collectors.toMap(tag -> tag.name().toLowerCase(), ProfileTagData::color,
							(first, second) -> first));
			profiles.setAll(accounts.stream().map(this::toProfileAux).toList());
			selectedProfileIds.retainAll(profiles.stream().map(ProfileAux::getId).collect(java.util.stream.Collectors.toSet()));
			selectLoadedProfile();
			reapplyCurrentSort();
			updateBulkSelectionBar();
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
		profileAux.setTags(account.getTags());
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
		target.setTags(dto.getTags());
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
			profileQueueExecutor.submit(() -> {
				boolean saved = profileManagerActionController.saveProfile(loadedProfile);
				if (saved && BEAR_PARTICIPATION_SCHEDULE_KEYS.contains(key)) {
					ScheduleService.obtain().realignBearTrapParticipationSchedule(loadedProfile.getId());
				}
			});
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
		private final Button btnDuplicate = iconButton("mdi2c-content-copy", 17, "#d2a8ff", "Duplicate Profile");
		private final HBox buttonContainer = new HBox(5, btnLoad, btnDuplicate, btnViewTasks, btnDelete);

		private ProfileActionsCell() {
			buttonContainer.setAlignment(Pos.CENTER);
			btnViewTasks.setOnAction(event -> {
				ProfileAux currentProfile = rowProfile(this);
				if (currentProfile != null) {
					showTasksPopup(currentProfile, btnViewTasks);
				}
			});
			btnDelete.setOnAction(this::deleteCurrentProfile);
			btnDuplicate.setOnAction(event -> duplicateCurrentProfile());
			btnLoad.setOnAction(event -> {
				ProfileAux currentProfile = rowProfile(this);
				if (currentProfile != null) {
					loadedProfileId = currentProfile.getId();
					notifyProfileLoadListeners(currentProfile);
				}
			});
		}

		private void duplicateCurrentProfile() {
			ProfileAux currentProfile = rowProfile(this);
			if (currentProfile == null) return;
			if (profileManagerActionController.duplicateProfile(currentProfile, new ArrayList<>(profiles))) {
				loadProfiles();
			} else {
				showAlert(Alert.AlertType.ERROR, "Duplicate failed", "The profile could not be duplicated.");
			}
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

	private final class ProfileSelectionCell extends TableCell<ProfileAux, Void> {
		private final CheckBox checkBox = new CheckBox();

		private ProfileSelectionCell() {
			checkBox.setOnAction(event -> setProfileSelected(rowProfile(this), checkBox.isSelected()));
			setAlignment(Pos.CENTER);
		}

		@Override
		protected void updateItem(Void item, boolean empty) {
			super.updateItem(item, empty);
			ProfileAux profile = rowProfile(this);
			if (empty || profile == null) {
				setGraphic(null);
				return;
			}
			checkBox.setSelected(selectedProfileIds.contains(profile.getId()));
			checkBox.setAccessibleText("Select profile " + profile.getName());
			setGraphic(checkBox);
		}
	}
}
