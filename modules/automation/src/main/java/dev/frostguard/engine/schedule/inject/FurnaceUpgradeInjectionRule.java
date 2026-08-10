package dev.frostguard.engine.schedule.inject;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.input.TapJitterPolicy;
import dev.frostguard.engine.schedule.DelayedTask;

/**
 * Detects and claims the Furnace Upgrade Pack shortcut.  When the pack
 * icon appears, the rule taps it, presses the claim button, confirms
 * via a fixed coordinate, and navigates back twice.
 */
public class FurnaceUpgradeInjectionRule implements InjectionRule {

    private static final PointData CONFIRM_COORD = new PointData(360, 858);

    @Override
    public boolean shouldInject(EmulatorController controller,
                                AccountDescriptor profile,
                                RawImageData frame) {
        try {
            return controller.locatePattern(
                    profile.getEmulatorNumber(), frame,
                    TemplatesEnum.FURNACE_UPGRADE_PACK, 90).isFound();
        } catch (Exception ignored) { return false; }
    }

    @Override
    public void executeInjection(EmulatorController controller,
                                 AccountDescriptor profile,
                                 DelayedTask activeTask) {
        activeTask.logDebug("FurnaceUpgrade injection starting");
        String devIdx = profile.getEmulatorNumber();
        TapInteractionService taps = TapInteractionService.forController(controller, devIdx);
        try {
            // Verify the pack is still visible
            ImageSearchResultData packResult = controller.locatePattern(
                    devIdx, TemplatesEnum.FURNACE_UPGRADE_PACK, 90);
            if (!packResult.isFound()) {
                activeTask.logDebug("FurnaceUpgrade: pack no longer on screen, aborting");
                return;
            }

            taps.tapInside(packResult);
            Thread.sleep(500);

            // Locate and tap the claim button
            ImageSearchResultData claimResult = controller.locatePattern(
                    devIdx, TemplatesEnum.FURNACE_UPGRADE_CLAIM, 90);
            if (!claimResult.isFound()) {
                activeTask.logDebug("FurnaceUpgrade: claim button missing, aborting with UI recovery back");
                // Changed by pernerch | Date: 2026-07-04 | Why: close unintended overlay/screen when pack tap succeeded but claim button was not found.
                controller.pressBack(devIdx);
                Thread.sleep(200);
                return;
            }

            taps.tapInside(claimResult);
            Thread.sleep(200);
            taps.tapNear(CONFIRM_COORD, TapJitterPolicy.DEFAULT_POINT_JITTER_RADIUS);
            Thread.sleep(200);
            controller.pressBack(devIdx);
            Thread.sleep(200);
            controller.pressBack(devIdx);

            activeTask.logDebug("FurnaceUpgrade injection completed");
        } catch (Exception ex) {
            activeTask.logError("FurnaceUpgrade injection error: " + ex.getMessage(), ex);
        }
    }

    @Override
    public String getRuleName() { return "FurnaceUpgradeInjectionRule"; }
}
