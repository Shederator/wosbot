package dev.frostguard.app.panel.economy;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;

import java.util.List;

// "Gem Shop" -- the top-right cart-icon panel, split out of the old combined
// "Shop" page so it's never confused with the bottom-nav "General Shop" (a completely different
// feature). Custom Armament Chest + Daily Deals are both confirmed live to live behind that same
// cart icon.
public class GemShopLayoutController extends AbstractProfileController {

	@FXML
	private CheckBox checkBoxCustomArmamentChest, checkBoxDailyDealsFreeChest;

	@FXML
	private void initialize() {
		gemShopSwitches().forEach(binding -> checkBoxMappings.put(binding.control(), binding.configKey()));
		initializeChangeEvents();
	}

	private List<GemShopSwitch> gemShopSwitches() {
		return List.of(
			new GemShopSwitch(checkBoxCustomArmamentChest, ConfigurationKeyEnum.SHOP_CUSTOM_ARMAMENT_CHEST_CLAIM_BOOL),
			new GemShopSwitch(checkBoxDailyDealsFreeChest, ConfigurationKeyEnum.SHOP_DAILY_DEALS_FREE_CHEST_CLAIM_BOOL)
		);
	}

	private record GemShopSwitch(CheckBox control, ConfigurationKeyEnum configKey) {
	}
}
