package dev.frostguard.app.panel.misc;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.engine.schedule.TaskQueue;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;

public class SkipTutorialLayoutController extends AbstractProfileController {

    @FXML
    private CheckBox checkBoxEnableSkipTutorial;

    private ProfileAux currentProfile;

    @FXML
    private void initialize() {
        // Map the controls to their respective configuration keys
        checkBoxMappings.put(checkBoxEnableSkipTutorial, ConfigurationKeyEnum.SKIP_TUTORIAL_ENABLED_BOOL);

        // Initialize change events (inherited from AbstractProfileController)
        initializeChangeEvents();

        // Add additional listener for dynamic control
        checkBoxEnableSkipTutorial.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (currentProfile != null) {
                handleDynamicToggle(newVal);
            }
        });
    }

    @Override
    public void onProfileLoad(ProfileAux profile) {
        super.onProfileLoad(profile);
        this.currentProfile = profile;
    }

    private void handleDynamicToggle(boolean enabled) {
        if (currentProfile == null) {
            return;
        }

        TaskQueue queue = ScheduleService.obtain().getCoordinator().getQueue(currentProfile.getId());
        if (queue == null) {
            return;
        }

        if (enabled) {
            queue.runNow(TpDailyTaskEnum.SKIP_TUTORIAL, true);
        } else {
            queue.dequeue(TpDailyTaskEnum.SKIP_TUTORIAL);
        }
    }
}
