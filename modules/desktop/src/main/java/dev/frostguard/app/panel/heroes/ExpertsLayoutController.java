package dev.frostguard.app.panel.heroes;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.app.shared.PriorityListView;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.ExpertSkillItemEnum;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.VBox;

import java.util.List;

public class ExpertsLayoutController extends AbstractProfileController {

    @FXML
    private CheckBox claimIntelCheckBox;

    @FXML
    private CheckBox claimLoyaltyTagCheckBox;

    @FXML
    private CheckBox claimTroopsCheckBox;

    @FXML
    private VBox troopOptionsVBox;

    @FXML
    private ComboBox<String> troopTypeComboBox;

    @FXML
    private CheckBox enableExpertSkillTrainingCheckBox;

    @FXML
    private VBox expertSkillTrainingVBox;

    @FXML
    private PriorityListView expertSkillPriorities;


    @FXML
    public void initialize() {
        configureOptions();
        registerSettings();
        wireDynamicSections();
        initializeChangeEvents();
    }

    private void configureOptions() {
        troopTypeComboBox.getItems().setAll(TroopChoice.labels());
    }

    private void registerSettings() {
        expertSwitches().forEach(binding -> checkBoxMappings.put(binding.control(), binding.configKey()));
        comboBoxMappings.put(troopTypeComboBox, ConfigurationKeyEnum.EXPERT_ROMULUS_TROOPS_TYPE_STRING);
        registerPriorityList(expertSkillPriorities, ConfigurationKeyEnum.EXPERT_SKILL_TRAINING_PRIORITIES_STRING, ExpertSkillItemEnum.class);
    }

    private List<ExpertSwitch> expertSwitches() {
        return List.of(
            new ExpertSwitch(claimIntelCheckBox, ConfigurationKeyEnum.EXPERT_AGNES_INTEL_BOOL),
            new ExpertSwitch(claimLoyaltyTagCheckBox, ConfigurationKeyEnum.EXPERT_ROMULUS_TAG_BOOL),
            new ExpertSwitch(claimTroopsCheckBox, ConfigurationKeyEnum.EXPERT_ROMULUS_TROOPS_BOOL),
            new ExpertSwitch(enableExpertSkillTrainingCheckBox, ConfigurationKeyEnum.EXPERT_SKILL_TRAINING_ENABLED_BOOL)
        );
    }

    private void wireDynamicSections() {
        setSkillTrainingVisible(enableExpertSkillTrainingCheckBox.isSelected());
        enableExpertSkillTrainingCheckBox.selectedProperty().addListener(
            (observable, oldValue, enabled) -> setSkillTrainingVisible(enabled)
        );
    }

    private void setSkillTrainingVisible(boolean visible) {
        expertSkillTrainingVBox.setVisible(visible);
        expertSkillTrainingVBox.setManaged(visible);
    }

    private record ExpertSwitch(CheckBox control, ConfigurationKeyEnum configKey) {
    }

    private enum TroopChoice {
        INFANTRY("Infantry"),
        LANCER("Lancer"),
        MARKSMAN("Marksman");

        private final String label;

        TroopChoice(String label) {
            this.label = label;
        }

        static List<String> labels() {
            return List.of(INFANTRY.label, LANCER.label, MARKSMAN.label);
        }
    }
}
