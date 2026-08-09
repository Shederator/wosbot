package dev.frostguard.engine.schedule;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Chooses conservative emulator recovery after character initialization fails.
 */
final class ProfileSwitchRecoveryPolicy {

    static final Duration RETRY_DELAY = Duration.ofMinutes(5);

    private ProfileSwitchRecoveryPolicy() {
    }

    static Decision decide(boolean enabledSiblingOnSameEmulator, LocalDateTime now) {
        return new Decision(enabledSiblingOnSameEmulator, now.plus(RETRY_DELAY));
    }

    record Decision(boolean keepEmulatorRunning, LocalDateTime retryAt) {
    }
}
