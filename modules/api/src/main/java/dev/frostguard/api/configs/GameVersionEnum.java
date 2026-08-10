package dev.frostguard.api.configs;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Identifies the regional variant of the game client. Each region
 * is uniquely distinguished by its Android application identifier
 * which the engine uses when launching via emulator.
 */
public enum GameVersionEnum {

    CHINA ("China",  "com.gof.china"),
    GLOBAL("Global", "com.gof.global");

    /* ---- reverse lookups ---- */

    private static final Map<String, GameVersionEnum> BY_PACKAGE;
    private static final Map<String, GameVersionEnum> BY_REGION;

    static {
        Map<String, GameVersionEnum> pkgMap = new HashMap<>();
        Map<String, GameVersionEnum> regionMap = new HashMap<>();
        for (GameVersionEnum variant : values()) {
            pkgMap.put(variant.androidPackageId, variant);
            regionMap.put(variant.regionName.toUpperCase(), variant);
        }
        BY_PACKAGE = Collections.unmodifiableMap(pkgMap);
        BY_REGION = Collections.unmodifiableMap(regionMap);
    }

    private final String regionName;
    private final String androidPackageId;

    GameVersionEnum(String regionName, String androidPackageId) {
        this.regionName       = regionName;
        this.androidPackageId = androidPackageId;
    }

    /** Operator-facing name of the game region. */
    public String regionName()       { return regionName; }

    /** Android package identifier used for emulator app launch. */
    public String androidPackageId() { return androidPackageId; }

    /**
     * {@code true} when this constant represents the international
     * (default) game build.
     */
    public boolean isDefault() {
        return this == GLOBAL;
    }

    /**
     * Tests whether the supplied package identifier belongs to
     * this particular game version.
     *
     * @param pkg the android package to test
     * @return {@code true} on an exact match
     */
    public boolean matchesPackage(String pkg) {
        return androidPackageId.equals(pkg);
    }

    /**
     * Resolves a region by the Android package identifier found on
     * the emulator. Returns {@link #GLOBAL} when the supplied value
     * is {@code null} or unrecognised.
     *
     * @param pkg the application identifier string
     * @return the matching variant or {@code GLOBAL} as fallback
     */
    public static GameVersionEnum fromPackage(String pkg) {
        if (pkg == null) {
            return GLOBAL;
        }
        return BY_PACKAGE.getOrDefault(pkg.trim(), GLOBAL);
    }

    /**
     * Case-insensitive lookup by the human-readable region name.
     *
     * @param region the display name to resolve (e.g. "China", "Global")
     * @return the matching version, or {@link #GLOBAL} as fallback
     */
    public static GameVersionEnum fromRegionName(String region) {
        if (region == null) {
            return GLOBAL;
        }
        return BY_REGION.getOrDefault(region.trim().toUpperCase(), GLOBAL);
    }

    /** Returns the total number of supported game regions. */
    public static int regionCount() {
        return values().length;
    }

    /* ---------- backward-compatible accessor shims ---------- */

    public String label()          { return regionName; }
    public String appPackage()     { return androidPackageId; }
    public String getDisplayName() { return regionName; }
    public String getPackageName() { return androidPackageId; }

    @Override
    public String toString() {
        return regionName + " [" + androidPackageId + "]";
    }
}
