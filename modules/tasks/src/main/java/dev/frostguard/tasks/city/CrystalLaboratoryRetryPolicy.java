package dev.frostguard.tasks.city;

import java.time.LocalDateTime;
import java.util.Objects;

final class CrystalLaboratoryRetryPolicy {

    private CrystalLaboratoryRetryPolicy() {
    }

    static LocalDateTime retryAt(LocalDateTime now, LocalDateTime dailyReset,
                                 int consecutiveNavigationFailures) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(dailyReset, "dailyReset");
        if (consecutiveNavigationFailures < 1) {
            throw new IllegalArgumentException("consecutiveNavigationFailures must be positive");
        }

        return switch (consecutiveNavigationFailures) {
            case 1 -> earlierOf(now.plusMinutes(5), dailyReset);
            case 2 -> earlierOf(now.plusMinutes(30), dailyReset);
            default -> dailyReset;
        };
    }

    private static LocalDateTime earlierOf(LocalDateTime first, LocalDateTime second) {
        return first.isBefore(second) ? first : second;
    }
}
