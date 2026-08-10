package dev.frostguard.api.configs;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps persisted configuration rows to their binding scope,
 * distinguishing between instance-wide and per-profile entries.
 *
 * <p>Each constant stores a numeric identifier for database
 * persistence and a qualified name string. Cross-reference with
 * {@link ConfigScope} is provided through {@link #matches(ConfigScope)}.</p>
 */
public enum TpConfigEnum {

    PROFILE_CONFIG(2, "PROFILE_CONFIG"),
    GLOBAL_CONFIG (1, "GLOBAL_CONFIG");

    /* ---- reverse maps ---- */

    private static final Map<Integer, TpConfigEnum> LOOKUP_BY_CODE;
    private static final Map<String, TpConfigEnum> LOOKUP_BY_NAME;

    static {
        Map<Integer, TpConfigEnum> byCode = new HashMap<>();
        Map<String, TpConfigEnum> byName = new HashMap<>();
        for (TpConfigEnum entry : values()) {
            byCode.put(entry.scopeCode, entry);
            byName.put(entry.qualifiedName.toUpperCase(), entry);
        }
        LOOKUP_BY_CODE = Collections.unmodifiableMap(byCode);
        LOOKUP_BY_NAME = Collections.unmodifiableMap(byName);
    }

    private final int scopeCode;
    private final String qualifiedName;

    TpConfigEnum(int scopeCode, String qualifiedName) {
        this.scopeCode     = scopeCode;
        this.qualifiedName = qualifiedName;
    }

    /** Persistent numeric identifier stored in database rows. */
    public int scopeCode()        { return scopeCode; }

    /** Qualified textual label for this scope category. */
    public String qualifiedName() { return qualifiedName; }

    /**
     * Determines whether the supplied {@link ConfigScope} corresponds
     * to this configuration type by comparing numeric codes.
     *
     * @param scope the scope to compare against
     * @return {@code true} when both share the same numeric code
     */
    public boolean matches(ConfigScope scope) {
        return scope != null && scope.numericCode() == this.scopeCode;
    }

    /**
     * {@code true} when this entry covers application-level settings.
     */
    public boolean isGlobalEntry() {
        return this == GLOBAL_CONFIG;
    }

    /**
     * {@code true} when this entry covers per-profile settings.
     */
    public boolean isProfileEntry() {
        return this == PROFILE_CONFIG;
    }

    /**
     * Returns the corresponding {@link ConfigScope} enum constant
     * that shares the same numeric code.
     *
     * @return the matching scope
     */
    public ConfigScope toScope() {
        return ConfigScope.fromCode(this.scopeCode);
    }

    /**
     * Maps a numeric code read from persistence back to the
     * corresponding enum constant.
     *
     * @param code the stored numeric code
     * @return the resolved constant
     * @throws IllegalArgumentException when the code is unrecognised
     */
    public static TpConfigEnum fromCode(int code) {
        TpConfigEnum result = LOOKUP_BY_CODE.get(code);
        if (result == null) {
            throw new IllegalArgumentException(
                    "No TpConfigEnum maps to code " + code);
        }
        return result;
    }

    /**
     * Case-insensitive lookup by qualified name string.
     *
     * @param name the name to resolve
     * @return the matching constant, or {@code null} when not found
     */
    public static TpConfigEnum fromName(String name) {
        if (name == null) return null;
        return LOOKUP_BY_NAME.get(name.trim().toUpperCase());
    }

    /* ---------- backward-compatible accessor shims ---------- */

    public int getId()       { return scopeCode; }
    public String getName()  { return qualifiedName; }

    public int getOrdinal()  { return scopeCode; }
    public String getLabel() { return qualifiedName; }

    @Override
    public String toString() {
        return qualifiedName + "[" + scopeCode + "]";
    }
}
