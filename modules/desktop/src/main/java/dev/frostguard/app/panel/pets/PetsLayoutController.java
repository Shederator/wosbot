package dev.frostguard.app.panel.pets;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;

import java.util.List;

public class PetsLayoutController extends AbstractProfileController {
	@FXML
	private CheckBox checkBoxPetSkills, checkBoxPetAllianceTreasure, checkBoxPetPersonalTreasure, checkboxFoodSkill,
			checkboxGatheringSkill, checkboxStaminaSkill, checkboxTreasureSkill;

	@FXML
	private ComboBox<String> comboBoxGatheringResource;

	@FXML
	private void initialize() {
		petSwitches().forEach(binding -> checkBoxMappings.put(binding.control(), binding.configKey()));
		comboBoxGatheringResource.getItems().setAll(GatherResource.labels());
		comboBoxMappings.put(comboBoxGatheringResource, ConfigurationKeyEnum.PET_SKILL_GATHERING_RESOURCE_STRING);
		initializeChangeEvents();
	}

	private List<PetSwitch> petSwitches() {
		return List.of(
			new PetSwitch(checkBoxPetSkills, ConfigurationKeyEnum.PET_SKILLS_BOOL),
			new PetSwitch(checkBoxPetAllianceTreasure, ConfigurationKeyEnum.ALLIANCE_PET_TREASURE_BOOL),
			new PetSwitch(checkBoxPetPersonalTreasure, ConfigurationKeyEnum.PET_PERSONAL_TREASURE_BOOL),
			new PetSwitch(checkboxFoodSkill, ConfigurationKeyEnum.PET_SKILL_FOOD_BOOL),
			new PetSwitch(checkboxGatheringSkill, ConfigurationKeyEnum.PET_SKILL_GATHERING_BOOL),
			new PetSwitch(checkboxStaminaSkill, ConfigurationKeyEnum.PET_SKILL_STAMINA_BOOL),
			new PetSwitch(checkboxTreasureSkill, ConfigurationKeyEnum.PET_SKILL_TREASURE_BOOL)
		);
	}

	private record PetSwitch(CheckBox control, ConfigurationKeyEnum configKey) {
	}

	private enum GatherResource {
		MEAT,
		WOOD,
		COAL,
		IRON;

		static List<String> labels() {
			return List.of(MEAT.name(), WOOD.name(), COAL.name(), IRON.name());
		}
	}
}
