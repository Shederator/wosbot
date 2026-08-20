package dev.frostguard.engine.emulator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EmulatorInstanceCaptureDiagnosticsTest {

    @Test
    void captureFailureRetainsAdbTargetCommandBytesCauseAndExitAvailability() {
        String message = EmulatorInstance.captureFailureMessage(
                "1", "127.0.0.1:16416", 4096,
                new IllegalStateException("pixel payload was incomplete"));

        assertTrue(message.contains("emulator=1"));
        assertTrue(message.contains("serial=127.0.0.1:16416"));
        assertTrue(message.contains("command=screencap"));
        assertTrue(message.contains("commandTimeoutMs=2000"));
        assertTrue(message.contains("receivedBytes=4096"));
        assertTrue(message.contains("exitDetail=ddmlib shell capture exposes no process exit code"));
        assertTrue(message.contains("cause=IllegalStateException: pixel payload was incomplete"));
    }
}
