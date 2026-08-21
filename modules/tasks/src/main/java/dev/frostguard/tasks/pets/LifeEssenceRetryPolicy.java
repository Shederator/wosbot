package dev.frostguard.tasks.pets;

import java.time.Duration;

final class LifeEssenceRetryPolicy {

    private static final int SHORT_BACKOFF_FAILURE_LIMIT = 5;
    private static final int SHORT_BACKOFF_STEP_MINUTES = 5;
    private static final int SATURATED_FAILURE_COUNT = SHORT_BACKOFF_FAILURE_LIMIT + 1;
    private static final Duration LONG_RETRY_DELAY = Duration.ofHours(1);

    private LifeEssenceRetryPolicy() {
    }

    static Decision afterFailure(int persistedFailures) {
        int normalizedFailures = Math.clamp(persistedFailures, 0, SATURATED_FAILURE_COUNT);
        int nextFailures = Math.min(normalizedFailures + 1, SATURATED_FAILURE_COUNT);
        Duration retryDelay = nextFailures <= SHORT_BACKOFF_FAILURE_LIMIT
                ? Duration.ofMinutes((long) SHORT_BACKOFF_STEP_MINUTES * nextFailures)
                : LONG_RETRY_DELAY;
        return new Decision(nextFailures, retryDelay);
    }

    record Decision(int persistedFailures, Duration retryDelay) {
    }
}
