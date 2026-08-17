package dev.frostguard.api.runtime;

import java.util.Locale;

public enum RuntimeChannel {
    DEVELOPMENT("Development", "Frostguard Development", "dev.frostguard.desktop.development"),
    NIGHTLY("Nightly", "Frostguard Nightly", "dev.frostguard.desktop.nightly"),
    STABLE("Stable", "Frostguard", "dev.frostguard.desktop");

    private final String displayName;
    private final String productName;
    private final String applicationId;

    RuntimeChannel(String displayName, String productName, String applicationId) {
        this.displayName = displayName;
        this.productName = productName;
        this.applicationId = applicationId;
    }

    public String directoryName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String displayName() {
        return displayName;
    }

    public String productName() {
        return productName;
    }

    public String applicationId() {
        return applicationId;
    }

    public boolean isPublicRelease() {
        return this == STABLE || this == NIGHTLY;
    }

    public RuntimeChannel alternateRelease() {
        return switch (this) {
            case STABLE -> NIGHTLY;
            case NIGHTLY -> STABLE;
            case DEVELOPMENT -> throw new IllegalStateException(
                    "Development has no public release-channel counterpart");
        };
    }

    public static RuntimeChannel from(String value) {
        if (value == null || value.isBlank()) {
            return STABLE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("Unsupported Frostguard channel: " + value);
        }
    }
}
