package dev.frostguard.api.configs;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Defines what should happen when the bot is stopped.
 */
public enum StopBehaviorEnum {
    // Changed by pernerch | Date: 2026-07-04 | Why: keep stop behavior explicit and human-readable in GUI/Telegram settings.
    DO_NOTHING("Do Nothing"),
    CLOSE_EMULATOR("Close Emulator");

    private static final Map<String, StopBehaviorEnum> BY_NAME;

    static {
        Map<String, StopBehaviorEnum> index = new HashMap<>();
        for (StopBehaviorEnum value : values()) {
            index.put(value.name().toUpperCase(), value);
        }
        BY_NAME = Collections.unmodifiableMap(index);
    }

    private final String label;

    StopBehaviorEnum(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }

    public static StopBehaviorEnum parse(String raw) {
        if (raw == null) {
            return DO_NOTHING;
        }
        return BY_NAME.getOrDefault(raw.trim().toUpperCase(), DO_NOTHING);
    }
}