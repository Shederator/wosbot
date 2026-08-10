package dev.frostguard.engine.helper;

/**
 * Numeric evidence read from the final deployment screen after the lineup has
 * been selected.
 */
public record DeploymentScreenRead(
        long travelTimeSeconds,
        int staminaCost,
        boolean staminaCostFallback) {

    public DeploymentScreenRead {
        if (travelTimeSeconds < 0) {
            throw new IllegalArgumentException("Travel time must not be negative");
        }
        if (staminaCost < 1) {
            throw new IllegalArgumentException("Stamina cost must be positive");
        }
    }
}
