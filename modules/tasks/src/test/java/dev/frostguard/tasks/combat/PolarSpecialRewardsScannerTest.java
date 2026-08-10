package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PolarSpecialRewardsScannerTest {

    @Test
    void waitsForSwipeAnimationBeforeScanningNextPosition() {
        List<String> actions = new ArrayList<>();
        AtomicInteger scans = new AtomicInteger();

        PolarSpecialRewardsScanner.Result result = PolarSpecialRewardsScanner.scan(
                () -> {
                    actions.add("scan");
                    return scans.incrementAndGet() == 3 ? 0 : null;
                },
                () -> actions.add("swipe"),
                delay -> actions.add("wait:" + delay));

        assertEquals(PolarSpecialRewardsScanner.Result.EXHAUSTED, result);
        assertEquals(List.of(
                "scan", "swipe", "wait:1000",
                "scan", "swipe", "wait:1000",
                "scan"), actions);
    }

    @Test
    void scansOnceBeforeSwipingAndAgainAfterEverySwipe() {
        List<Long> waits = new ArrayList<>();
        AtomicInteger scans = new AtomicInteger();
        AtomicInteger swipes = new AtomicInteger();

        PolarSpecialRewardsScanner.Result result = PolarSpecialRewardsScanner.scan(
                () -> {
                    scans.incrementAndGet();
                    return null;
                },
                swipes::incrementAndGet,
                waits::add);

        assertEquals(PolarSpecialRewardsScanner.Result.NOT_FOUND, result);
        assertEquals(PolarSpecialRewardsScanner.MAX_SWIPES + 1, scans.get());
        assertEquals(PolarSpecialRewardsScanner.MAX_SWIPES, swipes.get());
        assertEquals(List.of(1000L, 1000L, 1000L, 1000L, 1000L), waits);
    }

    @Test
    void stopsWithoutSwipingWhenPositiveRewardCountIsVisible() {
        AtomicInteger swipes = new AtomicInteger();

        PolarSpecialRewardsScanner.Result result = PolarSpecialRewardsScanner.scan(
                () -> 8,
                swipes::incrementAndGet,
                ignored -> { });

        assertEquals(PolarSpecialRewardsScanner.Result.AVAILABLE, result);
        assertEquals(0, swipes.get());
    }
}
