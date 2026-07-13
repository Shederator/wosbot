package dev.frostguard.app.panel.combat;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.app.shared.AbstractProfileController;
import javafx.beans.binding.BooleanExpression;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import org.controlsfx.control.CheckComboBox;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.Locale;

public class BearTrapLayoutController extends AbstractProfileController {

    private static final DateTimeFormatter TRAP_DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
        .parseStrict()
        .appendPattern("dd-MM-uuuu HH:mm")
        .toFormatter(Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT);

    @FXML
    private CheckBox checkBoxEnableBearTrap;

    @FXML
    private TextField textFieldScheduleDateTime;

    @FXML
    private TextField textFieldPreparationTime;

    @FXML
    private CheckBox checkBoxActivePets;

    @FXML
    private CheckBox checkBoxRecallTroops;

    @FXML
    private ComboBox<Integer> comboBoxTrapNumber;

    @FXML
    private CheckBox checkBoxCallRally;

    @FXML
    private ComboBox<Integer> comboBoxRallyFlag;

    @FXML
    private CheckBox checkBoxEnableJoin;

    @FXML
    private CheckComboBox<Integer> checkComboBoxJoinFlag;

    @FXML
    private Label labelDateTimeError;

    @FXML
    private void initialize() {
        registerConfigurationFields();
        populateFlagControls();
        bindEnabledState();
        prepareDateTimeField();
        initializeChangeEvents();
    }

    private void registerConfigurationFields() {
        checkBoxMappings.put(checkBoxEnableBearTrap, ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL);
        checkBoxMappings.put(checkBoxActivePets, ConfigurationKeyEnum.BEAR_TRAP_ACTIVE_PETS_BOOL);
        checkBoxMappings.put(checkBoxRecallTroops, ConfigurationKeyEnum.BEAR_TRAP_RECALL_TROOPS_BOOL);
        checkBoxMappings.put(checkBoxCallRally, ConfigurationKeyEnum.BEAR_TRAP_CALL_RALLY_BOOL);
        checkBoxMappings.put(checkBoxEnableJoin, ConfigurationKeyEnum.BEAR_TRAP_JOIN_RALLY_BOOL);

        textFieldMappings.put(textFieldScheduleDateTime, ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING);
        textFieldMappings.put(textFieldPreparationTime, ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT);

        comboBoxMappings.put(comboBoxTrapNumber, ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT);
        comboBoxMappings.put(comboBoxRallyFlag, ConfigurationKeyEnum.BEAR_TRAP_RALLY_FLAG_INT);
        checkComboBoxMappings.put(checkComboBoxJoinFlag, ConfigurationKeyEnum.BEAR_TRAP_JOIN_FLAG_INT);
    }

    private void populateFlagControls() {
        comboBoxTrapNumber.getItems().setAll(1, 2);
        comboBoxRallyFlag.getItems().setAll(1, 2, 3, 4, 5, 6, 7, 8);
        checkComboBoxJoinFlag.getItems().setAll(1, 2, 3, 4, 5, 6, 7, 8);
    }

    private void bindEnabledState() {
        BooleanExpression disabledUntilEnabled = checkBoxEnableBearTrap.selectedProperty().not();
        textFieldScheduleDateTime.disableProperty().bind(disabledUntilEnabled);
        textFieldPreparationTime.disableProperty().bind(disabledUntilEnabled);
        checkBoxActivePets.disableProperty().bind(disabledUntilEnabled);
        checkBoxRecallTroops.disableProperty().bind(disabledUntilEnabled);
        comboBoxTrapNumber.disableProperty().bind(disabledUntilEnabled);
        checkBoxCallRally.disableProperty().bind(disabledUntilEnabled);
        checkBoxEnableJoin.disableProperty().bind(disabledUntilEnabled);

        comboBoxRallyFlag.disableProperty().bind(disabledUntilEnabled.or(checkBoxCallRally.selectedProperty().not()));
        checkComboBoxJoinFlag.disableProperty().bind(disabledUntilEnabled.or(checkBoxEnableJoin.selectedProperty().not()));

        bindManagedVisibility(comboBoxRallyFlag, checkBoxCallRally.selectedProperty());
        bindManagedVisibility(checkComboBoxJoinFlag, checkBoxEnableJoin.selectedProperty());
    }

    private void prepareDateTimeField() {
        textFieldScheduleDateTime.setPromptText("dd-MM-yyyy HH:mm");
        textFieldScheduleDateTime.setTooltip(new Tooltip("Example: 10-06-2026 19:30"));
        textFieldScheduleDateTime.setTextFormatter(createDateTimeMask());
        textFieldScheduleDateTime.textProperty().addListener((obs, oldText, newText) -> validateDateTime(newText));
        textFieldScheduleDateTime.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                normalizeDateTimeField();
            }
        });
    }

    private TextFormatter<String> createDateTimeMask() {
        return new TextFormatter<>(change -> {
            if (!change.isContentChange()) {
                return change;
            }

            String digits = change.getControlNewText().replaceAll("\\D", "");
            if (digits.length() > 12) {
                digits = digits.substring(0, 12);
            }

            StringBuilder masked = new StringBuilder();
            for (int index = 0; index < digits.length(); index++) {
                if (index == 2 || index == 4) {
                    masked.append('-');
                } else if (index == 8) {
                    masked.append(' ');
                } else if (index == 10) {
                    masked.append(':');
                }
                masked.append(digits.charAt(index));
            }

            change.setRange(0, change.getControlText().length());
            change.setText(masked.toString());
            change.setCaretPosition(masked.length());
            change.setAnchor(masked.length());
            return change;
        });
    }

    private void normalizeDateTimeField() {
        String digits = textFieldScheduleDateTime.getText() == null
            ? ""
            : textFieldScheduleDateTime.getText().replaceAll("\\D", "");

        if (digits.length() == 12) {
            textFieldScheduleDateTime.setText("%s-%s-%s %s:%s".formatted(
                digits.substring(0, 2),
                digits.substring(2, 4),
                digits.substring(4, 8),
                digits.substring(8, 10),
                digits.substring(10, 12)
            ));
        }
    }

    private void validateDateTime(String dateTimeText) {
        clearDateTimeError();
        if (dateTimeText == null || dateTimeText.isBlank()) {
            return;
        }

        if (!dateTimeText.matches("\\d{2}-\\d{2}-\\d{4} \\d{2}:\\d{2}")) {
            showDateTimeError("Use dd-MM-yyyy HH:mm, for example 10-06-2026 19:30.");
            return;
        }

        try {
            LocalDateTime.parse(dateTimeText, TRAP_DATE_TIME_FORMATTER);
        } catch (java.time.DateTimeException ex) {
            showDateTimeError("That date or time is outside the valid calendar range.");
        }
    }

    private void clearDateTimeError() {
        labelDateTimeError.setText("");
        textFieldScheduleDateTime.getStyleClass().remove("field-error");
    }

    private void showDateTimeError(String message) {
        labelDateTimeError.setText(message);
        if (!textFieldScheduleDateTime.getStyleClass().contains("field-error")) { textFieldScheduleDateTime.getStyleClass().add("field-error"); }
    }

    private static void bindManagedVisibility(javafx.scene.Node node, javafx.beans.value.ObservableBooleanValue visible) {
        node.visibleProperty().bind(visible);
        node.managedProperty().bind(node.visibleProperty());
    }
}
