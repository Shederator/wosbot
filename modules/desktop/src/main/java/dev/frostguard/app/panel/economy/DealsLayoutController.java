package dev.frostguard.app.panel.economy;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;

import java.util.List;

/**
 * matt/2026-08-12: dedicated Deals-menu panel, built out one deal type at a time
 * ("let's go slow on this"). Bank is first (moved here from Shop, same config keys,
 * same BankRoutine underneath). Hero Rally and Monthly Card are next, each getting
 * its own card here once its live flow is confirmed against a real screenshot.
 */
public class DealsLayoutController extends AbstractProfileController {

	@FXML
	private CheckBox checkBoxBank, checkBoxEventHallOfChiefs, checkBoxEventDefeatBeasts,
			checkBoxEventHeroRally, checkBoxEventLuckyChipSupply, checkBoxEventBrothersInArms;

	@FXML
	private ComboBox<Integer> comboBoxBankDelay;

	@FXML
	private void initialize() {
		dealsSwitches().forEach(binding -> checkBoxMappings.put(binding.control(), binding.configKey()));
		comboBoxBankDelay.getItems().setAll(1, 7, 15, 30);
		comboBoxMappings.put(comboBoxBankDelay, ConfigurationKeyEnum.INT_BANK_DELAY);
		initializeChangeEvents();
	}

	private List<DealsSwitch> dealsSwitches() {
		return List.of(
			new DealsSwitch(checkBoxBank, ConfigurationKeyEnum.BOOL_BANK),
			new DealsSwitch(checkBoxEventHallOfChiefs, ConfigurationKeyEnum.EVENT_HALL_OF_CHIEFS_CLAIM_BOOL),
			new DealsSwitch(checkBoxEventDefeatBeasts, ConfigurationKeyEnum.EVENT_DEFEAT_BEASTS_CLAIM_BOOL),
			new DealsSwitch(checkBoxEventHeroRally, ConfigurationKeyEnum.EVENT_HERO_RALLY_CLAIM_BOOL),
			new DealsSwitch(checkBoxEventLuckyChipSupply, ConfigurationKeyEnum.EVENT_LUCKY_CHIP_SUPPLY_CLAIM_BOOL),
			new DealsSwitch(checkBoxEventBrothersInArms, ConfigurationKeyEnum.EVENT_BROTHERS_IN_ARMS_CLAIM_BOOL)
		);
	}

	private record DealsSwitch(CheckBox control, ConfigurationKeyEnum configKey) {
	}
}
