package dev.frostguard.tasks.lifecycle;

import dev.frostguard.engine.error.ProfileCooldownException;
import dev.frostguard.engine.error.StopExecutionException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InitializeEmulatorRetryTest {

    @Test
    void cancellationAfterStateProbePreventsLaunch() {
        AtomicInteger checks = new AtomicInteger();
        AtomicBoolean launched = new AtomicBoolean();

        assertThrows(StopExecutionException.class, () -> InitializeRoutine.awaitEmulatorRunning(
                () -> false,
                () -> launched.set(true),
                () -> { },
                () -> {
                    if (checks.incrementAndGet() == 2) {
                        throw new StopExecutionException("cancelled");
                    }
                }));

        assertFalse(launched.get());
    }

    @Test
    void cancellationAfterLaunchPreventsRetryDelay() {
        AtomicInteger checks = new AtomicInteger();
        AtomicBoolean launched = new AtomicBoolean();
        AtomicBoolean waited = new AtomicBoolean();

        assertThrows(StopExecutionException.class, () -> InitializeRoutine.awaitEmulatorRunning(
                () -> false,
                () -> launched.set(true),
                () -> waited.set(true),
                () -> {
                    if (checks.incrementAndGet() == 3) {
                        throw new StopExecutionException("cancelled");
                    }
                }));

        assertTrue(launched.get());
        assertFalse(waited.get());
    }

    @Test
    void deviceNeverReadyExhaustsBoundedRetriesAndInvokesExhaustionHandler() {
        AtomicInteger launches = new AtomicInteger();
        AtomicInteger delays = new AtomicInteger();
        AtomicBoolean exhausted = new AtomicBoolean();

        InitializeRoutine.awaitEmulatorRunning(
                () -> false,
                launches::incrementAndGet,
                delays::incrementAndGet,
                () -> { },
                3,
                () -> exhausted.set(true));

        assertEquals(3, launches.get(), "must bound launch attempts to maxAttempts");
        assertEquals(3, delays.get(), "must delay after each failed attempt");
        assertTrue(exhausted.get(), "must invoke exhaustion handler when attempts exhausted");
    }

    @Test
    void defaultOverloadThrowsProfileCooldownExceptionWhenRetriesExhausted() {
        AtomicInteger launches = new AtomicInteger();

        ProfileCooldownException ex = assertThrows(ProfileCooldownException.class,
                () -> InitializeRoutine.awaitEmulatorRunning(
                        () -> false,
                        launches::incrementAndGet,
                        () -> { },
                        () -> { }));

        assertEquals(StartupRecoveryPolicy.MAX_EMULATOR_BOOT_ATTEMPTS, launches.get());
        assertTrue(ex.getMessage().contains("bounded launch attempts"));
    }

    @Test
    void readyAfterRetryStopsLaunching() {
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger launches = new AtomicInteger();
        AtomicInteger delays = new AtomicInteger();

        InitializeRoutine.awaitEmulatorRunning(
                () -> probes.incrementAndGet() > 2,
                launches::incrementAndGet,
                delays::incrementAndGet,
                () -> { });

        assertEquals(2, launches.get(), "must stop launching once running probe succeeds");
        assertEquals(2, delays.get(), "must stop delays once running probe succeeds");
    }

    @Test
    void alreadyRunningDoesNotLaunchOrDelay() {
        AtomicInteger launches = new AtomicInteger();
        AtomicInteger delays = new AtomicInteger();

        InitializeRoutine.awaitEmulatorRunning(
                () -> true,
                launches::incrementAndGet,
                delays::incrementAndGet,
                () -> { });

        assertEquals(0, launches.get());
        assertEquals(0, delays.get());
    }

    @Test
    void cancellationAfterSuccessfulProbePreventsReturn() {
        AtomicInteger checks = new AtomicInteger();

        assertThrows(StopExecutionException.class, () -> InitializeRoutine.awaitEmulatorRunning(
                () -> true,
                () -> { },
                () -> { },
                () -> {
                    if (checks.incrementAndGet() == 2) {
                        throw new StopExecutionException("cancelled after successful probe");
                    }
                }));
    }

    @Test
    void cancellationAfterFinalProbePreventsExhaustion() {
        AtomicInteger checks = new AtomicInteger();
        AtomicBoolean exhausted = new AtomicBoolean();

        assertThrows(StopExecutionException.class, () -> InitializeRoutine.awaitEmulatorRunning(
                () -> false,
                () -> { },
                () -> { },
                () -> {
                    // 3 iterations * 3 checks = 9 checks; check 10 is before final probe, check 11 is after final probe
                    if (checks.incrementAndGet() == 11) {
                        throw new StopExecutionException("cancelled after final probe");
                    }
                },
                3,
                () -> exhausted.set(true)));

        assertFalse(exhausted.get(), "cancellation after final probe must prevent exhaustion invocation");
    }

    @Test
    void cancellationAfterFinalProbeSucceedsPreventsReturn() {
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger probes = new AtomicInteger();

        assertThrows(StopExecutionException.class, () -> InitializeRoutine.awaitEmulatorRunning(
                () -> probes.incrementAndGet() == 4, // true only on 4th (final) probe
                () -> { },
                () -> { },
                () -> {
                    if (checks.incrementAndGet() == 11) {
                        throw new StopExecutionException("cancelled after final probe success");
                    }
                },
                3,
                () -> { }));
    }
}
