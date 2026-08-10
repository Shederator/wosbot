package dev.frostguard.app.panel.economy;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;

import java.util.List;

public class ShopLayoutController extends AbstractProfileController {

	@FXML
	private CheckBox checkBoxNomadicMerchant, checkBoxNomadicMerchantVip,
			checkBoxBank, checkBoxMysteryShop, checkBoxMysteryShop50DiscountGear;

	@FXML
	private ComboBox<Integer> comboBoxBankDelay;

	@FXML
	private Label labelPeriod;

	@FXML
	private void initialize() {
		shopSwitches().forEach(binding -> checkBoxMappings.put(binding.control(), binding.configKey()));
		comboBoxBankDelay.getItems().setAll(1, 7, 15, 30);
		comboBoxMappings.put(comboBoxBankDelay, ConfigurationKeyEnum.INT_BANK_DELAY);
		initializeChangeEvents();
	}

	private List<ShopSwitch> shopSwitches() {
		return List.of(
			new ShopSwitch(checkBoxNomadicMerchant, ConfigurationKeyEnum.BOOL_NOMADIC_MERCHANT),
			new ShopSwitch(checkBoxNomadicMerchantVip, ConfigurationKeyEnum.BOOL_NOMADIC_MERCHANT_VIP_POINTS),
			new ShopSwitch(checkBoxBank, ConfigurationKeyEnum.BOOL_BANK),
			new ShopSwitch(checkBoxMysteryShop, ConfigurationKeyEnum.BOOL_MYSTERY_SHOP),
			new ShopSwitch(checkBoxMysteryShop50DiscountGear, ConfigurationKeyEnum.BOOL_MYSTERY_SHOP_250_HERO_WIDGET)
		);
	}

	private record ShopSwitch(CheckBox control, ConfigurationKeyEnum configKey) {
	}
}
