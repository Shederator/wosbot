package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CrystalClaimLoopTest {

    @Test
    void requiresThreeConsecutiveMissesAndResetsAfterAHit() {
        Queue<Boolean> detections = new ArrayDeque<>(
                List.of(true, false, false, true, false, false, false));
        AtomicInteger attempts = new AtomicInteger();

        CrystalClaimLoop.Result result = CrystalClaimLoop.collect(
                () -> {
                    attempts.incrementAndGet();
                    return detections.remove();
                },
                () -> { },
                3);

        assertEquals(2, result.claims());
        assertEquals(3, result.consecutiveMisses());
        assertEquals(7, attempts.get());
    }

    @Test
    void checksThreeTimesBeforeReportingZeroClaims() {
        AtomicInteger attempts = new AtomicInteger();

        CrystalClaimLoop.Result result = CrystalClaimLoop.collect(
                () -> {
                    attempts.incrementAndGet();
                    return false;
                },
                () -> { },
                3);

        assertEquals(0, result.claims());
        assertEquals(3, result.consecutiveMisses());
        assertEquals(3, attempts.get());
    }

    @Test
    void stopsIfAStaleButtonNeverDisappears() {
        AtomicInteger attempts = new AtomicInteger();

        CrystalClaimLoop.Result result = CrystalClaimLoop.collect(
                () -> {
                    attempts.incrementAndGet();
                    return true;
                },
                () -> { },
                3);

        assertEquals(CrystalClaimLoop.MAX_TOTAL_ATTEMPTS, result.claims());
        assertEquals(CrystalClaimLoop.MAX_TOTAL_ATTEMPTS, attempts.get());
        assertTrue(result.attemptLimitReached());
    }
}
