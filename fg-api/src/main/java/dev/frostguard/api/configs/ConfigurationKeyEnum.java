package dev.frostguard.api.configs;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Exhaustive registry of tuneable parameters consumed by the
 * Frostguard automation engine.
 *
 * <p>Every constant stores a serialised default value, the expected
 * Java target type for deserialisation, and a {@link ConfigCategory}
 * that determines where the key appears in the operator panel.</p>
 *
 * <p>Keys may be queried by category via {@link #byCategory(ConfigCategory)}.
 * Raw string values can be reified with {@link #castValue(String)}.</p>
 */
public enum ConfigurationKeyEnum {

    /* ─────────── analytics ─────────── */

    ANALYTICS_ENABLED_BOOL              ("true",             Boolean.class,       ConfigCategory.ANALYTICS),
    ANALYTICS_ID_STRING                 ("",                 String.class,        ConfigCategory.ANALYTICS),
    HIDE_ANALYTICS_LOGS_BOOL            ("true",             Boolean.class,       ConfigCategory.ANALYTICS),
    STATISTICS_JSON_STRING              ("{}",               String.class,        ConfigCategory.ANALYTICS),

    /* ─────────── alliance ─────────── */

    ALLIANCE_CHAMPIONSHIP_BOOL                          ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_CHAMPIONSHIP_INFANTRY_PERCENTAGE_INT       ("50",      Integer.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_CHAMPIONSHIP_LANCERS_PERCENTAGE_INT        ("20",      Integer.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_CHAMPIONSHIP_MARKSMANS_PERCENTAGE_INT      ("30",      Integer.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_CHAMPIONSHIP_OVERRIDE_DEPLOY_BOOL          ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_CHAMPIONSHIP_POSITION_STRING               ("CENTER",  String.class,   ConfigCategory.ALLIANCE),
    ALLIANCE_CHAMPIONSHIP_FLAG_STRING                   ("No Flag", String.class,   ConfigCategory.ALLIANCE),
    ALLIANCE_AUTOJOIN_BOOL                              ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_AUTOJOIN_QUEUES_INT                        ("1",       Integer.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_AUTOJOIN_USE_ALL_TROOPS_BOOL               ("true",    Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_AUTOJOIN_USE_PREDEFINED_FORMATION_BOOL     ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_CHESTS_BOOL                                ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_CHESTS_OFFSET_INT                          ("60",      Integer.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_HELP_BOOL                                  ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_HONOR_CHEST_BOOL                           ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_LIFE_ESSENCE_BOOL                          ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_LIFE_ESSENCE_OFFSET_INT                    ("60",      Integer.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_MOBILIZATION_AUTO_ACCEPT_BOOL              ("true",    Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_MOBILIZATION_BOOL                          ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_MOBILIZATION_BUILD_SPEEDUPS_BOOL           ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_MOBILIZATION_BUY_PACKAGE_BOOL              ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_MOBILIZATION_CHIEF_GEAR_CHARM_BOOL         ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_MOBILIZATION_CHIEF_GEAR_SCORE_BOOL         ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_MOBILIZATION_DEFEAT_BEASTS_BOOL            ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_MOBILIZATION_FIRE_CRYSTAL_BOOL             ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_MOBILIZATION_GATHER_RESOURCES_BOOL         ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_MOBILIZATION_HERO_GEAR_STONE_BOOL          ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_MOBILIZATION_MINIMUM_POINTS_120_INT        ("520",     Integer.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_MOBILIZATION_MINIMUM_POINTS_200_INT        ("800",     Integer.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_MOBILIZATION_MYTHIC_SHARD_BOOL             ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_MOBILIZATION_RALLY_BOOL                    ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_MOBILIZATION_REWARDS_PERCENTAGE_STRING     ("Any",     String.class,   ConfigCategory.ALLIANCE),
    ALLIANCE_MOBILIZATION_TRAIN_TROOPS_BOOL             ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_MOBILIZATION_TRAINING_SPEEDUPS_BOOL        ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_MOBILIZATION_USE_GEMS_BOOL                 ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_MOBILIZATION_USE_GEMS_FOR_ACCEPT_BOOL      ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_MOBILIZATION_USE_SPEEDUPS_BOOL             ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_PET_TREASURE_BOOL                          ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_SHOP_ENABLED_BOOL                          ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_SHOP_MIN_COINS_INT                         ("0",       Integer.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_SHOP_MIN_COINS_TO_ACTIVATE_INT             ("0",       Integer.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_SHOP_MIN_PERCENTAGE_INT                    ("50",      Integer.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_SHOP_PRIORITIES_STRING                     ("",        String.class,   ConfigCategory.ALLIANCE),
    ALLIANCE_TECH_BOOL                                  ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_TECH_OFFSET_INT                            ("60",      Integer.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_TRIUMPH_BOOL                               ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_TRIUMPH_OFFSET_INT                         ("60",      Integer.class,  ConfigCategory.ALLIANCE),

    /* ─────────── city ─────────── */

    CITY_ACCEPT_NEW_SURVIVORS_BOOL              ("false",   Boolean.class,  ConfigCategory.CITY),
    CITY_ACCEPT_NEW_SURVIVORS_OFFSET_INT        ("60",      Integer.class,  ConfigCategory.CITY),
    CITY_UPGRADE_FURNACE_BOOL                   ("false",   Boolean.class,  ConfigCategory.CITY),
    CITY_UPGRADE_RESERVE_PRODUCTION_BOOL        ("true",    Boolean.class,  ConfigCategory.CITY),
    CITY_UPGRADE_CONSTRUCTION_LOCK_STRING       ("",        String.class,   ConfigCategory.CITY),
    CITY_UPGRADE_PRIORITISE_FURNACE_BOOL        ("false",   Boolean.class,  ConfigCategory.CITY),
    RESEARCH_BATTLE_BOOL                        ("false",   Boolean.class,  ConfigCategory.CITY),
    RESEARCH_ECONOMY_BOOL                       ("false",   Boolean.class,  ConfigCategory.CITY),
    RESEARCH_ENABLED_BOOL                       ("false",   Boolean.class,  ConfigCategory.CITY),
    RESEARCH_GROWTH_BOOL                        ("false",   Boolean.class,  ConfigCategory.CITY),
    RESEARCH_PRIORITIES_STRING                  ("growth:Growth:1:true|economy:Economy:2:true|battle:Battle:3:true", String.class, ConfigCategory.CITY),

    /* ─────────── dailies ─────────── */

    ARENA_TASK_ACTIVATION_TIME_STRING   ("23:50",   String.class,   ConfigCategory.DAILIES),
    ARENA_TASK_BOOL                     ("false",   Boolean.class,  ConfigCategory.DAILIES),
    ARENA_TASK_EXTRA_ATTEMPTS_INT       ("0",       Integer.class,  ConfigCategory.DAILIES),
    /** Legacy arena state filter key retained only so existing persisted profiles can still be read. */
    ARENA_TASK_PLAYER_STATE_INT         ("0",       Integer.class,  ConfigCategory.DAILIES, true),
    ARENA_TASK_SERVER_POLICY_STRING     ("Any server", String.class, ConfigCategory.DAILIES),
    ARENA_TASK_ALLIANCE_POLICY_STRING   ("Avoid profile alliance", String.class, ConfigCategory.DAILIES),
    /** Legacy arena alliance protection key retained only so existing persisted profiles can still be read. */
    ARENA_TASK_PROTECT_ALLIANCE_BOOL    ("true",    Boolean.class,  ConfigCategory.DAILIES, true),
    ARENA_TASK_REFRESH_WITH_GEMS_BOOL   ("false",   Boolean.class,  ConfigCategory.DAILIES),
    DAILY_LABYRINTH_BOOL                ("false",   Boolean.class,  ConfigCategory.DAILIES),
    DAILY_MISSION_AUTO_SCHEDULE_BOOL    ("false",   Boolean.class,  ConfigCategory.DAILIES),
    DAILY_MISSION_BOOL                  ("false",   Boolean.class,  ConfigCategory.DAILIES),
    DAILY_MISSION_OFFSET_INT            ("60",      Integer.class,  ConfigCategory.DAILIES),
    MAIL_REWARDS_BOOL                   ("false",   Boolean.class,  ConfigCategory.DAILIES),
    MAIL_REWARDS_OFFSET_INT             ("60",      Integer.class,  ConfigCategory.DAILIES),
    STOREHOUSE_CHEST_BOOL               ("false",   Boolean.class,  ConfigCategory.DAILIES),
    STOREHOUSE_STAMINA_CLAIM_TIME_STRING("",        String.class,   ConfigCategory.DAILIES),

    /* ─────────── events ─────────── */

    BEAR_TRAP_ACTIVE_PETS_BOOL                  ("false",   Boolean.class,       ConfigCategory.EVENTS),
    BEAR_TRAP_CALL_RALLY_BOOL                   ("false",   Boolean.class,       ConfigCategory.EVENTS),
    BEAR_TRAP_EVENT_BOOL                        ("false",   Boolean.class,       ConfigCategory.EVENTS),
    BEAR_TRAP_ICON_PARTICIPATION_FALLBACK_BOOL  ("false",   Boolean.class,       ConfigCategory.EVENTS),
    BEAR_TRAP_JOIN_FLAG_INT                     ("1",       String.class,        ConfigCategory.EVENTS),
    BEAR_TRAP_JOIN_RALLY_BOOL                   ("false",   Boolean.class,       ConfigCategory.EVENTS),
    BEAR_TRAP_NUMBER_INT                        ("1",       Integer.class,       ConfigCategory.EVENTS),
    BEAR_TRAP_PREPARATION_TIME_INT              ("10",      Integer.class,       ConfigCategory.EVENTS),
    BEAR_TRAP_RALLY_FLAG_INT                    ("1",       Integer.class,       ConfigCategory.EVENTS),
    BEAR_TRAP_RECALL_TROOPS_BOOL                ("false",   Boolean.class,       ConfigCategory.EVENTS),
    BEAR_TRAP_TIMER_1_ENABLED_BOOL              ("false",   Boolean.class,       ConfigCategory.EVENTS),
    BEAR_TRAP_TIMER_1_BLOCK_RALLIES_BOOL        ("true",    Boolean.class,       ConfigCategory.EVENTS),
    BEAR_TRAP_TIMER_1_PAUSE_ALL_TASKS_BOOL      ("false",   Boolean.class,       ConfigCategory.EVENTS),
    BEAR_TRAP_SCHEDULE_DATETIME_STRING          ("",        LocalDateTime.class, ConfigCategory.EVENTS),
    BEAR_TRAP_TIMER_2_ENABLED_BOOL              ("false",   Boolean.class,       ConfigCategory.EVENTS),
    BEAR_TRAP_TIMER_2_BLOCK_RALLIES_BOOL        ("true",    Boolean.class,       ConfigCategory.EVENTS),
    BEAR_TRAP_TIMER_2_PAUSE_ALL_TASKS_BOOL      ("false",   Boolean.class,       ConfigCategory.EVENTS),
    BEAR_TRAP_TIMER_2_SCHEDULE_DATETIME_STRING  ("",        LocalDateTime.class, ConfigCategory.EVENTS),
    BEAST_HUNTING_ENABLED_BOOL                  ("false",   Boolean.class,       ConfigCategory.EVENTS),
    BEAST_HUNTING_LEVEL_INT                     ("30",      Integer.class,       ConfigCategory.EVENTS),
    BEAST_HUNTING_MARCHES_INT                   ("3",       Integer.class,       ConfigCategory.EVENTS),
    // Shared stamina reserve kept back for Intel/Rally; overflow sinks (Beast/Polar Terror) only spend above it.
    STAMINA_RESERVE_INT                         ("130",     Integer.class,       ConfigCategory.EVENTS),
    BOOL_CHIEF_ORDER_PRODUCTIVITY_DAY           ("false",   Boolean.class,       ConfigCategory.EVENTS),
    BOOL_CHIEF_ORDER_RUSH_JOB                   ("false",   Boolean.class,       ConfigCategory.EVENTS),
    BOOL_CHIEF_ORDER_URGENT_MOBILISATION        ("false",   Boolean.class,       ConfigCategory.EVENTS),
    FISHING_MINIGAME_ENABLED_BOOL               ("false",   Boolean.class,       ConfigCategory.EVENTS),
    HERO_MISSION_EVENT_BOOL                     ("false",   Boolean.class,       ConfigCategory.EVENTS),
    HERO_MISSION_FLAG_INT                       ("0",       Integer.class,       ConfigCategory.EVENTS),
    JOURNEY_OF_LIGHT_BOOL                       ("false",   Boolean.class,       ConfigCategory.EVENTS),
    MERCENARY_EVENT_BOOL                        ("false",   Boolean.class,       ConfigCategory.EVENTS),
    MERCENARY_FLAG_INT                          ("0",       Integer.class,       ConfigCategory.EVENTS),
    MYRIAD_BAZAAR_EVENT_BOOL                    ("false",   Boolean.class,       ConfigCategory.EVENTS),
    POLAR_TERROR_ENABLED_BOOL                   ("false",   Boolean.class,       ConfigCategory.EVENTS),
    POLAR_TERROR_LEVEL_INT                      ("1",       Integer.class,       ConfigCategory.EVENTS),
    POLAR_TERROR_HIGHEST_LEVEL_BOOL             ("false",   Boolean.class,       ConfigCategory.EVENTS),
    POLAR_TERROR_MARCH_1_FLAG_STRING            ("No Flag", String.class,        ConfigCategory.EVENTS),
    POLAR_TERROR_MARCH_2_FLAG_STRING            ("No Flag", String.class,        ConfigCategory.EVENTS),
    POLAR_TERROR_MARCH_3_FLAG_STRING            ("No Flag", String.class,        ConfigCategory.EVENTS),
    POLAR_TERROR_MARCH_4_FLAG_STRING            ("No Flag", String.class,        ConfigCategory.EVENTS),
    POLAR_TERROR_MARCH_5_FLAG_STRING            ("No Flag", String.class,        ConfigCategory.EVENTS),
    POLAR_TERROR_MARCH_6_FLAG_STRING            ("No Flag", String.class,        ConfigCategory.EVENTS),
    POLAR_TERROR_MARCHES_INT                    ("1",       Integer.class,       ConfigCategory.EVENTS),
    POLAR_TERROR_MODE_STRING                    (PolarTerrorMode.SPECIAL_REWARDS.getDisplayName(), String.class, ConfigCategory.EVENTS),
    POLAR_TERROR_USE_STAMINA_ITEMS_BOOL         ("false",   Boolean.class,       ConfigCategory.EVENTS),
    POLAR_TERROR_STAMINA_ITEM_RESERVE_INT       ("0",       Integer.class,       ConfigCategory.EVENTS),
    RALLY_ENABLED_BOOL                          ("false",   Boolean.class,       ConfigCategory.EVENTS),
    RALLY_MARCH_1_FLAG_STRING                   ("No Flag", String.class,        ConfigCategory.EVENTS),
    RALLY_MARCH_2_FLAG_STRING                   ("No Flag", String.class,        ConfigCategory.EVENTS),
    RALLY_MARCH_3_FLAG_STRING                   ("No Flag", String.class,        ConfigCategory.EVENTS),
    RALLY_MARCH_4_FLAG_STRING                   ("No Flag", String.class,        ConfigCategory.EVENTS),
    RALLY_MARCH_5_FLAG_STRING                   ("No Flag", String.class,        ConfigCategory.EVENTS),
    RALLY_MARCH_6_FLAG_STRING                   ("No Flag", String.class,        ConfigCategory.EVENTS),
    RALLY_MARCHES_INT                           ("1",       Integer.class,       ConfigCategory.EVENTS),
    RALLY_MODE_STRING                           ("Limited (10)", String.class,   ConfigCategory.EVENTS),
    RALLY_TARGET_STRING                         ("berserkCryptid", String.class, ConfigCategory.EVENTS),
    TEST_HOOK_LOOP_ENABLED_BOOL                 ("false",   Boolean.class,       ConfigCategory.EVENTS),
    TUNDRA_TRUCK_ACTIVATION_TIME_BOOL           ("false",   Boolean.class,       ConfigCategory.EVENTS),
    TUNDRA_TRUCK_ACTIVATION_TIME_STRING         ("14:00",   String.class,        ConfigCategory.EVENTS),
    TUNDRA_TRUCK_EVENT_BOOL                     ("false",   Boolean.class,       ConfigCategory.EVENTS),
    TUNDRA_TRUCK_SSR_BOOL                       ("false",   Boolean.class,       ConfigCategory.EVENTS),
    TUNDRA_TRUCK_USE_GEMS_BOOL                  ("false",   Boolean.class,       ConfigCategory.EVENTS),
    TUNDRA_TREK_AUTOMATION_BOOL                 ("false",   Boolean.class,       ConfigCategory.EVENTS),
    TUNDRA_TREK_SUPPLIES_BOOL                   ("false",   Boolean.class,       ConfigCategory.EVENTS),

    /* ─────────── intel ─────────── */

    INTEL_BEASTS_BOOL                           ("false",   Boolean.class,  ConfigCategory.INTEL),
    INTEL_BEASTS_EVENT_BOOL                     ("false",   Boolean.class,  ConfigCategory.INTEL),
    INTEL_BEASTS_FLAG_INT                       ("1",       Integer.class,  ConfigCategory.INTEL),
    INTEL_BOOL                                  ("false",   Boolean.class,  ConfigCategory.INTEL),
    INTEL_CAMP_BOOL                             ("false",   Boolean.class,  ConfigCategory.INTEL),
    INTEL_EXPLORATION_BOOL                      ("false",   Boolean.class,  ConfigCategory.INTEL),
    INTEL_FC_ERA_BOOL                           ("false",   Boolean.class,  ConfigCategory.INTEL),
    INTEL_FIRE_BEAST_BOOL                       ("false",   Boolean.class,  ConfigCategory.INTEL),
    INTEL_RECALL_GATHER_TROOPS_BOOL             ("false",   Boolean.class,  ConfigCategory.INTEL),
    INTEL_SMART_PROCESSING_BOOL                 ("false",   Boolean.class,  ConfigCategory.INTEL),
    INTEL_USE_FLAG_BOOL                         ("false",   Boolean.class,  ConfigCategory.INTEL),

    /* ─────────── experts ─────────── */

    EXPERT_AGNES_INTEL_BOOL                     ("false",       Boolean.class, ConfigCategory.EXPERTS),
    EXPERT_ROMULUS_TAG_BOOL                     ("false",       Boolean.class, ConfigCategory.EXPERTS),
    EXPERT_ROMULUS_TROOPS_BOOL                  ("false",       Boolean.class, ConfigCategory.EXPERTS),
    EXPERT_ROMULUS_TROOPS_TYPE_STRING           ("Infantry",    String.class,  ConfigCategory.EXPERTS),
    EXPERT_SKILL_TRAINING_ENABLED_BOOL         ("false",       Boolean.class, ConfigCategory.EXPERTS),
    EXPERT_SKILL_TRAINING_PRIORITIES_STRING     ("",            String.class,  ConfigCategory.EXPERTS),

    /* ─────────── gathering ─────────── */

    GATHER_ACTIVE_MARCH_QUEUE_INT   ("6",                   Integer.class,  ConfigCategory.GATHERING),
    GATHER_COAL_BOOL                ("false",               Boolean.class,  ConfigCategory.GATHERING),
    GATHER_COAL_LEVEL_INT           ("8",                   Integer.class,  ConfigCategory.GATHERING),
    GATHER_IRON_BOOL                ("false",               Boolean.class,  ConfigCategory.GATHERING),
    GATHER_IRON_LEVEL_INT           ("8",                   Integer.class,  ConfigCategory.GATHERING),
    GATHER_MEAT_BOOL                ("false",               Boolean.class,  ConfigCategory.GATHERING),
    GATHER_MEAT_LEVEL_INT           ("8",                   Integer.class,  ConfigCategory.GATHERING),
    GATHER_ONLY_FULL_RESOURCES_BOOL ("false",               Boolean.class,  ConfigCategory.GATHERING),
    GATHER_DOWNGRADE_LEVEL_BOOL     ("true",                Boolean.class,  ConfigCategory.GATHERING),
    GATHER_REMOVE_HEROS_BOOL        ("true",                Boolean.class,  ConfigCategory.GATHERING),
    GATHER_ROTATION_POOL            ("",                    String.class,   ConfigCategory.GATHERING),
    // pernerch/2026-07-02: timestamp of the last gather recall (Intel/Bear), stored per-profile task instance
    // to track troop return window and avoid re-deploying before troops are home.
    GATHER_LAST_RECALL_TIME_STRING  ("",                    String.class,   ConfigCategory.GATHERING),
    GATHER_SPEED_BOOL               ("false",               Boolean.class,  ConfigCategory.GATHERING),
    GATHER_SPEED_BOOST_TYPE_STRING  ("24h (600 gems)",      String.class,   ConfigCategory.GATHERING),
    GATHER_TASK_BOOL                ("false",               Boolean.class,  ConfigCategory.GATHERING),
    GATHER_WOOD_BOOL                ("false",               Boolean.class,  ConfigCategory.GATHERING),
    GATHER_WOOD_LEVEL_INT           ("8",                   Integer.class,  ConfigCategory.GATHERING),

    /* ─────────── pets ─────────── */

    LIFE_ESSENCE_BOOL                       ("false",   Boolean.class,  ConfigCategory.PETS),
    LIFE_ESSENCE_BUY_WEEKLY_SCROLL_BOOL     ("true",    Boolean.class,  ConfigCategory.PETS),
    LIFE_ESSENCE_CONSECUTIVE_FAILURES_INT   ("0",       Integer.class,  ConfigCategory.PETS),
    LIFE_ESSENCE_NEXT_SCROLL_TIME_STRING    ("",        String.class,   ConfigCategory.PETS),
    LIFE_ESSENCE_OFFSET_INT                 ("60",      Integer.class,  ConfigCategory.PETS),
    PET_PERSONAL_TREASURE_BOOL              ("false",   Boolean.class,  ConfigCategory.PETS),
    PET_SKILL_FOOD_BOOL                     ("false",   Boolean.class,  ConfigCategory.PETS),
    PET_SKILL_GATHERING_BOOL                ("false",   Boolean.class,  ConfigCategory.PETS),
    PET_SKILL_GATHERING_RESOURCE_STRING     ("MEAT",    String.class,   ConfigCategory.PETS),
    PET_SKILL_STAMINA_BOOL                  ("false",   Boolean.class,  ConfigCategory.PETS),
    PET_SKILL_TREASURE_BOOL                 ("false",   Boolean.class,  ConfigCategory.PETS),
    PET_SKILLS_BOOL                         ("false",   Boolean.class,  ConfigCategory.PETS),

    /* ─────────── shops ─────────── */

    BOOL_CRYSTAL_LAB_DAILY_DISCOUNTED_RFC   ("false",   Boolean.class,  ConfigCategory.SHOPS),
    BOOL_CRYSTAL_LAB_FC                     ("false",   Boolean.class,  ConfigCategory.SHOPS),
    BOOL_DO_EXPLORATION                     ("false",   Boolean.class,  ConfigCategory.SHOPS),
    BOOL_EXPLORATION_CHEST                  ("false",   Boolean.class,  ConfigCategory.SHOPS),
    BOOL_HERO_RECRUITMENT                   ("false",   Boolean.class,  ConfigCategory.SHOPS),
    BOOL_MYSTERY_SHOP                       ("false",   Boolean.class,  ConfigCategory.SHOPS),
    BOOL_MYSTERY_SHOP_250_HERO_WIDGET       ("false",   Boolean.class,  ConfigCategory.SHOPS),
    BOOL_NOMADIC_MERCHANT                   ("false",   Boolean.class,  ConfigCategory.SHOPS),
    BOOL_NOMADIC_MERCHANT_VIP_POINTS        ("false",   Boolean.class,  ConfigCategory.SHOPS),
    BOOL_VIP_POINTS                         ("false",   Boolean.class,  ConfigCategory.SHOPS),
    INT_EXPLORATION_CHEST_OFFSET            ("60",      Integer.class,  ConfigCategory.SHOPS),
    INT_WEEKLY_RFC                          ("0",       Integer.class,  ConfigCategory.SHOPS),
    VIP_MONTHLY_BUY_BOOL                    ("false",   Boolean.class,  ConfigCategory.SHOPS),
    VIP_NEXT_MONTHLY_BUY_TIME_STRING        ("",        String.class,   ConfigCategory.SHOPS),
    WAR_ACADEMY_TASK_BOOL                   ("false",   Boolean.class,  ConfigCategory.SHOPS),
    BOOL_BANK                               ("false",   Boolean.class,  ConfigCategory.SHOPS),
    INT_BANK_DELAY                          ("1",       Integer.class,  ConfigCategory.SHOPS),

    /* ─────────── system ─────────── */

    AUTO_START_DELAY_MINUTES_INT        ("5",           Integer.class,  ConfigCategory.SYSTEM),
    AUTO_START_ENABLED_BOOL             ("false",       Boolean.class,  ConfigCategory.SYSTEM),
    AUTO_START_MODE_STRING              ("Continuous",  String.class,   ConfigCategory.SYSTEM),
    BOOL_DEBUG                          ("false",       Boolean.class,  ConfigCategory.SYSTEM),
    CURRENT_EMULATOR_STRING             ("",            String.class,   ConfigCategory.SYSTEM),
    DISCORD_TOKEN_STRING                ("",            String.class,   ConfigCategory.SYSTEM),
    GAME_VERSION_STRING                 ("GLOBAL",      String.class,   ConfigCategory.SYSTEM),
    IDLE_BEHAVIOR_STRING                ("CLOSE_EMULATOR", String.class, ConfigCategory.SYSTEM),
    // Changed by pernerch | Date: 2026-07-04 | Why: allow explicit stop-policy selection for GUI stop action.
    STOP_BEHAVIOR_STRING                ("DO_NOTHING",  String.class,   ConfigCategory.SYSTEM),
    // Changed by pernerch | Date: 2026-07-04 | Why: separate Telegram stop behavior from local GUI stop behavior.
    STOP_BEHAVIOR_TELEGRAM_STRING       ("DO_NOTHING",  String.class,   ConfigCategory.SYSTEM),
    LDPLAYER_PATH_STRING                ("",            String.class,   ConfigCategory.SYSTEM),
    MAX_IDLE_TIME_INT                   ("15",          Integer.class,  ConfigCategory.SYSTEM),
    MAX_RUNNING_EMULATORS_INT           ("1",           Integer.class,  ConfigCategory.SYSTEM),
    // Added by Shederator | Why: serialize concurrent emulator boots to avoid host freezes when
    // launching 3+ instances at once. Delay (ms) enforced between consecutive emulator launches.
    EMULATOR_LAUNCH_DELAY_MS_INT        ("30000",       Integer.class,  ConfigCategory.SYSTEM),
    MEMU_PATH_STRING                    ("",            String.class,   ConfigCategory.SYSTEM),
    MUMU_PATH_STRING                    ("C:\\Program Files\\Netease\\MuMuPlayer\\nx_main", String.class, ConfigCategory.SYSTEM),
    PROFILE_SWITCH_COOLDOWN_MS_INT      ("10000",       Integer.class,  ConfigCategory.SYSTEM),
    TELEGRAM_ALLOWED_CHAT_ID_STRING     ("",            String.class,   ConfigCategory.SYSTEM),
    TELEGRAM_BOT_ENABLED_BOOL          ("false",       Boolean.class,  ConfigCategory.SYSTEM),
    TELEGRAM_BOT_TOKEN_STRING           ("",            String.class,   ConfigCategory.SYSTEM),
    GIFT_CODE_STATE_JSON                ("{}",          String.class,   ConfigCategory.SYSTEM, true),

    /* ─────────── testing ─────────── */

    CREATE_CHARACTER_ENABLED_BOOL       ("false",   Boolean.class,  ConfigCategory.TESTING),
    CREATE_CHARACTER_MAX_AGE_MINUTES_INT("17",      Integer.class,  ConfigCategory.TESTING),
    CREATE_CHARACTER_SKIP_TUTORIAL_BOOL ("false",   Boolean.class,  ConfigCategory.TESTING),
    DUMMY_TASK_ENABLED_BOOL             ("false",   Boolean.class,  ConfigCategory.TESTING),
    DUMMY_TASK_PRIORITY_INT             ("100",     Integer.class,  ConfigCategory.TESTING),
    KEEP_EMULATOR_OPEN_BOOL             ("false",   Boolean.class,  ConfigCategory.TESTING),
    PROFILE_MAX_ACTIVE_TIME_ENABLED_BOOL("false",   Boolean.class,  ConfigCategory.TESTING),
    PROFILE_MAX_ACTIVE_TIME_MINUTES_INT ("60",      Integer.class,  ConfigCategory.TESTING),
    SKIP_TUTORIAL_ENABLED_BOOL          ("false",   Boolean.class,  ConfigCategory.TESTING),
    TEST_GATHER_DEPLOY_PREEMPTION_BOOL  ("false",   Boolean.class,  ConfigCategory.TESTING),

    /* ─────────── training ─────────── */

    TRAIN_BOOL                          ("false",   Boolean.class,  ConfigCategory.TRAINING),
    TRAIN_INFANTRY_BOOL                 ("false",   Boolean.class,  ConfigCategory.TRAINING),
    TRAIN_LANCER_BOOL                   ("false",   Boolean.class,  ConfigCategory.TRAINING),
    TRAIN_MARKSMAN_BOOL                 ("false",   Boolean.class,  ConfigCategory.TRAINING),
    TRAIN_MINISTRY_APPOINTMENT_BOOL     ("false",   Boolean.class,  ConfigCategory.TRAINING),
    TRAIN_MINISTRY_APPOINTMENT_TIME_LONG("0",       Long.class,     ConfigCategory.TRAINING),
    TRAIN_PRIORITIZE_PROMOTION_BOOL     ("false",   Boolean.class,  ConfigCategory.TRAINING);

    /* ================================================================
     *  Functional groupings surfaced in the operator panel.
     * ================================================================ */

    /** Logical grouping of keys for UI display and bulk operations. */
    public enum ConfigCategory {
        ALLIANCE, ANALYTICS, CITY, DAILIES, EVENTS, EXPERTS,
        GATHERING, INTEL, PETS, SHOPS, SYSTEM, TESTING, TRAINING;

        /** Returns a title-cased display string for this category. */
        public String displayTitle() {
            String lower = name().toLowerCase();
            return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        }
    }

    /* ---- type conversion dispatch table ---- */

    private static final Map<Class<?>, Function<String, ?>> TYPE_CONVERTERS;

    /* ---- case-insensitive name → constant index ---- */

    private static final Map<String, ConfigurationKeyEnum> NAME_INDEX;

    static {
        TYPE_CONVERTERS = new HashMap<>();
        TYPE_CONVERTERS.put(Boolean.class,       Boolean::valueOf);
        TYPE_CONVERTERS.put(Integer.class,       Integer::valueOf);
        TYPE_CONVERTERS.put(Long.class,          Long::valueOf);
        TYPE_CONVERTERS.put(Double.class,        Double::valueOf);
        TYPE_CONVERTERS.put(String.class,        Function.identity());
        TYPE_CONVERTERS.put(LocalDateTime.class, ConfigurationKeyEnum::interpretDateTime);

        Map<String, ConfigurationKeyEnum> nameMap = new HashMap<>();
        for (ConfigurationKeyEnum k : values()) {
            nameMap.put(k.name().toUpperCase(), k);
        }
        NAME_INDEX = Collections.unmodifiableMap(nameMap);
    }

    /**
     * Attempts to parse a date-time string using the expected
     * persistence format. Falls back to the next whole UTC hour
     * when the input is absent or malformed.
     */
    private static LocalDateTime interpretDateTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return computeNextWholeHour();
        }
        try {
            return LocalDateTime.parse(raw,
                    DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"));
        } catch (Exception ignored) {
            return computeNextWholeHour();
        }
    }

    private static LocalDateTime computeNextWholeHour() {
        return LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.HOURS)
                .plusHours(1);
    }

    /* ---- per-constant state ---- */

    private final String initialValue;
    private final Class<?> valueKind;
    private final ConfigCategory category;
    private final boolean legacyOnly;

    ConfigurationKeyEnum(String initialValue, Class<?> valueKind,
                         ConfigCategory category) {
        this(initialValue, valueKind, category, false);
    }

    ConfigurationKeyEnum(String initialValue, Class<?> valueKind,
                         ConfigCategory category, boolean legacyOnly) {
        this.initialValue = initialValue;
        this.valueKind    = valueKind;
        this.category     = category;
        this.legacyOnly   = legacyOnly;
    }

    /* ---- primary accessors ---- */

    /** Serialised fallback used when no persisted value exists. */
    public String initialValue()     { return initialValue; }

    /** The Java type that raw values should be converted to. */
    public Class<?> valueKind()      { return valueKind; }

    /** Which operator-panel group this key belongs to. */
    public ConfigCategory category() { return category; }

    /** Whether this key is retained only for reading existing persisted configs. */
    public boolean isLegacyOnly() { return legacyOnly; }

    /* ---- convenience queries ---- */

    /**
     * Indicates whether this key governs a simple on/off toggle.
     */
    public boolean isToggle() {
        return valueKind == Boolean.class;
    }

    /**
     * Whether this key expects an integer, long, or double value.
     */
    public boolean isNumeric() {
        return valueKind == Integer.class
                || valueKind == Long.class
                || valueKind == Double.class;
    }

    /**
     * Whether this key stores free-form text or a date-time string.
     */
    public boolean isTextual() {
        return valueKind == String.class
                || valueKind == LocalDateTime.class;
    }

    /**
     * Tests whether this key is assigned to the given category.
     *
     * @param cat the category to check
     * @return {@code true} on match
     */
    public boolean belongsTo(ConfigCategory cat) {
        return this.category == cat;
    }

    /**
     * Interprets the initial value as a boolean directly.
     */
    public boolean defaultAsBoolean() {
        return Boolean.parseBoolean(initialValue);
    }

    /**
     * Interprets the initial value as an integer, yielding zero
     * when the stored text is not a valid number.
     */
    public int defaultAsInt() {
        try {
            return Integer.parseInt(initialValue);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * Interprets the initial value as a long, yielding zero
     * when the stored text is not a valid number.
     */
    public long defaultAsLong() {
        try {
            return Long.parseLong(initialValue);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /**
     * Collects every constant assigned to the requested category.
     *
     * <p>The result is freshly constructed on each invocation; callers
     * on performance-critical paths should cache the outcome.</p>
     *
     * @param cat the target category
     * @return an unmodifiable set of matching keys
     */
    public static Set<ConfigurationKeyEnum> byCategory(ConfigCategory cat) {
        EnumSet<ConfigurationKeyEnum> accumulator =
                EnumSet.noneOf(ConfigurationKeyEnum.class);
        for (ConfigurationKeyEnum entry : values()) {
            if (!entry.legacyOnly && entry.category == cat) {
                accumulator.add(entry);
            }
        }
        return accumulator;
    }

    /**
     * Returns the total number of keys assigned to the given category.
     *
     * @param cat the category to count
     * @return the number of matching keys
     */
    public static int countByCategory(ConfigCategory cat) {
        int count = 0;
        for (ConfigurationKeyEnum entry : values()) {
            if (!entry.legacyOnly && entry.category == cat) {
                count++;
            }
        }
        return count;
    }

    /**
     * Case-insensitive lookup by enum constant name.
     *
     * @param keyName the name to resolve
     * @return the matching key, or {@code null} when not found
     */
    public static ConfigurationKeyEnum fromName(String keyName) {
        if (keyName == null) return null;
        return NAME_INDEX.get(keyName.trim().toUpperCase());
    }

    /**
     * Converts a raw persistence string into this key's declared
     * Java type using the internal dispatch table.
     *
     * @throws UnsupportedOperationException when no converter is
     *         registered for the declared type
     */
    @SuppressWarnings("unchecked")
    public <T> T castValue(String raw) {
        Function<String, ?> converter = TYPE_CONVERTERS.get(valueKind);
        if (converter == null) {
            throw new UnsupportedOperationException(
                    "No type converter registered for "
                            + valueKind.getSimpleName());
        }
        return (T) converter.apply(raw);
    }

    /**
     * Attempts to cast a raw string using this key's type converter,
     * returning a fallback on failure instead of throwing.
     *
     * @param raw the raw string to convert
     * @param fallback the value returned on conversion failure
     * @return the converted value, or fallback
     */
    @SuppressWarnings("unchecked")
    public <T> T safelyCast(String raw, T fallback) {
        try {
            return castValue(raw);
        } catch (Exception ex) {
            return fallback;
        }
    }

    /* ---------- backward-compatible accessor shims ---------- */

    public String getDefaultValue() { return initialValue; }
    public Class<?> getType()       { return valueKind; }

    @Override
    public String toString() {
        return name() + "(" + category + ", " + valueKind.getSimpleName() + ")";
    }
}
