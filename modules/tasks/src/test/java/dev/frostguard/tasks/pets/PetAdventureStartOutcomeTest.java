package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class PetAdventureStartOutcomeTest {

    @Test
    void rechecksAnUnconfirmedStartOnTheCompletionIntervalInsteadOfTheShortRetry() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 28, 12, 0);

        assertEquals(now.plusHours(2), PetAdventureChestRoutine.unverifiedStartRecheck(now));
    }
}
