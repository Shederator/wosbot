package dev.frostguard.app.panel.economy;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;

import java.util.List;

public class ShopLayoutController extends AbstractProfileController {

	@FXML
	private CheckBox checkBoxNomadicMerchant, checkBoxNomadicMerchantVip,
			checkBoxMysteryShop, checkBoxMysteryShop50DiscountGear,
			checkBoxCustomArmamentChest, checkBoxDailyDealsFreeChest;

	@FXML
	private void initialize() {
		shopSwitches().forEach(binding -> checkBoxMappings.put(binding.control(), binding.configKey()));
		initializeChangeEvents();
	}

	private List<ShopSwitch> shopSwitches() {
		return List.of(
			new ShopSwitch(checkBoxNomadicMerchant, ConfigurationKeyEnum.BOOL_NOMADIC_MERCHANT),
			new ShopSwitch(checkBoxNomadicMerchantVip, ConfigurationKeyEnum.BOOL_NOMADIC_MERCHANT_VIP_POINTS),
			new ShopSwitch(checkBoxMysteryShop, ConfigurationKeyEnum.BOOL_MYSTERY_SHOP),
			new ShopSwitch(checkBoxMysteryShop50DiscountGear, ConfigurationKeyEnum.BOOL_MYSTERY_SHOP_250_HERO_WIDGET),
			new ShopSwitch(checkBoxCustomArmamentChest, ConfigurationKeyEnum.SHOP_CUSTOM_ARMAMENT_CHEST_CLAIM_BOOL),
			new ShopSwitch(checkBoxDailyDealsFreeChest, ConfigurationKeyEnum.SHOP_DAILY_DEALS_FREE_CHEST_CLAIM_BOOL)
		);
	}

	private record ShopSwitch(CheckBox control, ConfigurationKeyEnum configKey) {
	}
}
