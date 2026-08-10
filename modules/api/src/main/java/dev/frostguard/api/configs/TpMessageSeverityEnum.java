package dev.frostguard.api.configs;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Severity tiers assigned to structured log entries emitted by the
 * automation engine.
 *
 * <p>Constants are declared from least to most severe so that
 * {@link #ordinal()} naturally supports threshold-based filtering.</p>
 */
public enum TpMessageSeverityEnum {

    DEBUG  (0, "\uD83D\uDD0D", "log-debug"),
    INFO   (1, "\u2139\uFE0F",  "log-info"),
    WARNING(2, "\u26A0\uFE0F",  "log-warning"),
    ERROR  (3, "\u274C",         "log-error");

    /* ---- uppercase name → constant lookup ---- */

    private static final Map<String, TpMessageSeverityEnum> NAME_MAP;

    /** The lowest severity level in the hierarchy. */
    public static final TpMessageSeverityEnum LOWEST = DEBUG;

    /** The highest severity level in the hierarchy. */
    public static final TpMessageSeverityEnum HIGHEST = ERROR;

    static {
        Map<String, TpMessageSeverityEnum> temp = new HashMap<>();
        for (TpMessageSeverityEnum level : values()) {
            temp.put(level.name().toUpperCase(), level);
        }
        NAME_MAP = Collections.unmodifiableMap(temp);
    }

    private final int numericLevel;
    private final String symbol;
    private final String cssClass;

    TpMessageSeverityEnum(int numericLevel, String symbol, String cssClass) {
        this.numericLevel = numericLevel;
        this.symbol       = symbol;
        this.cssClass     = cssClass;
    }

    /** Numeric ordering value (0 = least severe). */
    public int numericLevel()  { return numericLevel; }

    /** Unicode emoji rendered alongside the log entry. */
    public String symbol()     { return symbol; }

    /** CSS class name applied to the log row in the UI. */
    public String cssClass()   { return cssClass; }

    /** {@code true} only for unrecoverable failures. */
    public boolean isError() {
        return this == ERROR;
    }

    /** {@code true} for WARNING and ERROR levels. */
    public boolean isWarningOrAbove() {
        return numericLevel >= WARNING.numericLevel;
    }

    /** {@code true} for diagnostic trace output only. */
    public boolean isDiagnostic() {
        return this == DEBUG;
    }

    /**
     * Tests whether this severity meets or exceeds the given
     * threshold level.
     *
     * @param threshold the minimum severity to compare against
     * @return {@code true} when this level is at or above the threshold
     */
    public boolean meetsThreshold(TpMessageSeverityEnum threshold) {
        return this.numericLevel >= threshold.numericLevel;
    }

    /**
     * Returns a formatted log prefix combining the emoji symbol
     * and the level name in brackets.
     *
     * @return e.g. "🔍 [DEBUG]"
     */
    public String logPrefix() {
        return symbol + " [" + name() + "]";
    }

    /**
     * Case-insensitive lookup that falls back to {@link #INFO}
     * when the input is {@code null} or not a recognised name.
     *
     * @param text the severity name to resolve
     * @return the matching constant or {@code INFO}
     */
    public static TpMessageSeverityEnum fromString(String text) {
        if (text == null) {
            return INFO;
        }
        return NAME_MAP.getOrDefault(text.trim().toUpperCase(), INFO);
    }

    @Override
    public String toString() {
        return symbol + " " + name();
    }
}
