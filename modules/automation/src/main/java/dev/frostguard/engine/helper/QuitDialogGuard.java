package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.error.QuitDialogStuckException;
import dev.frostguard.engine.nav.CommonGameAreas;

import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.LongConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared recovery for the game's "Quit game?" confirmation, which Back raises on a bare screen.
 *
 * <p>After a Back press the guard waits for the dialog's pop-in animation, taps Cancel when the
 * dialog is present, and re-checks that it closed. If it is still visible after the bounded
 * attempts the guard throws {@link QuitDialogStuckException} so the caller does not continue
 * against an unknown screen; {@code TaskQueue.routeError} recovers by re-running Initialize.
 *
 * <p>The decision logic lives in a package-private overload that takes the probe, tap, and sleep
 * as functions, so every outcome is testable without an emulator.
 */
public final class QuitDialogGuard {

    private static final Logger log = LoggerFactory.getLogger(QuitDialogGuard.class);

    // Time for the dialog's pop-in animation to finish before the first check is trustworthy.
    private static final long SETTLE_MS = 350L;
    private static final int MAX_DISMISS_ATTEMPTS = 2;
    private static final long POST_TAP_WAIT_MS = 500L;
    static final int DETECTION_THRESHOLD = 90;
    static final PointData CANCEL_BUTTON = CommonGameAreas.QUIT_GAME_DIALOG_CANCEL;

    private QuitDialogGuard() {}

    /** Presses Back, then dismisses the quit-game dialog if that press raised it. */
    public static void pressBackSafely(EmulatorController emu, String device) {
        emu.pressBack(device);
        dismissIfPresent(emu, device);
    }

    /**
     * Dismisses the quit-game dialog if it is present; a no-op otherwise.
     *
     * @throws QuitDialogStuckException if the dialog is still visible after every dismiss attempt
     */
    public static void dismissIfPresent(EmulatorController emu, String device) {
        dismissIfPresent(device,
                d -> emu.locatePattern(d, TemplatesEnum.QUIT_GAME_DIALOG, DETECTION_THRESHOLD),
                () -> emu.tapInteractions(device).tapNear(CANCEL_BUTTON),
                QuitDialogGuard::realSleep);
    }

    static void dismissIfPresent(String device, Function<String, ImageSearchResultData> locateDialog,
                                 Runnable tapCancel, LongConsumer sleeper) {
        sleeper.accept(SETTLE_MS);
        for (int attempt = 1; attempt <= MAX_DISMISS_ATTEMPTS; attempt++) {
            ImageSearchResultData dialog = locateDialog.apply(device);
            if (!dialog.isFound()) {
                if (attempt > 1) {
                    log.info("Quit-game dialog on {} confirmed dismissed after {} attempt(s).", device, attempt - 1);
                }
                return;
            }
            log.info("Quit-game dialog detected on {} (attempt {}/{}); tapping Cancel.",
                    device, attempt, MAX_DISMISS_ATTEMPTS);
            tapCancel.run();
            sleeper.accept(POST_TAP_WAIT_MS);
        }
        if (locateDialog.apply(device).isFound()) {
            log.warn("Quit-game dialog still visible on {} after {} dismiss attempts; aborting.",
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
