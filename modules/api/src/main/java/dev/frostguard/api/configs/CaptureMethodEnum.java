package dev.frostguard.api.configs;

import java.util.Locale;

/** Persisted screenshot preference; unsupported backends retain ADB capture. */
public enum CaptureMethodEnum {
    ADB("ADB (default)"),
    MUMU_NEMU_IPC("MuMu IPC (experimental)");

    private final String label;

    CaptureMethodEnum(String label) {
        this.label = label;
    }

    public static CaptureMethodEnum parse(String value) {
        if (value == null) {
            return ADB;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return ADB;
        }
    }

    @Override
    public String toString() {
        return label;
    }
}
