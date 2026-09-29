package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class CityUpgradeScheduleTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 29, 22, 0);

    @Test
    void anEarlierConstructionSlotComesBeforeTheTrainingCountdown() {
        LocalDateTime trainingHandoff = NOW.plusHours(7).plusMinutes(41).plusSeconds(3);
        LocalDateTime constructionSlot = CityUpgradeSchedule.constructionRetry(NOW, 20);

        assertEquals(NOW.plusMinutes(20), constructionSlot);
        assertEquals(constructionSlot, CityUpgradeSchedule.earliest(trainingHandoff, constructionSlot));
    }

    @Test
    void aLongConstructionSlotUsesHalfTimeWhenThatIsEarlier() {
        LocalDateTime trainingHandoff = NOW.plusHours(7).plusMinutes(41).plusSeconds(3);
        LocalDateTime constructionSlot = CityUpgradeSchedule.constructionRetry(NOW, 40);

        assertEquals(NOW.plusMinutes(20), constructionSlot);
        assertEquals(constructionSlot, CityUpgradeSchedule.earliest(trainingHandoff, constructionSlot));
    }

    @Test
    void theTrainingCountdownWinsWhenTheOtherSlotIsLater() {
        LocalDateTime trainingHandoff = NOW.plusMinutes(10).plusSeconds(2);
        LocalDateTime constructionSlot = CityUpgradeSchedule.constructionRetry(NOW, 120);

        assertEquals(NOW.plusMinutes(60), constructionSlot);
        assertEquals(trainingHandoff, CityUpgradeSchedule.earliest(trainingHandoff, constructionSlot));
    }

    @Test
    void aShortConstructionSlotKeepsItsFullMinutes() {
        assertEquals(NOW.plusMinutes(4), CityUpgradeSchedule.constructionRetry(NOW, 4));
    }
}
