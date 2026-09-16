package dev.frostguard.tasks.events;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FishingBaitConfirmationTest {
    @Test
    void waitsPastTheObservedEntryFrameMissingItsSlash() {
        var confirmation = new FishingBaitConfirmation();
        assertEquals(-1, confirmation.accept("710", 9));
        assertEquals(-1, confirmation.accept("7/10", 27));
        assertEquals(7, confirmation.accept("7/10", 40));
    }

    @Test
    void neverUsesTheSameOrOlderFrameAsASecondConfirmation() {
        var confirmation = new FishingBaitConfirmation();
        assertEquals(-1, confirmation.accept("7/10", 10));
        assertEquals(-1, confirmation.accept("7/10", 10));
        assertEquals(-1, confirmation.accept("7/10", 9));
        assertEquals(7, confirmation.accept("7/10", 11));
    }

    @Test
    void requiresAgreementAgainAfterAnInvalidOrChangedCount() {
        var confirmation = new FishingBaitConfirmation();
        assertEquals(-1, confirmation.accept("7/10", 1));
        assertEquals(-1, confirmation.accept(null, 2));
        assertEquals(-1, confirmation.accept("7/10", 3));
        assertEquals(-1, confirmation.accept("6/10", 4));
        assertEquals(6, confirmation.accept("6/10", 5));
    }

    @Test
    void alsoRequiresTheFractionCapacityToAgree() {
        var confirmation = new FishingBaitConfirmation();
        assertEquals(-1, confirmation.accept("7/10", 1));
        assertEquals(-1, confirmation.accept("7/20", 2));
        assertEquals(7, confirmation.accept("7/20", 3));
    }

    @Test
    void allowsOnlyWhitespaceDifferencesBetweenValidFractions() {
        var confirmation = new FishingBaitConfirmation();
        assertEquals(-1, confirmation.accept(" 7 / 10 ", 1));
        assertEquals(7, confirmation.accept("7/10", 2));
    }

    @Test
    void confirmsZeroWithoutTurningMalformedDigitsIntoBait() {
        var confirmation = new FishingBaitConfirmation();
        for (int sequence = 1; sequence < 4; sequence++) assertEquals(-1, confirmation.accept("710", sequence));
        assertEquals(-1, confirmation.accept("0/10", 4));
        assertEquals(0, confirmation.accept("0/10", 5));
    }
}
