package dev.frostguard.tasks.city;

import java.util.Objects;
import java.util.function.BooleanSupplier;

final class CrystalClaimLoop {

    static final int MAX_TOTAL_ATTEMPTS = 25;

    private CrystalClaimLoop() {
    }

    static Result collect(BooleanSupplier claimAttempt, Runnable pause, int maxConsecutiveMisses) {
        Objects.requireNonNull(claimAttempt, "claimAttempt");
        Objects.requireNonNull(pause, "pause");
        if (maxConsecutiveMisses < 1) {
            throw new IllegalArgumentException("maxConsecutiveMisses must be positive");
        }

        int attempts = 0;
        int claims = 0;
        int consecutiveMisses = 0;

        while (consecutiveMisses < maxConsecutiveMisses && attempts < MAX_TOTAL_ATTEMPTS) {
            attempts++;
            if (claimAttempt.getAsBoolean()) {
                claims++;
                consecutiveMisses = 0;
            } else {
                consecutiveMisses++;
            }

            if (consecutiveMisses < maxConsecutiveMisses && attempts < MAX_TOTAL_ATTEMPTS) {
                pause.run();
            }
        }

        boolean attemptLimitReached = attempts == MAX_TOTAL_ATTEMPTS
                && consecutiveMisses < maxConsecutiveMisses;
        return new Result(claims, consecutiveMisses, attemptLimitReached);
    }

    record Result(int claims, int consecutiveMisses, boolean attemptLimitReached) {
    }
}
