package dev.frostguard.tasks.combat;

final class PolarSpecialRewardsBudget {

    private Integer remaining;

    boolean requiresInitialRead() {
        return remaining == null;
    }

    void recordInitialRead(int count) {
        if (remaining != null) {
            throw new IllegalStateException("Special rewards were already read for this visit");
        }
        if (count < 0) {
            throw new IllegalArgumentException("Special rewards count cannot be negative");
        }
        remaining = count;
    }

    boolean isExhausted() {
        return remaining != null && remaining == 0;
    }

    int consumeConfirmedRally() {
        if (remaining == null) {
            throw new IllegalStateException("Cannot consume special rewards before reading the visit budget");
        }
        if (remaining == 0) {
            throw new IllegalStateException("Cannot consume an exhausted special rewards budget");
        }
        remaining--;
        return remaining;
    }

    int remaining() {
        if (remaining == null) {
            throw new IllegalStateException("Special rewards have not been read for this visit");
        }
        return remaining;
    }
}
