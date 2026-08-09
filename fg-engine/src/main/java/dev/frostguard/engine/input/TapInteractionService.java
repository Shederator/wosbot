package dev.frostguard.engine.input;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.emulator.EmulatorController;

/**
 * Single shared interaction layer for all production tap input.
 *
 * <p>No task, engine helper, service, or other application component should
 * issue taps through the low-level emulator primitives
 * exposed by the emulator backend. Instead, callers use one of the
 * intent-revealing methods here:</p>
 *
 * <ul>
 *   <li>{@link #tapInside(ImageSearchResultData)} — tap a detected template,
 *       randomized within its actual matched bounding region.</li>
 *   <li>{@link #tapInside(AreaData)} — tap a known safe UI area.</li>
 *   <li>{@link #tapNear(PointData, int)} — precision tap with an explicitly
 *       bounded jitter radius (for small controls / minigames).</li>
 *   <li>Repeated variants with count and delay for multi-tap flows.</li>
 * </ul>
 *
 * <p>This layer consistently applies coordinate randomization
 * ({@link TapJitterPolicy}), cooperative preemption checks, coordinate
 * clamping, and repeated-tap timing. Every repeated tap re-samples its
 * coordinate, so multi-taps do not hammer a single pixel; inter-tap delays
 * receive bounded upward jitter so multi-tap timing is not byte-identical.
 * Architecture conformance is enforced by {@code TapInputArchitectureTest}.</p>
 *
 * <h2>Repeat semantics (legacy parity)</h2>
 * <p>The behavior matches the original emulator implementation exactly:</p>
 * <ul>
 *   <li>{@code count} taps are performed — {@code count <= 0} performs
 *       <strong>no</strong> taps. Callers pass calculated counts such as
 *       {@code qty - 1}, so a zero count must be a no-op.</li>
 *   <li>The delay is applied after <strong>every</strong> tap, including
 *       the final one. Existing flows use the trailing delay as a
 *       UI-settle wait before the next action (e.g. {@code count=1,
 *       delay=300} means "tap, then give the UI 300 ms").</li>
 * </ul>
 */
public class TapInteractionService {

    /** Hook invoked before every physical tap (cooperative preemption). */
    @FunctionalInterface
    public interface PreTapCheck {
        void check();
    }

    /** Sleep strategy; injectable so orchestration tests run instantly. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(int millis) throws InterruptedException;
    }

    private static final PreTapCheck NO_OP = () -> { };
    private static final Sleeper THREAD_SLEEP = Thread::sleep;

    private final TapDispatcher dispatcher;
    private final PreTapCheck preTapCheck;
    private final Sleeper sleeper;

    // ── construction ────────────────────────────────────────────────

    /** Production wiring through the controller's randomized interaction capability. */
    public static TapInteractionService forController(EmulatorController controller, String emulatorNumber) {
        return forController(controller, emulatorNumber, NO_OP);
    }

    /** Production wiring with a cooperative preemption hook. */
    public static TapInteractionService forController(
            EmulatorController controller,
            String emulatorNumber,
            PreTapCheck preTapCheck) {
        if (controller != null) {
            return controller.tapInteractions(emulatorNumber, preTapCheck);
        }
        return new TapInteractionService(target -> {
            throw new IllegalStateException("No emulator controller available for tap dispatch");
        }, preTapCheck, THREAD_SLEEP);
    }

    /**
     * Full wiring for tests: a recording {@link TapDispatcher}, an optional
     * preemption hook, and an optional instant {@link Sleeper}.
     */
    public TapInteractionService(TapDispatcher dispatcher, PreTapCheck preTapCheck, Sleeper sleeper) {
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher must not be null");
        this.dispatcher = dispatcher;
        this.preTapCheck = preTapCheck != null ? preTapCheck : NO_OP;
        this.sleeper = sleeper != null ? sleeper : THREAD_SLEEP;
    }

    // ── detected elements ───────────────────────────────────────────

    /**
     * Taps a randomized coordinate inside the actual matched bounding region
     * of a template-search result. Falls back to bounded jitter around the
     * match center when the result does not carry region dimensions
     * (e.g. legacy producers).
     *
     * @return {@code true} when the result was a hit (even if {@code count}
     *         was zero), {@code false} for misses.
     */
    public boolean tapInside(ImageSearchResultData result) {
        return tapInside(result, 1, 0);
    }

    /** Repeated variant of {@link #tapInside(ImageSearchResultData)}. */
    public boolean tapInside(ImageSearchResultData result, int count, int delayMs) {
        if (result == null || !result.isFound()) {
            return false;
        }
        AreaData matched = result.getMatchedArea();
        if (matched != null) {
            tapInside(matched, count, delayMs);
        } else {
            tapNear(result.getPoint(), TapJitterPolicy.DEFAULT_POINT_JITTER_RADIUS, count, delayMs);
        }
        return true;
    }

    // ── known UI areas ──────────────────────────────────────────────

    /** Taps a randomized coordinate inside a known safe UI area. */
    public void tapInside(AreaData area) {
        tapInside(area, 1, 0);
    }

    /** Repeated variant of {@link #tapInside(AreaData)}; re-samples each tap. */
    public void tapInside(AreaData area, int count, int delayMs) {
        if (area == null) throw new IllegalArgumentException("area must not be null");
        repeatTaps(count, delayMs, () -> TapJitterPolicy.sampleInside(area));
    }

    // ── precision points ────────────────────────────────────────────

    /**
     * Taps within the default jitter radius
     * ({@link TapJitterPolicy#DEFAULT_POINT_JITTER_RADIUS} px) of the given
     * point. This is the standard replacement for legacy fixed-coordinate
     * taps; prefer {@link #tapInside(AreaData)} whenever a safe area is known.
     */
    public void tapNear(PointData point) {
        tapNear(point, TapJitterPolicy.DEFAULT_POINT_JITTER_RADIUS, 1, 0);
    }

    /**
     * Taps within {@code radius} pixels of the given point. Use a small
     * radius (or {@code 0}) for precision-sensitive interactions such as
     * minigames; prefer {@link #tapInside(AreaData)} whenever a safe area
     * is known.
     */
    public void tapNear(PointData point, int radius) {
        tapNear(point, radius, 1, 0);
    }

    /** Repeated variant of {@link #tapNear(PointData, int)}; re-samples each tap. */
    public void tapNear(PointData point, int radius, int count, int delayMs) {
        if (point == null) throw new IllegalArgumentException("point must not be null");
        repeatTaps(count, delayMs, () -> TapJitterPolicy.sampleNear(point, radius));
    }

    // ── internals ───────────────────────────────────────────────────

    @FunctionalInterface
    private interface CoordinateSampler {
        PointData sample();
    }

    /**
     * Legacy-parity orchestration: exactly {@code count} taps
     * ({@code count <= 0} taps nothing), delay after every tap including
     * the last, coordinate re-sampled per tap, delay jittered upward only.
     */
    private void repeatTaps(int count, int delayMs, CoordinateSampler sampler) {
        for (int i = 0; i < count; i++) {
            preTapCheck.check();
            dispatcher.tap(sampler.sample());
            if (delayMs > 0) {
                sleepQuietly(TapJitterPolicy.sampleDelay(delayMs));
            }
        }
    }

    private void sleepQuietly(int delayMs) {
        try {
            sleeper.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Tap sequence interrupted", ie);
        }
    }
}
