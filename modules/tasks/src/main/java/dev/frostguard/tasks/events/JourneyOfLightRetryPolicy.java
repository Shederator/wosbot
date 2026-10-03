package dev.frostguard.tasks.events;

import java.time.LocalDateTime;
import java.util.Objects;

final class JourneyOfLightRetryPolicy {

    static final int MAX_CONSECUTIVE_FAILURES = 3;

    private JourneyOfLightRetryPolicy() {
    }

    static LocalDateTime retryAt(LocalDateTime now, LocalDateTime dailyReset, int consecutiveFailures) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(dailyReset, "dailyReset");
        if (consecutiveFailures < 1) {
            throw new IllegalArgumentException("consecutiveFailures must be positive");
        }

        return switch (consecutiveFailures) {
            case 1 -> earlierOf(now.plusMinutes(5), dailyReset);
            case 2 -> earlierOf(now.plusMinutes(30), dailyReset);
            default -> dailyReset;
        };
    }

    private static LocalDateTime earlierOf(LocalDateTime first, LocalDateTime second) {
        return first.isBefore(second) ? first : second;
    }
}
