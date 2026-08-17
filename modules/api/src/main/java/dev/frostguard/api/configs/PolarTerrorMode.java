package dev.frostguard.api.configs;

public enum PolarTerrorMode {
    SPECIAL_REWARDS("Special Rewards (10)"),
    UNLIMITED("Unlimited");

    private static final String LEGACY_LIMITED_VALUE = "Limited (10)";

    private final String displayName;

    PolarTerrorMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static PolarTerrorMode fromStoredValue(String value) {
        if (SPECIAL_REWARDS.displayName.equals(value) || LEGACY_LIMITED_VALUE.equals(value)) {
            return SPECIAL_REWARDS;
        }
        return UNLIMITED;
    }

    public static boolean isLegacyValue(String value) {
        return LEGACY_LIMITED_VALUE.equals(value);
    }
}
