package dev.frostguard.tasks.lifecycle;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StartupRecoveryPolicyTest {

    @Test
    void permitsOnlyOneForegroundGameBack() {
        assertEquals(StartupRecoveryPolicy.UnknownBlockerAction.TRY_GAME_BACK,
                StartupRecoveryPolicy.forUnknownBlocker(true, 0));
        assertEquals(StartupRecoveryPolicy.UnknownBlockerAction.COOLDOWN_AND_RELEASE_SLOT,
                StartupRecoveryPolicy.forUnknownBlocker(true, 1));
        assertEquals(StartupRecoveryPolicy.UnknownBlockerAction.COOLDOWN_AND_RELEASE_SLOT,
                StartupRecoveryPolicy.forUnknownBlocker(true, 2));
    }

    @Test
    void sendsNoRecoveryInputOutsideTheForegroundGame() {
        assertEquals(StartupRecoveryPolicy.UnknownBlockerAction.COOLDOWN_AND_RELEASE_SLOT,
                StartupRecoveryPolicy.forUnknownBlocker(false, 0));
    }

    @Test
    void usesLongerCooldownOnlyForVerifiedPlayStoreRedirect() {
        assertEquals(Duration.ofMinutes(15), StartupRecoveryPolicy.UNKNOWN_BLOCKER_COOLDOWN);
        assertEquals(Duration.ofMinutes(15), StartupRecoveryPolicy.UPDATE_FOLLOW_UP_COOLDOWN);
        assertEquals(Duration.ofHours(1), StartupRecoveryPolicy.PLAY_STORE_REDIRECT_COOLDOWN);
    }
}
