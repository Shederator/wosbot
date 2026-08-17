package dev.frostguard.api.configs;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Complete catalogue of automated routines that the scheduler
 * can dispatch during a profile's active session.
 *
 * <p>Every constant carries a stable numeric code persisted in the
 * database, an operator-facing label, an optional toggle key from
 * {@link ConfigurationKeyEnum}, and a {@link RoutineCategory} for
 * grouping in the management panel.</p>
 */
public enum TpDailyTaskEnum {

    GAME_ANALYTICS_LABYRINTH(510, "Collect Alliance Labyrinth Ranking", null, RoutineCategory.ANALYTICS),
    GAME_ANALYTICS_POWER    (511, "Collect Alliance Power Ranking",     null, RoutineCategory.ANALYTICS),

    /* ── alliance ── */

    ALLIANCE_AUTOJOIN      (40,  "Alliance Autojoin",            ConfigurationKeyEnum.ALLIANCE_AUTOJOIN_BOOL,                  RoutineCategory.ALLIANCE),
    ALLIANCE_CHAMPIONSHIP  (49,  "Alliance Championship",        ConfigurationKeyEnum.ALLIANCE_CHAMPIONSHIP_BOOL,              RoutineCategory.ALLIANCE),
    ALLIANCE_CHESTS        (44,  "Alliance Chests",              ConfigurationKeyEnum.ALLIANCE_CHESTS_BOOL,                    RoutineCategory.ALLIANCE),
    ALLIANCE_MOBILIZATION  (46,  "Alliance Mobilization",        ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_BOOL,              RoutineCategory.ALLIANCE),
    ALLIANCE_PET_TREASURE  (43,  "Alliance Pet Treasure",        ConfigurationKeyEnum.ALLIANCE_PET_TREASURE_BOOL,              RoutineCategory.ALLIANCE),
    ALLIANCE_SHOP          (47,  "Alliance Shop",                null,                                                         RoutineCategory.ALLIANCE),
    ALLIANCE_TECH          (42,  "Alliance Tech",                ConfigurationKeyEnum.ALLIANCE_TECH_BOOL,                      RoutineCategory.ALLIANCE),
    ALLIANCE_TRIUMPH       (45,  "Alliance Triumph",             ConfigurationKeyEnum.ALLIANCE_TRIUMPH_BOOL,                   RoutineCategory.ALLIANCE),
    BEAR_TRAP              (48,  "Bear Trap Event",              ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL,                    RoutineCategory.ALLIANCE),

    /* ── chief orders ── */

    CHIEF_ORDER_PRODUCTIVITY_DAY   (302, "Chief Order: Productivity Day",    ConfigurationKeyEnum.BOOL_CHIEF_ORDER_PRODUCTIVITY_DAY,    RoutineCategory.CHIEF_ORDER),
    CHIEF_ORDER_RUSH_JOB           (300, "Chief Order: Rush Job",           ConfigurationKeyEnum.BOOL_CHIEF_ORDER_RUSH_JOB,            RoutineCategory.CHIEF_ORDER),
    CHIEF_ORDER_URGENT_MOBILIZATION(301, "Chief Order: Urgent Mobilization", ConfigurationKeyEnum.BOOL_CHIEF_ORDER_URGENT_MOBILISATION, RoutineCategory.CHIEF_ORDER),

    /* ── city ── */

    CITY_SURVIVORS                      (71, "City Survivors",     ConfigurationKeyEnum.CITY_ACCEPT_NEW_SURVIVORS_BOOL,        RoutineCategory.CITY),
    CITY_UPGRADE_FURNACE                (70, "City Upgrade Furnace", ConfigurationKeyEnum.CITY_UPGRADE_FURNACE_BOOL,           RoutineCategory.CITY),
    CITY_UPGRADE_PRIORITISE_FURNACE     (72, "Prioritise Furnace", ConfigurationKeyEnum.CITY_UPGRADE_PRIORITISE_FURNACE_BOOL,  RoutineCategory.CITY),
    RESEARCH                            (73, "Research",           ConfigurationKeyEnum.RESEARCH_ENABLED_BOOL,                 RoutineCategory.CITY),

    /* ── custom ── */

    CUSTOM_TASK            (500, "Custom Task",                  null,                                                         RoutineCategory.CUSTOM),

    /* ── daily objectives ── */

    DAILY_MISSIONS         (31,  "Daily Missions",               ConfigurationKeyEnum.DAILY_MISSION_BOOL,                     RoutineCategory.DAILY_OBJECTIVE),
    EXPERT_AGNES_INTEL     (34,  "Expert Agnes Intel",           ConfigurationKeyEnum.EXPERT_AGNES_INTEL_BOOL,                RoutineCategory.DAILY_OBJECTIVE),
    EXPERT_ROMULUS_TAG     (35,  "Expert Romulus Tag",           ConfigurationKeyEnum.EXPERT_ROMULUS_TAG_BOOL,                 RoutineCategory.DAILY_OBJECTIVE),
    EXPERT_ROMULUS_TROOPS  (36,  "Expert Romulus Troops",        ConfigurationKeyEnum.EXPERT_ROMULUS_TROOPS_BOOL,              RoutineCategory.DAILY_OBJECTIVE),
    EXPERT_SKILL_TRAINING  (37,  "Expert Skill Training",       ConfigurationKeyEnum.EXPERT_SKILL_TRAINING_ENABLED_BOOL,      RoutineCategory.DAILY_OBJECTIVE),
    INTEL                  (33,  "Intel",                        ConfigurationKeyEnum.INTEL_BOOL,                              RoutineCategory.DAILY_OBJECTIVE),
    MAIL_REWARDS           (30,  "Mail Rewards",                 ConfigurationKeyEnum.MAIL_REWARDS_BOOL,                      RoutineCategory.DAILY_OBJECTIVE),
    STOREHOUSE_CHEST       (32,  "Storehouse Chest",             ConfigurationKeyEnum.STOREHOUSE_CHEST_BOOL,                  RoutineCategory.DAILY_OBJECTIVE),

    /* ── events ── */

    EVENT_BERSERK_CRYPTID  (206, "Manual Rally Join",            ConfigurationKeyEnum.RALLY_ENABLED_BOOL,                     RoutineCategory.EVENT),
    EVENT_HERO_MISSION     (201, "Hero Mission Event",           ConfigurationKeyEnum.HERO_MISSION_EVENT_BOOL,                RoutineCategory.EVENT),
    EVENT_JOURNEY_OF_LIGHT (203, "Journey of Light Event",       ConfigurationKeyEnum.JOURNEY_OF_LIGHT_BOOL,                  RoutineCategory.EVENT),
    EVENT_MYRIAD_BAZAAR    (205, "Myriad Bazaar Event",          ConfigurationKeyEnum.MYRIAD_BAZAAR_EVENT_BOOL,               RoutineCategory.EVENT),
    EVENT_POLAR_TERROR     (204, "Polar Terror Hunting",         ConfigurationKeyEnum.POLAR_TERROR_ENABLED_BOOL,              RoutineCategory.EVENT),
    EVENT_TUNDRA_TRUCK     (200, "Tundra Truck Event",           ConfigurationKeyEnum.TUNDRA_TRUCK_EVENT_BOOL,                RoutineCategory.EVENT),
    FISHING_MINIGAME       (207, "Fishing Minigame",             ConfigurationKeyEnum.FISHING_MINIGAME_ENABLED_BOOL,          RoutineCategory.EVENT),
    MERCENARY_EVENT        (202, "Mercenary Event",              ConfigurationKeyEnum.MERCENARY_EVENT_BOOL,                   RoutineCategory.EVENT),
    TEST_HOOK_LOOP         (208, "Test Hook Loop",               ConfigurationKeyEnum.TEST_HOOK_LOOP_ENABLED_BOOL,            RoutineCategory.EVENT),

    /* ── lifecycle ── */

    CREATE_CHARACTER       (110, "Create Character",             ConfigurationKeyEnum.CREATE_CHARACTER_ENABLED_BOOL,           RoutineCategory.LIFECYCLE),
    DUMMY_TASK             (99,  "Dummy Task",                   ConfigurationKeyEnum.DUMMY_TASK_ENABLED_BOOL,                 RoutineCategory.LIFECYCLE),
    GATHER_BOOST           (101, "Gather Speed Boost",           ConfigurationKeyEnum.GATHER_SPEED_BOOL,                      RoutineCategory.LIFECYCLE),
    GATHER_RESOURCES       (102, "Gather Resources",             ConfigurationKeyEnum.GATHER_TASK_BOOL,                       RoutineCategory.LIFECYCLE),
    INITIALIZE             (100, "Initialize",                   null,                                                         RoutineCategory.LIFECYCLE),
    SKIP_TUTORIAL          (98,  "Skip Tutorial",                ConfigurationKeyEnum.SKIP_TUTORIAL_ENABLED_BOOL,             RoutineCategory.LIFECYCLE),

    /* ── military ── */

    // matt/2026-08-12: retired as a live task -- one combined task juggling all 3 troop
    // camps rescheduled to the single earliest completion across all of them, which meant
    // OCR/timing state for one camp could get tangled with another's. Split into 3
    // independent tasks below (TRAINING_INFANTRY/LANCER/MARKSMAN), each scheduling itself
    // off its own camp only. configKey nulled so this entry is never auto-scheduled; kept
    // (not deleted) so historical DB rows referencing task id 60 don't orphan.
    TRAINING_TROOPS        (60,  "Training (legacy, superseded)", null,                                                         RoutineCategory.MILITARY),

    /* ── pet ── */

    PET_SKILLS             (51,  "Pet Skills",                   ConfigurationKeyEnum.PET_SKILLS_BOOL,                         RoutineCategory.PET),
    PET_SKILL_STAMINA      (106, "Pet Skill: Stamina",          ConfigurationKeyEnum.PET_SKILL_STAMINA_BOOL,                  RoutineCategory.PET),
    PET_SKILL_TREASURE     (107, "Pet Skill: Treasure",         ConfigurationKeyEnum.PET_SKILL_TREASURE_BOOL,                 RoutineCategory.PET),

    /* ── resource ── */

    ARENA                  (15,  "Arena",                        ConfigurationKeyEnum.ARENA_TASK_BOOL,                         RoutineCategory.RESOURCE),
    BANK                   (14,  "Bank",                         ConfigurationKeyEnum.BOOL_BANK,                               RoutineCategory.RESOURCE),
    BEAST_HUNTING          (16,  "Beast Hunting",                ConfigurationKeyEnum.BEAST_HUNTING_ENABLED_BOOL,              RoutineCategory.RESOURCE),
    CRYSTAL_LABORATORY     (4,   "Crystal Laboratory",           ConfigurationKeyEnum.BOOL_CRYSTAL_LAB_FC,                    RoutineCategory.RESOURCE),
    DO_EXPLORATION         (105, "Do Exploration",               ConfigurationKeyEnum.BOOL_DO_EXPLORATION,                    RoutineCategory.RESOURCE),
    EXPLORATION_CHEST      (7,   "Exploration Chest",            ConfigurationKeyEnum.BOOL_EXPLORATION_CHEST,                 RoutineCategory.RESOURCE),
    HERO_RECRUITMENT       (1,   "Hero Recruitment",             ConfigurationKeyEnum.BOOL_HERO_RECRUITMENT,                  RoutineCategory.RESOURCE),
    LABYRINTH              (11,  "Labyrinth",                    ConfigurationKeyEnum.DAILY_LABYRINTH_BOOL,                   RoutineCategory.RESOURCE),
    LIFE_ESSENCE           (9,   "Life Essence",                 ConfigurationKeyEnum.LIFE_ESSENCE_BOOL,                      RoutineCategory.RESOURCE),
    LIFE_ESSENCE_CARING    (10,  "Life Essence Caring",          ConfigurationKeyEnum.ALLIANCE_LIFE_ESSENCE_BOOL,             RoutineCategory.RESOURCE),
    NOMADIC_MERCHANT       (2,   "Nomadic Merchant",             ConfigurationKeyEnum.BOOL_NOMADIC_MERCHANT,                  RoutineCategory.RESOURCE),
    PET_ADVENTURE          (6,   "Pet Adventure",                ConfigurationKeyEnum.PET_PERSONAL_TREASURE_BOOL,             RoutineCategory.RESOURCE),
    TREK_AUTOMATION        (12,  "Tundra Trek Automation",       ConfigurationKeyEnum.TUNDRA_TREK_AUTOMATION_BOOL,            RoutineCategory.RESOURCE),
    TREK_SUPPLIES          (8,   "Trek Supplies",                ConfigurationKeyEnum.TUNDRA_TREK_SUPPLIES_BOOL,              RoutineCategory.RESOURCE),
    VIP_POINTS             (5,   "VIP Points",                   ConfigurationKeyEnum.BOOL_VIP_POINTS,                        RoutineCategory.RESOURCE),
    WAR_ACADEMY            (3,   "War Academy Shards",           ConfigurationKeyEnum.WAR_ACADEMY_TASK_BOOL,                  RoutineCategory.RESOURCE),

    /* ── shop ── */

    SHOP_MYSTERY           (80,  "Shop Mystery",                 ConfigurationKeyEnum.BOOL_MYSTERY_SHOP,                      RoutineCategory.SHOP),

    /* ── Bearguard ── */

    // Id 900+ deliberately: upstream allocates in the low hundreds, so this
    // range will not collide with anything it adds later.
    EVENT_CRYPTID_HOST     (900, "Host Cryptid Rally",           ConfigurationKeyEnum.CRYPTID_HOST_ENABLED_BOOL,              RoutineCategory.EVENT),
    // No category here is a clean fit - closest existing bucket is ALLIANCE,
    // since Alliance chat is one of the three captured channels.
    CHAT_CAPTURE           (901, "Chat Capture",                 ConfigurationKeyEnum.CHAT_CAPTURE_ENABLED_BOOL,              RoutineCategory.ALLIANCE),
    // matt/2026-08-06: deliberately gated on the SAME bool as Smart Gathering
    // (GATHER_SMART_PRIORITY_BOOL), not a separate flag - matt shouldn't need to find and
    // check a second, unrelated-looking checkbox just to make the one he actually checked
    // do anything. Enabling Smart Gathering is enabling this scan; there is no standalone toggle.
    RESOURCE_STOCKPILE_SCAN (902, "Resource Stockpile Scan",     ConfigurationKeyEnum.GATHER_SMART_PRIORITY_BOOL,             RoutineCategory.RESOURCE),
    // matt/2026-08-08: read-only timer sweep. Runs first at startup and hourly thereafter,
    // reading every on-screen countdown and writing schedules WITHOUT performing any activity.
    TIMER_SWEEP             (903, "Timer Sweep",                  ConfigurationKeyEnum.TIMER_SWEEP_ENABLED_BOOL,               RoutineCategory.LIFECYCLE),

    // matt/2026-08-12: split out of the old combined TRAINING_TROOPS task. Each camp now
    // schedules independently off its own OCR'd completion time instead of all 3 sharing
    // one reschedule-to-earliest. Reuses the same three checkboxes/config keys the combined
    // task already read per-type -- no UI change needed, they just each drive their own task now.
    TRAINING_INFANTRY       (904, "Training: Infantry",           ConfigurationKeyEnum.TRAIN_INFANTRY_BOOL,                    RoutineCategory.MILITARY),
    TRAINING_LANCER         (905, "Training: Lancer",             ConfigurationKeyEnum.TRAIN_LANCER_BOOL,                      RoutineCategory.MILITARY),
    TRAINING_MARKSMAN       (906, "Training: Marksman",           ConfigurationKeyEnum.TRAIN_MARKSMAN_BOOL,                    RoutineCategory.MILITARY),

    // matt/2026-08-12: World map "Heal Injured" panel (over My City) -- Quick Select + Heal
    // the highest-priority injured troop queue, then tap Help to speed it via alliance
    // assistance (measured live: cut a 14:21 timer down to 1:45). Idle re-check every 30
    // minutes per matt when there's nothing currently injured.
    HEAL_INJURED_TROOPS     (907, "Heal Injured Troops",          ConfigurationKeyEnum.HEAL_INJURED_ENABLED_BOOL,              RoutineCategory.MILITARY),

    // matt/2026-08-12: "Explore the World" Atlas/Monument -- claims ready quest rows,
    // opens owned Scene Fragment Packs, runs daily Alliance Trade requests/sends.
    MONUMENT                (908, "Monument",                    ConfigurationKeyEnum.MONUMENT_ENABLED_BOOL,                  RoutineCategory.MILITARY),

    // matt/2026-08-12: "event slop" -- rotating limited-time Events-tab events where
    // the bot's only job is finding a ready Claim and hitting it. Twice-a-day recheck.
    EVENT_HALL_OF_CHIEFS    (909, "Event: Hall of Chiefs",        ConfigurationKeyEnum.EVENT_HALL_OF_CHIEFS_CLAIM_BOOL,        RoutineCategory.MILITARY),
    EVENT_DEFEAT_BEASTS     (910, "Event: Defeat Nearby Beasts",  ConfigurationKeyEnum.EVENT_DEFEAT_BEASTS_CLAIM_BOOL,         RoutineCategory.MILITARY),
    EVENT_HERO_RALLY_CLAIM  (911, "Event: Hero Rally Claim All",  ConfigurationKeyEnum.EVENT_HERO_RALLY_CLAIM_BOOL,            RoutineCategory.MILITARY),
    EVENT_LUCKY_CHIP_SUPPLY (912, "Event: Lucky Chip Supply",     ConfigurationKeyEnum.EVENT_LUCKY_CHIP_SUPPLY_CLAIM_BOOL,     RoutineCategory.MILITARY),

    // matt/2026-08-13: live-verified by hand -- tab icon cropped fresh from a live account, real
    // ranking (15th, 1,004,289 points) with 4 tiers already green/ready to claim.
    EVENT_BROTHERS_IN_ARMS (915, "Event: Brothers in Arms",       ConfigurationKeyEnum.EVENT_BROTHERS_IN_ARMS_CLAIM_BOOL,      RoutineCategory.MILITARY),

    // matt/2026-08-13: top-right cart-icon Shop panel, built tab by tab. Custom
    // Armament Chest's free Claimable badge is periodic -- once-a-day recheck.
    // matt/2026-08-13, Part 2: "Gem Shop" prefix on both -- this is the top-right cart-icon panel,
    // distinct from the bottom-nav "Shop" (Backpack/Recycle Shop/etc, a completely different feature).
    SHOP_CUSTOM_ARMAMENT_CHEST (913, "Gem Shop - Custom Armament Chest", ConfigurationKeyEnum.SHOP_CUSTOM_ARMAMENT_CHEST_CLAIM_BOOL, RoutineCategory.MILITARY),
    SHOP_DAILY_DEALS_FREE_CHEST (914, "Gem Shop - Daily Deals Free Chest", ConfigurationKeyEnum.SHOP_DAILY_DEALS_FREE_CHEST_CLAIM_BOOL, RoutineCategory.MILITARY),

    // matt/2026-08-14: "the new labyrinth" -- Research Center (Tech stats) + Gear Forge (Chief
    // Gear stats), the stage-ladder raid system distinct from the classic Land of Heroes/Cave of
    // Monsters/Charm Mine zones (LABYRINTH, id 11, above). Raid-claim only -- Challenge (real
    // battle, limited daily attempts) deliberately not automated yet.
    LABYRINTH_RAID (916, "Labyrinth Raid Claims", ConfigurationKeyEnum.LABYRINTH_RAID_ENABLED_BOOL, RoutineCategory.RESOURCE);

    /* ================================================================
     *  Category taxonomy used to group routines in the management UI.
     * ================================================================ */

    /** Broad operational classification of a scheduled routine. */
    public enum RoutineCategory {
        ALLIANCE, ANALYTICS, CHIEF_ORDER, CITY, CUSTOM, DAILY_OBJECTIVE,
        EVENT, LIFECYCLE, MILITARY, PET, RESOURCE, SHOP
    }

    /* ---- eager reverse index from numeric code ---- */

    private static final Map<Integer, TpDailyTaskEnum> BY_CODE;

    /* ---- per-category pre-built grouping ---- */

    private static final Map<RoutineCategory, List<TpDailyTaskEnum>> ROUTINES_BY_CATEGORY;

    static {
        Map<Integer, TpDailyTaskEnum> temp = new HashMap<>();
        Map<RoutineCategory, List<TpDailyTaskEnum>> catMap =
                new java.util.EnumMap<>(RoutineCategory.class);
        for (RoutineCategory rc : RoutineCategory.values()) {
            catMap.put(rc, new java.util.ArrayList<>());
        }
        for (TpDailyTaskEnum entry : values()) {
            temp.put(entry.routineCode, entry);
            catMap.get(entry.routineCategory).add(entry);
        }
        BY_CODE = Collections.unmodifiableMap(temp);
        Map<RoutineCategory, List<TpDailyTaskEnum>> immutable =
                new java.util.EnumMap<>(RoutineCategory.class);
        for (var me : catMap.entrySet()) {
            immutable.put(me.getKey(), Collections.unmodifiableList(me.getValue()));
        }
        ROUTINES_BY_CATEGORY = Collections.unmodifiableMap(immutable);
    }

    /* ---- per-constant state ---- */

    private final int routineCode;
    private final String displayText;
    private final ConfigurationKeyEnum activationSwitch;
    private final RoutineCategory routineCategory;

    TpDailyTaskEnum(int routineCode, String displayText,
                    ConfigurationKeyEnum activationSwitch,
                    RoutineCategory routineCategory) {
        this.routineCode      = routineCode;
        this.displayText      = displayText;
        this.activationSwitch = activationSwitch;
        this.routineCategory  = routineCategory;
    }

    /* ---- primary accessors ---- */

    /** Stable numeric identifier persisted in scheduling tables. */
    public int routineCode()                       { return routineCode; }

    /** Label rendered in the operator's management panel. */
    public String displayText()                    { return displayText; }

    /** Optional configuration key that enables/disables this routine. */
    public ConfigurationKeyEnum activationSwitch() { return activationSwitch; }

    /** Broad category this routine belongs to. */
    public RoutineCategory routineCategory()       { return routineCategory; }

    /* ---- convenience queries ---- */

    /**
     * Whether this routine has an associated on/off toggle key.
     */
    public boolean isToggleable() {
        return activationSwitch != null;
    }

    /** {@code true} for routines tied to limited-time game events. */
    public boolean isEvent() {
        return routineCategory == RoutineCategory.EVENT;
    }

    /** {@code true} for routines that run on a daily cadence. */
    public boolean isDaily() {
        return routineCategory == RoutineCategory.DAILY_OBJECTIVE
            || routineCategory == RoutineCategory.RESOURCE;
    }

    /** {@code true} for routines related to combat or military. */
    public boolean isCombatRelated() {
        return routineCategory == RoutineCategory.MILITARY
            || routineCategory == RoutineCategory.ALLIANCE;
    }

    /** {@code true} for lifecycle-phase routines like initialisation. */
    public boolean isLifecycle() {
        return routineCategory == RoutineCategory.LIFECYCLE;
    }

    /** Whether this routine can create a new rally rather than only joining one. */
    public boolean canStartRally() {
        return this == EVENT_HERO_MISSION
            || this == EVENT_POLAR_TERROR
            || this == MERCENARY_EVENT;
    }

    /** {@code true} for shop or merchant related routines. */
    public boolean isShopRelated() {
        return routineCategory == RoutineCategory.SHOP
            || routineCategory == RoutineCategory.RESOURCE;
    }

    /**
     * Whether the activation switch defaults to enabled.
     *
     * @return {@code true} when the toggle key's default value is "true"
     */
    public boolean isEnabledByDefault() {
        if (activationSwitch == null) return false;
        return activationSwitch.defaultAsBoolean();
    }

    /**
     * Maps a persisted numeric code back to the corresponding
     * routine constant.
     *
     * @param id the stored numeric identifier
     * @return the resolved constant
     * @throws IllegalArgumentException when no mapping exists
     */
    public static TpDailyTaskEnum fromNumericId(int id) {
        TpDailyTaskEnum result = BY_CODE.get(id);
        if (result == null) {
            throw new IllegalArgumentException(
                    "No TpDailyTaskEnum registered for code " + id);
        }
        return result;
    }

    /**
     * Safe version that returns {@code null} on unknown codes.
     *
     * @param id the stored numeric identifier
     * @return the resolved constant, or {@code null}
     */
    public static TpDailyTaskEnum fromNumericIdOrNull(int id) {
        return BY_CODE.get(id);
    }

    /**
     * Case-insensitive lookup by enum constant name.
     *
     * @param enumName the name to resolve
     * @return the matching routine, or {@code null} when not found
     */
    public static TpDailyTaskEnum fromNameSafe(String enumName) {
        if (enumName == null) return null;
        try {
            return TpDailyTaskEnum.valueOf(enumName.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Collects every routine belonging to the specified category.
     *
     * @param cat the target category
     * @return an unmodifiable set of matching routines
     */
    public static Set<TpDailyTaskEnum> inCategory(RoutineCategory cat) {
        EnumSet<TpDailyTaskEnum> accumulator =
                EnumSet.noneOf(TpDailyTaskEnum.class);
        for (TpDailyTaskEnum entry : values()) {
            if (entry.routineCategory == cat) {
                accumulator.add(entry);
            }
        }
        return Collections.unmodifiableSet(accumulator);
    }

    /**
     * Pre-indexed list of routines for a given category. Faster
     * than {@link #inCategory(RoutineCategory)} for repeated calls.
     *
     * @param cat the target category
     * @return an unmodifiable list of matching routines
     */
    public static List<TpDailyTaskEnum> routinesInCategory(
            RoutineCategory cat) {
        return ROUTINES_BY_CATEGORY.getOrDefault(
                cat, Collections.emptyList());
    }

    /* ---------- backward-compatible accessor shims ---------- */

    public static TpDailyTaskEnum fromId(int id)  { return fromNumericId(id); }

    public int getId()                            { return routineCode; }
    public int getNumericId()                     { return routineCode; }
    public String getName()                       { return displayText; }
    public String getLabel()                      { return displayText; }
    public ConfigurationKeyEnum getConfigKey()    { return activationSwitch; }
    public ConfigurationKeyEnum getToggleKey()    { return activationSwitch; }

    @Override
    public String toString() {
        return displayText + " [" + routineCode + "]";
    }
}
