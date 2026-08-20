package dev.frostguard.tasks.lifecycle;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.error.StartupCaptureException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupCaptureRetryTest {

    @Test
    void firstCaptureFailureThenFreshHomeFrameContinuesStartup() {
        RawImageData homeFrame = RawImageData.capture(new byte[] {0, 0, 0, 0}, 1, 1, 32);
        AtomicInteger attempts = new AtomicInteger();
        List<String> logs = new ArrayList<>();
        List<Long> sleeps = new ArrayList<>();

        RawImageData captured = StartupCaptureRetry.capture(
                context("game launch requested and settle delay completed"),
                () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw captureFailure("ShellCommandUnresponsiveException: transition in progress");
                    }
                    return homeFrame;
                },
                logs::add,
                sleeps::add);

        assertSame(homeFrame, captured);
        assertEquals(2, attempts.get());
        assertEquals(List.of(StartupCaptureRetry.RETRY_DELAY_MS), sleeps);
        assertTrue(logs.getFirst().contains("captureAttempt=1/3"));
        assertTrue(logs.getFirst().contains("serial=127.0.0.1:16416"));
        assertTrue(logs.getFirst().contains("retry fresh frame after 500 ms"));
        assertTrue(logs.getLast().contains("outcome=fresh frame captured"));
    }

    @Test
    void repeatedCaptureFailuresExposeContextAndBoundedFailureDecision() {
        AtomicInteger attempts = new AtomicInteger();
        List<String> logs = new ArrayList<>();
        List<Long> sleeps = new ArrayList<>();

        StartupCaptureException failure = assertThrows(StartupCaptureException.class,
                () -> StartupCaptureRetry.capture(
                        context("game foreground package verified"),
                        () -> {
                            attempts.incrementAndGet();
                            throw captureFailure("TimeoutException: screencap did not finish");
                        },
                        logs::add,
                        sleeps::add));

        assertEquals(StartupCaptureRetry.MAX_ATTEMPTS, attempts.get());
        assertEquals(List.of(
                StartupCaptureRetry.RETRY_DELAY_MS,
                StartupCaptureRetry.RETRY_DELAY_MS), sleeps);
        assertEquals(StartupCaptureRetry.MAX_ATTEMPTS, logs.size());
        assertTrue(failure.getMessage().contains("emulator=1"));
        assertTrue(failure.getMessage().contains("serial=127.0.0.1:16416"));
        assertTrue(failure.getMessage().contains("inspection=home/world pattern inspection"));
        assertTrue(failure.getMessage().contains("captureAttempt=3/3"));
        assertTrue(failure.getMessage().contains("lastVerifiedState=game foreground package verified"));
        assertTrue(failure.getMessage().contains("exitDetail=ddmlib shell capture exposes no process exit code"));
        assertTrue(failure.getMessage().contains("bounded task-failure reschedule after capture retry limit"));
    }

    private static StartupCaptureRetry.CaptureContext context(String lastVerifiedState) {
        return new StartupCaptureRetry.CaptureContext(
                "1", "127.0.0.1:16416", "home/world pattern inspection", lastVerifiedState);
    }

    private static RuntimeException captureFailure(String cause) {
        return new RuntimeException(
                "ADB screencap failed; emulator=1; serial=127.0.0.1:16416; command=screencap; "
                        + "receivedBytes=unavailable; "
                        + "exitDetail=ddmlib shell capture exposes no process exit code; cause=" + cause);
    }
}
