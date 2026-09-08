package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.LongConsumer;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.ImageSearchResultData;

/**
 * Cancellation has to survive the guard's own delays and the cleanup around them.
 *
 * <p>The guard turns an interrupted sleep into a {@link CancellationException}, which is the right
 * signal -- but a broad {@code catch (Exception)} downstream swallowed it, so a stop request landing
 * during a post-tap delay was logged as an ordinary error and the routine carried on driving the
 * emulator. That is the failure these tests pin: interrupting mid-delay must reach the caller, and
 * must not be mistaken for a dialog that would not dismiss.
 *
 * <p>Everything here drives the package-private seam with plain lambdas, so no emulator or ADB is
 * involved and the "sleep" can be made to throw on demand.
 *
 * <p>Evidence level: automated tests.
 */
class QuitDialogGuardCancellationTest {

    private static final String DEVICE = "0";

    private static ImageSearchResultData notFound() {
        return new ImageSearchResultData(false, null, 0.0);
    }

    /** Read from production rather than copied, for the same reason the evidence test does: a
     *  duplicated coordinate stops exercising the shipped one the moment either side moves, and
     *  the test then passes while validating a value the code no longer uses. */
    private static ImageSearchResultData found() {
        return new ImageSearchResultData(true, QuitDialogGuard.CANCEL_BUTTON, 95.0);
    }

    /** A sleeper that behaves like a real interrupted Thread.sleep on the Nth call. */
    private static LongConsumer sleeperInterruptingOnCall(int interruptOnCall, AtomicInteger calls) {
        return ms -> {
            if (calls.incrementAndGet() == interruptOnCall) {
                Thread.currentThread().interrupt();
                throw new CancellationException("QuitDialogGuard interrupted");
            }
        };
    }

    @Test
    void interruptDuringTheSettleDelayPropagates() {
        AtomicInteger sleeps = new AtomicInteger();
        Function<String, ImageSearchResultData> locate = d -> notFound();

        assertThrows(CancellationException.class, () -> QuitDialogGuard.dismissIfPresent(
                DEVICE, locate, () -> { }, sleeperInterruptingOnCall(1, sleeps)));

        assertEquals(1, sleeps.get(), "it should stop on the very first delay, not press on");
        assertTrue(Thread.interrupted(), "the interrupt flag must be left set for the caller");
    }

    @Test
    void interruptDuringAPostTapDelayPropagates() {
        // The case named in review: the dialog IS present, Cancel is tapped, and the stop lands
        // while waiting for the tap to take effect.
        AtomicInteger sleeps = new AtomicInteger();
        AtomicInteger taps = new AtomicInteger();
        Function<String, ImageSearchResultData> locate = d -> found();

        assertThrows(CancellationException.class, () -> QuitDialogGuard.dismissIfPresent(
                DEVICE, locate, taps::incrementAndGet, sleeperInterruptingOnCall(2, sleeps)));

        assertTrue(taps.get() >= 1, "the Cancel tap should have happened before the interrupt");
        assertTrue(Thread.interrupted(), "the interrupt flag must be left set for the caller");
    }

    @Test
    void cancellationIsNotReportedAsAStuckDialog() {
        // A stop is not the same as "the dialog would not go away". Confusing the two would send
        // the profile into re-initialisation instead of simply stopping.
        AtomicInteger sleeps = new AtomicInteger();
        Function<String, ImageSearchResultData> locate = d -> found();

        Throwable thrown = assertThrows(Throwable.class, () -> QuitDialogGuard.dismissIfPresent(
                DEVICE, locate, () -> { }, sleeperInterruptingOnCall(2, sleeps)));

        assertEquals(CancellationException.class, thrown.getClass(),
                "expected cancellation, got " + thrown.getClass().getSimpleName());
        Thread.interrupted();
    }

    @Test
    void anUninterruptedRunStillCompletesNormally() {
        // Guard against the fix turning every delay into a cancellation.
        AtomicInteger sleeps = new AtomicInteger();
        Function<String, ImageSearchResultData> locate = d -> notFound();

        QuitDialogGuard.dismissIfPresent(DEVICE, locate, () -> { }, ms -> sleeps.incrementAndGet());

        assertTrue(sleeps.get() >= 1, "the settle delay should still be taken");
        assertTrue(!Thread.currentThread().isInterrupted(), "nothing should be interrupted here");
    }
}
