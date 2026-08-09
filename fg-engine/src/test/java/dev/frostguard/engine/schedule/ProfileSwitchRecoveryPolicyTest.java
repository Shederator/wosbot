package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class ProfileSwitchRecoveryPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 9, 23, 45);

    @Test
    void keepsSharedEmulatorRunningForSiblingHandover() {
        ProfileSwitchRecoveryPolicy.Decision decision =
                ProfileSwitchRecoveryPolicy.decide(true, NOW);

        assertTrue(decision.keepEmulatorRunning());
        assertEquals(NOW.plusMinutes(5), decision.retryAt());
    }

    @Test
    void requestsConservativeRestartWithoutSibling() {
        ProfileSwitchRecoveryPolicy.Decision decision =
                ProfileSwitchRecoveryPolicy.decide(false, NOW);

        assertFalse(decision.keepEmulatorRunning());
        assertEquals(NOW.plusMinutes(5), decision.retryAt());
    }
}
