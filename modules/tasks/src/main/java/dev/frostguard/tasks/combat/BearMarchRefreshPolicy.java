package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.MarchSlotState;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Schedules expensive march-sidebar reads from known release evidence. */
final class BearMarchRefreshPolicy {

    private static final Duration RELEASE_GUARD = Duration.ofSeconds(2);
    private static final Duration UNKNOWN_OCCUPIED_REFRESH = Duration.ofSeconds(5);
    private static final Duration ALL_IDLE_REFRESH = Duration.ofSeconds(30);
    private static final Duration MINIMUM_REFRESH = Duration.ofMillis(250);

    private BearMarchRefreshPolicy() {}

    static Duration nextDelay(
            List<MarchSlotState> slots,
            boolean specialRallyPreparing,
            Instant ownRallyBusyUntil,
            Instant observedAt) {
        Duration knownRelease = slots.stream()
                .filter(MarchSlotState::hasExactReleaseCountdown)
                .map(MarchSlotState::countdown)
                .min(Duration::compareTo)
                .orElse(null);
        if (ownRallyBusyUntil != null && ownRallyBusyUntil.isAfter(observedAt)) {
            Duration ownRelease = Duration.between(observedAt, ownRallyBusyUntil);
            if (knownRelease == null || ownRelease.compareTo(knownRelease) < 0) {
                knownRelease = ownRelease;
            }
        }
        if (knownRelease != null) {
            Duration guarded = knownRelease.minus(RELEASE_GUARD);
            return guarded.isPositive() ? guarded : MINIMUM_REFRESH;
        }
        boolean uncertainOccupiedMarch = specialRallyPreparing
                || slots.stream().anyMatch(slot -> !slot.isIdle());
        return uncertainOccupiedMarch ? UNKNOWN_OCCUPIED_REFRESH : ALL_IDLE_REFRESH;
    }
}
