package dev.frostguard.app.panel.city;

import java.util.Map;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.app.shared.AbstractProfileController;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;

public class CityUpgradesLayoutController extends AbstractProfileController {

	@FXML
	private CheckBox checkBoxUpgradeFurnace;

	@FXML
	private CheckBox checkBoxReserveProduction;

	@FXML
	private CheckBox checkBoxPrioritiseFurnace;

	@FXML
	private CheckBox checkboxAcceptNewSurvivors;

	@FXML
	private TextField textFieldSirvivorsOffset;

	@FXML
	private void initialize() {
		registerCityUpgradeControls();
		initializeChangeEvents();
	}

	private void registerCityUpgradeControls() {
		Map.of(
				checkBoxUpgradeFurnace, ConfigurationKeyEnum.CITY_UPGRADE_FURNACE_BOOL,
				checkBoxReserveProduction, ConfigurationKeyEnum.CITY_UPGRADE_RESERVE_PRODUCTION_BOOL,
				checkBoxPrioritiseFurnace, ConfigurationKeyEnum.CITY_UPGRADE_PRIORITISE_FURNACE_BOOL,
				checkboxAcceptNewSurvivors, ConfigurationKeyEnum.CITY_ACCEPT_NEW_SURVIVORS_BOOL)
				.forEach(this::registerCheckBox);
		Map.of(textFieldSirvivorsOffset, ConfigurationKeyEnum.CITY_ACCEPT_NEW_SURVIVORS_OFFSET_INT)
				.forEach(this::registerTextField);
	}
}
