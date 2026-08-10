package dev.frostguard.data.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes every building slot tracked within a profile's city
 * layout. Each constant carries a {@link StructureRole}, a display
 * label, and an upgrade eligibility flag so that UI panels and
 * automated upgrade routines can filter by purpose.
 */
public enum BuildingType {

    /* ── core ── */
    FURNACE    (StructureRole.CORE,        "Furnace",      true),

    /* ── support ── */
    COOKHOUSE  (StructureRole.SUPPORT,     "Cookhouse",    true),
    CLINIC     (StructureRole.SUPPORT,     "Clinic",       true),

    /* ── production ── */
    SAWMILL    (StructureRole.PRODUCTION,  "Sawmill",      true),
    HUNTERS_HUT(StructureRole.PRODUCTION,  "Hunter's Hut", true),
    COAL_MINE  (StructureRole.PRODUCTION,  "Coal Mine",    true),
    IRON_MINE  (StructureRole.PRODUCTION,  "Iron Mine",    true),

    /* ── residential shelters ── */
    SHELTER1   (StructureRole.RESIDENTIAL, "Shelter 1",    true),
    SHELTER2   (StructureRole.RESIDENTIAL, "Shelter 2",    true),
    SHELTER3   (StructureRole.RESIDENTIAL, "Shelter 3",    true),
    SHELTER4   (StructureRole.RESIDENTIAL, "Shelter 4",    true),
    SHELTER5   (StructureRole.RESIDENTIAL, "Shelter 5",    true),
    SHELTER6   (StructureRole.RESIDENTIAL, "Shelter 6",    true),
    SHELTER7   (StructureRole.RESIDENTIAL, "Shelter 7",    true),
    SHELTER8   (StructureRole.RESIDENTIAL, "Shelter 8",    true);

    /**
     * Functional classification applied to city structures.
     */
    public enum StructureRole {
        CORE, SUPPORT, PRODUCTION, RESIDENTIAL
    }

    /* ---- case-insensitive label lookup ---- */

    private static final Map<String, BuildingType> BY_LABEL;

    static {
        Map<String, BuildingType> temp = new HashMap<>();
        for (BuildingType bt : values()) {
            temp.put(bt.displayLabel.toLowerCase(), bt);
        }
        BY_LABEL = Collections.unmodifiableMap(temp);
    }

    /* ---- pre-computed shelter subset ---- */

    private static final List<BuildingType> ALL_SHELTERS;

    /* ---- per-role pre-built grouping ---- */

    private static final Map<StructureRole, List<BuildingType>> BY_ROLE;

    /** Total number of upgradeable buildings. */
    public static final int UPGRADEABLE_COUNT;

    static {
        List<BuildingType> shelters = new ArrayList<>();
        Map<StructureRole, List<BuildingType>> roleMap = new java.util.EnumMap<>(StructureRole.class);
        for (StructureRole r : StructureRole.values()) {
            roleMap.put(r, new ArrayList<>());
        }
        int upgradeableSum = 0;
        for (BuildingType bt : values()) {
            roleMap.get(bt.role).add(bt);
            if (bt.isShelter()) {
                shelters.add(bt);
            }
            if (bt.upgradeable) {
                upgradeableSum++;
            }
        }
        ALL_SHELTERS = Collections.unmodifiableList(shelters);
        UPGRADEABLE_COUNT = upgradeableSum;
        Map<StructureRole, List<BuildingType>> immutable =
                new java.util.EnumMap<>(StructureRole.class);
        for (var entry : roleMap.entrySet()) {
            immutable.put(entry.getKey(),
                    Collections.unmodifiableList(entry.getValue()));
        }
        BY_ROLE = Collections.unmodifiableMap(immutable);
    }

    private final StructureRole role;
    private final String displayLabel;
    private final boolean upgradeable;

    BuildingType(StructureRole role, String displayLabel,
                 boolean upgradeable) {
        this.role         = role;
        this.displayLabel = displayLabel;
        this.upgradeable  = upgradeable;
    }

    /** Functional classification of this structure. */
    public StructureRole role()       { return role; }

    /** Operator-facing name shown in the UI. */
    public String displayLabel()      { return displayLabel; }

    /** Whether the automated upgrade routine may target this building. */
    public boolean isUpgradeable()    { return upgradeable; }

    /** {@code true} for residential shelter slots. */
    public boolean isShelter()        { return role == StructureRole.RESIDENTIAL; }

    /** {@code true} for resource production buildings. */
    public boolean isProductionBuilding() { return role == StructureRole.PRODUCTION; }

    /** {@code true} for the central furnace structure. */
    public boolean isCoreStructure()  { return role == StructureRole.CORE; }

    /** {@code true} for support structures (cookhouse, clinic). */
    public boolean isSupportStructure() { return role == StructureRole.SUPPORT; }

    /**
     * Tests whether this building belongs to the given structural role.
     *
     * @param target the role to check
     * @return {@code true} on match
     */
    public boolean belongsToRole(StructureRole target) {
        return this.role == target;
    }

    /**
     * Provides all residential shelter entries.
     *
     * @return an unmodifiable list of shelter-type buildings
     */
    public static List<BuildingType> shelterSlots() {
        return ALL_SHELTERS;
    }

    /** Returns the total number of shelter slots. */
    public static int shelterCount() {
        return ALL_SHELTERS.size();
    }

    /**
     * Retrieves all buildings belonging to the specified role.
     *
     * @param target the structural role
     * @return an unmodifiable list of matching buildings
     */
    public static List<BuildingType> buildingsByRole(StructureRole target) {
        return BY_ROLE.getOrDefault(target, Collections.emptyList());
    }

    /**
     * Returns the total number of buildings that are upgrade-eligible.
     */
    public static int upgradeableCount() {
        return UPGRADEABLE_COUNT;
    }

    /**
     * Performs a case-insensitive lookup by display label.
     *
     * @param label the label text to search for
     * @return the matching type, or {@code null} when not found
     */
    public static BuildingType fromLabel(String label) {
        if (label == null) {
            return null;
        }
        return BY_LABEL.get(label.trim().toLowerCase());
    }

    @Override
    public String toString() {
        return displayLabel;
    }
}
