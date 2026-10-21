package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.PointData;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Bounded memory for rally rows the UI already proved ineligible. */
final class BearJoinAttemptLedger {

    private final Duration rejectionCooldown;
    private final Map<Integer, Instant> rejectedRows = new HashMap<>();

    BearJoinAttemptLedger(Duration rejectionCooldown) {
        if (rejectionCooldown.isNegative() || rejectionCooldown.isZero()) {
            throw new IllegalArgumentException("Rejection cooldown must be positive");
        }
        this.rejectionCooldown = rejectionCooldown;
    }

    boolean canAttempt(PointData point, Instant now) {
        expire(now);
        return !rejectedRows.containsKey(rowKey(point));
    }

    void reject(PointData point, Instant now) {
        rejectedRows.put(rowKey(point), now.plus(rejectionCooldown));
    }

    void clear() {
        rejectedRows.clear();
    }

    private void expire(Instant now) {
        rejectedRows.entrySet().removeIf(entry -> !now.isBefore(entry.getValue()));
    }

    private static int rowKey(PointData point) {
        return Math.round(point.getY() / 10.0f) * 10;
    }
}
