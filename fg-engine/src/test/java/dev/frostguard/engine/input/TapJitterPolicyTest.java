package dev.frostguard.engine.input;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the coordinate randomization policy backing the
 * centralized tap-input layer.
 */
class TapJitterPolicyTest {

    private static final int SAMPLES = 500;

    // ── sampleInside(AreaData) ──────────────────────────────────────

    @Test
    void sampleInsideStaysWithinAreaWithEdgeMargin() {
        AreaData area = AreaData.of(100, 200, 300, 400); // 200x200
        int marginX = (int) Math.floor(200 * TapJitterPolicy.AREA_EDGE_MARGIN_RATIO);
        int marginY = (int) Math.floor(200 * TapJitterPolicy.AREA_EDGE_MARGIN_RATIO);

        for (int i = 0; i < SAMPLES; i++) {
            PointData p = TapJitterPolicy.sampleInside(area);
            assertTrue(p.getX() >= 100 + marginX && p.getX() <= 300 - marginX,
                    "x " + p.getX() + " outside inner region");
            assertTrue(p.getY() >= 200 + marginY && p.getY() <= 400 - marginY,
                    "y " + p.getY() + " outside inner region");
        }
    }

    @Test
    void sampleInsideProducesVariedCoordinates() {
        AreaData area = AreaData.of(0, 0, 200, 200);
        Set<String> distinct = new HashSet<>();
        for (int i = 0; i < SAMPLES; i++) {
            PointData p = TapJitterPolicy.sampleInside(area);
            distinct.add(p.getX() + "," + p.getY());
        }
        assertTrue(distinct.size() > 10,
                "expected varied coordinates, got " + distinct.size() + " distinct values");
    }

    @Test
    void sampleInsideHandlesInvertedCorners() {
        AreaData inverted = new AreaData(new PointData(300, 400), new PointData(100, 200));
        for (int i = 0; i < SAMPLES; i++) {
            PointData p = TapJitterPolicy.sampleInside(inverted);
            assertTrue(p.getX() >= 100 && p.getX() <= 300);
            assertTrue(p.getY() >= 200 && p.getY() <= 400);
        }
    }

    @Test
    void degeneratePointAreaFallsBackToBoundedJitter() {
        AreaData pointArea = AreaData.of(360, 640, 360, 640);
        int r = TapJitterPolicy.DEFAULT_POINT_JITTER_RADIUS;
        for (int i = 0; i < SAMPLES; i++) {
            PointData p = TapJitterPolicy.sampleInside(pointArea);
            assertTrue(Math.abs(p.getX() - 360) <= r, "x jitter exceeded radius");
            assertTrue(Math.abs(p.getY() - 640) <= r, "y jitter exceeded radius");
        }
    }

    @Test
    void sampleInsideRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> TapJitterPolicy.sampleInside(null));
    }

    // ── sampleNear(PointData, int) ──────────────────────────────────

    @Test
    void sampleNearStaysWithinRadius() {
        PointData center = new PointData(360, 640);
        for (int i = 0; i < SAMPLES; i++) {
            PointData p = TapJitterPolicy.sampleNear(center, 10);
            assertTrue(Math.abs(p.getX() - 360) <= 10);
            assertTrue(Math.abs(p.getY() - 640) <= 10);
        }
    }

    @Test
    void sampleNearWithZeroRadiusReturnsExactPoint() {
        PointData p = TapJitterPolicy.sampleNear(new PointData(50, 60), 0);
        assertEquals(50, p.getX());
        assertEquals(60, p.getY());
    }

    @Test
    void negativeRadiusTreatedAsZero() {
        PointData p = TapJitterPolicy.sampleNear(new PointData(50, 60), -5);
        assertEquals(50, p.getX());
        assertEquals(60, p.getY());
    }

    @Test
    void sampleNearRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> TapJitterPolicy.sampleNear(null, 5));
    }

    // ── sampleDelay(int) ────────────────────────────────────────────

    @Test
    void sampledDelayIsNeverShorterThanRequested() {
        for (int i = 0; i < SAMPLES; i++) {
            assertTrue(TapJitterPolicy.sampleDelay(300) >= 300,
                    "requested delays are UI-settle waits and must not shrink");
        }
    }

    @Test
    void sampledDelayJitterIsBoundedByRatioAndCap() {
        int ratioBound = (int) Math.round(300 * TapJitterPolicy.DELAY_JITTER_RATIO);
        for (int i = 0; i < SAMPLES; i++) {
            assertTrue(TapJitterPolicy.sampleDelay(300) <= 300 + ratioBound);
            // Large delays are capped by the absolute bound, not the ratio.
            assertTrue(TapJitterPolicy.sampleDelay(60_000)
                    <= 60_000 + TapJitterPolicy.DELAY_JITTER_CAP_MS);
        }
    }

    @Test
    void sampledDelayVariesAcrossCalls() {
        Set<Integer> distinct = new HashSet<>();
        for (int i = 0; i < SAMPLES; i++) {
            distinct.add(TapJitterPolicy.sampleDelay(500));
        }
        assertTrue(distinct.size() > 5,
                "expected varied delays, got " + distinct.size() + " distinct values");
    }

    @Test
    void nonPositiveDelaysPassThroughUnchanged() {
        assertEquals(0, TapJitterPolicy.sampleDelay(0));
        assertEquals(-5, TapJitterPolicy.sampleDelay(-5));
    }

    // ── clamping ────────────────────────────────────────────────────

    @Test
    void coordinatesAreClampedToScreen() {
        for (int i = 0; i < SAMPLES; i++) {
            PointData nearEdge = TapJitterPolicy.sampleNear(new PointData(0, 0), 20);
            assertTrue(nearEdge.getX() >= 0 && nearEdge.getY() >= 0);

            PointData farEdge = TapJitterPolicy.sampleNear(
                    new PointData(TapJitterPolicy.SCREEN_WIDTH - 1, TapJitterPolicy.SCREEN_HEIGHT - 1), 20);
            assertTrue(farEdge.getX() < TapJitterPolicy.SCREEN_WIDTH);
            assertTrue(farEdge.getY() < TapJitterPolicy.SCREEN_HEIGHT);
        }
    }
}
