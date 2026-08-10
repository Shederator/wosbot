package dev.frostguard.app.panel.profile;

import java.util.List;
import java.util.function.UnaryOperator;

import dev.frostguard.api.domain.AccountDescriptor;
import javafx.beans.value.ChangeListener;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.util.converter.IntegerStringConverter;

/**
 * Handles the "New Profile" form – validates user input, enforces
 * formatting constraints, and delegates persistence to the parent
 * {@link ProfileManagerActionController}.
 */
public class NewProfileLayoutController {

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
		linkPriorityLabelToSlider();
		registerFieldValidationWatchers();
		buttonSaveProfile.setDisable(!isFormValid());
	}

	/* ────────────────────────────────────────────────
	 *  Input formatting
	 * ──────────────────────────────────────────────── */

	private void applyInputFormatters() {
		attachIntegerFormatter(textfieldEmulatorNumber, 0);
		attachIntegerFormatter(textfieldReconnectionTime, 0);
		attachIntegerFormatter(textfieldCharacterId, null);
		textfieldCharacterAllianceCode.setTextFormatter(new TextFormatter<>(TAG_FILTER));
		textfieldCharacterServer.setTextFormatter(new TextFormatter<>(NUMERIC_FILTER));
	}

	private void attachIntegerFormatter(TextField target, Integer fallback) {
		target.setTextFormatter(
				new TextFormatter<>(new IntegerStringConverter(), fallback, NUMERIC_FILTER));
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
			buttonSaveProfile.setDisable(!isFormValid());
		mandatoryFields().forEach(tf -> tf.textProperty().addListener(refresher));
	}

	private List<TextField> mandatoryFields() {
		return List.of(textfieldEmulatorNumber, textfieldProfileName, textfieldReconnectionTime);
	}

	private boolean isFormValid() {
		String emuText = textfieldEmulatorNumber.getText();
		String nameText = textfieldProfileName.getText();
		String reconnectText = textfieldReconnectionTime.getText();

		if (emuText.isEmpty() || nameText.isEmpty()) {
			return false;
		}

		try {
			int emuIndex = Integer.parseInt(emuText);
			if (emuIndex < 0) {
				return false;
			}

			if (!reconnectText.isEmpty()) {
				int reconnectDelay = Integer.parseInt(reconnectText);
				if (reconnectDelay < 0) {
					return false;
				}
			}

			return true;
		} catch (NumberFormatException ex) {
			return false;
		}
	}

	/* ────────────────────────────────────────────────
	 *  Actions
	 * ──────────────────────────────────────────────── */

	@FXML
	private void handleSaveProfileButton(ActionEvent event) {
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