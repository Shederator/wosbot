package dev.frostguard.app.shared;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.controlsfx.control.CheckComboBox;
import org.jetbrains.annotations.NotNull;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.PrioritizableItemData;
import dev.frostguard.api.domain.PriorityItemData;
import dev.frostguard.app.panel.profile.IProfileChangeObserver;
import dev.frostguard.app.panel.profile.IProfileLoadListener;
import dev.frostguard.app.panel.profile.IProfileObserverInjectable;
import dev.frostguard.app.panel.profile.ProfileAux;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.ToggleGroup;

public abstract class AbstractProfileController implements IProfileLoadListener, IProfileObserverInjectable {

	private static final DateTimeFormatter PROFILE_DATE_TIME = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
	private static final int MAX_CONFIG_INT = 99_999_999;

	protected final Map<CheckBox, ConfigurationKeyEnum> checkBoxMappings = new HashMap<>();
	protected final Map<TextField, ConfigurationKeyEnum> textFieldMappings = new HashMap<>();
	protected final Map<RadioButton, ConfigurationKeyEnum> radioButtonMappings = new HashMap<>();
	protected final Map<ComboBox<?>, ConfigurationKeyEnum> comboBoxMappings = new HashMap<>();
	protected final Map<CheckComboBox<?>, ConfigurationKeyEnum> checkComboBoxMappings = new HashMap<>();
	protected final Map<PriorityListView, ConfigurationKeyEnum> priorityListMappings = new HashMap<>();
	protected final Map<PriorityListView, Class<? extends Enum<?>>> priorityListEnumClasses = new HashMap<>();
	protected IProfileChangeObserver profileObserver;
	protected boolean isLoadingProfile = false;

	@Override
	public void attachProfileListener(IProfileChangeObserver observer) {
		this.profileObserver = observer;
	}

	protected void registerCheckBox(CheckBox checkBox, ConfigurationKeyEnum configKey) {
		checkBoxMappings.put(checkBox, configKey);
	}

	protected void registerTextField(TextField textField, ConfigurationKeyEnum configKey) {
		textFieldMappings.put(textField, configKey);
	}

	protected void registerRadioButton(RadioButton radioButton, ConfigurationKeyEnum configKey) {
		radioButtonMappings.put(radioButton, configKey);
	}

	protected void registerComboBox(ComboBox<?> comboBox, ConfigurationKeyEnum configKey) {
		comboBoxMappings.put(comboBox, configKey);
	}

	protected void registerCheckComboBox(CheckComboBox<?> checkComboBox, ConfigurationKeyEnum configKey) {
		checkComboBoxMappings.put(checkComboBox, configKey);
	}

	protected <T extends Enum<T> & PrioritizableItemData> void registerPriorityList(
			PriorityListView priorityListView,
			ConfigurationKeyEnum configKey,
			Class<T> enumClass) {
		priorityListMappings.put(priorityListView, configKey);
		priorityListEnumClasses.put(priorityListView, enumClass);
	}

	protected void initializeChangeEvents() {
		checkBoxMappings.entrySet().stream()
				.filter(entry -> entry.getKey() != null)
				.forEach(entry -> setupCheckBoxListener(entry.getKey(), entry.getValue()));
		textFieldMappings.entrySet().stream()
				.filter(entry -> entry.getKey() != null)
				.forEach(entry -> setupTextFieldUpdateOnFocusOrEnter(entry.getKey(), entry.getValue()));
		radioButtonMappings.entrySet().stream()
				.filter(entry -> entry.getKey() != null)
				.forEach(entry -> setupRadioButtonListener(entry.getKey(), entry.getValue()));
		comboBoxMappings.entrySet().stream()
				.filter(entry -> entry.getKey() != null)
				.forEach(entry -> setupComboBoxListener(entry.getKey(), entry.getValue()));
		checkComboBoxMappings.entrySet().stream()
				.filter(entry -> entry.getKey() != null)
				.forEach(entry -> setupCheckComboBoxListener(entry.getKey(), entry.getValue()));
		priorityListMappings.entrySet().stream()
				.filter(entry -> entry.getKey() != null)
				.forEach(entry -> setupPriorityListListener(entry.getKey(), entry.getValue()));
		priorityListEnumClasses.entrySet().stream()
				.filter(entry -> entry.getKey() != null)
				.forEach(entry -> initializePriorityListFromEnum(entry.getKey(), entry.getValue()));
	}

	public Map<ConfigurationKeyEnum, String> getRegisteredSettings() {
		Map<ConfigurationKeyEnum, String> settings = new HashMap<>();
		checkBoxMappings.forEach((control, key) -> {
			if (control != null) {
				settings.put(key, readable(control.getText(), key));
			}
		});
		radioButtonMappings.forEach((control, key) -> {
			if (control != null) {
				settings.put(key, readable(control.getText(), key));
			}
		});
		textFieldMappings.forEach((control, key) -> {
			if (control != null) {
				settings.put(key, readable(control.getPromptText(), key));
			}
		});
		comboBoxMappings.forEach((control, key) -> {
			if (control != null) {
				settings.put(key, readable(control.getPromptText(), key));
			}
		});
		checkComboBoxMappings.values().forEach(key -> settings.put(key, formatEnumName(key.name())));
		priorityListMappings.values().forEach(key -> settings.put(key, formatEnumName(key.name())));
		return settings;
	}

	private String readable(String uiText, ConfigurationKeyEnum key) {
		return uiText == null || uiText.isBlank() ? formatEnumName(key.name()) : uiText;
	}

	private String formatEnumName(String name) {
		if (name == null) {
			return "";
		}
		String[] words = name.toLowerCase().split("_");
		StringBuilder label = new StringBuilder();
		for (String word : words) {
			if (!word.isEmpty()) {
				label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
			}
		}
		return label.toString().trim();
	}

	protected void createToggleGroup(RadioButton... radioButtons) {
		ToggleGroup toggleGroup = new ToggleGroup();
		for (RadioButton radioButton : radioButtons) {
			radioButton.setToggleGroup(toggleGroup);
		}
	}

	protected void setupRadioButtonListener(RadioButton radioButton, ConfigurationKeyEnum configKey) {
		radioButton.selectedProperty().addListener((obs, oldVal, selected) -> publishWhenReady(configKey, selected));
	}

	protected void setupCheckBoxListener(CheckBox checkBox, ConfigurationKeyEnum configKey) {
		checkBox.selectedProperty().addListener((obs, oldVal, selected) -> publishWhenReady(configKey, selected));
	}

	protected void setupTextFieldUpdateOnFocusOrEnter(TextField textField, ConfigurationKeyEnum configKey) {
		textField.focusedProperty().addListener((obs, wasFocused, focused) -> {
			if (!focused) {
				updateProfile(textField, configKey);
			}
		});
		textField.setOnAction(event -> updateProfile(textField, configKey));
	}

	protected void setupComboBoxListener(ComboBox<?> comboBox, ConfigurationKeyEnum configKey) {
		comboBox.valueProperty().addListener((obs, oldVal, value) -> {
			if (value != null) {
				publishWhenReady(configKey, value);
			}
		});
	}

	protected void setupCheckComboBoxListener(CheckComboBox<?> checkComboBox, ConfigurationKeyEnum configKey) {
		checkComboBox.getCheckModel().getCheckedItems().addListener(
				(javafx.collections.ListChangeListener.Change<?> change) -> publishWhenReady(configKey, checkedItemsAsConfig(checkComboBox)));
	}

	protected void setupPriorityListListener(PriorityListView priorityListView, ConfigurationKeyEnum configKey) {
		priorityListView.setOnChangeCallback(() -> publishWhenReady(configKey, priorityListView.toConfigString()));
	}

	protected final void publishWhenReady(ConfigurationKeyEnum configKey, Object value) {
		if (!isLoadingProfile && profileObserver != null) {
			profileObserver.notifyProfileChange(configKey, value);
		}
	}

	private String checkedItemsAsConfig(CheckComboBox<?> checkComboBox) {
		return checkComboBox.getCheckModel().getCheckedItems().stream()
				.map(String::valueOf)
				.collect(Collectors.joining(","));
	}

	private void updateProfile(TextField textField, ConfigurationKeyEnum configKey) {
		if (isLoadingProfile) {
			return;
		}

		String candidate = textField.getText();
		Class<?> expectedType = configKey.getType();
		if (Integer.class.equals(expectedType)) {
			commitIntegerField(textField, configKey, candidate);
		} else if (LocalDateTime.class.equals(expectedType)) {
			commitDateTimeField(textField, configKey, candidate);
		} else {
			publishWhenReady(configKey, candidate);
		}
	}

	private void commitIntegerField(TextField textField, ConfigurationKeyEnum configKey, String candidate) {
		if (isValidPositiveInteger(candidate)) {
			publishWhenReady(configKey, Integer.valueOf(candidate));
			return;
		}
		textField.setText(configKey.getDefaultValue());
	}

	private void commitDateTimeField(TextField textField, ConfigurationKeyEnum configKey, String candidate) {
		if (candidate == null || candidate.trim().isEmpty()) {
			publishWhenReady(configKey, "NOW");
			return;
		}

		try {
			LocalDateTime.parse(candidate, PROFILE_DATE_TIME);
			publishWhenReady(configKey, candidate);
		} catch (DateTimeParseException e) {
			textField.setText(configKey.getDefaultValue());
		}
	}

	private boolean isValidPositiveInteger(String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		try {
			int number = Integer.parseInt(value);
			return number >= 0 && number <= MAX_CONFIG_INT;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	@Override
	public void onProfileLoad(ProfileAux profile) {
		isLoadingProfile = true;
		try {
			checkBoxMappings.entrySet().stream()
					.filter(entry -> entry.getKey() != null)
					.forEach(entry -> entry.getKey().setSelected(Boolean.TRUE.equals(profile.<Boolean>getConfiguration(entry.getValue()))));
			textFieldMappings.entrySet().stream()
					.filter(entry -> entry.getKey() != null)
					.forEach(entry -> loadTextField(profile, entry.getKey(), entry.getValue()));
			radioButtonMappings.entrySet().stream()
					.filter(entry -> entry.getKey() != null)
					.forEach(entry -> entry.getKey().setSelected(Boolean.TRUE.equals(profile.<Boolean>getConfiguration(entry.getValue()))));
			comboBoxMappings.entrySet().stream()
					.filter(entry -> entry.getKey() != null)
					.forEach(entry -> loadComboBox(profile, entry.getKey(), entry.getValue()));
			checkComboBoxMappings.entrySet().stream()
					.filter(entry -> entry.getKey() != null)
					.forEach(entry -> loadCheckComboBox(profile, entry.getKey(), entry.getValue()));
			priorityListMappings.entrySet().stream()
					.filter(entry -> entry.getKey() != null)
					.forEach(entry -> loadPriorityList(profile, entry.getKey(), entry.getValue()));
		} finally {
			isLoadingProfile = false;
		}
	}

	private void loadTextField(ProfileAux profile, TextField textField, ConfigurationKeyEnum key) {
		Object value = profile.getConfiguration(key);
		if (value instanceof LocalDateTime dateTime) {
			textField.setText(dateTime.format(PROFILE_DATE_TIME));
		} else if (value != null) {
			textField.setText(value.toString());
		} else {
			textField.setText(key.getDefaultValue());
		}
	}

	@SuppressWarnings("unchecked")
	private void loadComboBox(ProfileAux profile, ComboBox<?> comboBox, ConfigurationKeyEnum key) {
		Object value = profile.getConfiguration(key);
		if (value != null) {
			((ComboBox<Object>) comboBox).setValue(value);
		}
	}

	private void loadCheckComboBox(ProfileAux profile, CheckComboBox<?> checkComboBox, ConfigurationKeyEnum key) {
		String value = profile.getConfiguration(key);
		checkComboBox.getCheckModel().clearChecks();
		if (value == null || value.trim().isEmpty()) {
			return;
		}

		for (String token : value.split(",")) {
			checkCheckComboValue(checkComboBox, token.trim());
		}
	}

	@SuppressWarnings("unchecked")
	private void checkCheckComboValue(CheckComboBox<?> checkComboBox, String token) {
		if (token.isEmpty() || checkComboBox.getItems().isEmpty()) {
			return;
		}

		CheckComboBox<Object> typed = (CheckComboBox<Object>) checkComboBox;
		Object firstItem = checkComboBox.getItems().get(0);
		try {
			if (firstItem instanceof Integer) {
				Object item = Integer.valueOf(token);
				typed.getCheckModel().check(item);
			} else {
				typed.getCheckModel().check(token);
			}
		} catch (NumberFormatException ignored) {
			// Stored check-combo values may outlive an older option set.
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void loadPriorityList(ProfileAux profile, PriorityListView priorityListView, ConfigurationKeyEnum key) {
		String value = profile.getConfiguration(key);
		Class<? extends Enum<?>> enumClass = priorityListEnumClasses.get(priorityListView);

		if (value != null && !value.trim().isEmpty()) {
			priorityListView.fromConfigString(value);
			if (enumClass != null) {
				mergeEnumWithSavedPriorities(priorityListView, (Class) enumClass, key);
			}
			return;
		}

		if (enumClass != null) {
			reinitializePriorityListWithDefaults(priorityListView, enumClass);
		}
	}

	protected <T extends Enum<T> & PrioritizableItemData> void initializePriorityListFromEnum(
			PriorityListView priorityListView,
			Class<? extends Enum<?>> enumClass) {
		priorityListView.setItems(defaultPriorityItems(enumClass));
	}

	private <T extends Enum<T> & PrioritizableItemData> void reinitializePriorityListWithDefaults(
			PriorityListView priorityListView,
			Class<? extends Enum<?>> enumClass) {
		priorityListView.setItems(defaultPriorityItems(enumClass));
	}

	@SuppressWarnings("unchecked")
	private <T extends Enum<T> & PrioritizableItemData> List<PriorityItemData> defaultPriorityItems(Class<? extends Enum<?>> enumClass) {
		T[] enumConstants = ((Class<T>) enumClass).getEnumConstants();
		List<PriorityItemData> items = new ArrayList<>(enumConstants.length);
		for (int i = 0; i < enumConstants.length; i++) {
			items.add(new PriorityItemData(
					enumConstants[i].getIdentifier(),
					enumConstants[i].getDisplayName(),
					i + 1,
					false));
		}
		return items;
	}

	protected <T extends Enum<T> & PrioritizableItemData> void mergeEnumWithSavedPriorities(
			PriorityListView priorityListView,
			Class<T> enumClass,
			ConfigurationKeyEnum configKey) {
		List<PriorityItemData> currentItems = priorityListView.getItems();
		if (currentItems.isEmpty()) {
			return;
		}

		Map<String, PriorityItemData> savedItems = indexByIdentifier(currentItems);
		List<PriorityItemData> mergedItems = mergeKnownItems(enumClass, savedItems);
		mergedItems.addAll(newItemsFromEnum(enumClass, savedItems));
		renumberPriorities(mergedItems);

		boolean changed = mergedItems.size() != currentItems.size() || !haveSameIdentifiers(mergedItems, currentItems);
		priorityListView.setItems(mergedItems);
		if (changed) {
			publishWhenReady(configKey, priorityListView.toConfigString());
		}
	}

	private Map<String, PriorityItemData> indexByIdentifier(List<PriorityItemData> items) {
		Map<String, PriorityItemData> indexed = new HashMap<>();
		for (PriorityItemData item : items) {
			indexed.put(item.getIdentifier(), item);
		}
		return indexed;
	}

	private <T extends Enum<T> & PrioritizableItemData> List<PriorityItemData> mergeKnownItems(
			Class<T> enumClass,
			Map<String, PriorityItemData> savedItems) {
		List<PriorityItemData> merged = new ArrayList<>();
		for (T enumItem : enumClass.getEnumConstants()) {
			PriorityItemData savedItem = savedItems.get(enumItem.getIdentifier());
			if (savedItem != null) {
				merged.add(new PriorityItemData(
						enumItem.getIdentifier(),
						enumItem.getDisplayName(),
						savedItem.getPriority(),
						savedItem.isEnabled()));
			}
		}
		merged.sort(Comparator.comparingInt(PriorityItemData::getPriority));
		return merged;
	}

	private <T extends Enum<T> & PrioritizableItemData> List<PriorityItemData> newItemsFromEnum(
			Class<T> enumClass,
			Map<String, PriorityItemData> savedItems) {
		List<PriorityItemData> newItems = new ArrayList<>();
		for (T enumItem : enumClass.getEnumConstants()) {
			if (!savedItems.containsKey(enumItem.getIdentifier())) {
				newItems.add(new PriorityItemData(
						enumItem.getIdentifier(),
						enumItem.getDisplayName(),
						0,
						false));
			}
		}
		return newItems;
	}

	private void renumberPriorities(List<PriorityItemData> items) {
		for (int i = 0; i < items.size(); i++) {
			items.get(i).setPriority(i + 1);
		}
	}

	private boolean haveSameIdentifiers(List<PriorityItemData> left, List<PriorityItemData> right) {
		if (left.size() != right.size()) {
			return false;
		}
		for (int i = 0; i < left.size(); i++) {
			if (!left.get(i).getIdentifier().equals(right.get(i).getIdentifier())) {
				return false;
			}
		}
		return true;
	}

	protected static @NotNull TextFormatter<String> getTimeTextFormatter() {
		return new TextFormatter<>(change -> {
			if (change.isContentChange()) {
				String masked = timeMask(change.getControlNewText());
				change.setRange(0, change.getControlText().length());
				change.setText(masked);
				change.setCaretPosition(masked.length());
				change.setAnchor(masked.length());
			}
			return change;
		});
	}

	private static String timeMask(String text) {
		String digits = text.chars()
				.filter(Character::isDigit)
				.limit(4)
				.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
				.toString();
		if (digits.length() <= 2) {
			return digits;
		}
		return digits.substring(0, 2) + ":" + digits.substring(2);
	}
}
