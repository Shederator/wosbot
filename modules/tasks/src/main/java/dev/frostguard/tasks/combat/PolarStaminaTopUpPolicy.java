package dev.frostguard.tasks.combat;

import dev.frostguard.engine.helper.StaminaTopUpResult;

final class PolarStaminaTopUpPolicy {

    enum Decision {
        CONTINUE,
        RETRY_SOON,
        WAIT_FOR_REGENERATION
    }

    private PolarStaminaTopUpPolicy() {}

    static Decision decide(StaminaTopUpResult result) {
        if (result.successful()) {
            return Decision.CONTINUE;
        }
        if (result.confirmedItemShortage()) {
            return Decision.WAIT_FOR_REGENERATION;
        }
        return Decision.RETRY_SOON;
    }
}
