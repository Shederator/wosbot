package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.frostguard.engine.helper.StaminaTopUpResult;
import org.junit.jupiter.api.Test;

class PolarStaminaTopUpPolicyTest {

    @Test
    void successfulTopUpContinuesRally() {
        var result = new StaminaTopUpResult(
                StaminaTopUpResult.Status.TOPPED_UP, 67, null, 8, 147);

        assertEquals(PolarStaminaTopUpPolicy.Decision.CONTINUE,
                PolarStaminaTopUpPolicy.decide(result));
    }

    @Test
    void unreadableDialogRetriesSoonInsteadOfWaitingForRegeneration() {
        var result = new StaminaTopUpResult(
                StaminaTopUpResult.Status.READ_FAILED, 67, null, 8, null);

        assertEquals(PolarStaminaTopUpPolicy.Decision.RETRY_SOON,
                PolarStaminaTopUpPolicy.decide(result));
    }

    @Test
    void confirmedItemShortageWaitsForRegeneration() {
        var result = new StaminaTopUpResult(
                StaminaTopUpResult.Status.INSUFFICIENT_ITEMS, 67, 155, 8, 67);

        assertEquals(PolarStaminaTopUpPolicy.Decision.WAIT_FOR_REGENERATION,
                PolarStaminaTopUpPolicy.decide(result));
    }
}
