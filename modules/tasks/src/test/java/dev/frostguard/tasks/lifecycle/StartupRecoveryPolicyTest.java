package dev.frostguard.tasks.lifecycle;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StartupRecoveryPolicyTest {

    @Test
    void permitsOnlyOneImmediateEmulatorRestart() {
        assertEquals(StartupRecoveryPolicy.UnknownBlockerAction.RESTART_EMULATOR,
                StartupRecoveryPolicy.forUnknownBlocker(0));
        assertEquals(StartupRecoveryPolicy.UnknownBlockerAction.COOLDOWN_AND_RELEASE_SLOT,
                StartupRecoveryPolicy.forUnknownBlocker(1));
        assertEquals(StartupRecoveryPolicy.UnknownBlockerAction.COOLDOWN_AND_RELEASE_SLOT,
                StartupRecoveryPolicy.forUnknownBlocker(2));
    }

    @Test
    void usesLongerCooldownOnlyForVerifiedPlayStoreRedirect() {
        assertEquals(Duration.ofMinutes(15), StartupRecoveryPolicy.UNKNOWN_BLOCKER_COOLDOWN);
        assertEquals(Duration.ofMinutes(15), StartupRecoveryPolicy.UPDATE_FOLLOW_UP_COOLDOWN);
        assertEquals(Duration.ofHours(1), StartupRecoveryPolicy.PLAY_STORE_REDIRECT_COOLDOWN);
    }
}
