package dev.frostguard.app.panel.heroes;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.app.shared.AbstractProfileController;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.HBox;

import java.util.List;

public class IntelLayoutController extends AbstractProfileController {

    private static final List<Integer> MARCH_FLAGS = List.of(1, 2, 3, 4, 5, 6, 7, 8);

    @FXML
    private CheckBox checkBoxBeast;
    @FXML
    private CheckBox checkBoxFireBeast;
    @FXML
    private CheckBox checkBoxIntel;
    @FXML
    private CheckBox checkBoxJourney;
    @FXML
    private CheckBox checkBoxSurvivors;
    @FXML
    private CheckBox checkBoxFireCrystalEra;
    @FXML
    private CheckBox checkBoxSmartIntel;
    @FXML
    private CheckBox checkBoxRecallGatherTroops;
    @FXML
    private CheckBox checkBoxUseFlag;
    @FXML
    private HBox hboxFlagSelection;
    @FXML
    private ComboBox<Integer> comboBoxBeastFlag;

    @FXML
    private void initialize() {
        intelSwitches().forEach(binding -> checkBoxMappings.put(binding.control(), binding.configKey()));
        comboBoxMappings.put(comboBoxBeastFlag, ConfigurationKeyEnum.INTEL_BEASTS_FLAG_INT);
        prepareFlagPicker();
        enforceSingleAdvancedMode();
        initializeChangeEvents();
    }

    private List<IntelSwitch> intelSwitches() {
        return List.of(
            new IntelSwitch(checkBoxIntel, ConfigurationKeyEnum.INTEL_BOOL),
            new IntelSwitch(checkBoxBeast, ConfigurationKeyEnum.INTEL_BEASTS_BOOL),
            new IntelSwitch(checkBoxFireBeast, ConfigurationKeyEnum.INTEL_FIRE_BEAST_BOOL),
            new IntelSwitch(checkBoxJourney, ConfigurationKeyEnum.INTEL_EXPLORATION_BOOL),
            new IntelSwitch(checkBoxSurvivors, ConfigurationKeyEnum.INTEL_CAMP_BOOL),
            new IntelSwitch(checkBoxFireCrystalEra, ConfigurationKeyEnum.INTEL_FC_ERA_BOOL),
            new IntelSwitch(checkBoxUseFlag, ConfigurationKeyEnum.INTEL_USE_FLAG_BOOL),
            new IntelSwitch(checkBoxSmartIntel, ConfigurationKeyEnum.INTEL_SMART_PROCESSING_BOOL),
            new IntelSwitch(checkBoxRecallGatherTroops, ConfigurationKeyEnum.INTEL_RECALL_GATHER_TROOPS_BOOL)
        );
    }

    private void prepareFlagPicker() {
        comboBoxBeastFlag.getItems().setAll(MARCH_FLAGS);
        comboBoxBeastFlag.disableProperty().bind(checkBoxUseFlag.selectedProperty().not());
        hboxFlagSelection.visibleProperty().bind(checkBoxUseFlag.selectedProperty());
        hboxFlagSelection.managedProperty().bind(hboxFlagSelection.visibleProperty());
    }

    private void enforceSingleAdvancedMode() {
        ChangeListener<Boolean> smartIntelListener = (obs, wasSelected, isSelected) -> clearOtherWhenSelected(isSelected, checkBoxRecallGatherTroops);
        ChangeListener<Boolean> recallListener = (obs, wasSelected, isSelected) -> clearOtherWhenSelected(isSelected, checkBoxSmartIntel);
        checkBoxSmartIntel.selectedProperty().addListener(smartIntelListener);
        checkBoxRecallGatherTroops.selectedProperty().addListener(recallListener);
    }

    private void clearOtherWhenSelected(boolean selected, CheckBox other) {
        if (selected && other.isSelected()) {
            other.setSelected(false);
        }
    }

    private record IntelSwitch(CheckBox control, ConfigurationKeyEnum configKey) {
    }
}
