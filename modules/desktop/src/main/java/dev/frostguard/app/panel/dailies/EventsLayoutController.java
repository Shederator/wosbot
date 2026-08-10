package dev.frostguard.app.panel.dailies;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.app.shared.AbstractProfileController;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.Locale;

public class EventsLayoutController extends AbstractProfileController {

    private static final DateTimeFormatter TIME_FORMATTER = new DateTimeFormatterBuilder()
        .parseStrict()
        .appendPattern("HH:mm")
        .toFormatter(Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT);

    @FXML
    private CheckBox checkBoxTundraEvent, checkBoxTundraUseGems, checkBoxTundraSSR, checkBoxHeroMission,
        checkBoxMercenaryEvent, checkBoxJourneyofLight, checkBoxMyriadBazaar, checkBoxTundraEventActivationHour;

    @FXML
    private TextField textfieldTundraActivationHour;

    @FXML
    private ComboBox<Integer> comboBoxMercenaryFlag, comboBoxHeroMissionFlag;

    @FXML
    private Label labelDateTimeError;

    @FXML
    private void initialize() {
        fillFlagOptions();
        registerConfigurationFields();
        wireEnablementRules();
        prepareActivationTimeField();
        initializeChangeEvents();
    }

    private void fillFlagOptions() {
        comboBoxMercenaryFlag.getItems().setAll(0, 1, 2, 3, 4, 5, 6, 7, 8);
        comboBoxHeroMissionFlag.getItems().setAll(0, 1, 2, 3, 4, 5, 6, 7, 8);
    }

    private void registerConfigurationFields() {
        checkBoxMappings.put(checkBoxTundraEvent, ConfigurationKeyEnum.TUNDRA_TRUCK_EVENT_BOOL);
        checkBoxMappings.put(checkBoxTundraUseGems, ConfigurationKeyEnum.TUNDRA_TRUCK_USE_GEMS_BOOL);
        checkBoxMappings.put(checkBoxTundraSSR, ConfigurationKeyEnum.TUNDRA_TRUCK_SSR_BOOL);
        checkBoxMappings.put(checkBoxTundraEventActivationHour, ConfigurationKeyEnum.TUNDRA_TRUCK_ACTIVATION_TIME_BOOL);
        checkBoxMappings.put(checkBoxHeroMission, ConfigurationKeyEnum.HERO_MISSION_EVENT_BOOL);
        checkBoxMappings.put(checkBoxMercenaryEvent, ConfigurationKeyEnum.MERCENARY_EVENT_BOOL);
        checkBoxMappings.put(checkBoxJourneyofLight, ConfigurationKeyEnum.JOURNEY_OF_LIGHT_BOOL);
        checkBoxMappings.put(checkBoxMyriadBazaar, ConfigurationKeyEnum.MYRIAD_BAZAAR_EVENT_BOOL);

        comboBoxMappings.put(comboBoxMercenaryFlag, ConfigurationKeyEnum.MERCENARY_FLAG_INT);
        comboBoxMappings.put(comboBoxHeroMissionFlag, ConfigurationKeyEnum.HERO_MISSION_FLAG_INT);
        textFieldMappings.put(textfieldTundraActivationHour, ConfigurationKeyEnum.TUNDRA_TRUCK_ACTIVATION_TIME_STRING);
    }

    private void wireEnablementRules() {
        checkBoxTundraUseGems.disableProperty().bind(checkBoxTundraEvent.selectedProperty().not());
        checkBoxTundraSSR.disableProperty().bind(checkBoxTundraEvent.selectedProperty().not());
        checkBoxTundraEventActivationHour.disableProperty().bind(checkBoxTundraEvent.selectedProperty().not());
        textfieldTundraActivationHour.disableProperty().bind(
            checkBoxTundraEvent.selectedProperty().not().or(checkBoxTundraEventActivationHour.selectedProperty().not())
        );
        comboBoxHeroMissionFlag.disableProperty().bind(checkBoxHeroMission.selectedProperty().not());
        comboBoxMercenaryFlag.disableProperty().bind(checkBoxMercenaryEvent.selectedProperty().not());
    }

    private void prepareActivationTimeField() {
        textfieldTundraActivationHour.setPromptText("HH:mm");
        textfieldTundraActivationHour.setTooltip(new Tooltip("Example: 15:30"));
        textfieldTundraActivationHour.setTextFormatter(getTimeTextFormatter());
        textfieldTundraActivationHour.textProperty().addListener((obs, oldText, newText) -> validateTime(newText));
        textfieldTundraActivationHour.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                normalizeTimeField();
            }
        });
        textfieldTundraActivationHour.setOnAction(event -> normalizeTimeField());
    }

    private void normalizeTimeField() {
        String digits = textfieldTundraActivationHour.getText() == null
            ? ""
            : textfieldTundraActivationHour.getText().replaceAll("\\D", "");

        if (digits.length() == 2) {
            textfieldTundraActivationHour.setText(digits + ":00");
        } else if (digits.length() == 4) {
            textfieldTundraActivationHour.setText(digits.substring(0, 2) + ":" + digits.substring(2, 4));
        }
    }

    private void validateTime(String timeText) {
        clearTimeError();
        if (timeText == null || timeText.isBlank()) {
            return;
        }

        if (!timeText.matches("\\d{2}:\\d{2}")) {
            showTimeError("Use HH:mm, for example 19:30.");
            return;
        }

        try {
            LocalTime.parse(timeText, TIME_FORMATTER);
        } catch (java.time.DateTimeException ex) {
            showTimeError("Hour must be 00-23 and minute must be 00-59.");
        }
    }

    private void clearTimeError() {
        labelDateTimeError.setText("");
        textfieldTundraActivationHour.setStyle("");
    }

    private void showTimeError(String message) {
        labelDateTimeError.setText(message);
        textfieldTundraActivationHour.setStyle("-fx-border-color: #ef4444; -fx-border-width: 2px;");
    }
}
