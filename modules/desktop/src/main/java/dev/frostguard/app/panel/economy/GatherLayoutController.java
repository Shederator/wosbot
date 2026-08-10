package dev.frostguard.app.panel.economy;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.GridPane;

import java.util.List;
import java.util.stream.IntStream;

public class GatherLayoutController extends AbstractProfileController {

	private static final List<Integer> RESOURCE_LEVELS = IntStream.rangeClosed(1, 8).boxed().toList();
	private static final List<Integer> MARCH_QUEUE_SIZES = IntStream.rangeClosed(1, 6).boxed().toList();
	private static final List<String> SPEED_BOOST_OPTIONS = List.of("8h (250 gems)", "24h (600 gems)");
	private static final String DEFAULT_SPEED_BOOST = "24h (600 gems)";

	@FXML
	private CheckBox checkBoxGatherResources, checkBoxGatherCoal, checkBoxGatherIron,
			checkBoxGatherMeat, checkBoxGatherWood,
			checkBoxGatherSpeedBoost, checkBoxOnlyFullResources, checkBoxDowngradeLevel,
			checkBoxRemoveHeros, checkBoxTestPreemption;
	@FXML
	private ComboBox<Integer> comboBoxActiveMarchQueue, comboBoxLevelCoal,
			comboBoxLevelIron, comboBoxLevelMeat,
			comboBoxLevelWood;
	@FXML
	private ComboBox<String> comboBoxGatherSpeedBoostType;
	@FXML
	private GridPane gridPaneGathering;

	@FXML
	private void initialize() {
		gatherSwitches().forEach(binding -> checkBoxMappings.put(binding.control(), binding.configKey()));
		resourceLevelSelectors().forEach(this::configureLevelSelector);
		configureSpeedBoostSelector();
		comboBoxActiveMarchQueue.getItems().setAll(MARCH_QUEUE_SIZES);
		comboBoxMappings.put(comboBoxActiveMarchQueue, ConfigurationKeyEnum.GATHER_ACTIVE_MARCH_QUEUE_INT);
		wireConditionalSections();
		initializeChangeEvents();
	}

	private List<GatherSwitch> gatherSwitches() {
		return List.of(
			new GatherSwitch(checkBoxGatherResources, ConfigurationKeyEnum.GATHER_TASK_BOOL),
			new GatherSwitch(checkBoxGatherCoal, ConfigurationKeyEnum.GATHER_COAL_BOOL),
			new GatherSwitch(checkBoxGatherIron, ConfigurationKeyEnum.GATHER_IRON_BOOL),
			new GatherSwitch(checkBoxGatherMeat, ConfigurationKeyEnum.GATHER_MEAT_BOOL),
			new GatherSwitch(checkBoxGatherWood, ConfigurationKeyEnum.GATHER_WOOD_BOOL),
			new GatherSwitch(checkBoxGatherSpeedBoost, ConfigurationKeyEnum.GATHER_SPEED_BOOL),
			new GatherSwitch(checkBoxOnlyFullResources, ConfigurationKeyEnum.GATHER_ONLY_FULL_RESOURCES_BOOL),
			new GatherSwitch(checkBoxDowngradeLevel, ConfigurationKeyEnum.GATHER_DOWNGRADE_LEVEL_BOOL),
			new GatherSwitch(checkBoxRemoveHeros, ConfigurationKeyEnum.GATHER_REMOVE_HEROS_BOOL),
			new GatherSwitch(checkBoxTestPreemption, ConfigurationKeyEnum.TEST_GATHER_DEPLOY_PREEMPTION_BOOL)
		);
	}

	private List<LevelSelector> resourceLevelSelectors() {
		return List.of(
			new LevelSelector(comboBoxLevelCoal, ConfigurationKeyEnum.GATHER_COAL_LEVEL_INT),
			new LevelSelector(comboBoxLevelIron, ConfigurationKeyEnum.GATHER_IRON_LEVEL_INT),
			new LevelSelector(comboBoxLevelMeat, ConfigurationKeyEnum.GATHER_MEAT_LEVEL_INT),
			new LevelSelector(comboBoxLevelWood, ConfigurationKeyEnum.GATHER_WOOD_LEVEL_INT)
		);
	}

	private void configureLevelSelector(LevelSelector selector) {
		selector.control().getItems().setAll(RESOURCE_LEVELS);
		comboBoxMappings.put(selector.control(), selector.configKey());
	}

	private void configureSpeedBoostSelector() {
		comboBoxGatherSpeedBoostType.getItems().setAll(SPEED_BOOST_OPTIONS);
		comboBoxGatherSpeedBoostType.setValue(DEFAULT_SPEED_BOOST);
		comboBoxMappings.put(comboBoxGatherSpeedBoostType, ConfigurationKeyEnum.GATHER_SPEED_BOOST_TYPE_STRING);
	}

	private void wireConditionalSections() {
		setSpeedBoostChoiceVisible(checkBoxGatherSpeedBoost.isSelected());
		checkBoxGatherSpeedBoost.selectedProperty().addListener((obs, oldVal, enabled) -> setSpeedBoostChoiceVisible(enabled));
		gridPaneGathering.setDisable(!checkBoxGatherResources.isSelected());
		checkBoxGatherResources.selectedProperty().addListener((obs, oldVal, enabled) -> gridPaneGathering.setDisable(!enabled));
	}

	private void setSpeedBoostChoiceVisible(boolean visible) {
		comboBoxGatherSpeedBoostType.setVisible(visible);
		comboBoxGatherSpeedBoostType.setManaged(visible);
	}

	private record GatherSwitch(CheckBox control, ConfigurationKeyEnum configKey) {
	}

	private record LevelSelector(ComboBox<Integer> control, ConfigurationKeyEnum configKey) {
	}
}
