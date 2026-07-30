package dev.frostguard.tasks.alliance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AllianceChampionshipRoutineTest {

    @Test
    void parsesConfiguredFlags() {
        assertEquals(1, AllianceChampionshipRoutine.parseFlagNumber("1"));
        assertEquals(8, AllianceChampionshipRoutine.parseFlagNumber(" 8 "));
    }

    @Test
    void treatsNoFlagAsCustomTroopMix() {
        assertNull(AllianceChampionshipRoutine.parseFlagNumber("No Flag"));
        assertNull(AllianceChampionshipRoutine.parseFlagNumber(" no flag "));
        assertNull(AllianceChampionshipRoutine.parseFlagNumber(""));
        assertNull(AllianceChampionshipRoutine.parseFlagNumber(null));
    }

    @Test
    void rejectsInvalidFlags() {
        assertNull(AllianceChampionshipRoutine.parseFlagNumber("0"));
        assertNull(AllianceChampionshipRoutine.parseFlagNumber("9"));
        assertNull(AllianceChampionshipRoutine.parseFlagNumber("Squad 2"));
    }
}
