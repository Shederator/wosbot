package dev.frostguard.engine.schedule.inject;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.schedule.DelayedTask;

/**
 * Taps the Alliance Help shortcut button when it appears on the main
 * screen.  Runs cooperatively during a task's idle sleep window and
 * respects the per-profile enable flag.
 */
public class HelpAllianceInjectionRule implements InjectionRule {

    @Override
    public boolean shouldInject(EmulatorController controller,
                                AccountDescriptor profile,
                                RawImageData frame) {
        if (!profile.getConfig(ConfigurationKeyEnum.ALLIANCE_HELP_BOOL, Boolean.class)) {
            return false;
        }
        try {
            return controller.locatePattern(
                    profile.getEmulatorNumber(), frame,
                    TemplatesEnum.GAME_HOME_SHORTCUTS_HELP_REQUEST2, 90).isFound();
        } catch (Exception ignored) { return false; }
    }

    @Override
    public void executeInjection(EmulatorController controller,
                                 AccountDescriptor profile,
                                 DelayedTask activeTask) {
        activeTask.logDebug("Attempting Alliance Help tap");
        try {
            ImageSearchResultData result = controller.locatePattern(
                    profile.getEmulatorNumber(),
                    TemplatesEnum.GAME_HOME_SHORTCUTS_HELP_REQUEST2, 90);
            if (result.isFound()) {
                TapInteractionService.forController(controller, profile.getEmulatorNumber()).tapInside(result);
                Thread.sleep(1000);
            }
        } catch (Exception ex) {
            activeTask.logError("Alliance Help tap encountered an error.", ex);
        }
    }

    @Override
    public String getRuleName() { return "HelpAllianceInjectionRule"; }
}
