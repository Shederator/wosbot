package dev.frostguard.app.panel.combat;

import dev.frostguard.api.configs.BearTrapParticipationTriggerEnum;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.app.shared.UtcDateTimeEditor;
import dev.frostguard.app.shared.UtcDateTimeValue;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanExpression;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.controlsfx.control.CheckComboBox;

import java.time.LocalDateTime;
import java.util.List;

public class BearTrapLayoutController extends AbstractProfileController {

    @FXML
    private CheckBox checkBoxEnableBearTrap;

    @FXML
    private ComboBox<ProtectionMode> comboBoxProtectionTimer1;

    @FXML
    private Label labelProtectionHelperTimer1;

    @FXML
    private UtcDateTimeEditor timer1DateTimeEditor;

    @FXML
    private ComboBox<ProtectionMode> comboBoxProtectionTimer2;

    @FXML
    private Label labelProtectionHelperTimer2;

    @FXML
    private UtcDateTimeEditor timer2DateTimeEditor;

    @FXML
    private TextField textFieldPreparationTime;

    @FXML
    private Label labelPreparationTimeError;

    @FXML
    private CheckBox checkBoxActivePets;

    @FXML
    private CheckBox checkBoxRecallTroops;

    @FXML
    private ComboBox<Integer> comboBoxTrapNumber;

    @FXML
    private ComboBox<BearTrapParticipationTriggerEnum> comboBoxParticipationTrigger;

    @FXML
    private Label labelParticipationHelper;

    @FXML
    private Label labelParticipationWarning;

    @FXML
    private Label labelSelectedTimerWarning;

    @FXML
    private Label labelTimerRecommendation;

    @FXML
    private Label labelTrapSelectionHelper;

    @FXML
    private Label labelParticipationTriggerInfo;

    @FXML
    private CheckBox checkBoxCallRally;

    @FXML
    private ComboBox<Integer> comboBoxRallyFlag;

    @FXML
    private CheckBox checkBoxEnableJoin;

    @FXML
    private CheckComboBox<Integer> checkComboBoxJoinFlag;

    private List<TimerBinding> timerBindings;
    private boolean loadingParticipationTrigger;
    private boolean loadingProtectionModes;

    @FXML
    private void initialize() {
        timerBindings = List.of(
                new TimerBinding(timer1DateTimeEditor, comboBoxProtectionTimer1, labelProtectionHelperTimer1,
                        ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING,
                        ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_ENABLED_BOOL,
                        ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_BLOCK_RALLIES_BOOL,
                        ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_PAUSE_ALL_TASKS_BOOL,
                        1),
                new TimerBinding(timer2DateTimeEditor, comboBoxProtectionTimer2, labelProtectionHelperTimer2,
                        ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_SCHEDULE_DATETIME_STRING,
                        ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_ENABLED_BOOL,
                        ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_BLOCK_RALLIES_BOOL,
                        ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_PAUSE_ALL_TASKS_BOOL,
                        2));
        registerConfigurationFields();
        populateFlagControls();
        configureProtectionModes();
        configureParticipationTrigger();
        configureParticipationTriggerTooltip();
        configureParticipationScheduleHelp();
        configureDateTimeEditors();
        refreshAvailableParticipationTimers();
        bindEnabledState();
        initializeChangeEvents();
    }

    private void registerConfigurationFields() {
        checkBoxMappings.put(checkBoxEnableBearTrap, ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL);
        checkBoxMappings.put(checkBoxActivePets, ConfigurationKeyEnum.BEAR_TRAP_ACTIVE_PETS_BOOL);
        checkBoxMappings.put(checkBoxRecallTroops, ConfigurationKeyEnum.BEAR_TRAP_RECALL_TROOPS_BOOL);
        checkBoxMappings.put(checkBoxCallRally, ConfigurationKeyEnum.BEAR_TRAP_CALL_RALLY_BOOL);
        checkBoxMappings.put(checkBoxEnableJoin, ConfigurationKeyEnum.BEAR_TRAP_JOIN_RALLY_BOOL);

        registerTextField(textFieldPreparationTime, labelPreparationTimeError,
                ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT);

        comboBoxMappings.put(comboBoxTrapNumber, ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT);
        comboBoxMappings.put(comboBoxRallyFlag, ConfigurationKeyEnum.BEAR_TRAP_RALLY_FLAG_INT);
        checkComboBoxMappings.put(checkComboBoxJoinFlag, ConfigurationKeyEnum.BEAR_TRAP_JOIN_FLAG_INT);
    }

    private void populateFlagControls() {
        comboBoxTrapNumber.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer trapNumber) {
                return trapNumber == null ? "" : "Bear Trap " + trapNumber + " (Timer " + trapNumber + ")";
            }

            @Override
            public Integer fromString(String value) {
                return comboBoxTrapNumber.getValue();
            }
        });
        comboBoxRallyFlag.getItems().setAll(1, 2, 3, 4, 5, 6, 7, 8);
        checkComboBoxJoinFlag.getItems().setAll(1, 2, 3, 4, 5, 6, 7, 8);
    }

    private void configureParticipationTrigger() {
        comboBoxParticipationTrigger.getItems().setAll(BearTrapParticipationTriggerEnum.values());
        comboBoxParticipationTrigger.setValue(BearTrapParticipationTriggerEnum.TIMER_ONLY);
        comboBoxParticipationTrigger.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue == null) {
                return;
            }
            updateParticipationHelp();
            if (!loadingParticipationTrigger) {
                publishWhenReady(ConfigurationKeyEnum.BEAR_TRAP_ICON_PARTICIPATION_FALLBACK_BOOL,
                        newValue.isIconFallbackEnabled());
            }
        });
        updateParticipationHelp();
    }

    private void configureParticipationTriggerTooltip() {
        Tooltip tooltip = new Tooltip(
                "Bear icon detection always protects the event by blocking unrelated rally-starting tasks. "
                        + "This setting only controls whether the icon may also start Bear participation.");
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(420);
        tooltip.setShowDelay(Duration.millis(150));
        tooltip.setShowDuration(Duration.seconds(20));
        labelParticipationTriggerInfo.setTooltip(tooltip);
    }

    private void configureProtectionModes() {
        timerBindings.forEach(binding -> {
            binding.protectionMode().getItems().setAll(ProtectionMode.values());
            binding.protectionMode().setValue(ProtectionMode.OFF);
            binding.protectionMode().valueProperty().addListener((obs, oldValue, newValue) -> {
                if (newValue == null) {
                    return;
                }
                updateProtectionHelper(binding);
                updateParticipationHelp();
                if (!loadingProtectionModes) {
                    publishProtectionMode(binding, newValue);
                }
            });
            updateProtectionHelper(binding);
        });
    }

    private void publishProtectionMode(TimerBinding binding, ProtectionMode mode) {
        publishWhenReady(binding.enabledKey(), mode != ProtectionMode.OFF);
        publishWhenReady(binding.blockRalliesKey(), mode == ProtectionMode.BLOCK_RALLIES);
        publishWhenReady(binding.pauseAllKey(), mode == ProtectionMode.PAUSE_ALL);
    }

    private void configureParticipationScheduleHelp() {
        comboBoxTrapNumber.valueProperty().addListener((obs, oldValue, newValue) -> updateParticipationHelp());
        checkBoxEnableBearTrap.selectedProperty().addListener((obs, oldValue, newValue) -> updateParticipationHelp());
        updateParticipationHelp();
    }

    private void updateParticipationHelp() {
        BearTrapParticipationTriggerEnum trigger = comboBoxParticipationTrigger.getValue();
        Integer trapNumber = comboBoxTrapNumber.getValue();
        boolean iconFallback = trigger == BearTrapParticipationTriggerEnum.TIMER_ICON_FALLBACK;
        if (trapNumber == null) {
            labelParticipationHelper.setText("Select an applied event timer to schedule participation.");
        } else {
            labelParticipationHelper.setText(iconFallback
                    ? "Uses Timer " + trapNumber + "'s UTC schedule; a Bear Trap icon may also start participation."
                    : "Uses Timer " + trapNumber + "'s UTC schedule or Run now.");
        }
        labelParticipationWarning.setVisible(iconFallback);
        labelParticipationWarning.setManaged(iconFallback);

        ProtectionMode selectedMode = trapNumber == null
                ? ProtectionMode.OFF
                : timerBindings.get(trapNumber - 1).protectionMode().getValue();
        boolean selectedProtectionEnabled = selectedMode != null && selectedMode != ProtectionMode.OFF;
        boolean showProtectionWarning = checkBoxEnableBearTrap.isSelected()
                && trapNumber != null
                && !selectedProtectionEnabled;
        labelSelectedTimerWarning.setText(trapNumber == null
                ? ""
                : "Timer " + trapNumber
                        + " protection is disabled. Participation still uses its UTC schedule.");
        labelSelectedTimerWarning.setVisible(showProtectionWarning);
        labelSelectedTimerWarning.setManaged(showProtectionWarning);
    }

    private void updateProtectionHelper(TimerBinding binding) {
        if (!binding.editor().hasCommittedDateTime()) {
            binding.protectionHelper().setText("Apply this event timer before choosing protection.");
            return;
        }
        ProtectionMode mode = binding.protectionMode().getValue();
        binding.protectionHelper().setText(mode == null ? "" : mode.helperText());
    }

    private void refreshAvailableParticipationTimers() {
        Integer selectedTrap = comboBoxTrapNumber.getValue();
        List<Integer> availableTraps = timerBindings.stream()
                .filter(binding -> binding.editor().hasCommittedDateTime())
                .map(TimerBinding::trapNumber)
                .toList();

        boolean wasLoadingProfile = isLoadingProfile;
        isLoadingProfile = true;
        try {
            comboBoxTrapNumber.getItems().setAll(availableTraps);
            comboBoxTrapNumber.setValue(availableTraps.contains(selectedTrap) ? selectedTrap : null);
        } finally {
            isLoadingProfile = wasLoadingProfile;
        }
        comboBoxTrapNumber.setPromptText(availableTraps.isEmpty()
                ? "Set an event timer first"
                : "Select a configured timer");

        boolean bothTimersConfigured = availableTraps.size() == timerBindings.size();
        labelTimerRecommendation.setText(bothTimersConfigured
                ? "Both 48-hour event timers are configured."
                : "Strongly recommended: Set both UTC event timers, even without participation automation, so either Bear Trap can be protected or selected without reconfiguration.");
        labelTimerRecommendation.setStyle(bothTimersConfigured
                ? "-fx-background-color: rgba(34, 197, 94, 0.12); -fx-background-radius: 4; -fx-padding: 6 8; -fx-text-fill: #86efac; -fx-font-size: 11px;"
                : "-fx-background-color: rgba(245, 158, 11, 0.14); -fx-background-radius: 4; -fx-padding: 6 8; -fx-text-fill: #fbbf24; -fx-font-size: 11px;");
        labelTrapSelectionHelper.setText(availableTraps.isEmpty()
                ? "Set a complete UTC date and time above and click Apply first."
                : "Only event timers applied above can be selected.");
        timerBindings.forEach(binding -> {
            binding.protectionMode().setDisable(!binding.editor().hasCommittedDateTime());
            updateProtectionHelper(binding);
        });
        updateParticipationHelp();
    }

    private void bindEnabledState() {
        timerBindings.forEach(this::bindTimerState);

        BooleanExpression disabledUntilEnabled = checkBoxEnableBearTrap.selectedProperty().not();
        checkBoxActivePets.disableProperty().bind(disabledUntilEnabled);
        checkBoxRecallTroops.disableProperty().bind(disabledUntilEnabled);
        comboBoxTrapNumber.disableProperty().bind(
                disabledUntilEnabled.or(Bindings.isEmpty(comboBoxTrapNumber.getItems())));
        comboBoxParticipationTrigger.disableProperty().bind(disabledUntilEnabled);
        checkBoxCallRally.disableProperty().bind(disabledUntilEnabled);
        checkBoxEnableJoin.disableProperty().bind(disabledUntilEnabled);

        comboBoxRallyFlag.disableProperty().bind(disabledUntilEnabled.or(checkBoxCallRally.selectedProperty().not()));
        checkComboBoxJoinFlag.disableProperty().bind(disabledUntilEnabled.or(checkBoxEnableJoin.selectedProperty().not()));

        bindManagedVisibility(comboBoxRallyFlag, checkBoxCallRally.selectedProperty());
        bindManagedVisibility(checkComboBoxJoinFlag, checkBoxEnableJoin.selectedProperty());
    }

    private void bindTimerState(TimerBinding timer) {
        BooleanExpression selectedForParticipation = Bindings.createBooleanBinding(
                () -> checkBoxEnableBearTrap.isSelected()
                        && Integer.valueOf(timer.trapNumber()).equals(comboBoxTrapNumber.getValue()),
                checkBoxEnableBearTrap.selectedProperty(),
                comboBoxTrapNumber.valueProperty());
        BooleanExpression protectionEnabled = Bindings.createBooleanBinding(
                () -> timer.protectionMode().getValue() != null
                        && timer.protectionMode().getValue() != ProtectionMode.OFF,
                timer.protectionMode().valueProperty());
        BooleanExpression timerInUse = protectionEnabled.or(selectedForParticipation);
        timer.editor().timerEnabledProperty().bind(timerInUse);
    }

    private void configureDateTimeEditors() {
        timerBindings.forEach(binding -> {
            binding.editor().setOnCommit(value -> {
                boolean firstConfiguration = !comboBoxTrapNumber.getItems().contains(binding.trapNumber());
                publishWhenReady(binding.scheduleKey(), UtcDateTimeValue.formatPersisted(value));
                if (firstConfiguration && binding.protectionMode().getValue() == ProtectionMode.OFF) {
                    binding.protectionMode().setValue(ProtectionMode.BLOCK_RALLIES);
                }
                refreshAvailableParticipationTimers();
            });
            binding.editor().setOnClear(() -> {
                publishWhenReady(binding.scheduleKey(), "");
                loadingProtectionModes = true;
                try {
                    binding.protectionMode().setValue(ProtectionMode.OFF);
                } finally {
                    loadingProtectionModes = false;
                }
                publishProtectionMode(binding, ProtectionMode.OFF);
                refreshAvailableParticipationTimers();
            });
        });
    }

    @Override
    public void onProfileLoad(ProfileAux profile) {
        super.onProfileLoad(profile);
        isLoadingProfile = true;
        loadingParticipationTrigger = true;
        loadingProtectionModes = true;
        try {
            boolean iconFallback = Boolean.TRUE.equals(profile.<Boolean>getConfiguration(
                    ConfigurationKeyEnum.BEAR_TRAP_ICON_PARTICIPATION_FALLBACK_BOOL));
            comboBoxParticipationTrigger.setValue(
                    BearTrapParticipationTriggerEnum.fromIconFallbackEnabled(iconFallback));
            timerBindings.forEach(binding -> binding.editor().setDateTime(
                    loadSavedDateTime(profile, binding.scheduleKey())));
            timerBindings.forEach(binding -> binding.protectionMode().setValue(
                    binding.editor().hasCommittedDateTime() ? loadProtectionMode(profile, binding) : ProtectionMode.OFF));
            refreshAvailableParticipationTimers();
        } finally {
            loadingProtectionModes = false;
            loadingParticipationTrigger = false;
            isLoadingProfile = false;
        }
        updateParticipationHelp();
    }

    private ProtectionMode loadProtectionMode(ProfileAux profile, TimerBinding binding) {
        boolean explicitlyEnabled = Boolean.TRUE.equals(
                profile.<Boolean>getConfiguration(binding.enabledKey()));
        boolean legacyTimer1Enabled = binding.trapNumber() == 1
                && !profileHasConfiguration(profile, binding.enabledKey())
                && Boolean.TRUE.equals(profile.<Boolean>getConfiguration(ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL));
        if (!explicitlyEnabled && !legacyTimer1Enabled) {
            return ProtectionMode.OFF;
        }
        if (Boolean.TRUE.equals(profile.<Boolean>getConfiguration(binding.pauseAllKey()))) {
            return ProtectionMode.PAUSE_ALL;
        }
        if (legacyTimer1Enabled
                || Boolean.TRUE.equals(profile.<Boolean>getConfiguration(binding.blockRalliesKey()))) {
            return ProtectionMode.BLOCK_RALLIES;
        }
        return ProtectionMode.OFF;
    }

    private boolean profileHasConfiguration(ProfileAux profile, ConfigurationKeyEnum key) {
        return profile.getConfigs().stream().anyMatch(config -> key.name().equalsIgnoreCase(config.getName()));
    }

    private LocalDateTime loadSavedDateTime(ProfileAux profile, ConfigurationKeyEnum key) {
        return profile.getConfigs().stream()
                .filter(config -> key.name().equalsIgnoreCase(config.getName()))
                .map(config -> config.getValue())
                .map(UtcDateTimeValue::parsePersisted)
                .flatMap(java.util.Optional::stream)
                .findFirst()
                .orElse(null);
    }

    private static void bindManagedVisibility(javafx.scene.Node node, javafx.beans.value.ObservableBooleanValue visible) {
        node.visibleProperty().bind(visible);
        node.managedProperty().bind(node.visibleProperty());
    }

    private record TimerBinding(
            UtcDateTimeEditor editor,
            ComboBox<ProtectionMode> protectionMode,
            Label protectionHelper,
            ConfigurationKeyEnum scheduleKey,
            ConfigurationKeyEnum enabledKey,
            ConfigurationKeyEnum blockRalliesKey,
            ConfigurationKeyEnum pauseAllKey,
            int trapNumber) {
    }

    private enum ProtectionMode {
        OFF("Off", "No scheduled tasks are blocked for this timer."),
        BLOCK_RALLIES("Block rally-starting tasks (Recommended)",
                "Prevents Polar Terror, Hero Mission, and Mercenary from starting rallies."),
        PAUSE_ALL("Pause all scheduled tasks",
                "Prevents all non-essential scheduled tasks from starting during the protection window.");

        private final String label;
        private final String helperText;

        ProtectionMode(String label, String helperText) {
            this.label = label;
            this.helperText = helperText;
        }

        String helperText() {
            return helperText;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
