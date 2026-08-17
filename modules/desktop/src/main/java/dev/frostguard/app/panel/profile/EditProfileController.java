package dev.frostguard.app.panel.profile;

import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.app.shared.FieldValidationPresenter;
import dev.frostguard.app.shared.SettingValidator;
import dev.frostguard.app.shared.SettingValidators;
import dev.frostguard.app.shared.ValidationResult;
import dev.frostguard.engine.service.LoggingService;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.FlowPane;
import javafx.stage.Stage;

import java.net.URL;
import java.util.ResourceBundle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

public class EditProfileController implements Initializable {
	private static final long MAX_RECONNECTION_MINUTES = 10_080;

    private static final UnaryOperator<TextFormatter.Change> DIGITS_ONLY = change ->
        change.getControlNewText().matches("\\d*") ? change : null;

    private static final UnaryOperator<TextFormatter.Change> THREE_DIGIT_NUMBER = change ->
        change.getControlNewText().length() <= 3 && change.getControlNewText().matches("\\d*") ? change : null;

    private static final UnaryOperator<TextFormatter.Change> ALLIANCE_CODE = change ->
        change.getControlNewText().length() <= 3 && change.getControlNewText().matches("[A-Za-z0-9]*") ? change : null;

    @FXML
    private TextField txtProfileName;

    @FXML
    private TextField txtEmulatorNumber;

    @FXML
    private CheckBox chkEnabled;

    @FXML
    private Slider sliderPriority;

    @FXML
    private Label lblPriorityValue;

    @FXML
    private Button btnSave;

    @FXML
    private Button btnCancel;

    @FXML
    private TextField txtReconnectionTime;

    @FXML
    private Label lblProfileNameError;

    @FXML
    private Label lblEmulatorNumberError;

    @FXML
    private Label lblReconnectionTimeError;

    @FXML
    private TextField txtCharacterName;

    @FXML
    private TextField txtCharacterId;

    @FXML
    private TextField txtCharacterAllianceCode;

    @FXML
    private TextField txtCharacterServer;

    @FXML
    private FlowPane tagChoicesPane;

    @FXML
    private TextField txtNewTag;

    private final List<CheckBox> tagChoices = new ArrayList<>();

    private ProfileAux profileToEdit;
    private ProfileManagerActionController actionController;
    private Stage dialogStage;
    private boolean saveClicked = false;
    private FieldValidationPresenter profileNamePresenter;
    private FieldValidationPresenter emulatorNumberPresenter;
    private FieldValidationPresenter reconnectionTimePresenter;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        installInputGuards();
        profileNamePresenter = new FieldValidationPresenter(txtProfileName, lblProfileNameError);
        emulatorNumberPresenter = new FieldValidationPresenter(txtEmulatorNumber, lblEmulatorNumberError);
        reconnectionTimePresenter = new FieldValidationPresenter(txtReconnectionTime, lblReconnectionTimeError);
        installValidationListeners();
        bindPriorityLabel();
    }

    public void setProfileToEdit(ProfileAux profile) {
        this.profileToEdit = profile;
        populateFields();
    }

    public void setActionController(ProfileManagerActionController controller) {
        this.actionController = controller;
        populateTagChoices();
    }

    public void setDialogStage(Stage stage) {
        this.dialogStage = stage;
    }

    public boolean isSaveClicked() {
        return saveClicked;
    }

    private void populateFields() {
        if (profileToEdit != null) {
            txtProfileName.setText(profileToEdit.getName());
            txtEmulatorNumber.setText(profileToEdit.getEmulatorNumber());
            chkEnabled.setSelected(profileToEdit.isEnabled());
            sliderPriority.setValue(profileToEdit.getPriority().doubleValue());
            lblPriorityValue.setText(String.valueOf(profileToEdit.getPriority()));
            txtReconnectionTime.setText(String.valueOf(profileToEdit.getReconnectionTime()));
            txtCharacterId.setText(orBlank(profileToEdit.getCharacterId()));
            txtCharacterName.setText(orBlank(profileToEdit.getCharacterName()));
            txtCharacterAllianceCode.setText(orBlank(profileToEdit.getCharacterAllianceCode()));
            txtCharacterServer.setText(orBlank(profileToEdit.getCharacterServer()));
            populateTagChoices();
        }
    }

    private void populateTagChoices() {
        if (profileToEdit == null || actionController == null || tagChoicesPane == null) return;
        TreeSet<String> available = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        available.addAll(actionController.loadTags());
        available.addAll(profileToEdit.getTags());
        tagChoices.clear();
        tagChoicesPane.getChildren().clear();
        for (String tag : available) {
            CheckBox choice = new CheckBox(tag);
            choice.getStyleClass().add("profile-tag-choice");
            choice.setSelected(profileToEdit.getTags().stream().anyMatch(current -> current.equalsIgnoreCase(tag)));
            tagChoices.add(choice);
            tagChoicesPane.getChildren().add(choice);
        }
    }

    @FXML
    private void handleAddTag() {
        String name = txtNewTag.getText() == null ? "" : txtNewTag.getText().trim().replaceAll("\\s+", " ");
        if (name.isBlank()) return;
        tagChoices.stream().filter(choice -> choice.getText().equalsIgnoreCase(name)).findFirst()
            .ifPresentOrElse(choice -> choice.setSelected(true), () -> {
                CheckBox choice = new CheckBox(name.length() <= 40 ? name : name.substring(0, 40));
                choice.getStyleClass().add("profile-tag-choice");
                choice.setSelected(true);
                tagChoices.add(choice);
                tagChoices.sort(Comparator.comparing(CheckBox::getText, String.CASE_INSENSITIVE_ORDER));
                tagChoicesPane.getChildren().setAll(tagChoices);
            });
        txtNewTag.clear();
    }

    @FXML
    private void handleSave() {
        if (!validateInput(true)) {
            return;
        }

        applyFormValues();
        boolean saved = actionController.saveProfile(profileToEdit);

        if (saved) {
            saveClicked = true;
            showAlert(Alert.AlertType.INFORMATION, "Success", null, "Profile updated successfully.");
            dialogStage.close();
            log(TpMessageSeverityEnum.INFO, "Profile '" + profileToEdit.getName() + "' updated successfully");
        } else {
            showAlert(Alert.AlertType.ERROR, "Error", null, "Failed to update profile. Please try again.");
            log(TpMessageSeverityEnum.ERROR, "Failed to update profile '" + profileToEdit.getName() + "'");
        }
    }

    @FXML
    private void handleCancel() {
        dialogStage.close();
    }

    private boolean validateInput(boolean presentErrors) {
        boolean nameValid = validateField(
            txtProfileName, SettingValidators.requiredText("Profile name"), profileNamePresenter, presentErrors);
        boolean emulatorValid = validateField(
            txtEmulatorNumber, SettingValidators.nonNegativeInteger("Emulator number"),
            emulatorNumberPresenter, presentErrors);
        boolean reconnectValid = validateField(
            txtReconnectionTime, SettingValidators.rangedLong("Reconnection time", 0, MAX_RECONNECTION_MINUTES),
            reconnectionTimePresenter, presentErrors);
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

    private void installValidationListeners() {
        List.of(txtProfileName, txtEmulatorNumber, txtReconnectionTime).forEach(field ->
            field.textProperty().addListener((obs, oldValue, newValue) ->
                btnSave.setDisable(!validateInput(true))));
        btnSave.setDisable(!validateInput(false));
    }

    private void installInputGuards() {
        txtEmulatorNumber.setTextFormatter(new TextFormatter<>(THREE_DIGIT_NUMBER));
        txtReconnectionTime.setTextFormatter(new TextFormatter<>(DIGITS_ONLY));
        txtCharacterId.setTextFormatter(new TextFormatter<>(DIGITS_ONLY));
        txtCharacterAllianceCode.setTextFormatter(new TextFormatter<>(ALLIANCE_CODE));
        txtCharacterServer.setTextFormatter(new TextFormatter<>(DIGITS_ONLY));
    }

    private void bindPriorityLabel() {
        sliderPriority.valueProperty().addListener((observable, oldValue, newValue) ->
            lblPriorityValue.setText(String.valueOf(newValue.intValue()))
        );
    }

    private void applyFormValues() {
        profileToEdit.setName(txtProfileName.getText());
        profileToEdit.setEmulatorNumber(txtEmulatorNumber.getText());
        profileToEdit.setEnabled(chkEnabled.isSelected());
        profileToEdit.setPriority((long) sliderPriority.getValue());
        profileToEdit.setReconnectionTime(parseLongOrZero(txtReconnectionTime));
        profileToEdit.setCharacterId(blankToNull(txtCharacterId));
        profileToEdit.setCharacterName(blankToNull(txtCharacterName));
        profileToEdit.setCharacterAllianceCode(blankToNullUppercase(txtCharacterAllianceCode));
        profileToEdit.setCharacterServer(blankToNull(txtCharacterServer));
        profileToEdit.setTags(tagChoices.stream().filter(CheckBox::isSelected).map(CheckBox::getText).toList());
    }

    private long parseLongOrZero(TextField textField) {
        String value = textField.getText();
        return Long.parseLong(value == null || value.isEmpty() ? "0" : value);
    }

    private String blankToNull(TextField textField) {
        String value = textField.getText() == null ? "" : textField.getText().trim();
        return value.isEmpty() ? null : value;
    }

    private String blankToNullUppercase(TextField textField) {
        String value = blankToNull(textField);
        return value == null ? null : value.toUpperCase();
    }

    private String orBlank(String value) {
        return value == null ? "" : value;
    }

    private void showAlert(Alert.AlertType type, String title, String header, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private void log(TpMessageSeverityEnum severity, String message) {
        LoggingService.obtain().emit(severity, "Profile Editor", "-", message);
    }
}
