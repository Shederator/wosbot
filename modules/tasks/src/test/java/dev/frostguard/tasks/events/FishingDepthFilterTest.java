package dev.frostguard.tasks.events;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FishingDepthFilterTest {
    @Test
    void eightyPercentNeedsTwoPlausibleReadsAndDoesNotClaimFullLine() {
        var filter = new FishingDepthFilter();
        filter.accept(depth(76), 1, 100_000_000L);
        filter.accept(depth(78), 2, 300_000_000L);
        filter.accept(depth(80), 3, 500_000_000L);
        assertFalse(filter.sufficientDescent());
        filter.accept(depth(81), 4, 700_000_000L);
        assertTrue(filter.sufficientDescent());
        assertFalse(filter.lineExhausted());
        filter.accept(depth(78), 5, 900_000_000L);
        assertTrue(filter.sufficientDescent(), "Confirmed descent survives return");
    }
    private static FishingDepthMonitor.Depth depth(int value) { return new FishingDepthMonitor.Depth(value, 100); }

    @Test
    void oneBadInitialMaximumCannotLockTheWholeSession() {
        var filter = new FishingDepthFilter();
        assertNull(filter.accept(new FishingDepthMonitor.Depth(0, 10), 1, 100_000_000L));
        assertNull(filter.accept(depth(2), 2, 300_000_000L));
        assertNull(filter.accept(depth(2), 2, 400_000_000L), "Repeated frame is not confirmation");
        assertNotNull(filter.accept(depth(4), 3, 500_000_000L));
        assertEquals(4, filter.peak());
    }

    @Test
    void initialConfirmationAlsoRequiresPlausibleDepthAndIncreasingTime() {
        var filter = new FishingDepthFilter();
        assertNull(filter.accept(depth(2), 1, 100_000_000L));
        assertNull(filter.accept(depth(99), 2, 300_000_000L));
        assertNull(filter.accept(depth(3), 3, 50_000_000L));
        assertNotNull(filter.accept(depth(4), 4, 500_000_000L));
        assertFalse(filter.lineExhausted());
    }

    @Test
    void rejectsTheMissingDigitGlitchObservedInPractice() {
        var filter = new FishingDepthFilter();
        assertNull(filter.accept(depth(37), 0, 800_000_000L));
        assertNotNull(filter.accept(depth(39), 1, 1_000_000_000L));
        assertNull(filter.accept(depth(6), 2, 1_200_000_000L));
        assertNotNull(filter.accept(depth(42), 3, 1_400_000_000L));
        assertEquals(42, filter.peak());
    }

    @Test
    void rejectsAnUnverifiedJumpToFullLine() {
        var filter = new FishingDepthFilter();
        filter.accept(depth(48), 0, 800_000_000L);
        filter.accept(depth(50), 1, 1_000_000_000L);
        assertNull(filter.accept(depth(100), 2, 1_200_000_000L));
        assertFalse(filter.lineExhausted());
        assertEquals(50, filter.peak());
    }

    @Test
    void fullLineRequiresTwoDistinctPlausibleFrames() {
        var filter = new FishingDepthFilter();
        filter.accept(depth(94), 0, 800_000_000L);
        filter.accept(depth(96), 1, 1_000_000_000L);
        filter.accept(depth(100), 2, 1_200_000_000L);
        assertFalse(filter.lineExhausted());
        assertNull(filter.accept(depth(100), 2, 1_300_000_000L));
        assertFalse(filter.lineExhausted());
        filter.accept(depth(100), 3, 1_400_000_000L);
        assertTrue(filter.lineExhausted());
    }

    @Test
    void acceptsRealReturnButRejectsChangingMaximumAndNonMonotonicTime() {
        var filter = new FishingDepthFilter();
        filter.accept(depth(58), 0, 800_000_000L);
        filter.accept(depth(60), 1, 1_000_000_000L);
        assertNotNull(filter.accept(depth(56), 2, 1_200_000_000L));
        assertNull(filter.accept(new FishingDepthMonitor.Depth(55, 550), 3, 1_400_000_000L));
        assertNull(filter.accept(depth(52), 4, 900_000_000L));
        assertNotNull(filter.accept(depth(52), 5, 1_600_000_000L));
    }
}
