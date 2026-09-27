package dev.frostguard.tasks.city;

import dev.frostguard.engine.error.ADBConnectionException;
import dev.frostguard.engine.error.HomeNotFoundException;
import dev.frostguard.engine.error.ProfileCooldownException;
import dev.frostguard.engine.error.ProfileInReconnectStateException;
import dev.frostguard.engine.error.StopExecutionException;
import dev.frostguard.engine.error.TaskPreemptedException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static dev.frostguard.tasks.city.CityUpgradeFlow.FailureReason.CONTROL_NOT_RECOGNIZED;
import static org.junit.jupiter.api.Assertions.*;

class CityUpgradeFlowTest {
    @Test
    void retriesAfterHomeRecoveryAndClosesSuccessfulPanelBeforeNextQueue() {
        List<String> events = new ArrayList<>();
        String result = CityUpgradeFlow.handleQueue(1, queue(events, true));
        events.add("queue-2");
        assertEquals("construction", result);
        assertEquals(List.of("attempt-1", "snapshot-1", "home", "attempt-2", "home", "queue-2"), events);
    }

    @Test
    void terminalFailureCapturesBeforeRootRecoveryAndNeverProcessesSecondQueue() {
        List<String> events = new ArrayList<>();
        var failure = assertThrows(CityUpgradeFlow.UnresolvedBuildingException.class,
                () -> CityUpgradeFlow.execute(() -> {
                    CityUpgradeFlow.handleQueue(1, queue(events, false));
                    events.add("queue-2");
                }, recovery(events, null)));
        assertEquals(List.of("attempt-1", "snapshot-1", "home", "attempt-2", "snapshot-2",
                "root", "recovered-true"), events);
        assertTrue(failure.getMessage().contains("queue=1"));
        assertTrue(failure.getMessage().contains("CONTROL_NOT_RECOGNIZED"));
    }

    @Test
    void productionBlockerClosesSpeedupBeforeAnotherQueue() {
        List<String> events = new ArrayList<>();
        var ui = new CityUpgradeFlow.QueueUi<String>() {
            public CityUpgradeFlow.Attempt<String> attempt(int number) {
                events.add("speedup");
                return CityUpgradeFlow.Attempt.completed("blocked-by-production");
            }
            public void retainFailure(int number, CityUpgradeFlow.FailureReason reason) { fail(); }
            public void recoverHome() { events.add("home"); }
        };
        assertEquals("blocked-by-production", CityUpgradeFlow.handleQueue(1, ui));
        events.add("queue-2");
        assertEquals(List.of("speedup", "home", "queue-2"), events);
    }

    @Test
    void failedRootRecoveryPreservesOriginalAndUsesNavigationFailureRouting() {
        List<String> events = new ArrayList<>();
        RuntimeException original = new IllegalStateException("original control missing");
        HomeNotFoundException navigation = new HomeNotFoundException("root unavailable");
        RuntimeException thrown = assertThrows(HomeNotFoundException.class,
                () -> CityUpgradeFlow.execute(() -> { throw original; }, recovery(events, navigation)));
        assertSame(navigation, thrown);
        assertArrayEquals(new Throwable[]{original}, thrown.getSuppressed());
        assertEquals(List.of("unexpected-failure", "root", "root-recovery-failed", "recovered-false"), events);
    }

    @Test
    void successfulRootRecoveryRethrowsSameFailureForSchedulerRetryAndIncidentTracking() {
        List<String> events = new ArrayList<>();
        RuntimeException original = new IllegalStateException("missing confirmation");
        assertSame(original, assertThrows(IllegalStateException.class,
                () -> CityUpgradeFlow.execute(() -> { throw original; }, recovery(events, null))));
        assertEquals(List.of("unexpected-failure", "root", "recovered-true"), events);
    }

    @Test
    void failedHomeRecoveryPreventsRetryAndPreservesTheAttemptReason() {
        List<String> events = new ArrayList<>();
        var navigation = new HomeNotFoundException("Home unavailable between attempts");
        var ui = new CityUpgradeFlow.QueueUi<String>() {
            public CityUpgradeFlow.Attempt<String> attempt(int number) {
                events.add("attempt-" + number);
                return CityUpgradeFlow.Attempt.unresolved(CONTROL_NOT_RECOGNIZED);
            }
            public void retainFailure(int number, CityUpgradeFlow.FailureReason reason) { events.add("snapshot"); }
            public void recoverHome() { throw navigation; }
        };
        assertSame(navigation, assertThrows(HomeNotFoundException.class, () -> CityUpgradeFlow.execute(() -> {
            CityUpgradeFlow.handleQueue(1, ui);
            events.add("queue-2");
        }, recovery(events, null))));
        assertEquals(List.of("attempt-1", "snapshot", "unexpected-failure", "root", "recovered-true"), events);
        assertEquals(1, navigation.getSuppressed().length);
        assertTrue(navigation.getSuppressed()[0].getMessage().contains("CONTROL_NOT_RECOGNIZED"));
    }

    @Test
    void controlSignalsDoNotCaptureOrRecover() {
        for (RuntimeException signal : signals()) {
            List<String> events = new ArrayList<>();
            assertSame(signal, assertThrows(signal.getClass(), () -> CityUpgradeFlow.execute(
                    () -> { throw signal; }, recovery(events, null))));
            assertTrue(events.isEmpty(), signal.getClass().getSimpleName());
        }
    }

    @Test
    void wrappedAdbCaptureFailureBypassesNavigationAndRestoresSchedulerSignal() {
        List<String> events = new ArrayList<>();
        var adb = new ADBConnectionException("device offline");
        assertSame(adb, assertThrows(ADBConnectionException.class, () -> CityUpgradeFlow.execute(
                () -> { throw new RuntimeException("capture failed", adb); }, recovery(events, null))));
        assertTrue(events.isEmpty());
    }

    @Test
    void controlSignalDuringRecoveryYieldsWithoutFurtherCapture() {
        for (RuntimeException signal : signals()) {
            List<String> events = new ArrayList<>();
            RuntimeException original = new IllegalStateException("unresolved");
            assertSame(signal, assertThrows(signal.getClass(), () -> CityUpgradeFlow.execute(
                    () -> { throw original; }, recovery(events, signal))));
            assertEquals(List.of("unexpected-failure", "root"), events);
            assertArrayEquals(new Throwable[]{original}, signal.getSuppressed());
        }
    }

    @Test
    void interruptedThreadDoesNotStartRecovery() {
        List<String> events = new ArrayList<>();
        try {
            Thread.currentThread().interrupt();
            assertThrows(StopExecutionException.class, () -> CityUpgradeFlow.execute(
                    () -> { throw new IllegalStateException("unresolved"); }, recovery(events, null)));
            assertTrue(events.isEmpty());
        } finally {
            Thread.interrupted();
        }
    }

    private static List<RuntimeException> signals() {
        return List.of(StopExecutionException.userCancelled(), TaskPreemptedException.because("higher priority"),
                new ProfileInReconnectStateException("reconnecting"),
                new ProfileCooldownException("cooldown", LocalDateTime.now().plusMinutes(5)),
                new ADBConnectionException("device unavailable"));
    }

    private static CityUpgradeFlow.QueueUi<String> queue(List<String> events, boolean succeedsOnRetry) {
        return new CityUpgradeFlow.QueueUi<>() {
            public CityUpgradeFlow.Attempt<String> attempt(int number) {
                events.add("attempt-" + number);
                return number == 2 && succeedsOnRetry ? CityUpgradeFlow.Attempt.completed("construction")
                        : CityUpgradeFlow.Attempt.unresolved(CONTROL_NOT_RECOGNIZED);
            }
            public void retainFailure(int number, CityUpgradeFlow.FailureReason reason) {
                events.add("snapshot-" + number);
            }
            public void recoverHome() { events.add("home"); }
        };
    }

    private static CityUpgradeFlow.Recovery recovery(List<String> events, RuntimeException failure) {
        return new CityUpgradeFlow.Recovery() {
            public void retainFailure(String type) { events.add(type); }
            public void recoverRoot() {
                events.add("root");
                if (failure != null) throw failure;
            }
            public void reportRecovery(boolean recovered, RuntimeException original, RuntimeException recoveryFailure) {
                events.add("recovered-" + recovered);
            }
        };
    }
}
