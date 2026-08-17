package dev.frostguard.app.panel.combat;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;

public class BeastHuntingLayoutController extends AbstractProfileController {

    @FXML
    private CheckBox checkBoxEnableBeastHunting;

    @FXML
    private ComboBox<Integer> comboBoxBeastHuntingMarches;

    @FXML
    private ComboBox<Integer> comboBoxBeastHuntingLevel;

    @FXML
    private TextField textFieldBeastStaminaReserve;

    @FXML
    private void initialize() {
        // Map checkbox
        checkBoxMappings.put(checkBoxEnableBeastHunting, ConfigurationKeyEnum.BEAST_HUNTING_ENABLED_BOOL);

        // Initialize marches ComboBox (1–6)
        comboBoxBeastHuntingMarches.getItems().addAll(1, 2, 3, 4, 5, 6);
        comboBoxMappings.put(comboBoxBeastHuntingMarches, ConfigurationKeyEnum.BEAST_HUNTING_MARCHES_INT);

        // Initialize level ComboBox (1–30)
        for (int i = 1; i <= 30; i++) {
            comboBoxBeastHuntingLevel.getItems().add(i);
        }
        comboBoxMappings.put(comboBoxBeastHuntingLevel, ConfigurationKeyEnum.BEAST_HUNTING_LEVEL_INT);

        // Shared stamina reserve kept back for Intel/Rally
        registerTextField(textFieldBeastStaminaReserve, ConfigurationKeyEnum.STAMINA_RESERVE_INT);

        // Disable controls when checkbox is not selected
        comboBoxBeastHuntingMarches.disableProperty().bind(checkBoxEnableBeastHunting.selectedProperty().not());
        comboBoxBeastHuntingLevel.disableProperty().bind(checkBoxEnableBeastHunting.selectedProperty().not());
        textFieldBeastStaminaReserve.disableProperty().bind(checkBoxEnableBeastHunting.selectedProperty().not());

        // Initialize change events (inherited from AbstractProfileController)
        initializeChangeEvents();
    }
}
