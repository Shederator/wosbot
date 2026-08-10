package dev.frostguard.engine.input;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Orchestration tests for {@link TapInteractionService}: exact tap counts,
 * inter-tap and trailing delays, per-tap coordinate re-sampling, preemption
 * ordering, and miss handling. These encode the legacy emulator semantics
 * ({@code EmulatorInstance.tap}): exactly {@code reps} taps, sleep after
 * every tap including the final one, zero count taps nothing.
 */
class TapInteractionServiceTest {

    /** Records every dispatched coordinate. */
    private static final class RecordingDispatcher implements TapDispatcher {
        final List<PointData> taps = new ArrayList<>();

        @Override
        public void tap(PointData target) {
            taps.add(target);
        }
    }

    /** Records every sleep without actually sleeping. */
    private static final class RecordingSleeper implements TapInteractionService.Sleeper {
        final List<Integer> sleeps = new ArrayList<>();

        @Override
        public void sleep(int millis) {
            sleeps.add(millis);
        }
    }

    private static TapInteractionService service(RecordingDispatcher taps, RecordingSleeper sleeps) {
        return new TapInteractionService(taps, null, sleeps);
    }

    private static final AreaData AREA = AreaData.of(100, 200, 300, 400);
    private static final PointData POINT = new PointData(360, 640);

    // ── exact tap counts (legacy parity) ────────────────────────────

    @Test
    void zeroCountPerformsNoTaps() {
        RecordingDispatcher taps = new RecordingDispatcher();
        RecordingSleeper sleeps = new RecordingSleeper();

        // Callers pass calculated counts such as qty - 1; zero must be a no-op.
        service(taps, sleeps).tapInside(AREA, 0, 300);

        assertTrue(taps.taps.isEmpty(), "count=0 must not tap");
        assertTrue(sleeps.sleeps.isEmpty(), "count=0 must not sleep");
    }

    @Test
    void negativeCountPerformsNoTaps() {
        RecordingDispatcher taps = new RecordingDispatcher();
        service(taps, new RecordingSleeper()).tapInside(AREA, -3, 100);
        assertTrue(taps.taps.isEmpty());
    }

    @Test
    void repeatedCountPerformsExactlyThatManyTaps() {
        RecordingDispatcher taps = new RecordingDispatcher();
        service(taps, new RecordingSleeper()).tapInside(AREA, 5, 50);
        assertEquals(5, taps.taps.size());
    }

    @Test
    void zeroCountOnHitResultStillReportsTheHit() {
        RecordingDispatcher taps = new RecordingDispatcher();
        ImageSearchResultData hit = ImageSearchResultData.hit(360, 640, 95.0, 40, 20);

        boolean found = service(taps, new RecordingSleeper()).tapInside(hit, 0, 100);

        assertTrue(found, "return value reports the match, not the tap count");
        assertTrue(taps.taps.isEmpty());
    }

    // ── delay semantics (legacy parity) ─────────────────────────────

    @Test
    void singleTapWithDelayStillWaitsAfterTheTap() {
        RecordingDispatcher taps = new RecordingDispatcher();
        RecordingSleeper sleeps = new RecordingSleeper();

        // count=1, delay>0 flows (75+ call sites) use the delay as a
        // UI-settle wait before the next action — it must not be dropped.
        service(taps, sleeps).tapInside(AREA, 1, 300);

        assertEquals(1, taps.taps.size());
        assertEquals(1, sleeps.sleeps.size(), "trailing delay must be honored");
        assertTrue(sleeps.sleeps.get(0) >= 300, "delays may only be lengthened, never shortened");
    }

    @Test
    void delayIsAppliedAfterEveryTapIncludingTheLast() {
        RecordingSleeper sleeps = new RecordingSleeper();
        service(new RecordingDispatcher(), sleeps).tapNear(POINT, 3, 4, 100);

        assertEquals(4, sleeps.sleeps.size(), "legacy semantics: sleep after every tap");
        for (int ms : sleeps.sleeps) {
            assertTrue(ms >= 100 && ms <= 100 + TapJitterPolicy.DELAY_JITTER_CAP_MS);
        }
    }

    @Test
    void zeroDelayNeverSleeps() {
        RecordingSleeper sleeps = new RecordingSleeper();
        service(new RecordingDispatcher(), sleeps).tapInside(AREA, 3, 0);
        assertTrue(sleeps.sleeps.isEmpty());
    }

    // ── coordinate behavior ─────────────────────────────────────────

    @Test
    void everyRepetitionResamplesItsCoordinate() {
        RecordingDispatcher taps = new RecordingDispatcher();
        service(taps, new RecordingSleeper()).tapInside(AREA, 50, 0);

        Set<String> distinct = new HashSet<>();
        for (PointData p : taps.taps) {
            distinct.add(p.getX() + "," + p.getY());
        }
        assertTrue(distinct.size() > 5, "multi-taps must not hammer one pixel");
    }

    @Test
    void hitWithMatchedAreaTapsInsideThatRegion() {
        RecordingDispatcher taps = new RecordingDispatcher();
        ImageSearchResultData hit = ImageSearchResultData.hit(360, 640, 95.0, 100, 60);

        assertTrue(service(taps, new RecordingSleeper()).tapInside(hit));
        assertEquals(1, taps.taps.size());

        AreaData area = hit.getMatchedArea();
        PointData p = taps.taps.get(0);
        assertTrue(p.getX() >= area.topLeft().getX() && p.getX() <= area.bottomRight().getX());
        assertTrue(p.getY() >= area.topLeft().getY() && p.getY() <= area.bottomRight().getY());
    }

    @Test
    void hitWithoutDimensionsFallsBackToBoundedJitterAroundCenter() {
        RecordingDispatcher taps = new RecordingDispatcher();
        ImageSearchResultData hit = ImageSearchResultData.hit(360, 640, 95.0);

        assertTrue(service(taps, new RecordingSleeper()).tapInside(hit));
        PointData p = taps.taps.get(0);
        int r = TapJitterPolicy.DEFAULT_POINT_JITTER_RADIUS;
        assertTrue(Math.abs(p.getX() - 360) <= r);
        assertTrue(Math.abs(p.getY() - 640) <= r);
    }

    @Test
    void missIsANoOpReturningFalse() {
        RecordingDispatcher taps = new RecordingDispatcher();
        assertFalse(service(taps, new RecordingSleeper()).tapInside(ImageSearchResultData.miss()));
        assertFalse(service(taps, new RecordingSleeper()).tapInside((ImageSearchResultData) null));
        assertTrue(taps.taps.isEmpty());
    }

    // ── preemption ordering ─────────────────────────────────────────

    @Test
    void preemptionIsCheckedBeforeEveryTap() {
        RecordingDispatcher taps = new RecordingDispatcher();
        AtomicInteger checks = new AtomicInteger();
        TapInteractionService svc = new TapInteractionService(
                taps, checks::incrementAndGet, new RecordingSleeper());

        svc.tapInside(AREA, 3, 0);
        assertEquals(3, checks.get(), "one preemption check per physical tap");
    }

    @Test
    void preemptionAbortsBeforeTheBlockedTapIsDispatched() {
        RecordingDispatcher taps = new RecordingDispatcher();
        AtomicInteger checks = new AtomicInteger();
        TapInteractionService svc = new TapInteractionService(taps, () -> {
            if (checks.incrementAndGet() == 3) {
                throw new IllegalStateException("preempted");
            }
        }, new RecordingSleeper());

        assertThrows(IllegalStateException.class, () -> svc.tapInside(AREA, 5, 0));
        assertEquals(2, taps.taps.size(), "the preempted tap must not be dispatched");
    }

    // ── argument validation ─────────────────────────────────────────

    @Test
    void nullTargetsAreRejected() {
        TapInteractionService svc = service(new RecordingDispatcher(), new RecordingSleeper());
        assertThrows(IllegalArgumentException.class, () -> svc.tapInside((AreaData) null));
        assertThrows(IllegalArgumentException.class, () -> svc.tapNear(null, 3));
    }

    @Test
    void nullDispatcherIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new TapInteractionService((TapDispatcher) null, null, null));
    }
}
