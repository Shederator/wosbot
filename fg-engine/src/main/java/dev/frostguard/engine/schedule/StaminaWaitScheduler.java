package dev.frostguard.engine.schedule;

import java.time.LocalDateTime;

/**
 * Scheduling boundary used by stamina gates without coupling their helper to a
 * concrete task implementation.
 */
public interface StaminaWaitScheduler {

    void reschedule(LocalDateTime retryAt);

    default void deferForStamina(int minimumRequired, int regenerationTarget, LocalDateTime retryAt) {
        deferForStamina(minimumRequired, regenerationTarget, retryAt, LocalDateTime.now());
    }

    void deferForStamina(
            int minimumRequired,
            int regenerationTarget,
            LocalDateTime retryAt,
            LocalDateTime earliestRunnableAt);
}
