package dev.frostguard.tasks.combat;

import dev.frostguard.engine.error.BearSessionExecutionException;

/** Decides whether a preparation failure may safely wait for the active event without input. */
final class BearPreparationPolicy {

    private BearPreparationPolicy() {
    }

    static boolean mayWaitForActivation(BearSessionExecutionException failure) {
        return failure != null
                && failure.failureKind()
                        == BearSessionExecutionException.FailureKind.FATAL_CONFIGURATION
                && failure.recoveryDirective()
                        == BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION
                && failure.operation() != null
                && (failure.operation().equals("disable-autojoin")
                || failure.operation().equals("recall-troops")
                || failure.operation().equals("activate-pet")
                || failure.operation().equals("navigate-to-configured-bear"));
    }
}
