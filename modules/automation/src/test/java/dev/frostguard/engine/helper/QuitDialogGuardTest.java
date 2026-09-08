package dev.frostguard.engine.helper;

import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.engine.error.QuitDialogStuckException;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "no automated guard tests (absent/delayed/first-attempt/
 * persistent-failure/interruption) — needs a mocking seam onto EmulatorController."
 *
 * <p>{@code QuitDialogGuard.dismissIfPresent(String, Function, Runnable, LongConsumer)} is that
 * seam — plain lambdas standing in for "locate the dialog" and "tap Cancel", with no
 * {@code EmulatorController} or ADB call anywhere in it. These tests drive it directly, covering
 * every outcome the review named, including that a definitive failure now throws instead of
 * returning silently).</p>
 */
class QuitDialogGuardTest {

    @Test
    void absentDialogReturnsWithoutTappingAnything() {
        AtomicInteger taps = new AtomicInteger();

        QuitDialogGuard.dismissIfPresent("dev0",
                d -> ImageSearchResultData.miss(),
                taps::incrementAndGet,
                ms -> {});

        assertEquals(0, taps.get());
    }

    @Test
    void checksOnlyAfterTheSettleDelayNotImmediatelyAfterBack() {
        List<String> events = new ArrayList<>();

        QuitDialogGuard.dismissIfPresent("dev0",
                d -> { events.add("locate"); return ImageSearchResultData.miss(); },
                () -> events.add("tap"),
                ms -> events.add("sleep:" + ms));

        // 350ms settle wait happens before the very first check -- a dialog that pops in a beat
        // late (the original review finding) is still caught, not missed by a zero-delay check.
        assertEquals(List.of("sleep:350", "locate"), events);
    }

    @Test
    void dismissesOnTheFirstAttemptWhenTheCancelTapWorks() {
        Deque<ImageSearchResultData> script = new ArrayDeque<>(List.of(
                ImageSearchResultData.hit(100, 100, 0.95), // present on the first check
                ImageSearchResultData.miss()));            // gone right after the tap
        AtomicInteger taps = new AtomicInteger();

        assertDoesNotThrow(() -> QuitDialogGuard.dismissIfPresent("dev0",
                d -> script.poll(), taps::incrementAndGet, ms -> {}));

        assertEquals(1, taps.get());
    }

    @Test
    void takesTheSecondAttemptWhenTheFirstCancelTapMisses() {
        Deque<ImageSearchResultData> script = new ArrayDeque<>(List.of(
                ImageSearchResultData.hit(100, 100, 0.95), // present, attempt 1 check
                ImageSearchResultData.hit(100, 100, 0.95), // still present after tap 1
                ImageSearchResultData.miss()));            // gone after tap 2
        AtomicInteger taps = new AtomicInteger();

        assertDoesNotThrow(() -> QuitDialogGuard.dismissIfPresent("dev0",
                d -> script.poll(), taps::incrementAndGet, ms -> {}));

        assertEquals(2, taps.get());
    }

    @Test
    void persistentFailureThrowsInsteadOfReturningSilently() {
        // This used to log a warning and return normally, letting the caller
        // proceed as if the dialog had been handled. It must now throw, definitively.
        AtomicInteger taps = new AtomicInteger();

        QuitDialogStuckException ex = assertThrows(QuitDialogStuckException.class, () ->
                QuitDialogGuard.dismissIfPresent("dev0",
                        d -> ImageSearchResultData.hit(100, 100, 0.95), // always present, never dismisses
                        taps::incrementAndGet, ms -> {}));

        assertEquals(2, taps.get()); // MAX_DISMISS_ATTEMPTS -- tried exactly the bounded budget, no more
        assertEquals(2, ex.getAttemptsMade());
        assertTrue(ex.getMessage().contains("dev0"));
    }

    @Test
    void interruptionMidGuardAbortsInsteadOfContinuingTapsBlind() {
        AtomicInteger taps = new AtomicInteger();

        assertThrows(CancellationException.class, () ->
                QuitDialogGuard.dismissIfPresent("dev0",
                        d -> ImageSearchResultData.hit(100, 100, 0.95),
                        taps::incrementAndGet,
                        ms -> { throw new CancellationException("interrupted"); }));

        assertEquals(0, taps.get()); // aborted on the settle sleep, before ever checking or tapping
    }
}
