package dev.frostguard.engine.input;

import dev.frostguard.api.domain.PointData;

/**
 * Physical tap sink for {@link TapInteractionService}.
 *
 * <p>Production wiring is created inside {@code EmulatorController}, where
 * the exact-coordinate backend primitive remains private. Tests supply a
 * recording implementation so the service's orchestration behavior
 * (exact tap counts, inter-tap and trailing delays, per-tap re-sampling,
 * preemption ordering) can be verified without an emulator.</p>
 */
@FunctionalInterface
public interface TapDispatcher {

    /** Issues one physical tap at an already randomized, clamped coordinate. */
    void tap(PointData target);
}
