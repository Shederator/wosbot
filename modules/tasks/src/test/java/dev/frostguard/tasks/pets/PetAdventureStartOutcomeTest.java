package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;

class PetAdventureStartOutcomeTest {

    @Test
    void rechecksAnUnconfirmedStartOnTheCompletionIntervalInsteadOfTheShortRetry() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 28, 12, 0);

        assertEquals(now.plusHours(2), PetAdventureChestRoutine.unverifiedStartRecheck(now));
    }

    @Test
    void treatsATimerUnderThePinAsAnOccupiedChest() {
        PointData chest = new PointData(180, 360);
        ImageSearchResultData timer = ImageSearchResultData.hit(175, 450, 95);
        assertTrue(PetAdventureDecisions.occupiedByTimer(chest, List.of(timer)));
    }

    @Test
    void leavesAChestIdleWhenTheTimerBelongsToAnotherPin() {
        PointData chest = new PointData(600, 700);
        ImageSearchResultData timer = ImageSearchResultData.hit(175, 450, 95);
        assertFalse(PetAdventureDecisions.occupiedByTimer(chest, List.of(timer)));
    }
}
