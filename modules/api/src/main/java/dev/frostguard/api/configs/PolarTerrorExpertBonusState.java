package dev.frostguard.api.configs;

import java.util.Arrays;

/**
 * Describes whether the Polar Terror Reward Details dialog contains the Expert Bonus section.
 */
public enum PolarTerrorExpertBonusState {
    NOT_DEFINED("Not defined"),
    EXIST("Exists"),
    MISSING("Missing"),
    NOT_CONFIRMED("Not confirmed");

    private final String displayName;

    PolarTerrorExpertBonusState(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static PolarTerrorExpertBonusState fromStoredValue(String value) {
        return Arrays.stream(values())
                .filter(state -> state.displayName.equals(value) || state.name().equals(value))
                .findFirst()
                .orElse(NOT_DEFINED);
    }
}
