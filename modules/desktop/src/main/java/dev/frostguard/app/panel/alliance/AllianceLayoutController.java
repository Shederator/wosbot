package dev.frostguard.app.panel.alliance;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.app.shared.AbstractProfileController;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;

import java.util.List;
import java.util.Objects;

public class AllianceLayoutController extends AbstractProfileController {

    private static final List<Integer> QUEUE_COUNTS = List.of(1, 2, 3, 4, 5, 6);

    @FXML
    private CheckBox checkBoxAutojoin, checkBoxChests,
        checkBoxTechContribution, checkBoxHelpRequests,
        checkBoxTriumph, checkBoxAlliesEssence,
        checkBoxHonorChest;

    @FXML
    private TextField textfieldAutojoinQueues, textfieldChestOffset,
        textfieldTechOffset, textfieldTriumphOffset, textfieldAlliesEssenceOffsett;

    @FXML
    private ComboBox<Integer> comboBoxAutojoinQueues;

    @FXML
    private HBox hboxAutojoinQueues;

    @FXML
    private RadioButton radioAllTroops, radioUseFormation;

    @FXML
    private void initialize() {
        taskToggles().forEach(binding -> checkBoxMappings.put(binding.control(), binding.configKey()));
        scheduledOffsets().forEach(binding -> textFieldMappings.put(binding.control(), binding.configKey()));
        prepareAutoJoinPanel();
        initializeChangeEvents();
    }

    private List<ToggleBinding> taskToggles() {
        return List.of(
            new ToggleBinding(checkBoxAutojoin, ConfigurationKeyEnum.ALLIANCE_AUTOJOIN_BOOL),
            new ToggleBinding(checkBoxTechContribution, ConfigurationKeyEnum.ALLIANCE_TECH_BOOL),
            new ToggleBinding(checkBoxChests, ConfigurationKeyEnum.ALLIANCE_CHESTS_BOOL),
            new ToggleBinding(checkBoxHonorChest, ConfigurationKeyEnum.ALLIANCE_HONOR_CHEST_BOOL),
            new ToggleBinding(checkBoxHelpRequests, ConfigurationKeyEnum.ALLIANCE_HELP_BOOL),
            new ToggleBinding(checkBoxTriumph, ConfigurationKeyEnum.ALLIANCE_TRIUMPH_BOOL),
            new ToggleBinding(checkBoxAlliesEssence, ConfigurationKeyEnum.ALLIANCE_LIFE_ESSENCE_BOOL)
        );
    }

    private List<TextBinding> scheduledOffsets() {
        return List.of(
            new TextBinding(textfieldTechOffset, ConfigurationKeyEnum.ALLIANCE_TECH_OFFSET_INT),
            new TextBinding(textfieldChestOffset, ConfigurationKeyEnum.ALLIANCE_CHESTS_OFFSET_INT),
            new TextBinding(textfieldTriumphOffset, ConfigurationKeyEnum.ALLIANCE_TRIUMPH_OFFSET_INT),
            new TextBinding(textfieldAlliesEssenceOffsett, ConfigurationKeyEnum.ALLIANCE_LIFE_ESSENCE_OFFSET_INT)
        );
    }

    private void prepareAutoJoinPanel() {
        comboBoxAutojoinQueues.getItems().setAll(QUEUE_COUNTS);
        comboBoxMappings.put(comboBoxAutojoinQueues, ConfigurationKeyEnum.ALLIANCE_AUTOJOIN_QUEUES_INT);

        radioButtonMappings.put(radioAllTroops, ConfigurationKeyEnum.ALLIANCE_AUTOJOIN_USE_ALL_TROOPS_BOOL);
        radioButtonMappings.put(radioUseFormation, ConfigurationKeyEnum.ALLIANCE_AUTOJOIN_USE_PREDEFINED_FORMATION_BOOL);
        createToggleGroup(radioAllTroops, radioUseFormation);
        radioAllTroops.setSelected(true);

        disableWithAutoJoin(comboBoxAutojoinQueues);
        disableWithAutoJoin(hboxAutojoinQueues);
        disableWithAutoJoin(textfieldAutojoinQueues);
        showWithAutoJoin(radioAllTroops);
        showWithAutoJoin(radioUseFormation);
    }

    private void disableWithAutoJoin(Node node) {
        if (node != null) {
            node.disableProperty().bind(checkBoxAutojoin.selectedProperty().not());
        }
    }

    private void showWithAutoJoin(Node node) {
        Objects.requireNonNull(node, "node");
        node.visibleProperty().bind(checkBoxAutojoin.selectedProperty());
        node.managedProperty().bind(node.visibleProperty());
    }

    private record ToggleBinding(CheckBox control, ConfigurationKeyEnum configKey) {
    }

    private record TextBinding(TextField control, ConfigurationKeyEnum configKey) {
    }
}
