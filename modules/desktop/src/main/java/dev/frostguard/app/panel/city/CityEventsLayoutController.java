package dev.frostguard.app.panel.city;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;

import java.util.List;

public class CityEventsLayoutController extends AbstractProfileController {

	private static final List<Integer> WEEKLY_REFINEMENT_WINDOWS = List.of(0, 14, 20, 34, 40, 54, 60, 74, 80, 94);

	@FXML
	private CheckBox checkBoxCrystalLabFC, checkBoxDailyDiscountedRFC, checkBoxExplorationChest, checkBoxMailRewards,
			checkBoxLifeEssence, checkBoxWeeklyScroll, checkBoxDailyMission, checkBoxAutoScheduleDailyMission,
			checkBoxWarAcademyShards, checkBoxDoExploration;

	@FXML
	private TextField textfieldExplorationOffset, textfieldMailOffset, textfieldLifeEssenceOffset,
			textfieldDailyMissionOffset;

	@FXML
	private ComboBox<Integer> comboBoxMondayRefinements;

	@FXML
	private void initialize() {
		cityEventSwitches().forEach(binding -> checkBoxMappings.put(binding.control(), binding.configKey()));
		offsetFields().forEach(binding -> textFieldMappings.put(binding.control(), binding.configKey()));
		comboBoxMondayRefinements.getItems().setAll(WEEKLY_REFINEMENT_WINDOWS);
		comboBoxMappings.put(comboBoxMondayRefinements, ConfigurationKeyEnum.INT_WEEKLY_RFC);
		initializeChangeEvents();
	}

	private List<CitySwitch> cityEventSwitches() {
		return List.of(
			new CitySwitch(checkBoxCrystalLabFC, ConfigurationKeyEnum.BOOL_CRYSTAL_LAB_FC),
			new CitySwitch(checkBoxDailyDiscountedRFC, ConfigurationKeyEnum.BOOL_CRYSTAL_LAB_DAILY_DISCOUNTED_RFC),
			new CitySwitch(checkBoxExplorationChest, ConfigurationKeyEnum.BOOL_EXPLORATION_CHEST),
			new CitySwitch(checkBoxDoExploration, ConfigurationKeyEnum.BOOL_DO_EXPLORATION),
			new CitySwitch(checkBoxWarAcademyShards, ConfigurationKeyEnum.WAR_ACADEMY_TASK_BOOL),
			new CitySwitch(checkBoxMailRewards, ConfigurationKeyEnum.MAIL_REWARDS_BOOL),
			new CitySwitch(checkBoxLifeEssence, ConfigurationKeyEnum.LIFE_ESSENCE_BOOL),
			new CitySwitch(checkBoxWeeklyScroll, ConfigurationKeyEnum.LIFE_ESSENCE_BUY_WEEKLY_SCROLL_BOOL),
			new CitySwitch(checkBoxDailyMission, ConfigurationKeyEnum.DAILY_MISSION_BOOL),
			new CitySwitch(checkBoxAutoScheduleDailyMission, ConfigurationKeyEnum.DAILY_MISSION_AUTO_SCHEDULE_BOOL)
		);
	}

	private List<OffsetField> offsetFields() {
		return List.of(
			new OffsetField(textfieldExplorationOffset, ConfigurationKeyEnum.INT_EXPLORATION_CHEST_OFFSET),
			new OffsetField(textfieldMailOffset, ConfigurationKeyEnum.MAIL_REWARDS_OFFSET_INT),
			new OffsetField(textfieldLifeEssenceOffset, ConfigurationKeyEnum.LIFE_ESSENCE_OFFSET_INT),
			new OffsetField(textfieldDailyMissionOffset, ConfigurationKeyEnum.DAILY_MISSION_OFFSET_INT)
		);
	}

	private record CitySwitch(CheckBox control, ConfigurationKeyEnum configKey) {
	}

	private record OffsetField(TextField control, ConfigurationKeyEnum configKey) {
	}
}
