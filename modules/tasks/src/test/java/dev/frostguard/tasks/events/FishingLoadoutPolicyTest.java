package dev.frostguard.tasks.events;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FishingLoadoutPolicyTest {
    @Test
    void boosterCastBudgetDoesNotEnableUnsupportedItemsOrUnboundedSpending() {
        for (var item : FishingLoadoutPolicy.Item.values()) {
            assertFalse(FishingLoadoutPolicy.wants(item, true, true, 2, 2));
            assertFalse(FishingLoadoutPolicy.wants(item, false, false, 0, 2));
        }
        assertTrue(FishingLoadoutPolicy.wants(FishingLoadoutPolicy.Item.LANTERN, true, false, 0, 2));
        assertTrue(FishingLoadoutPolicy.wants(FishingLoadoutPolicy.Item.STABILIZER, false, true, 0, 2));
        assertFalse(FishingLoadoutPolicy.wants(FishingLoadoutPolicy.Item.HORN, true, true, 0, 2));
        assertFalse(FishingLoadoutPolicy.wants(FishingLoadoutPolicy.Item.SCANNER, true, true, 0, 2));
        for (Integer invalid : new Integer[] {null, -1, 11, Integer.MAX_VALUE})
            assertEquals(0, FishingSessionPolicy.specialCastLimit(invalid));
    }

    @Test
    void inventoryMustBeAnUnambiguousNonnegativeCount() {
        assertEquals(37, FishingLoadoutPolicy.count("37"));
        assertEquals(0, FishingLoadoutPolicy.count("0"));
        for (String invalid : new String[] {null, "", "-1", "3/7", "10000", "Purchase"})
            assertEquals(-1, FishingLoadoutPolicy.count(invalid));
    }
}
