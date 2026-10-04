package dev.frostguard.tasks.combat;

import dev.frostguard.engine.error.BearSessionExecutionException;
import java.util.List;
import java.util.function.Consumer;

/** Missing evidence skips only an independent step; uncertain input still aborts preparation. */
final class BearPreparationSequence {
    private BearPreparationSequence() { }

    static void run(List<Runnable> steps, Consumer<String> unavailable) {
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (BearSessionExecutionException failure) {
                if (!BearPreparationPolicy.mayWaitForActivation(failure)) throw failure;
                unavailable.accept(failure.operation());
            }
        }
    }
}
