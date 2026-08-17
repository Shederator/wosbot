package dev.frostguard.engine.schedule.preempt;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.RawImageData;

/**
 * Test-only preemption rule that triggers resource gathering when the
 * farm-meat shortcut icon is detected on screen.  Only activates when
 * explicitly enabled via the profile configuration flag.
 */
public class GatherDeployPreemptionRule implements PreemptionRule {

    @Override
    public boolean shouldPreempt(EmulatorController controller,
                                 AccountDescriptor profile,
                                 RawImageData screenshot) {
        Boolean enabled = profile.getConfig(
                ConfigurationKeyEnum.TEST_GATHER_DEPLOY_PREEMPTION_BOOL, Boolean.class);
        if (!Boolean.TRUE.equals(enabled)) return false;

        try {
            ImageSearchResultData match = controller.locatePattern(
                    profile.getEmulatorNumber(), screenshot,
                    TemplatesEnum.GAME_HOME_SHORTCUTS_FARM_MEAT, 90);
            return match.isFound();
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public TpDailyTaskEnum getTaskToExecute() { return TpDailyTaskEnum.GATHER_RESOURCES; }

    @Override
    public String getRuleName() { return "TestGatherDeployPreemption"; }
}
