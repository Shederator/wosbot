package dev.frostguard.engine.schedule;

import java.util.Arrays;
import java.util.Optional;

/**
 * Declares the expected in-game screen state that should be active
 * before a task begins execution.
 */
public enum LaunchPoint {

    HOME("Base City", true),
    WORLD("World Map", true),
    ANY("Indifferent", false);

    private final String screenLabel;
    private final boolean navigationRequired;

    LaunchPoint(String screenLabel, boolean navigationRequired) {
        this.screenLabel = screenLabel;
        this.navigationRequired = navigationRequired;
    }

    /** Short human-readable label for the required screen. */
    public String describeScreen() { return screenLabel; }

    /** {@code true} when the scheduler must navigate before launch. */
    public boolean requiresNavigation() { return navigationRequired; }

    /** Resolves a launch point by case-insensitive name, or empty. */
    public static Optional<LaunchPoint> fromName(String name) {
        if (name == null) return Optional.empty();
        return Arrays.stream(values())
                .filter(lp -> lp.name().equalsIgnoreCase(name.trim()))
                .findFirst();
    }

    /** Returns {@link #ANY} when the name cannot be resolved. */
    public static LaunchPoint fromNameOrDefault(String name) {
        return fromName(name).orElse(ANY);
    }
}
