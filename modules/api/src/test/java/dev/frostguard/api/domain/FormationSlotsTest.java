package dev.frostguard.api.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormationSlotsTest {

    @Test
    void exposesOneSharedRangeThroughSlotTwelve() {
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), FormationSlots.numbers());
        assertEquals(List.of("No Flag", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"),
                FormationSlots.labelsWithNone("No Flag"));
        assertTrue(FormationSlots.supports(12));
        assertFalse(FormationSlots.supports(13));
    }

    @Test
    void parsesOnlySupportedFormationNumbers() {
        assertEquals(12, FormationSlots.parse(" 12 "));
        assertNull(FormationSlots.parse("0"));
        assertNull(FormationSlots.parse("13"));
        assertNull(FormationSlots.parse("Squad 9"));
        assertNull(FormationSlots.parse(null));
    }
}
