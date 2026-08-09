package dev.frostguard.api.configs;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Specifies the engine's reaction when every profile has completed
 * its assigned routines and no further work is scheduled within the
 * configured idle timeout window.
 */
public enum IdleBehaviorEnum {
    KEEP_RUNNING      ("Keep Running",   false),
    CLOSE_EMULATOR    ("Close Emulator", false),
    SEND_TO_BACKGROUND("Close Game",     true),
    PC_SLEEP          ("PC Sleep",       false);

    /* ---- case-insensitive name lookup ---- */

    private static final Map<String, IdleBehaviorEnum> BY_NAME;

    static {
        Map<String, IdleBehaviorEnum> temp = new HashMap<>();
        for (IdleBehaviorEnum b : values()) {
            temp.put(b.name().toUpperCase(), b);
        }
        BY_NAME = Collections.unmodifiableMap(temp);
    }

    private final String label;
    private final boolean backgroundsApp;

    IdleBehaviorEnum(String label, boolean backgroundsApp) {
        this.label          = label;
        this.backgroundsApp = backgroundsApp;
    }

    /** Operator-facing description of this behaviour. */
    public String label() {
        return label;
    }

    /** Whether this mode keeps the game process alive in the background. */
    public boolean backgroundsApp() {
        return backgroundsApp;
    }

    /**
     * {@code true} when this mode terminates the emulator entirely
     * or puts the host machine to sleep.
     */
    public boolean terminatesSession() {
        return this == CLOSE_EMULATOR || this == PC_SLEEP;
    }

    /** Whether this mode needs a positive idle timeout before taking action. */
    public boolean requiresIdleTimeout() {
        return this != KEEP_RUNNING;
    }

    /**
     * {@code true} when this mode triggers operating system sleep.
     */
    public boolean involvesOsSleep() {
        return this == PC_SLEEP;
    }

    /* ---------- backward-compatible accessor shims ---------- */

    public String getDisplayName()          { return label; }
    public boolean shouldSendToBackground() { return backgroundsApp; }

    @Override
    public String toString() {
        return label;
    }

    /**
     * Resolves the behaviour from a stored name, yielding
     * {@link #CLOSE_EMULATOR} when the input is unrecognised or absent.
     *
     * @param raw the persisted enum name
     * @return the resolved constant
     */
    public static IdleBehaviorEnum parse(String raw) {
        if (raw == null) {
            return CLOSE_EMULATOR;
        }
        return BY_NAME.getOrDefault(raw.trim().toUpperCase(), CLOSE_EMULATOR);
    }

    /** Alias retained for backward compatibility. */
    public static IdleBehaviorEnum fromString(String name) {
        return parse(name);
    }
}
