package dev.frostguard.tasks.economy;

import java.time.Duration;

final class GatherSameTargetRetryPolicy {

    static final int MAX_NODE_ATTEMPTS = 2;
    static final Duration EXHAUSTED_RETRY_DELAY = Duration.ofMinutes(30);

    private GatherSameTargetRetryPolicy() {
    }

    static boolean shouldSearchAnotherNode(int failedAttempts) {
        return failedAttempts < MAX_NODE_ATTEMPTS;
    }

    static boolean requiresCooldown(int exhaustedConflicts, int unfilledSlots) {
        return exhaustedConflicts > 0 && unfilledSlots > 0;
    }
}
