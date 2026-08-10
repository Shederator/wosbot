package dev.frostguard.api.configs;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Discriminates between configuration entries that belong to
 * the whole application versus those scoped to an individual
 * operator profile.
 *
 * <p>Each constant carries a persisted numeric identifier and
 * a descriptive storage tag. Look-ups by code are provided
 * through {@link #fromCode(int)}.</p>
 */
public enum ConfigScope {

    ACCOUNT_SCOPE(2, "PROFILE_CONFIG"),
    GLOBAL_SCOPE (1, "GLOBAL_CONFIG");

    /* ---- reverse index built eagerly at class-load ---- */

    private static final Map<Integer, ConfigScope> REVERSE_MAP;
    private static final Map<String, ConfigScope> LABEL_MAP;

    static {
        Map<Integer, ConfigScope> byCode = new HashMap<>();
        Map<String, ConfigScope> byLabel = new HashMap<>();
        for (ConfigScope entry : values()) {
            byCode.put(entry.numericCode, entry);
            byLabel.put(entry.storageLabel.toUpperCase(), entry);
        }
        REVERSE_MAP = Collections.unmodifiableMap(byCode);
        LABEL_MAP = Collections.unmodifiableMap(byLabel);
    }

    private final int numericCode;
    private final String storageLabel;

    ConfigScope(int numericCode, String storageLabel) {
        this.numericCode  = numericCode;
        this.storageLabel = storageLabel;
    }

    /** Persistent numeric identifier written to the database. */
    public int numericCode()     { return numericCode; }

    /** Human-readable tag used in diagnostic output. */
    public String storageLabel() { return storageLabel; }

    /** {@code true} only for the application-wide scope. */
    public boolean isGlobal()     { return this == GLOBAL_SCOPE; }

    /** {@code true} only for per-profile scope. */
    public boolean isPerProfile() { return this == ACCOUNT_SCOPE; }

    /**
     * Returns the complementary scope — global becomes account-scoped
     * and vice versa.
     *
     * @return the opposite scope constant
     */
    public ConfigScope complement() {
        return this == GLOBAL_SCOPE ? ACCOUNT_SCOPE : GLOBAL_SCOPE;
    }

    /**
     * Maps a previously persisted numeric identifier back to the
     * corresponding enum constant.
     *
     * @param code the stored identifier
     * @return the resolved scope
     * @throws IllegalArgumentException if no constant matches
     */
    public static ConfigScope fromCode(int code) {
        ConfigScope found = REVERSE_MAP.get(code);
        if (found == null) {
            throw new IllegalArgumentException(
                    "No ConfigScope registered for code " + code);
        }
        return found;
    }

    /**
     * Case-insensitive lookup by storage label text.
     *
     * @param label the label string to resolve
     * @return the matching scope, or {@code null} when not found
     */
    public static ConfigScope fromLabel(String label) {
        if (label == null) return null;
        return LABEL_MAP.get(label.trim().toUpperCase());
    }

    /**
     * Safe variant of {@link #fromCode(int)} that returns a
     * fallback instead of throwing on unknown codes.
     *
     * @param code the stored identifier
     * @param fallback value to return when code is unrecognised
     * @return the resolved scope or the supplied fallback
     */
    public static ConfigScope fromCodeOrDefault(int code,
                                                ConfigScope fallback) {
        return REVERSE_MAP.getOrDefault(code, fallback);
    }

    /* ---------- backward-compatible accessor shims ---------- */

    public int getId()      { return numericCode; }
    public String getName() { return storageLabel; }

    @Override
    public String toString() {
        return storageLabel + " (" + numericCode + ")";
    }
}
