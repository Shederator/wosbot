package dev.frostguard.app.panel.training;

import java.util.Map;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.ResearchCategoryEnum;
import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.app.shared.PriorityListView;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;

public class ResearchLayoutController extends AbstractProfileController {

	@FXML
	private CheckBox checkBoxEnableResearch;

	@FXML
	private PriorityListView researchPriorities;

	@FXML
	private void initialize() {
		registerResearchControls();
		initializeChangeEvents();
	}

	private void registerResearchControls() {
		Map.of(
				checkBoxEnableResearch, ConfigurationKeyEnum.RESEARCH_ENABLED_BOOL)
				.forEach(this::registerCheckBox);
		registerPriorityList(researchPriorities, ConfigurationKeyEnum.RESEARCH_PRIORITIES_STRING, ResearchCategoryEnum.class);
	}
}
