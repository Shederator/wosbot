package dev.frostguard.engine.error;

import java.time.Instant;

// Signals that the game UI is stuck on its "reconnecting" overlay, blocking automation.
// The dispatcher treats this as recoverable by restarting the session.
public class ProfileInReconnectStateException extends RuntimeException {

    private static final long serialVersionUID = 5512089734106L;

    private final long detectedEpochMs;

    public ProfileInReconnectStateException(String msg) {
        super(msg);
        this.detectedEpochMs = System.currentTimeMillis();
    }

    public static ProfileInReconnectStateException whileNavigating(String who) {
        return new ProfileInReconnectStateException("Reconnect screen hit mid-navigation [" + who + "]");
    }

    public static ProfileInReconnectStateException whileLaunching(String who) {
        return new ProfileInReconnectStateException("Reconnect required at launch [" + who + "]");
    }

    public Instant getDetectedAt() {
        return Instant.ofEpochMilli(detectedEpochMs);
    }

    public long ageMs() {
        return Math.max(0, System.currentTimeMillis() - detectedEpochMs);
    }
}
