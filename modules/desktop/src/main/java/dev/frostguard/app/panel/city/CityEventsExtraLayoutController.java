package dev.frostguard.app.panel.city;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.app.shared.AbstractProfileController;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;

public class CityEventsExtraLayoutController extends AbstractProfileController {

    private static final List<Integer> EXTRA_ATTEMPT_OPTIONS = List.of(0, 1, 2, 3, 4, 5);
    private static final List<String> SERVER_POLICY_OPTIONS = List.of(
        "Any server",
        "Prefer profile server",
        "Avoid profile server",
        "Never attack profile server"
    );
    private static final List<String> ALLIANCE_POLICY_OPTIONS = List.of(
        "Any alliance",
        "Avoid profile alliance",
        "Never attack profile alliance"
    );
    private static final String PROFILE_VALUE_NOT_SET = "Not set";

    @FXML
    private CheckBox checkBoxDailyVipRewards;
    @FXML
    private CheckBox checkBoxBuyMonthlyVip;
    @FXML
    private CheckBox checkBoxStorehouseChest;
    @FXML
    private CheckBox checkBoxDailyLabyrinth;
    @FXML
    private CheckBox checkBoxHeroRecruitment;
    @FXML
    private CheckBox checkBoxTrekSupplies;
    @FXML
    private CheckBox checkBoxTrekAutomation;
    @FXML
    private CheckBox checkBoxArena;
    @FXML
    private CheckBox checkBoxArenaRefreshWithGems;
    @FXML
    private TextField textFieldArenaActivationHour;
    @FXML
    private Label labelArenaProfileAlliance;
    @FXML
    private Label labelArenaProfileServer;
    @FXML
    private Label labelArenaTargetingHint;
    @FXML
    private ComboBox<Integer> comboBoxArenaExtraAttempts;
    @FXML
    private ComboBox<String> comboBoxArenaAlliancePolicy;
    @FXML
    private ComboBox<String> comboBoxArenaServerPolicy;
    @FXML
    private Label labelDateTimeError;

    @FXML
    private void initialize() {
        cityRoutineToggles().forEach(toggle -> checkBoxMappings.put(toggle.control(), toggle.configKey()));
        arenaTextFields().forEach(field -> textFieldMappings.put(field.control(), field.configKey()));
        comboBoxArenaExtraAttempts.getItems().setAll(EXTRA_ATTEMPT_OPTIONS);
        comboBoxArenaAlliancePolicy.getItems().setAll(ALLIANCE_POLICY_OPTIONS);
        comboBoxArenaServerPolicy.getItems().setAll(SERVER_POLICY_OPTIONS);
        comboBoxMappings.put(comboBoxArenaExtraAttempts, ConfigurationKeyEnum.ARENA_TASK_EXTRA_ATTEMPTS_INT);
        comboBoxMappings.put(comboBoxArenaAlliancePolicy, ConfigurationKeyEnum.ARENA_TASK_ALLIANCE_POLICY_STRING);
        comboBoxMappings.put(comboBoxArenaServerPolicy, ConfigurationKeyEnum.ARENA_TASK_SERVER_POLICY_STRING);

        new ArenaSection().install();
        new ArenaTimeField().install();
        initializeChangeEvents();
    }

    private List<ToggleBinding> cityRoutineToggles() {
        return List.of(
            new ToggleBinding(checkBoxDailyVipRewards, ConfigurationKeyEnum.BOOL_VIP_POINTS),
            new ToggleBinding(checkBoxBuyMonthlyVip, ConfigurationKeyEnum.VIP_MONTHLY_BUY_BOOL),
            new ToggleBinding(checkBoxStorehouseChest, ConfigurationKeyEnum.STOREHOUSE_CHEST_BOOL),
            new ToggleBinding(checkBoxDailyLabyrinth, ConfigurationKeyEnum.DAILY_LABYRINTH_BOOL),
            new ToggleBinding(checkBoxHeroRecruitment, ConfigurationKeyEnum.BOOL_HERO_RECRUITMENT),
            new ToggleBinding(checkBoxTrekSupplies, ConfigurationKeyEnum.TUNDRA_TREK_SUPPLIES_BOOL),
            new ToggleBinding(checkBoxTrekAutomation, ConfigurationKeyEnum.TUNDRA_TREK_AUTOMATION_BOOL),
            new ToggleBinding(checkBoxArena, ConfigurationKeyEnum.ARENA_TASK_BOOL),
            new ToggleBinding(checkBoxArenaRefreshWithGems, ConfigurationKeyEnum.ARENA_TASK_REFRESH_WITH_GEMS_BOOL)
        );
    }

    private List<TextBinding> arenaTextFields() {
        return List.of(
            new TextBinding(textFieldArenaActivationHour, ConfigurationKeyEnum.ARENA_TASK_ACTIVATION_TIME_STRING)
        );
    }

    private void disableWhenArenaOff(Node node) {
        node.disableProperty().bind(checkBoxArena.selectedProperty().not());
    }

    @Override
    public void onProfileLoad(ProfileAux profile) {
        super.onProfileLoad(profile);
        isLoadingProfile = true;
        try {
            String alliance = normalizeProfileValue(profile.getCharacterAllianceCode());
            String server = normalizeProfileValue(profile.getCharacterServer());
            labelArenaProfileAlliance.setText(alliance);
            labelArenaProfileServer.setText(server);
            if (PROFILE_VALUE_NOT_SET.equals(alliance)) {
                comboBoxArenaAlliancePolicy.setValue("Any alliance");
            }
            if (PROFILE_VALUE_NOT_SET.equals(server)) {
                comboBoxArenaServerPolicy.setValue("Any server");
            }
            labelArenaTargetingHint.setText(buildArenaTargetingHint(alliance, server));
        } finally {
            isLoadingProfile = false;
        }
    }

    private String normalizeProfileValue(String value) {
        return value == null || value.isBlank() ? PROFILE_VALUE_NOT_SET : value.trim();
    }

    private String buildArenaTargetingHint(String alliance, String server) {
        if (!PROFILE_VALUE_NOT_SET.equals(alliance) && !PROFILE_VALUE_NOT_SET.equals(server)) {
            return "Target filters use the selected profile's Character Information: Alliance and Server.";
        }
        if (PROFILE_VALUE_NOT_SET.equals(alliance) && PROFILE_VALUE_NOT_SET.equals(server)) {
            return "Set Alliance and Server in Profile > Character Information to enable target filters.";
        }
        if (PROFILE_VALUE_NOT_SET.equals(alliance)) {
            return "Set Alliance in Profile > Character Information to protect alliance members.";
        }
        return "Set Server in Profile > Character Information to enable server preferences.";
    }

    private record ToggleBinding(CheckBox control, ConfigurationKeyEnum configKey) {
    }

    private record TextBinding(TextField control, ConfigurationKeyEnum configKey) {
    }

    private final class ArenaSection {
        private void install() {
            List.of(checkBoxArenaRefreshWithGems, textFieldArenaActivationHour, comboBoxArenaExtraAttempts)
                .forEach(CityEventsExtraLayoutController.this::disableWhenArenaOff);
            comboBoxArenaAlliancePolicy.disableProperty().bind(
                checkBoxArena.selectedProperty().not()
                    .or(Bindings.createBooleanBinding(
                        () -> PROFILE_VALUE_NOT_SET.equals(labelArenaProfileAlliance.getText()),
                        labelArenaProfileAlliance.textProperty())));
            comboBoxArenaServerPolicy.disableProperty().bind(
                checkBoxArena.selectedProperty().not()
                    .or(Bindings.createBooleanBinding(
                        () -> PROFILE_VALUE_NOT_SET.equals(labelArenaProfileServer.getText()),
                        labelArenaProfileServer.textProperty())));
            comboBoxArenaAlliancePolicy.setTooltip(new Tooltip("Set Alliance in Profile > Character Information to enable alliance preferences."));
            comboBoxArenaServerPolicy.setTooltip(new Tooltip("Set Server in Profile > Character Information to enable server preferences."));
        }
    }

    private final class ArenaTimeField {
        private final DateTimeFormatter parser = new DateTimeFormatterBuilder()
            .parseStrict()
            .appendPattern("HH:mm")
            .toFormatter(Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

        private void install() {
            textFieldArenaActivationHour.setPromptText("HH:mm");
            textFieldArenaActivationHour.setTooltip(new Tooltip("Arena runs before 23:56 UTC; example: 19:30"));
            textFieldArenaActivationHour.setTextFormatter(getTimeTextFormatter());
            textFieldArenaActivationHour.textProperty().addListener((obs, oldText, newText) -> validate(newText));
            textFieldArenaActivationHour.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
                if (!isFocused) {
                    normalize();
                }
            });
            textFieldArenaActivationHour.setOnAction(event -> normalize());
        }

        private void normalize() {
            String digits = textFieldArenaActivationHour.getText() == null
                ? ""
                : textFieldArenaActivationHour.getText().replaceAll("\\D", "");

            switch (digits.length()) {
                case 2 -> textFieldArenaActivationHour.setText(digits + ":00");
                case 4 -> textFieldArenaActivationHour.setText(digits.substring(0, 2) + ":" + digits.substring(2));
                default -> {
                }
            }
        }

        private void validate(String timeText) {
            clearWarning();
            if (timeText == null || timeText.isBlank()) {
                return;
            }
            if (!timeText.matches("\\d{2}:\\d{2}")) {
                showWarning("Use HH:mm, for example 19:30.");
                return;
            }
            try {
                LocalTime time = LocalTime.parse(timeText, parser);
                if (time.isAfter(LocalTime.of(23, 55))) {
                    showWarning("Arena time must be 23:55 UTC or earlier.");
                }
            } catch (java.time.DateTimeException ex) {
                showWarning("Hour must be 00-23 and minute must be 00-59.");
            }
        }

        private void clearWarning() {
            labelDateTimeError.setText("");
            textFieldArenaActivationHour.setStyle("");
        }

        private void showWarning(String message) {
            labelDateTimeError.setText(message);
            textFieldArenaActivationHour.setStyle("-fx-border-color: #ef4444; -fx-border-width: 2px;");
        }
    }
}
