package dev.frostguard.api.domain;

import java.time.LocalDateTime;

public record TaskFailureStreakData(
        Long profileId,
        String taskKey,
        String signature,
        int consecutiveFailures,
        LocalDateTime firstFailureAt,
        LocalDateTime lastFailureAt) {
}
