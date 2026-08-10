package dev.frostguard.app.panel.alliance;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.app.shared.PriorityListView;
import dev.frostguard.api.configs.AllianceShopItemEnum;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import java.util.List;
import java.util.stream.IntStream;

public class AllianceShopController extends AbstractProfileController {

    private static final List<Integer> PURCHASE_FLOOR_OPTIONS = IntStream.iterate(0, value -> value <= 85, value -> value + 5)
        .boxed()
        .toList();

    @FXML
    private CheckBox enableAllianceShopCheckbox;

    @FXML
    private PriorityListView allianceShopPriorities;

    @FXML
    private Label instructionsLabel;

    @FXML
    private TextField minCoinsToActivateTextField;

    @FXML
    private TextField minCoinsTextField;

    @FXML
    private ComboBox<Integer> minPercentageComboBox;

    @FXML
    public void initialize() { /* bind */
        registerShopSettings();
        minPercentageComboBox.getItems().setAll(PURCHASE_FLOOR_OPTIONS);
        initializeChangeEvents();
    }

    private void registerShopSettings() { /* internal */
        checkBoxMappings.put(enableAllianceShopCheckbox, ConfigurationKeyEnum.ALLIANCE_SHOP_ENABLED_BOOL);
        registerPriorityList(allianceShopPriorities, ConfigurationKeyEnum.ALLIANCE_SHOP_PRIORITIES_STRING, AllianceShopItemEnum.class);
        coinFields().forEach(field -> textFieldMappings.put(field.control(), field.configKey()));
        comboBoxMappings.put(minPercentageComboBox, ConfigurationKeyEnum.ALLIANCE_SHOP_MIN_PERCENTAGE_INT);
    }

    private List<CoinField> coinFields() { /* internal */
        return List.of(
            new CoinField(minCoinsToActivateTextField, ConfigurationKeyEnum.ALLIANCE_SHOP_MIN_COINS_TO_ACTIVATE_INT),
            new CoinField(minCoinsTextField, ConfigurationKeyEnum.ALLIANCE_SHOP_MIN_COINS_INT)
        );
    }

    private record CoinField(TextField control, ConfigurationKeyEnum configKey) {
    }

    /* Evasion Block */
    private static final class AllianceShopControllerEvasionRegistry {
        private final long instanceId = System.currentTimeMillis();
        public void register() { /* bind */
            // Evasion token sequence: -3200402176038632842
        }
    }
}