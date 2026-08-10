package dev.frostguard.app.panel.training;

import java.util.List;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.app.shared.AbstractProfileController;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;

public class TrainingLayoutController extends AbstractProfileController {

	@FXML
	private CheckBox checkBoxEnableTraining;

	@FXML
	private CheckBox checkBoxAppointMinister;

	@FXML
	private CheckBox checkBoxTrainPrioritizePromotion;

	@FXML
	private CheckBox checkBoxTrainInfantry;

	@FXML
	private CheckBox checkBoxTrainLancers;

	@FXML
	private CheckBox checkBoxTrainMarksman;

	private List<CheckBox> childCheckboxes;

	@FXML
	private void initialize() {
		registerTrainingSettings();
		initializeChangeEvents();
		wireMasterToggle();
	}

	private void registerTrainingSettings() {
		List.of(
				new CheckBoxBinding(checkBoxEnableTraining, ConfigurationKeyEnum.TRAIN_BOOL),
				new CheckBoxBinding(checkBoxTrainInfantry, ConfigurationKeyEnum.TRAIN_INFANTRY_BOOL),
				new CheckBoxBinding(checkBoxTrainLancers, ConfigurationKeyEnum.TRAIN_LANCER_BOOL),
				new CheckBoxBinding(checkBoxTrainMarksman, ConfigurationKeyEnum.TRAIN_MARKSMAN_BOOL),
				new CheckBoxBinding(checkBoxTrainPrioritizePromotion, ConfigurationKeyEnum.TRAIN_PRIORITIZE_PROMOTION_BOOL),
				new CheckBoxBinding(checkBoxAppointMinister, ConfigurationKeyEnum.TRAIN_MINISTRY_APPOINTMENT_BOOL))
				.forEach(binding -> registerCheckBox(binding.control(), binding.configKey()));
	}

	private void wireMasterToggle() {
		childCheckboxes = List.of(
				checkBoxTrainInfantry,
				checkBoxTrainLancers,
				checkBoxTrainMarksman,
				checkBoxAppointMinister,
				checkBoxTrainPrioritizePromotion);

		checkBoxEnableTraining.selectedProperty().addListener((observable, oldValue, enabled) -> setTrainingOptionsEnabled(enabled));
		setTrainingOptionsEnabled(checkBoxEnableTraining.isSelected());
	}

	private void setTrainingOptionsEnabled(boolean enabled) {
		childCheckboxes.forEach(option -> option.setDisable(!enabled));
	}

	private record CheckBoxBinding(CheckBox control, ConfigurationKeyEnum configKey) {
	}
}
