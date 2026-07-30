package dev.frostguard.app.panel.alliance;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.app.shared.AbstractProfileController;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;

import java.util.List;

public class AllianceChampionshipLayoutController extends AbstractProfileController {

    private static final List<String> LANES = List.of("LEFT", "CENTER", "RIGHT");
    private static final List<String> FLAGS = List.of("No Flag", "1", "2", "3", "4", "5", "6", "7", "8");

    @FXML
    private CheckBox checkBoxEnableChampionship, checkBoxOverrideCurrentDeploy;

    @FXML
    private TextField textfieldInfantryPercentage, textfieldLancersPercentage, textfieldMarksmansPercentage;

    @FXML
    private ComboBox<String> comboBoxPosition, comboBoxFlag;

    @FXML
    private void initialize() {
        championshipSwitches().forEach(switchBinding -> checkBoxMappings.put(switchBinding.control(), switchBinding.configKey()));
        troopMixFields().forEach(fieldBinding -> textFieldMappings.put(fieldBinding.control(), fieldBinding.configKey()));
        comboBoxMappings.put(comboBoxPosition, ConfigurationKeyEnum.ALLIANCE_CHAMPIONSHIP_POSITION_STRING);
        comboBoxMappings.put(comboBoxFlag, ConfigurationKeyEnum.ALLIANCE_CHAMPIONSHIP_FLAG_STRING);

        comboBoxPosition.getItems().setAll(LANES);
        comboBoxPosition.setVisibleRowCount(LANES.size());
        comboBoxFlag.getItems().setAll(FLAGS);
        comboBoxFlag.setVisibleRowCount(FLAGS.size());
        keepSettingsLockedUntilEnabled();
        initializeChangeEvents();
    }

    private List<SwitchBinding> championshipSwitches() {
        return List.of(
            new SwitchBinding(checkBoxEnableChampionship, ConfigurationKeyEnum.ALLIANCE_CHAMPIONSHIP_BOOL),
            new SwitchBinding(checkBoxOverrideCurrentDeploy, ConfigurationKeyEnum.ALLIANCE_CHAMPIONSHIP_OVERRIDE_DEPLOY_BOOL)
        );
    }

    private List<FieldBinding> troopMixFields() {
        return List.of(
            new FieldBinding(textfieldInfantryPercentage, ConfigurationKeyEnum.ALLIANCE_CHAMPIONSHIP_INFANTRY_PERCENTAGE_INT),
            new FieldBinding(textfieldLancersPercentage, ConfigurationKeyEnum.ALLIANCE_CHAMPIONSHIP_LANCERS_PERCENTAGE_INT),
            new FieldBinding(textfieldMarksmansPercentage, ConfigurationKeyEnum.ALLIANCE_CHAMPIONSHIP_MARKSMANS_PERCENTAGE_INT)
        );
    }

    private void keepSettingsLockedUntilEnabled() {
        BooleanBinding championshipOff = checkBoxEnableChampionship.selectedProperty().not();
        BooleanBinding flagSelected = Bindings.createBooleanBinding(
            () -> !"No Flag".equals(comboBoxFlag.getValue()),
            comboBoxFlag.valueProperty()
        );
        championshipSwitches().stream()
            .map(SwitchBinding::control)
            .filter(control -> control != checkBoxEnableChampionship)
            .forEach(control -> control.disableProperty().bind(championshipOff));
        troopMixFields().forEach(field -> field.control().disableProperty().bind(championshipOff.or(flagSelected)));
        comboBoxPosition.disableProperty().bind(championshipOff);
        comboBoxFlag.disableProperty().bind(championshipOff);
    }

    private record SwitchBinding(CheckBox control, ConfigurationKeyEnum configKey) {
    }

    private record FieldBinding(TextField control, ConfigurationKeyEnum configKey) {
    }
}
