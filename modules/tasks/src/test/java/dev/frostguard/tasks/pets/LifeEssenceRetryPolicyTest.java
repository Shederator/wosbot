package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class LifeEssenceRetryPolicyTest {

    @Test
    void increasesShortBackoffForTheFirstFiveFailures() {
        assertDecision(0, 1, Duration.ofMinutes(5));
        assertDecision(1, 2, Duration.ofMinutes(10));
        assertDecision(2, 3, Duration.ofMinutes(15));
        assertDecision(3, 4, Duration.ofMinutes(20));
        assertDecision(4, 5, Duration.ofMinutes(25));
    }

    @Test
    void retriesHourlyWithoutGrowingFailureStateAfterTheSixthFailure() {
        assertDecision(5, 6, Duration.ofHours(1));
        assertDecision(6, 6, Duration.ofHours(1));
        assertDecision(Integer.MAX_VALUE, 6, Duration.ofHours(1));
    }

    @Test
    void treatsMalformedNegativeFailureStateAsNoPreviousFailure() {
        assertDecision(-7, 1, Duration.ofMinutes(5));
    }

    private void assertDecision(int previousFailures, int expectedFailures, Duration expectedDelay) {
        LifeEssenceRetryPolicy.Decision decision = LifeEssenceRetryPolicy.afterFailure(previousFailures);

        assertEquals(expectedFailures, decision.persistedFailures());
        assertEquals(expectedDelay, decision.retryDelay());
    }
}
