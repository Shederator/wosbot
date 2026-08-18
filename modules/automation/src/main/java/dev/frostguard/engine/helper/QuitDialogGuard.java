package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.emulator.EmulatorController;

/**
 * Single shared implementation of the "quit game?" confirmation-dialog recovery, so every Back
 * press in the codebase gets the same protection instead of each call site inventing its own.
 *
 * <p>Dave's #252 review flagged two real gaps in the original version of this guard (which
 * lived only in {@code NavigationHelper}): (1) the dialog was checked for immediately after the
 * ADB Back command with no settle delay, so a dialog that animates in a beat late was missed, and
 * (2) several direct {@code EmulatorController.pressBack(...)} call sites across the codebase
 * (AllianceHelper, CharacterSwitchHelper, IntelScreenHelper, StaminaHelper,
 * FurnaceUpgradeInjectionRule, TaskBuilderService) never went through the guard at all, so
 * "anywhere" coverage wasn't actually anywhere. This class fixes both: one settle delay before the
 * first check, a bounded retry (tap Cancel, re-check, tap again if it's somehow still up) instead
 * of a single unverified tap, and every Back call site below now routes through here.</p>
 */
public final class QuitDialogGuard {

    private QuitDialogGuard() {}

    // Time for the dialog's pop-in animation to finish before the first check is trustworthy.
    private static final long SETTLE_MS = 350L;
    // Cancel button, read off a live capture of the dialog (shop-debug reference: quit_dialog.png).
    private static final PointData CANCEL_BUTTON = new PointData(207, 789);
    private static final int MAX_DISMISS_ATTEMPTS = 2;
    private static final long POST_TAP_WAIT_MS = 500L;

    /** Presses Back on the given emulator, then checks for and dismisses the quit-game dialog. */
    public static void pressBackSafely(EmulatorController emu, String device) {
        emu.pressBack(device);
        dismissIfPresent(emu, device);
    }

    /**
     * Checks for the quit-game dialog and dismisses it if present. Safe to call after any action
     * that might have triggered it (not just Back) — a no-op when the dialog isn't there.
     */
    public static void dismissIfPresent(EmulatorController emu, String device) {
        sleep(SETTLE_MS);
        for (int attempt = 1; attempt <= MAX_DISMISS_ATTEMPTS; attempt++) {
            ImageSearchResultData dialog = emu.locatePattern(device, TemplatesEnum.QUIT_GAME_DIALOG, 90);
            if (!dialog.isFound()) {
                return; // not present, or successfully dismissed by a prior attempt in this loop
            }
            emu.tapInteractions(device).tapNear(CANCEL_BUTTON);
            sleep(POST_TAP_WAIT_MS);
        }
        // Two tap attempts against a confirmed sighting and it's still there -- leave it; forcing a
        // third blind tap risks hitting something else entirely if this isn't actually the dialog.
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
