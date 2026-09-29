package dev.frostguard.tasks.city;

import java.time.LocalDateTime;

/** Next city-upgrade visit from a training countdown and a busy construction slot. */
final class CityUpgradeSchedule {

    private CityUpgradeSchedule() {
    }

    static LocalDateTime constructionRetry(LocalDateTime now, long minutesRemaining) {
        long wait = minutesRemaining > 30 ? minutesRemaining / 2 : minutesRemaining;
        return now.plusMinutes(wait);
    }

    static LocalDateTime earliest(LocalDateTime trainingHandoff, LocalDateTime constructionSlot) {
        return constructionSlot.isBefore(trainingHandoff) ? constructionSlot : trainingHandoff;
    }
}
