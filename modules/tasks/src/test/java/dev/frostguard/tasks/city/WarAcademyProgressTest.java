package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WarAcademyProgressTest {

    @Test
    void onlyADecreaseConfirmsRedemptionProgress() {
        assertTrue(WarAcademyRoutine.redemptionMadeProgress(5, 0));
        assertTrue(WarAcademyRoutine.redemptionMadeProgress(5, 3));
        assertFalse(WarAcademyRoutine.redemptionMadeProgress(5, 5));
        assertFalse(WarAcademyRoutine.redemptionMadeProgress(0, 0));
        assertFalse(WarAcademyRoutine.redemptionMadeProgress(5, 6));
    }
}
