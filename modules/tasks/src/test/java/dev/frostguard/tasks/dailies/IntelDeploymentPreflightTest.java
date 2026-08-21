package dev.frostguard.tasks.dailies;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IntelDeploymentPreflightTest {

    @Test
    void rejectsUnreadableTravelTime() {
        assertFalse(IntelDeploymentPreflight.assess(0).allowed());
    }

    @Test
    void rejectsImplausiblyLongIntelTravelTime() {
        assertFalse(IntelDeploymentPreflight.assess(300).allowed());
    }

    @Test
    void acceptsTravelTimeBelowFiveMinutes() {
        assertTrue(IntelDeploymentPreflight.assess(
                IntelDeploymentPreflight.MAX_TRAVEL_SECONDS_EXCLUSIVE - 1).allowed());
    }
}
