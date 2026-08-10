package dev.frostguard.engine.input;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Pure randomization policy used by {@link TapInteractionService}.
 *
 * <p>Repeated taps at identical pixel coordinates — or with identical
 * millisecond timing — create deterministic input patterns. This policy
 * converts every logical tap target (a detected template region, a fixed UI
 * area, or a single reference point) into a randomized-but-safe physical
 * coordinate, and every requested inter-tap delay into a randomized-but-safe
 * wait:</p>
 *
 * <ul>
 *   <li>Area targets are sampled from an inner region of the rectangle so
 *       taps stay away from the control's edges. Sampling is
 *       <em>center-weighted</em> (Bates distribution, the mean of two
 *       uniforms) rather than uniform, mimicking how human taps cluster
 *       around the middle of a control while still covering the whole
 *       inner region.</li>
 *   <li>Point targets receive bounded jitter within a caller-supplied
 *       radius, so precision-sensitive interactions remain reliable.</li>
 *   <li>Requested delays are only ever <em>lengthened</em>, never
 *       shortened, so UI-settle waits keep their contract while the exact
 *       timing stops being deterministic (see {@link #sampleDelay(int)}).</li>
 *   <li>All produced coordinates are clamped to the visible screen.</li>
 * </ul>
 *
 * <p>This is risk reduction through input-pattern variation, not a
 * guarantee of avoiding detection. The class is deliberately free of
 * emulator dependencies so it can be unit-tested in isolation.</p>
 */
public final class TapJitterPolicy {

    /** Standard emulator viewport used across the project. */
    public static final int SCREEN_WIDTH = 720;
    public static final int SCREEN_HEIGHT = 1280;

    /**
     * Fraction of the target rectangle's width/height kept as an untouched
     * margin on each side when sampling inside an area. Sampling within the
     * central portion avoids edge pixels that may fall outside the real
     * control due to template-match tolerance.
     */
    static final double AREA_EDGE_MARGIN_RATIO = 0.15;

    /** Default jitter radius (px) for point targets without an explicit radius. */
    public static final int DEFAULT_POINT_JITTER_RADIUS = 3;

    /**
     * Maximum fraction of a requested delay that may be added as timing
     * jitter. Delays are never shortened — a routine asking for a 300 ms
     * UI-settle wait always gets at least 300 ms.
     */
    static final double DELAY_JITTER_RATIO = 0.15;

    /** Absolute cap (ms) on added timing jitter so long waits do not balloon. */
    static final int DELAY_JITTER_CAP_MS = 120;

    private TapJitterPolicy() {
        // static utility
    }

    /**
     * Picks a randomized tap coordinate inside the given rectangle, keeping
     * a safety margin from the edges and weighting samples toward the
     * center of the control. Degenerate rectangles (zero or negative size)
     * collapse to their center point with default jitter.
     */
    public static PointData sampleInside(AreaData area) {
        if (area == null) {
            throw new IllegalArgumentException("area must not be null");
        }
        PointData tl = area.topLeft();
        PointData br = area.bottomRight();

        int x0 = Math.min(tl.getX(), br.getX());
        int x1 = Math.max(tl.getX(), br.getX());
        int y0 = Math.min(tl.getY(), br.getY());
        int y1 = Math.max(tl.getY(), br.getY());

        int width = x1 - x0;
        int height = y1 - y0;

        if (width <= 1 && height <= 1) {
            // Effectively a point: apply the default bounded jitter instead.
            return sampleNear(new PointData((x0 + x1) / 2, (y0 + y1) / 2), DEFAULT_POINT_JITTER_RADIUS);
        }

        int marginX = (int) Math.floor(width * AREA_EDGE_MARGIN_RATIO);
        int marginY = (int) Math.floor(height * AREA_EDGE_MARGIN_RATIO);

        int innerX0 = x0 + marginX;
        int innerX1 = x1 - marginX;
        int innerY0 = y0 + marginY;
        int innerY1 = y1 - marginY;

        // Guard against margins swallowing the whole rectangle.
        if (innerX0 > innerX1) { innerX0 = x0; innerX1 = x1; }
        if (innerY0 > innerY1) { innerY0 = y0; innerY1 = y1; }

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int x = sampleCenterWeighted(rng, innerX0, innerX1);
        int y = sampleCenterWeighted(rng, innerY0, innerY1);
        return clampToScreen(x, y);
    }

    /**
     * Picks a randomized tap coordinate within {@code radius} pixels of the
     * given point (Chebyshev distance). Radius {@code 0} returns the point
     * itself (clamped); negative radii are treated as {@code 0}.
     */
    public static PointData sampleNear(PointData point, int radius) {
        if (point == null) {
            throw new IllegalArgumentException("point must not be null");
        }
        int r = Math.max(0, radius);
        if (r == 0) {
            return clampToScreen(point.getX(), point.getY());
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int x = point.getX() + rng.nextInt(-r, r + 1);
        int y = point.getY() + rng.nextInt(-r, r + 1);
        return clampToScreen(x, y);
    }

    /**
     * Turns a requested inter-tap delay into a randomized actual delay.
     *
     * <p>The result is always {@code >= requestedMs}: routines request
     * delays as UI-settle waits, so shortening them could break flows.
     * Up to {@value #DELAY_JITTER_RATIO} of the requested delay (capped at
     * {@value #DELAY_JITTER_CAP_MS} ms) is added on top, so consecutive
     * multi-taps stop having byte-identical timing. Non-positive requests
     * are returned unchanged.</p>
     */
    public static int sampleDelay(int requestedMs) {
        if (requestedMs <= 0) {
            return requestedMs;
        }
        int maxBonus = (int) Math.min(Math.round(requestedMs * DELAY_JITTER_RATIO), DELAY_JITTER_CAP_MS);
        if (maxBonus <= 0) {
            return requestedMs;
        }
        return requestedMs + ThreadLocalRandom.current().nextInt(maxBonus + 1);
    }

    /**
     * Samples an integer in {@code [lo, hi]} (inclusive) with a triangular,
     * center-weighted distribution (Bates n=2): the mean of two independent
     * uniforms. Human taps cluster around the middle of a control; pure
     * uniform sampling over-represents the edges relative to real input.
     */
    private static int sampleCenterWeighted(ThreadLocalRandom rng, int lo, int hi) {
        if (lo >= hi) {
            return lo;
        }
        double u = (rng.nextDouble() + rng.nextDouble()) / 2.0; // in [0, 1), peak at 0.5
        int span = hi - lo;
        int offset = (int) Math.round(u * span);
        return lo + Math.min(span, Math.max(0, offset));
    }

    /** Clamps a coordinate to the visible 720x1280 viewport. */
    static PointData clampToScreen(int x, int y) {
        int cx = Math.max(0, Math.min(SCREEN_WIDTH - 1, x));
        int cy = Math.max(0, Math.min(SCREEN_HEIGHT - 1, y));
        return new PointData(cx, cy);
    }
}
