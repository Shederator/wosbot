package dev.frostguard.app.panel.economy;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;

import java.util.List;

// "General Shop" -- split out of the old combined "Shop" page. Everything here is
// NOT the top-right cart-icon panel (that split off into GemShopLayoutController) -- Nomadic
// Merchant and Mystery Shop, to keep the two genuinely different shops from being lumped together.
public class ShopLayoutController extends AbstractProfileController {

	@FXML
	private CheckBox checkBoxNomadicMerchant, checkBoxNomadicMerchantVip,
			checkBoxMysteryShop, checkBoxMysteryShop50DiscountGear;

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
			new ShopSwitch(checkBoxMysteryShop50DiscountGear, ConfigurationKeyEnum.BOOL_MYSTERY_SHOP_250_HERO_WIDGET)
		);
	}

	private record ShopSwitch(CheckBox control, ConfigurationKeyEnum configKey) {
	}
}
