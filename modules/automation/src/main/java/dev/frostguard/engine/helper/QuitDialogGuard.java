package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.error.QuitDialogStuckException;

import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.LongConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single shared implementation of the "quit game?" confirmation-dialog recovery, so every Back
 * press in the codebase gets the same protection instead of each call site inventing its own.
 *
 * <p>The review flagged two real gaps in the original version of this guard (which
 * lived only in {@code NavigationHelper}): (1) the dialog was checked for immediately after the
 * ADB Back command with no settle delay, so a dialog that animates in a beat late was missed, and
 * (2) several direct {@code EmulatorController.pressBack(...)} call sites across the codebase
 * (AllianceHelper, CharacterSwitchHelper, IntelScreenHelper, StaminaHelper,
 * FurnaceUpgradeInjectionRule, TaskBuilderService) never went through the guard at all, so
 * "anywhere" coverage wasn't actually anywhere. This class fixes both: one settle delay before the
 * first check, a bounded retry (tap Cancel, re-check, tap again if it's somehow still up), and
 * every Back call site below now routes through here.</p>
 *
 * <p>The guard used to swallow a definitively-stuck dialog as a logged
 * warning and return normally, letting every caller above proceed as if nothing happened -- into
 * a screen that may still be showing the quit confirmation. It now throws
 * {@link QuitDialogStuckException} in that case (see {@link #dismissIfPresent(EmulatorController,
 * String)}), which {@code TaskQueue.routeError} catches the same way it already catches
 * {@code HomeNotFoundException} -- re-running INITIALIZE to recover to a known screen instead of
 * silently continuing blind. And the actual decision logic (settle → probe → tap → re-probe →
 * give up) is now the package-private {@link #dismissIfPresent(String, Function, Runnable,
 * LongConsumer)} overload: a pure seam over "locate the dialog" / "tap Cancel" / "sleep", with no
 * {@link EmulatorController} or ADB call in it, so {@code QuitDialogGuardTest} can drive every
 * outcome (absent, delayed, dismissed-first-attempt, still-stuck, interrupted mid-guard) with fake
 * lambdas instead of a live emulator.</p>
 */
public final class QuitDialogGuard {

    private static final Logger log = LoggerFactory.getLogger(QuitDialogGuard.class);

    private QuitDialogGuard() {}

    // Time for the dialog's pop-in animation to finish before the first check is trustworthy.
    private static final long SETTLE_MS = 350L;
    // Cancel button. Verified against a real, live-account capture of the full
    // dialog (not just the title-only quitGameDialog.png detection crop) -- see
    // quitGameDialog-liveAccount-20260819.png under modules/automation/src/test/resources/system.
    // Measured the button's actual on-screen region directly from that frame: roughly
    // x=[80,335] y=[753,823], so (207,789) sits inside it with real margin on every side, not a
    // hair's-width guess. Then physically tapped this exact coordinate on the live account and
    // confirmed the dialog dismissed correctly. QuitGameDialogCancelButtonEvidenceTest locks this
    // in as a regression check against the committed frame.
    /** Package-private so the evidence test can assert against THIS value rather than keeping
     *  its own copy of the numbers -- a duplicated coordinate silently stops testing the real
     *  one the moment either side moves. */
    static final PointData CANCEL_BUTTON = new PointData(207, 789);
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
     *
     * @throws QuitDialogStuckException if the dialog is still confirmed present after every
     *      dismiss attempt — callers must not treat a normal return as "handled" and proceed;
     *      that used to happen silently and is now impossible: this method
     *      either returns because the dialog is genuinely gone, or throws.
     */
    public static void dismissIfPresent(EmulatorController emu, String device) {
        dismissIfPresent(device,
                d -> emu.locatePattern(d, TemplatesEnum.QUIT_GAME_DIALOG, 90),
                () -> emu.tapInteractions(device).tapNear(CANCEL_BUTTON),
                QuitDialogGuard::realSleep);
    }

    /**
     * The actual decision logic, with no {@link EmulatorController}/ADB dependency — everything
     * it needs comes in as plain lambdas, so it's directly unit-testable.
     *
     * @param locateDialog probes the current screen for the dialog; returns a found/not-found result
     * @param tapCancel taps the Cancel button once
     * @param sleeper the settle/retry delay; a fake can make this a no-op in tests instead of
     *      actually blocking ~1.3s per test
     */
    static void dismissIfPresent(String device, Function<String, ImageSearchResultData> locateDialog,
                                  Runnable tapCancel, LongConsumer sleeper) {
        sleeper.accept(SETTLE_MS);
        for (int attempt = 1; attempt <= MAX_DISMISS_ATTEMPTS; attempt++) {
            ImageSearchResultData dialog = locateDialog.apply(device);
            if (!dialog.isFound()) {
                if (attempt > 1) {
                    log.info("Quit-game dialog on {} confirmed dismissed after {} attempt(s).", device, attempt - 1);
                }
                return; // not present, or successfully dismissed by a prior attempt in this loop
            }
            log.info("Quit-game dialog detected on {} (attempt {}/{}) -- tapping Cancel.",
                    device, attempt, MAX_DISMISS_ATTEMPTS);
            tapCancel.run();
            sleeper.accept(POST_TAP_WAIT_MS);
        }
        // Final verification: confirm the last tap actually closed the dialog rather than assuming
        // success because the attempt budget ran out. Two confirmed-present tap attempts with the
        // dialog still up means the assumption is wrong, not that it needs more time, so stop and
        // tell the caller definitively instead of returning as if it were handled. Reporting a
        // silent success here would leave the next action tapping into a modal still on screen.
        ImageSearchResultData stillPresent = locateDialog.apply(device);
        if (stillPresent.isFound()) {
            log.warn("Quit-game dialog still visible on {} after {} dismiss attempts -- aborting rather than guessing further.",
                    device, MAX_DISMISS_ATTEMPTS);
            throw QuitDialogStuckException.afterAttempts(device, MAX_DISMISS_ATTEMPTS);
        }
        log.info("Quit-game dialog on {} confirmed dismissed after {} attempt(s).", device, MAX_DISMISS_ATTEMPTS);
    }

    private static void realSleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("QuitDialogGuard interrupted");
        }
    }
}
