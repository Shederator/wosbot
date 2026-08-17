package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrainingRoutineSchedulingTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 27, 4, 26, 31);

    @Test
    void upgradingQueueWakesAtEarlierTrainingCompletion() {
        LocalDateTime trainingCompletion = NOW.plusMinutes(4);
        LocalDateTime cityBuildRun = NOW.plusMinutes(7);

        LocalDateTime wakeup = TrainingRoutine.selectUpgradingWakeup(
                NOW, List.of(trainingCompletion), cityBuildRun, null);

        assertEquals(trainingCompletion, wakeup);
    }

    @Test
    void upgradingQueueWakesAtEarlierCityBuildRun() {
        LocalDateTime trainingCompletion = NOW.plusMinutes(8);
        LocalDateTime cityBuildRun = NOW.plusMinutes(3);

        LocalDateTime wakeup = TrainingRoutine.selectUpgradingWakeup(
                NOW, List.of(trainingCompletion), cityBuildRun, null);

        assertEquals(cityBuildRun, wakeup);
    }

    @Test
    void constructionHandoffCanWakeTrainingBeforeOtherEvents() {
        LocalDateTime constructionCheck = NOW.plusMinutes(2);

        LocalDateTime wakeup = TrainingRoutine.selectUpgradingWakeup(
                NOW, List.of(NOW.plusMinutes(6)), NOW.plusMinutes(5), constructionCheck);

        assertEquals(constructionCheck, wakeup);
    }

    @Test
    void tenMinuteFallbackIsUsedWhenNoFutureEventIsKnown() {
        LocalDateTime wakeup = TrainingRoutine.selectUpgradingWakeup(
                NOW, List.of(NOW.minusMinutes(1)), null, NOW);

        assertEquals(NOW.plusMinutes(10), wakeup);
    }

    @Test
    void knownEventAfterTenMinutesAvoidsPeriodicPolling() {
        LocalDateTime trainingCompletion = NOW.plusMinutes(45);

        LocalDateTime wakeup = TrainingRoutine.selectUpgradingWakeup(
                NOW, List.of(trainingCompletion), null, null);

        assertEquals(trainingCompletion, wakeup);
    }
}
