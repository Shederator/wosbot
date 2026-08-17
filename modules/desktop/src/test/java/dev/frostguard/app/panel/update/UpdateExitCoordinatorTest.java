package dev.frostguard.app.panel.update;

import dev.frostguard.update.InstallerHandoff;
import dev.frostguard.update.UpdateException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateExitCoordinatorTest {
    @Test
    void shutsDownThenAuthorizesAndExits() throws Exception {
        List<String> calls = new ArrayList<>();
        Session session = new Session(calls);
        UpdateExitCoordinator coordinator = new UpdateExitCoordinator(() -> calls.add("shutdown"),
                () -> calls.add("exit"), () -> { throw new AssertionError("Failure exit should not run"); });

        coordinator.execute(session);

        assertTrue(session.authorized);
        assertFalse(session.cancelled);
        assertEquals(List.of("shutdown", "authorize", "exit"), calls);
    }

    @Test
    void cancelsHandoffAndExitsWhenShutdownFails() {
        Session session = new Session(new ArrayList<>());
        AtomicBoolean failedExit = new AtomicBoolean();
        UpdateExitCoordinator coordinator = new UpdateExitCoordinator(
                () -> { throw new IllegalStateException("database busy"); },
                () -> { throw new AssertionError("Success exit should not run"); },
                () -> failedExit.set(true));

        assertThrows(IllegalStateException.class, () -> coordinator.execute(session));
        assertFalse(session.authorized);
        assertTrue(session.cancelled);
        assertTrue(failedExit.get());
    }

    @Test
    void cancelsHandoffAndExitsWhenAuthorizationFails() {
        Session session = new Session(new ArrayList<>());
        session.authorizationFailure = new UpdateException("token write failed");
        AtomicBoolean shutdown = new AtomicBoolean();
        AtomicBoolean failedExit = new AtomicBoolean();
        UpdateExitCoordinator coordinator = new UpdateExitCoordinator(
                () -> shutdown.set(true),
                () -> { throw new AssertionError("Success exit should not run"); },
                () -> failedExit.set(true));

        assertThrows(UpdateException.class, () -> coordinator.execute(session));
        assertTrue(shutdown.get());
        assertTrue(session.cancelled);
        assertTrue(failedExit.get());
    }

    private static final class Session implements InstallerHandoff.HandoffSession {
        private final List<String> calls;
        private boolean authorized;
        private boolean cancelled;
        private UpdateException authorizationFailure;

        private Session(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void authorize() throws UpdateException {
            calls.add("authorize");
            if (authorizationFailure != null) {
                throw authorizationFailure;
            }
            authorized = true;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
