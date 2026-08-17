package dev.frostguard.app.bootstrap;

import java.util.ArrayList;
import java.util.List;

final class RuntimeShutdownCoordinator {
    private final List<Step> steps;

    RuntimeShutdownCoordinator(List<Step> steps) {
        this.steps = List.copyOf(steps);
    }

    void shutdown() throws ShutdownException {
        List<String> failures = new ArrayList<>();
        for (Step step : steps) {
            try {
                step.action().run();
            } catch (Exception exception) {
                String message = exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage();
                failures.add(step.name() + ": " + message);
            }
        }
        if (!failures.isEmpty()) {
            throw new ShutdownException(failures);
        }
    }

    record Step(String name, ShutdownAction action) {
        Step {
            if (name == null || name.isBlank() || action == null) {
                throw new IllegalArgumentException("Shutdown steps require a name and action");
            }
        }
    }

    @FunctionalInterface
    interface ShutdownAction {
        void run() throws Exception;
    }

    static final class ShutdownException extends Exception {
        private final List<String> failures;

        ShutdownException(List<String> failures) {
            super("Runtime shutdown was incomplete: " + String.join("; ", failures));
            this.failures = List.copyOf(failures);
        }

        List<String> failures() {
            return failures;
        }
    }
}
