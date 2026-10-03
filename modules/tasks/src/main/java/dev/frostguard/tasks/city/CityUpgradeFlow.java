package dev.frostguard.tasks.city;

import dev.frostguard.tasks.diagnostics.TaskControlSignals;

/** Keeps failure recovery separate from construction success and scheduler control signals. */
final class CityUpgradeFlow {
    private static final int MAX_ATTEMPTS = 2;

    enum FailureReason {
        CONTROL_NOT_RECOGNIZED,
        RESOURCES_NOT_OBTAINED,
        CONFIRMATION_NOT_FOUND,
        HOME_TRANSITION_NOT_CONFIRMED
    }

    record Attempt<T>(T result, FailureReason failure) {
        static <T> Attempt<T> completed(T result) {
            return new Attempt<>(result, null);
        }

        static <T> Attempt<T> unresolved(FailureReason reason) {
            return new Attempt<>(null, reason);
        }
    }

    interface QueueUi<T> {
        Attempt<T> attempt(int number);
        void retainFailure(int number, FailureReason reason);
        void recoverHome();
    }

    interface Recovery {
        void retainFailure(String type);
        void recoverRoot();
        void reportRecovery(boolean recovered, RuntimeException original, RuntimeException recoveryFailure);
    }

    private CityUpgradeFlow() {}

    static <T> T handleQueue(int queue, QueueUi<T> ui) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Attempt<T> result = ui.attempt(attempt);
            if (result.failure() == null) {
                // Also closes production/Speedup panels before the caller processes another queue.
                ui.recoverHome();
                return result.result();
            }
            ui.retainFailure(attempt, result.failure());
            if (attempt == MAX_ATTEMPTS) {
                throw new UnresolvedBuildingException(queue, attempt, result.failure());
            }
            try {
                ui.recoverHome();
            } catch (RuntimeException recoveryFailure) {
                recoveryFailure.addSuppressed(new UnresolvedBuildingException(queue, attempt, result.failure()));
                throw recoveryFailure;
            }
        }
        throw new IllegalStateException("Missing City Upgrade outcome");
    }

    static void execute(Runnable action, Recovery recovery) {
        try {
            action.run();
        } catch (RuntimeException original) {
            rethrowControlSignal(original);
            if (!(original instanceof UnresolvedBuildingException)) {
                recovery.retainFailure("unexpected-failure");
            }
            try {
                recovery.recoverRoot();
            } catch (RuntimeException recoveryFailure) {
                if (recoveryFailure != original) {
                    recoveryFailure.addSuppressed(original);
                }
                rethrowControlSignal(recoveryFailure);
                recovery.retainFailure("root-recovery-failed");
                recovery.reportRecovery(false, original, recoveryFailure);
                throw recoveryFailure;
            }
            recovery.reportRecovery(true, original, null);
            throw original;
        }
    }

    public static void rethrowControlSignal(Throwable failure) {
        TaskControlSignals.rethrowControlSignal(failure);
    }

    static final class UnresolvedBuildingException extends IllegalStateException {
        UnresolvedBuildingException(int queue, int attempts, FailureReason reason) {
            super("City Upgrade unresolved: queue=" + queue + "; attempts=" + attempts + "; reason=" + reason);
        }
    }
}
