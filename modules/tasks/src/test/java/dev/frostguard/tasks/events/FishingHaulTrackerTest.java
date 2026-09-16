package dev.frostguard.tasks.events;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FishingHaulTrackerTest {
    @Test
    void observesPracticeAndRealCapacityWithoutHardcodingTwenty() {
        for (int limit : new int[] {10, 20}) {
            var tracker = new FishingHaulTracker();
            assertNull(tracker.accept("0/" + limit, 1, 100));
            assertEquals(limit, tracker.accept("0/" + limit, 2, 200).freeSlots());
            assertEquals(1, tracker.accept((limit - 1) + "/" + limit, 3, 300).freeSlots());
        }
    }

    @Test
    void fullNeedsTwoDistinctReadsAndCannotBeConfirmedByDuplicates() {
        var tracker = new FishingHaulTracker();
        tracker.accept("19/20", 1, 100);
        assertFalse(tracker.accept("20/20", 2, 200).full());
        assertNull(tracker.accept("20/20", 2, 200));
        assertNull(tracker.accept("20/20", 3, 150));
        assertTrue(tracker.accept("20/20", 4, 400).full());
    }

    @Test
    void rejectsRegressionChangingConfirmedCapacityAndMalformedReads() {
        var tracker = new FishingHaulTracker();
        tracker.accept("5/20", 1, 100);
        tracker.accept("6/20", 2, 200);
        for (String text : new String[] {"5/20", "7/10", "21/20", "0/0", "abc", "1/999999999999999"}) {
            assertNull(tracker.accept(text, 3, 300));
        }
        assertEquals(13, tracker.accept("7/20", 4, 400).freeSlots());
    }
}
