package dev.frostguard.app.panel.misc;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.engine.schedule.TaskQueue;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;

public class FishingLayoutController extends AbstractProfileController {

    @FXML
    private CheckBox checkBoxEnableFishing;

    @FXML
    private CheckBox checkBoxEnableTestHookLoop;

    private ProfileAux currentProfile;

    @FXML
    private void initialize() {
        checkBoxMappings.put(checkBoxEnableFishing, ConfigurationKeyEnum.FISHING_MINIGAME_ENABLED_BOOL);
        checkBoxMappings.put(checkBoxEnableTestHookLoop, ConfigurationKeyEnum.TEST_HOOK_LOOP_ENABLED_BOOL);
        initializeChangeEvents();

        // Immediately start/stop the loop when the checkbox is toggled
        checkBoxEnableTestHookLoop.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (currentProfile != null) {
                handleTestHookLoopToggle(newVal);
            }
        });
    }

    @Override
    public void onProfileLoad(ProfileAux profile) {
        super.onProfileLoad(profile);
        this.currentProfile = profile;
    }

    private void handleTestHookLoopToggle(boolean enabled) {
        if (currentProfile == null) return;

        TaskQueue queue = ScheduleService.obtain().getCoordinator().getQueue(currentProfile.getId());
        if (queue == null) return;

        if (enabled) {
            queue.runNow(TpDailyTaskEnum.TEST_HOOK_LOOP, true);
        } else {
            queue.dequeue(TpDailyTaskEnum.TEST_HOOK_LOOP);
            // TestHookLoopTask checks the config flag each iteration and will exit naturally
        }
    }
}
