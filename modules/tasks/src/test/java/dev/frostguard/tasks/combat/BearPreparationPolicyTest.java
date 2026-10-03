package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.engine.error.BearSessionExecutionException;
import org.junit.jupiter.api.Test;

class BearPreparationPolicyTest {

    @Test
    void unavailablePreparationEvidenceWaitsForActivationWithoutInput() {
        assertTrue(BearPreparationPolicy.mayWaitForActivation(failure(
                BearSessionExecutionException.FailureKind.FATAL_CONFIGURATION,
                BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION,
                "recall-troops")));
        assertTrue(BearPreparationPolicy.mayWaitForActivation(failure(
                BearSessionExecutionException.FailureKind.FATAL_CONFIGURATION,
                BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION,
                "navigate-to-configured-bear")));
    }

    @Test
    void deviceAndActiveSessionFailuresStillEnterProtectedRecovery() {
        assertFalse(BearPreparationPolicy.mayWaitForActivation(failure(
                BearSessionExecutionException.FailureKind.DEVICE_OFFLINE,
                BearSessionExecutionException.RecoveryDirective.REBIND_DEVICE,
                "recall-troops")));
        assertFalse(BearPreparationPolicy.mayWaitForActivation(failure(
                BearSessionExecutionException.FailureKind.FATAL_CONFIGURATION,
                BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION,
                "start-own-rally")));
    }

    private static BearSessionExecutionException failure(
            BearSessionExecutionException.FailureKind kind,
            BearSessionExecutionException.RecoveryDirective directive,
            String operation) {
        return new BearSessionExecutionException(
                kind, directive, "1", operation, "test", null);
    }
}
