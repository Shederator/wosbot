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

public class FishingLayoutController extends AbstractProfileController {

    @FXML
    private CheckBox checkBoxEnableFishing;

    @FXML
    private TextField textFieldMaxCasts;
    @FXML private TextField textFieldTargetCatches;
    @FXML private TextField textFieldMaxRestarts;
    @FXML private CheckBox checkBoxRestartShortHaul;
    @FXML private CheckBox checkBoxDarkCorner;
    @FXML private TextField textFieldMaxSpecialCasts;
    @FXML private CheckBox checkBoxUseLantern;
    @FXML private CheckBox checkBoxUseStabilizer;
    @FXML private CheckBox checkBoxRequireSpecialItems;

    @FXML
    private CheckBox checkBoxEnableTestHookLoop;

    private ProfileAux currentProfile;

    @FXML
    private void initialize() {
        checkBoxMappings.put(checkBoxEnableFishing, ConfigurationKeyEnum.FISHING_MINIGAME_ENABLED_BOOL);
        registerTextField(textFieldMaxCasts, ConfigurationKeyEnum.FISHING_MAX_CASTS_INT);
        registerTextField(textFieldTargetCatches, ConfigurationKeyEnum.FISHING_TARGET_CATCHES_INT);
        registerTextField(textFieldMaxRestarts, ConfigurationKeyEnum.FISHING_MAX_RESTARTS_INT);
        checkBoxMappings.put(checkBoxRestartShortHaul, ConfigurationKeyEnum.FISHING_SHORT_HAUL_RESTART_ENABLED_BOOL);
        checkBoxMappings.put(checkBoxDarkCorner, ConfigurationKeyEnum.FISHING_DARK_CORNER_ENABLED_BOOL);
        registerTextField(textFieldMaxSpecialCasts, ConfigurationKeyEnum.FISHING_MAX_SPECIAL_CASTS_INT);
        checkBoxMappings.put(checkBoxUseLantern, ConfigurationKeyEnum.FISHING_USE_LANTERN_BOOL);
        checkBoxMappings.put(checkBoxUseStabilizer, ConfigurationKeyEnum.FISHING_USE_STABILIZER_BOOL);
        checkBoxMappings.put(checkBoxRequireSpecialItems, ConfigurationKeyEnum.FISHING_REQUIRE_SPECIAL_ITEMS_BOOL);
        checkBoxMappings.put(checkBoxEnableTestHookLoop, ConfigurationKeyEnum.TEST_HOOK_LOOP_ENABLED_BOOL);
        initializeChangeEvents();

        // Immediately start/stop the loop when the checkbox is toggled
        checkBoxEnableTestHookLoop.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (!isLoadingProfile && currentProfile != null) {
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
