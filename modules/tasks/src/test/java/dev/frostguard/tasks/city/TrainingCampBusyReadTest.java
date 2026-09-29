package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import dev.frostguard.tasks.city.ConstructionBlockerRegistry.Consumer;
import dev.frostguard.tasks.city.TrainingCampBusyRead.Decision;

class TrainingCampBusyReadTest {

    @Test
    void threeExactReadsAreABusyLancerCamp() {
        Decision decision = TrainingCampBusyRead.positive("LancerCamp", "07:41:01", false);

        assertEquals(Consumer.LANCER, decision.camp());
        assertEquals(Duration.ofHours(7).plusMinutes(41).plusSeconds(1), decision.remaining());
    }

    @Test
    void twoOfThreeStayUnknown() {
        assertNull(TrainingCampBusyRead.positive("LancerCamp", "07:41:01", true));
        assertNull(TrainingCampBusyRead.positive("Sawmill", "07:41:01", false));
        assertNull(TrainingCampBusyRead.positive("LancerCamp", "3 07:4050", false));
        assertNull(TrainingCampBusyRead.positive("LancerCamp", "d 07:41:01", false));
        assertNull(TrainingCampBusyRead.positive("LancerCamp", "", false));
        assertNull(TrainingCampBusyRead.positive(null, "07:41:01", false));
    }

    @Test
    void aClockPastOneDayStillCounts() {
        assertEquals(Duration.ofHours(25).plusMinutes(10), TrainingCampBusyRead.fullClock("25:10:00"));
        assertNull(TrainingCampBusyRead.fullClock("07:61:01"));
    }
}
