package dev.frostguard.engine.schedule;

import java.time.LocalDateTime;

import dev.frostguard.engine.service.StaminaService;

/**
 * Why a task is sleeping on stamina: the minimum that makes it runnable and the
 * preferred level it chose for a natural-regeneration wake-up.
 */
public record StaminaDeferral(
        int minimumRequired,
        int regenerationTarget,
        LocalDateTime earliestRunnableAt) {

    public StaminaDeferral {
        if (minimumRequired < 0) {
            throw new IllegalArgumentException("Minimum stamina must not be negative");
        }
        if (regenerationTarget < minimumRequired) {
            throw new IllegalArgumentException("Regeneration target must cover the runnable minimum");
        }
        if (earliestRunnableAt == null) {
            throw new IllegalArgumentException("Earliest runnable time is required");
        }
    }

    public LocalDateTime revisedWakeAt(int currentStamina, LocalDateTime now) {
        LocalDateTime staminaReadyAt = currentStamina >= minimumRequired
                ? now
                : now.plusMinutes(StaminaService.minutesToRegenerate(currentStamina, regenerationTarget));
        return staminaReadyAt.isBefore(earliestRunnableAt) ? earliestRunnableAt : staminaReadyAt;
    }
}
