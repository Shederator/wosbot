package dev.frostguard.tasks.lifecycle;

import java.time.Duration;

final class StartupRecoveryPolicy {

    static final int MAX_EMULATOR_RESTART_ATTEMPTS = 1;
    static final Duration UNKNOWN_BLOCKER_COOLDOWN = Duration.ofMinutes(15);
    static final Duration PLAY_STORE_REDIRECT_COOLDOWN = Duration.ofHours(1);
    static final Duration UPDATE_FOLLOW_UP_COOLDOWN = Duration.ofMinutes(15);

    private StartupRecoveryPolicy() {
    }

    static UnknownBlockerAction forUnknownBlocker(int completedRestartAttempts) {
        return completedRestartAttempts < MAX_EMULATOR_RESTART_ATTEMPTS
                ? UnknownBlockerAction.RESTART_EMULATOR
                : UnknownBlockerAction.COOLDOWN_AND_RELEASE_SLOT;
    }

    enum UnknownBlockerAction {
        RESTART_EMULATOR,
        COOLDOWN_AND_RELEASE_SLOT
    }
}
