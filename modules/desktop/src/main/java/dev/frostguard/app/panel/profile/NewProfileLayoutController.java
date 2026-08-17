package dev.frostguard.app.panel.profile;

import java.util.List;
import java.util.function.UnaryOperator;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.app.shared.FieldValidationPresenter;
import dev.frostguard.app.shared.SettingValidator;
import dev.frostguard.app.shared.SettingValidators;
import dev.frostguard.app.shared.ValidationResult;
import javafx.beans.value.ChangeListener;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;

/**
 * Handles the "New Profile" form – validates user input, enforces
 * formatting constraints, and delegates persistence to the parent
 * {@link ProfileManagerActionController}.
 */
public class NewProfileLayoutController {
	private static final long MAX_RECONNECTION_MINUTES = 10_080;

	/** Rejects any character that is not a digit. */
	private static final UnaryOperator<TextFormatter.Change> NUMERIC_FILTER = change ->
		change.getControlNewText().matches("\\d*") ? change : null;

	/** Allows up to three alphanumeric characters (alliance tag). */
	private static final UnaryOperator<TextFormatter.Change> TAG_FILTER = change ->
		change.getControlNewText().length() <= 3 && change.getControlNewText().matches("[A-Za-z0-9]*") ? change : null;

	private ProfileManagerActionController profileManagerActionController;

	/* ── FXML-injected controls ── */

	@FXML
	private Button buttonSaveProfile;

	@FXML
	private TextField textfieldEmulatorNumber;

	@FXML
	private TextField textfieldProfileName;

	@FXML
	private CheckBox checkboxEnabled;

	@FXML
	private Slider sliderPriority;

	@FXML
	private Label labelPriorityValue;

	@FXML
	private TextField textfieldReconnectionTime;

	@FXML
	private TextField textfieldCharacterName;

	@FXML
	private TextField textfieldCharacterId;

	@FXML
	private TextField textfieldCharacterAllianceCode;

	@FXML
	private TextField textfieldCharacterServer;

	@FXML
	private Label labelProfileNameError;

	@FXML
	private Label labelEmulatorNumberError;

	@FXML
	private Label labelReconnectionTimeError;

	private FieldValidationPresenter profileNamePresenter;
	private FieldValidationPresenter emulatorNumberPresenter;
	private FieldValidationPresenter reconnectionTimePresenter;

	/* ── Constructor ── */

	public NewProfileLayoutController(ProfileManagerActionController profileManagerActionController) {
		this.profileManagerActionController = profileManagerActionController;
	}

	/* ────────────────────────────────────────────────
	 *  Lifecycle
	 * ──────────────────────────────────────────────── */

	@FXML
	private void initialize() {
		applyInputFormatters();
		profileNamePresenter = new FieldValidationPresenter(textfieldProfileName, labelProfileNameError);
		emulatorNumberPresenter = new FieldValidationPresenter(textfieldEmulatorNumber, labelEmulatorNumberError);
		reconnectionTimePresenter = new FieldValidationPresenter(textfieldReconnectionTime, labelReconnectionTimeError);
		linkPriorityLabelToSlider();
		registerFieldValidationWatchers();
		buttonSaveProfile.setDisable(!isFormValid());
	}

	/* ────────────────────────────────────────────────
	 *  Input formatting
	 * ──────────────────────────────────────────────── */

	private void applyInputFormatters() {
		attachIntegerFormatter(textfieldEmulatorNumber);
		attachIntegerFormatter(textfieldReconnectionTime);
		attachIntegerFormatter(textfieldCharacterId);
		textfieldCharacterAllianceCode.setTextFormatter(new TextFormatter<>(TAG_FILTER));
		textfieldCharacterServer.setTextFormatter(new TextFormatter<>(NUMERIC_FILTER));
	}

	private void attachIntegerFormatter(TextField target) {
		target.setTextFormatter(new TextFormatter<>(NUMERIC_FILTER));
	}

	/* ────────────────────────────────────────────────
	 *  Priority label binding
	 * ──────────────────────────────────────────────── */

	private void linkPriorityLabelToSlider() {
		labelPriorityValue.setText(String.valueOf((int) sliderPriority.getValue()));
		sliderPriority.valueProperty().addListener((obs, oldVal, newVal) ->
			labelPriorityValue.setText(String.valueOf(newVal.intValue())));
	}

	/* ────────────────────────────────────────────────
	 *  Validation
	 * ──────────────────────────────────────────────── */

	private void registerFieldValidationWatchers() {
		ChangeListener<String> refresher = (obs, oldVal, newVal) ->
			buttonSaveProfile.setDisable(!validateForm(true));
		mandatoryFields().forEach(tf -> tf.textProperty().addListener(refresher));
	}

	private List<TextField> mandatoryFields() {
		return List.of(textfieldEmulatorNumber, textfieldProfileName, textfieldReconnectionTime);
	}

	private boolean isFormValid() {
		return validateForm(false);
	}

	private boolean validateForm(boolean presentErrors) {
		boolean nameValid = validateField(
				textfieldProfileName, SettingValidators.requiredText("Profile name"), profileNamePresenter, presentErrors);
		boolean emulatorValid = validateField(
				textfieldEmulatorNumber, SettingValidators.nonNegativeInteger("Emulator number"),
				emulatorNumberPresenter, presentErrors);
		SettingValidator<Long> reconnectValidator = input -> input == null || input.isBlank()
				? ValidationResult.valid(0L)
				: SettingValidators.rangedLong("Reconnection time", 0, MAX_RECONNECTION_MINUTES).validate(input);
		boolean reconnectValid = validateField(
				textfieldReconnectionTime, reconnectValidator, reconnectionTimePresenter, presentErrors);
		return nameValid && emulatorValid && reconnectValid;
	}

	private <T> boolean validateField(
			TextField field,
			SettingValidator<T> validator,
			FieldValidationPresenter presenter,
			boolean presentErrors) {
		ValidationResult<T> result = validator.validate(field.getText());
		if (presentErrors) {
			if (result.isValid()) {
				presenter.clear();
			} else {
				presenter.showError(result.message());
			}
		}
		return result.isValid();
	}

	/* ────────────────────────────────────────────────
	 *  Actions
	 * ──────────────────────────────────────────────── */

	@FXML
	private void handleSaveProfileButton(ActionEvent event) {
		if (!validateForm(true)) {
			return;
		}
		profileManagerActionController.addProfile(assembleDescriptor());
		profileManagerActionController.closeNewProfileDialog();
	}

	/* ────────────────────────────────────────────────
	 *  Descriptor assembly
	 * ──────────────────────────────────────────────── */

	private AccountDescriptor assembleDescriptor() {
		return new AccountDescriptor(
			-1L,
			textfieldProfileName.getText(),
			textfieldEmulatorNumber.getText(),
			checkboxEnabled.isSelected(),
			(long) sliderPriority.getValue(),
			extractLongOrZero(textfieldReconnectionTime),
			trimmedOrNull(textfieldCharacterId),
			trimmedOrNull(textfieldCharacterName),
			trimmedToUpperOrNull(textfieldCharacterAllianceCode),
			trimmedOrNull(textfieldCharacterServer)
		);
	}

	/* ────────────────────────────────────────────────
	 *  Text-field helpers
	 * ──────────────────────────────────────────────── */

	private long extractLongOrZero(TextField field) {
		String raw = field.getText();
		return Long.parseLong(raw == null || raw.isEmpty() ? "0" : raw);
	}

	private String trimmedOrNull(TextField field) {
		String raw = field.getText() == null ? "" : field.getText().trim();
		return raw.isEmpty() ? null : raw;
	}

	private String trimmedToUpperOrNull(TextField field) {
		String trimmed = trimmedOrNull(field);
		return trimmed == null ? null : trimmed.toUpperCase();
	}
}
