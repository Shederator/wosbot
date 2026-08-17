package dev.frostguard.api.runtime;

import java.util.Locale;

public enum RuntimeChannel {
    // matt/Claude, 2026-08-17: display name only -- Bearguard's own branding. applicationId is
    // left as dev.frostguard.desktop.* on purpose: it's the Windows install/update identity,
    // untouched by the upstream sync, and renaming it is a separate decision with real
    // update-channel consequences, not a cosmetic one.
    DEVELOPMENT("Development", "Bearguard Development", "dev.frostguard.desktop.development"),
    NIGHTLY("Nightly", "Bearguard Nightly", "dev.frostguard.desktop.nightly"),
    STABLE("Stable", "Bearguard", "dev.frostguard.desktop");

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
