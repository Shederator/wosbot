package dev.frostguard.tasks.events;

import dev.frostguard.api.domain.TaskFailureStreakData;
import dev.frostguard.data.repository.TaskFailureStreakRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/** Persistent per-profile visit budget measured against the game's UTC reset. */
final class HeroMissionVisitBudget {
    static final int MAX_VISITS = 3;

    enum FailureKind {
        NAVIGATION("hero-mission-visit-navigation"),
        PROGRESS("hero-mission-visit-progress");

        private final String taskKey;

        FailureKind(String taskKey) {
            this.taskKey = taskKey;
        }
    }

    record Decision(int attempt, LocalDateTime nextVisit, boolean exhausted) {
    }

    private final TaskFailureStreakRepository streaks;
    private final long profileId;
    private final Clock clock;

    HeroMissionVisitBudget(TaskFailureStreakRepository streaks, long profileId, Clock clock) {
        this.streaks = Objects.requireNonNull(streaks);
        this.profileId = profileId;
        this.clock = Objects.requireNonNull(clock);
    }

    boolean exhausted(FailureKind kind) {
        LocalDateTime resetBoundary = lastReset(clock.instant());
        return streaks.find(profileId, kind.taskKey)
                .filter(streak -> !streak.lastFailureAt().isBefore(resetBoundary))
                .map(TaskFailureStreakData::consecutiveFailures)
                .orElse(0) >= MAX_VISITS;
    }

    Decision recordFailure(FailureKind kind) {
        Instant now = clock.instant();
        LocalDateTime localNow = localTime(now);
        int attempt = streaks.recordFailureSince(
                profileId, kind.taskKey, kind.name(), utcTime(now), lastReset(now)).consecutiveFailures();
        LocalDateTime reset = nextReset(now);
        boolean exhausted = attempt >= MAX_VISITS;
        LocalDateTime retry = localNow.plusMinutes(5);
        return new Decision(attempt, exhausted || retry.isAfter(reset) ? reset : retry, exhausted);
    }

    void succeeded(FailureKind kind) {
        streaks.clear(profileId, kind.taskKey);
    }

    LocalDateTime nextResetAt() {
        return nextReset(clock.instant());
    }

    private LocalDateTime lastReset(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay();
    }

    private LocalDateTime nextReset(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC)
                .withZoneSameInstant(clock.getZone()).toLocalDateTime();
    }

    private LocalDateTime localTime(Instant now) {
        return now.atZone(clock.getZone()).toLocalDateTime();
    }

    private LocalDateTime utcTime(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDateTime();
    }
}
