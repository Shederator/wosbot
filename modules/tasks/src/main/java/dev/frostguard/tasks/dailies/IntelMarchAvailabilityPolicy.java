package dev.frostguard.tasks.dailies;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import dev.frostguard.api.domain.MarchSlotState;

final class IntelMarchAvailabilityPolicy {

    static final Duration UNKNOWN_RELEASE_RETRY = Duration.ofMinutes(5);

    private IntelMarchAvailabilityPolicy() {
    }

    static Decision assess(List<MarchSlotState> slots) {
        if (slots == null || slots.isEmpty()) {
            return new Decision(false, UNKNOWN_RELEASE_RETRY, false, 0);
        }

        int idleCount = (int) slots.stream().filter(MarchSlotState::isIdle).count();
        if (idleCount > 0) {
            return new Decision(true, Duration.ZERO, false, idleCount);
        }

        Duration exactRelease = slots.stream()
                .filter(MarchSlotState::hasExactReleaseCountdown)
                .map(MarchSlotState::countdown)
                .min(Duration::compareTo)
                .orElse(null);
        if (exactRelease != null) {
            return new Decision(false, exactRelease, true, 0);
        }
        return new Decision(false, UNKNOWN_RELEASE_RETRY, false, 0);
    }

    static LocalDateTime resolveNextRelease(
            LocalDateTime now,
            LocalDateTime queueRelease,
            List<LocalDateTime> knownBeastReturns) {
        LocalDateTime validQueueRelease = futureOrNull(now, queueRelease);
        LocalDateTime validBeastReturn = knownBeastReturns == null ? null : knownBeastReturns.stream()
                .filter(candidate -> futureOrNull(now, candidate) != null)
                .min(LocalDateTime::compareTo)
                .orElse(null);

        if (validQueueRelease == null) {
            return validBeastReturn == null ? now.plus(UNKNOWN_RELEASE_RETRY) : validBeastReturn;
        }
        if (validBeastReturn == null) {
            return validQueueRelease;
        }
        return validQueueRelease.isBefore(validBeastReturn) ? validQueueRelease : validBeastReturn;
    }

    private static LocalDateTime futureOrNull(LocalDateTime now, LocalDateTime candidate) {
        return candidate != null && candidate.isAfter(now) ? candidate : null;
    }

    record Decision(boolean available, Duration retryDelay, boolean exactRelease, int idleCount) {
    }
}
