package dev.frostguard.app.panel.misc;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.engine.schedule.TaskQueue;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;

public class CharacterLayoutController extends AbstractProfileController {

    @FXML
    private CheckBox checkBoxEnableCreateCharacter;
    @FXML
    private CheckBox checkBoxSkipTutorial;
    @FXML
    private TextField textFieldMaxAgeMinutes;

    private ProfileAux currentProfile;

    @FXML
    private void initialize() {
        // Map the checkbox and textfield to the configuration keys
        checkBoxMappings.put(checkBoxEnableCreateCharacter, ConfigurationKeyEnum.CREATE_CHARACTER_ENABLED_BOOL);
        checkBoxMappings.put(checkBoxSkipTutorial, ConfigurationKeyEnum.CREATE_CHARACTER_SKIP_TUTORIAL_BOOL);
        textFieldMappings.put(textFieldMaxAgeMinutes, ConfigurationKeyEnum.CREATE_CHARACTER_MAX_AGE_MINUTES_INT);

        // Initialize change events (inherited from AbstractProfileController)
        initializeChangeEvents();

        // Add additional listener for dynamic control
        checkBoxEnableCreateCharacter.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (currentProfile != null) {
                handleDynamicToggle(newVal);
            }
        });

        // Add listener for Max Age changes to update the task immediately
        textFieldMaxAgeMinutes.textProperty().addListener((obs, oldVal, newVal) -> {
            if (currentProfile != null && checkBoxEnableCreateCharacter.isSelected()) {
                handleDynamicToggle(true);
            }
        });
    }

    @Override
    public void onProfileLoad(ProfileAux profile) {
        super.onProfileLoad(profile);
        this.currentProfile = profile;
    }

    private void handleDynamicToggle(boolean enabled) {
        if (currentProfile == null)
            return;

        TaskQueue queue = ScheduleService.obtain().getCoordinator().getQueue(currentProfile.getId());

        if (queue == null)
            return;

        if (enabled) {
            queue.runNow(TpDailyTaskEnum.CREATE_CHARACTER, true);
        } else {
            queue.dequeue(TpDailyTaskEnum.CREATE_CHARACTER);
        }
    }
}
