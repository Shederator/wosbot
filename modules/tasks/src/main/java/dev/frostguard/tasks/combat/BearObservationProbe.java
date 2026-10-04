package dev.frostguard.tasks.combat;

import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;

/** Runs the expensive read-only row analysis without exposing an input or scheduling capability. */
final class BearObservationProbe {
    private BearObservationProbe() { }

    static <T> void inspect(BearFrameStream<T> frames, BearFrameStream.Snapshot<T> frame,
            ToIntFunction<BearFrameStream.Snapshot<T>> scan, LongSupplier ticker,
            Consumer<String> diagnostics) {
        if (frame.screen() != BearNavigationPolicy.Screen.WAR_LIST) return;
        long start = ticker.getAsLong();
        int rows = scan.applyAsInt(frame);
        long elapsed = Math.max(0, ticker.getAsLong() - start);
        diagnostics.accept("observe-only-row-scan frame=" + frame.sequence()
                + " rows=" + rows + " scanMs=" + elapsed / 1_000_000
                + " decisionAgeMs=" + frames.age(frame).toMillis()
                + " withinFreshnessBudget=" + frames.isCurrent(frame,
                        BearVerifiedActionExecutor.MAXIMUM_AUTHORIZING_FRAME_AGE)
                + " inputSent=false");
    }
}
