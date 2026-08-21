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
    ALLIANCE_CHESTS_OFFSET_INT                          ("240",     Integer.class,  ConfigCategory.ALLIANCE),
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
    ALLIANCE_TECH_OFFSET_INT                            ("200",     Integer.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_TRIUMPH_BOOL                               ("false",   Boolean.class,  ConfigCategory.ALLIANCE),
    ALLIANCE_TRIUMPH_OFFSET_INT                         ("240",     Integer.class,  ConfigCategory.ALLIANCE),

    /* ─────────── city ─────────── */

    CITY_ACCEPT_NEW_SURVIVORS_BOOL              ("false",   Boolean.class,  ConfigCategory.CITY),
    CITY_ACCEPT_NEW_SURVIVORS_OFFSET_INT        ("360",     Integer.class,  ConfigCategory.CITY),
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
    /** Testing-profile-only safety valve — taps the challenge
     *  button to confirm which opponent was selected, then stops before the
     *  actual battle so a targeting-logic change can be verified live without
     *  spending a real attempt. Default false everywhere. */
    ARENA_TASK_DRY_RUN_BOOL             ("false",   Boolean.class,  ConfigCategory.DAILIES),
    ARENA_TASK_ATTACK_QUICK_DEPLOY_BOOL ("true",    Boolean.class,  ConfigCategory.DAILIES),
    /** Legacy arena state filter key retained only so existing persisted profiles can still be read. */
    ARENA_TASK_PLAYER_STATE_INT         ("0",       Integer.class,  ConfigCategory.DAILIES, true),
    ARENA_TASK_SERVER_POLICY_STRING     ("Any server", String.class, ConfigCategory.DAILIES),
    ARENA_TASK_ALLIANCE_POLICY_STRING   ("Avoid profile alliance", String.class, ConfigCategory.DAILIES),
    /** Legacy arena alliance protection key retained only so existing persisted profiles can still be read. */
    ARENA_TASK_PROTECT_ALLIANCE_BOOL    ("true",    Boolean.class,  ConfigCategory.DAILIES, true),
    ARENA_TASK_REFRESH_WITH_GEMS_BOOL   ("false",   Boolean.class,  ConfigCategory.DAILIES),
    DAILY_LABYRINTH_BOOL                ("false",   Boolean.class,  ConfigCategory.DAILIES),
    /** Testing-only gate for the Land-of-Heroes formation-setup flow. When true,
     *  DailyLabyrinthRoutine.execute() ONLY runs setupLandOfHeroesFormation() (tap Challenge → Quick
     *  Deploy → Balance → OCR-drive the troop ratio → use-as-default → Confirm → Edit Formation → STOP)
     *  and skips the normal daily-clear logic, so the free formation-setup can be triggered without
     *  spending a daily battle attempt. Default false everywhere. */
    LABYRINTH_FORMATION_TEST_BOOL       ("false",   Boolean.class,  ConfigCategory.DAILIES),
    /** "kick Labyrinth off at noon every day" -- the daily run used to always
     *  reschedule to the game's own 00:00 UTC reset (dailyResetTime()), which lands at an unintuitive
     *  local-time hour depending on DST. Picked, HH:mm local time, defaults to noon. */
    LABYRINTH_DAILY_START_TIME_STRING   ("12:00",   String.class,   ConfigCategory.DAILIES),
    /** Labyrinth generation the account is playing (this account is Gen 1). Informational + future tuning. */
    LABYRINTH_GENERATION_STRING         ("Gen 1",   String.class,   ConfigCategory.DAILIES),
    /** Land-of-Heroes per-squad troop ratios (Infantry/Lancer/Marksman), driven from the Labyrinth tab.
     *  : defaults set to an alliance-mate's posted recommendation ("For the best results
     *  in the labyrinth ... this will allow you to get the farthest you can") -- Land of Heroes 50/20/30,
     *  applied to both squads since the post gave one ratio per zone. */
    LABYRINTH_SQUAD1_INFANTRY_INT       ("50",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_SQUAD1_LANCER_INT         ("20",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_SQUAD1_MARKSMAN_INT       ("30",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_SQUAD2_INFANTRY_INT       ("50",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_SQUAD2_LANCER_INT         ("20",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_SQUAD2_MARKSMAN_INT       ("30",      Integer.class,  ConfigCategory.DAILIES),
    // "we're up to like three now" -- extended the same per-squad troop-ratio
    // formation-setup to Cave of Monsters and Charm Mine (Labyrinth zones 2 & 3). Each zone's own
    // in-game rule ("Only the stats of Pets/Chief Charms take effect here") means troop stats don't
    // score the fight directly, but composition still affects tanking/positioning -- the explicit
    // call to build the ratio controls for these anyway.
    // Defaults updated to match the same alliance-mate recommendation --
    // Cave of Monsters 50/10/40, Charm Mine 60/20/20. Cave/Charm are single-squad zones
    // (see ZoneFormation.singleSquad -- only squad1Keys is actually read), squad2 kept in sync anyway.
    LABYRINTH_CAVE_SQUAD1_INFANTRY_INT  ("50",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_CAVE_SQUAD1_LANCER_INT    ("10",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_CAVE_SQUAD1_MARKSMAN_INT  ("40",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_CAVE_SQUAD2_INFANTRY_INT  ("50",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_CAVE_SQUAD2_LANCER_INT    ("10",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_CAVE_SQUAD2_MARKSMAN_INT  ("40",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_CHARM_SQUAD1_INFANTRY_INT ("60",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_CHARM_SQUAD1_LANCER_INT   ("20",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_CHARM_SQUAD1_MARKSMAN_INT ("20",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_CHARM_SQUAD2_INFANTRY_INT ("60",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_CHARM_SQUAD2_LANCER_INT   ("20",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_CHARM_SQUAD2_MARKSMAN_INT ("20",      Integer.class,  ConfigCategory.DAILIES),
    // "add the research center in the gear forge... where we can start entering
    // true default troop ratios" -- Research Center and Gear Forge are single-troop-composition
    // Challenge fights (no Squad1/Squad2 split like the other zones), so one Infantry/Lancer/Marksman
    // % triple each. LabyrinthRaidRoutine.challengeZone() uses this as the FIRST attempt's preset,
    // replacing the OCR-derived guess it used to make on its own; a Defeat still falls back to the
    // existing live-tested "escalate to a different lean" safety net for the second attempt. Defaults
    // match the same alliance-mate recommendation the other zones were seeded from: Research Center
    // 50/20/30, Gear Forge 60/10/30.
    LABYRINTH_RESEARCH_INFANTRY_INT     ("50",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_RESEARCH_LANCER_INT       ("20",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_RESEARCH_MARKSMAN_INT     ("30",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_GEARFORGE_INFANTRY_INT    ("60",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_GEARFORGE_LANCER_INT      ("10",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_GEARFORGE_MARKSMAN_INT    ("30",      Integer.class,  ConfigCategory.DAILIES),
    // "once you add Gaia [Heart], I should be able to put in both formation
    // percentages, and you'll automatically be able to adjust those in the game." Live-calibrated the
    // same day (a Sunday, Gaia Heart's real open day) -- it's a genuine two-squad zone (Squad Config,
    // Quick Deploy, per-squad Edit Formation/Balance), same shape as Land of Heroes, NOT single-squad
    // like Cave of Monsters/Charm Mine. Deploys real troops/heroes, not normalized ones, but the
    // Infantry/Lancer/Marksman comp lever works the same way. Squad 3 unlocks at Stage 15-10 (confirmed
    // live -- "Clear Gaia Heart Stage 15-10 to unlock" shown on the still-locked 3rd slot); scaffolded
    // now so the config/UI/automation are all ready the moment this account unlocks it, rather than
    // needing a second round-trip later. Defaults for all 3 squads come from community guides
    // (topuplive.com's Whiteout Survival troops guide + corroborating community writeups, 2026-08-16
    // research pass) recommending exactly a 3-army split of 60/40/0, 50/0/50, 50/20/30 for Gaia Heart
    // -- happens to match the Land of Heroes "mirrored fix" ratio for squads 1-2 plus a third balanced
    // comp for squad 3. Tune from real loss reports once the operator has enough data.
    LABYRINTH_GAIA_SQUAD1_INFANTRY_INT  ("60",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_GAIA_SQUAD1_LANCER_INT    ("40",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_GAIA_SQUAD1_MARKSMAN_INT  ("0",       Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_GAIA_SQUAD2_INFANTRY_INT  ("50",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_GAIA_SQUAD2_LANCER_INT    ("0",       Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_GAIA_SQUAD2_MARKSMAN_INT  ("50",      Integer.class,  ConfigCategory.DAILIES),
    // Squad 3 -- locked until Gaia Heart Stage 15-10. Not yet reachable/testable live, so this ratio
    // is 100% community-sourced (see note above), unverified against the real Squad 3 UI.
    LABYRINTH_GAIA_SQUAD3_INFANTRY_INT  ("50",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_GAIA_SQUAD3_LANCER_INT    ("20",      Integer.class,  ConfigCategory.DAILIES),
    LABYRINTH_GAIA_SQUAD3_MARKSMAN_INT  ("30",      Integer.class,  ConfigCategory.DAILIES),
    DAILY_MISSION_AUTO_SCHEDULE_BOOL    ("false",   Boolean.class,  ConfigCategory.DAILIES),
    DAILY_MISSION_BOOL                  ("false",   Boolean.class,  ConfigCategory.DAILIES),
    DAILY_MISSION_OFFSET_INT            ("720",     Integer.class,  ConfigCategory.DAILIES),
    MAIL_REWARDS_BOOL                   ("false",   Boolean.class,  ConfigCategory.DAILIES),
    MAIL_REWARDS_OFFSET_INT             ("720",     Integer.class,  ConfigCategory.DAILIES),
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
    // Set by IntelligenceRoutine each pass — true when the board actually had a
    // mission, false when empty. GatherRoutine reads it so it only defers/recalls for an imminent
    // Intel that will really consume a march slot, never for Intel's idle ~15-min beast-recheck.
    INTEL_LAST_RUN_HAD_MISSIONS_BOOL            ("false",   Boolean.class,  ConfigCategory.INTEL),
    // "if we run on stamina, go ahead and refresh it" - same
    // Chief Stamina item top-up pattern PolarTerror/Cryptid already use, wired
    // into IntelligenceRoutine's stamina gate so a low-stamina Intel run tops
    // up from the backpack instead of idling until natural regeneration.
    INTEL_USE_STAMINA_ITEMS_BOOL                ("false",   Boolean.class,  ConfigCategory.INTEL),
    INTEL_STAMINA_ITEM_RESERVE_INT              ("0",       Integer.class,  ConfigCategory.INTEL),
    INTEL_USE_FLAG_BOOL                         ("false",   Boolean.class,  ConfigCategory.INTEL),
    // "it's reaching a beast that's too hard to defeat, and it's just looping...
    // then it's gonna run in another fifteen minutes and do the same thing." Root cause: the
    // same-run circuit breaker (consecutiveBeastDeploymentFailures/beastStuckThisRun in
    // IntelligenceRoutine) lives on plain instance fields, but DelayedTaskRegistry.create() hands
    // out a BRAND NEW instance every scheduled run -- so it correctly stops re-attacking a
    // certain-to-fail beast for the rest of ONE run, then forgets completely and re-attacks the
    // identical still-there beast on the very next run 15 minutes later, forever. This timestamp
    // persists that "give up on beast-hunting for a while" state across runs (Survivor
    // Camps/Explorations are unaffected -- only beast/fire-beast attempts are skipped while it's
    // in the future).
    INTEL_BEAST_SKIP_UNTIL_LONG                 ("0",       Long.class,     ConfigCategory.INTEL),

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
    GATHER_NO_HERO_FALLBACK_BOOL    ("false",               Boolean.class,  ConfigCategory.GATHERING),
    GATHER_ROTATION_POOL            ("",                    String.class,   ConfigCategory.GATHERING),
    // pernerch/2026-07-02: timestamp of the last gather recall (Intel/Bear), stored per-profile task instance
    // to track troop return window and avoid re-deploying before troops are home.
    GATHER_LAST_RECALL_TIME_STRING  ("",                    String.class,   ConfigCategory.GATHERING),
    GATHER_SPEED_BOOL               ("false",               Boolean.class,  ConfigCategory.GATHERING),
    GATHER_SPEED_BOOST_TYPE_STRING  ("24h (600 gems)",      String.class,   ConfigCategory.GATHERING),
    // When enabled, the rotation pool is ordered by scarcity-relative-to-value
    // (stockpile / valueWeight, ascending) instead of Collections.shuffle(). See
    // GatherRoutine.RESOURCE_VALUE_WEIGHT for the sourced value ratio. Off by default so existing
    // blind-rotation behavior is unchanged unless a user opts in.
    GATHER_SMART_PRIORITY_BOOL      ("false",               Boolean.class,  ConfigCategory.GATHERING),
    // Cache written by ResourceStockpileRoutine (runs only when
    // GATHER_SMART_PRIORITY_BOOL is on - no separate checkbox to find/enable), read back by
    // GatherRoutine.readCurrentStockpiles(). Source screen: Research Center -> Research -> any
    // non-maxed tech node's "Research Cost" panel, which shows all 4 resources as current/cost -
    // verified live 2026-08-06 (see RESOURCE_STOCKPILE_SCREEN comment in ResourceStockpileRoutine).
    RESOURCE_STOCKPILE_MEAT_LONG        ("0",  Long.class,    ConfigCategory.GATHERING),
    RESOURCE_STOCKPILE_WOOD_LONG        ("0",  Long.class,    ConfigCategory.GATHERING),
    RESOURCE_STOCKPILE_COAL_LONG        ("0",  Long.class,    ConfigCategory.GATHERING),
    RESOURCE_STOCKPILE_IRON_LONG        ("0",  Long.class,    ConfigCategory.GATHERING),
    // Added 2026-08-10 for the Backpack "Resource & Speedup Summary" reader. Steel is the 5th
    // resource (no "Total Items" column, so its "Total Resources" value is read instead), and the
    // five speedup buckets are stored in MINUTES (parsed from the game's "6 day(s)3 hr(s)3 min" form).
    RESOURCE_STOCKPILE_STEEL_LONG       ("0",  Long.class,    ConfigCategory.GATHERING),
    SPEEDUP_GENERAL_MIN_LONG            ("0",  Long.class,    ConfigCategory.GATHERING),
    SPEEDUP_TRAINING_MIN_LONG          ("0",  Long.class,    ConfigCategory.GATHERING),
    SPEEDUP_CONSTRUCTION_MIN_LONG      ("0",  Long.class,    ConfigCategory.GATHERING),
    SPEEDUP_RESEARCH_MIN_LONG          ("0",  Long.class,    ConfigCategory.GATHERING),
    SPEEDUP_HEALING_MIN_LONG           ("0",  Long.class,    ConfigCategory.GATHERING),
    RESOURCE_STOCKPILE_LAST_READ_STRING ("",   String.class,  ConfigCategory.GATHERING),
    GATHER_TASK_BOOL                ("false",               Boolean.class,  ConfigCategory.GATHERING),
    GATHER_WOOD_BOOL                ("false",               Boolean.class,  ConfigCategory.GATHERING),
    GATHER_WOOD_LEVEL_INT           ("8",                   Integer.class,  ConfigCategory.GATHERING),

    /* ─────────── pets ─────────── */

    LIFE_ESSENCE_BOOL                       ("false",   Boolean.class,  ConfigCategory.PETS),
    LIFE_ESSENCE_BUY_WEEKLY_SCROLL_BOOL     ("true",    Boolean.class,  ConfigCategory.PETS),
    LIFE_ESSENCE_CONSECUTIVE_FAILURES_INT   ("0",       Integer.class,  ConfigCategory.PETS),
    LIFE_ESSENCE_NEXT_SCROLL_TIME_STRING    ("",        String.class,   ConfigCategory.PETS),
    LIFE_ESSENCE_OFFSET_INT                 ("360",     Integer.class,  ConfigCategory.PETS),
    PET_PERSONAL_TREASURE_BOOL              ("false",   Boolean.class,  ConfigCategory.PETS),
    PET_SKILL_FOOD_BOOL                     ("false",   Boolean.class,  ConfigCategory.PETS),
    PET_SKILL_GATHERING_BOOL                ("false",   Boolean.class,  ConfigCategory.PETS),
    PET_SKILL_GATHERING_LAST_DEPLOYED_AT_STRING ("",    String.class,   ConfigCategory.PETS),
    PET_SKILL_GATHERING_RESOURCE_STRING     ("MEAT",    String.class,   ConfigCategory.PETS),
    PET_SKILL_STAMINA_BOOL                  ("false",   Boolean.class,  ConfigCategory.PETS),
    PET_SKILL_TREASURE_BOOL                 ("false",   Boolean.class,  ConfigCategory.PETS),
    PET_SKILLS_BOOL                         ("false",   Boolean.class,  ConfigCategory.PETS),

    /* ─────────── shops ─────────── */

    BOOL_CRYSTAL_LAB_DAILY_DISCOUNTED_RFC   ("false",   Boolean.class,  ConfigCategory.SHOPS),
    BOOL_CRYSTAL_LAB_FC                     ("false",   Boolean.class,  ConfigCategory.SHOPS),
    BOOL_DO_EXPLORATION                     ("false",   Boolean.class,  ConfigCategory.SHOPS),
    DO_EXPLORATION_QUICK_DEPLOY_BOOL        ("true",    Boolean.class,  ConfigCategory.SHOPS),
    BOOL_EXPLORATION_CHEST                  ("false",   Boolean.class,  ConfigCategory.SHOPS),
    BOOL_HERO_RECRUITMENT                   ("false",   Boolean.class,  ConfigCategory.SHOPS),
    BOOL_MYSTERY_SHOP                       ("false",   Boolean.class,  ConfigCategory.SHOPS),
    BOOL_MYSTERY_SHOP_250_HERO_WIDGET       ("false",   Boolean.class,  ConfigCategory.SHOPS),
    BOOL_NOMADIC_MERCHANT                   ("false",   Boolean.class,  ConfigCategory.SHOPS),
    BOOL_NOMADIC_MERCHANT_VIP_POINTS        ("false",   Boolean.class,  ConfigCategory.SHOPS),
    BOOL_VIP_POINTS                         ("false",   Boolean.class,  ConfigCategory.SHOPS),
    INT_EXPLORATION_CHEST_OFFSET            ("360",     Integer.class,  ConfigCategory.SHOPS),
    INT_WEEKLY_RFC                          ("0",       Integer.class,  ConfigCategory.SHOPS),
    VIP_MONTHLY_BUY_BOOL                    ("false",   Boolean.class,  ConfigCategory.SHOPS),
    VIP_NEXT_MONTHLY_BUY_TIME_STRING        ("",        String.class,   ConfigCategory.SHOPS),
    WAR_ACADEMY_TASK_BOOL                   ("false",   Boolean.class,  ConfigCategory.SHOPS),
    BOOL_BANK                               ("false",   Boolean.class,  ConfigCategory.SHOPS),
    INT_BANK_DELAY                          ("1",       Integer.class,  ConfigCategory.SHOPS),

    /* ─────────── system ─────────── */

    AUTO_START_DELAY_MINUTES_INT        ("5",           Integer.class,  ConfigCategory.SYSTEM),
    /** Sub-minute precision for the countdown (he wants 30s specifically). Takes priority over the minutes key when present. */
    AUTO_START_DELAY_SECONDS_INT        ("30",          Integer.class,  ConfigCategory.SYSTEM),
    AUTO_START_ENABLED_BOOL             ("false",       Boolean.class,  ConfigCategory.SYSTEM),
    AUTO_START_MODE_STRING              ("Continuous",  String.class,   ConfigCategory.SYSTEM),
    /** Set by the "Full Stop" button — persists across app restarts, unlike a
     *  regular Stop. Suppresses auto-start entirely until Start Bot is clicked manually again. */
    AUTO_START_SUPPRESSED_BOOL          ("false",       Boolean.class,  ConfigCategory.SYSTEM),
    BOOL_DEBUG                          ("false",       Boolean.class,  ConfigCategory.SYSTEM),
    /** Pixels of scatter applied to every tap so repeats never land identically.
     *  0 restores the old exact-pixel behaviour. Kept small — tap targets are button centres. */
    TAP_JITTER_RADIUS_PX_INT            ("3",           Integer.class,  ConfigCategory.SYSTEM),
    /** Percent of the wait added as random delay, so reschedules never land on
     *  an exact repeating time. Always paired with the absolute cap below. 0 disables. */
    SCHEDULE_JITTER_PERCENT_INT         ("15",          Integer.class,  ConfigCategory.SYSTEM),
    /** Hard ceiling on schedule jitter. Without it, 15% of a 16h timer would idle ~2.5h — the operator's
     *  explicit requirement was a cap of a couple of minutes, not a percentage of a long wait. */
    SCHEDULE_JITTER_MAX_SECONDS_INT     ("150",         Integer.class,  ConfigCategory.SYSTEM),
    /** An idle gap at least this long counts as sleep rather than a pause
     *  between tasks. His spec: minimum twenty minutes, no upper bound. Derived from the real
     *  schedule, so it replaced the fixed nightly window he originally asked for. */
    SLEEP_IDLE_THRESHOLD_MINUTES_INT    ("20",          Integer.class,  ConfigCategory.SYSTEM),
    /** Read-only timer sweep at startup and hourly. */
    TIMER_SWEEP_ENABLED_BOOL            ("true",        Boolean.class,  ConfigCategory.SYSTEM),
    /** Minutes between timer sweeps once the startup sweep has run. */
    TIMER_SWEEP_INTERVAL_MINUTES_INT    ("60",          Integer.class,  ConfigCategory.SYSTEM),
    /** DEFAULT OFF, deliberately reversed after the operator watched it run.
     *
     *  <p>Forcing every task due at startup does re-derive real timers, but it does so by
     *  running each routine's full workload — navigate, perform the task, then reschedule.
     *  From the outside that is a bot blindly tearing through every activity it owns the moment
     *  it starts, which is the opposite of the order of operations the request was for: read all the
     *  timers first, record them, then act only on what is genuinely due.</p>
     *
     *  <p>Left in place as an escape hatch for the case it was built for — schedules gone badly
     *  stale after a profile sat disabled — but it is not the startup path. The read-only timer
     *  sweep is.</p> */
    STARTUP_FULL_RESCAN_BOOL            ("false",       Boolean.class,  ConfigCategory.SYSTEM),
    CURRENT_EMULATOR_STRING             ("",            String.class,   ConfigCategory.SYSTEM),
    DISCORD_TOKEN_STRING                ("",            String.class,   ConfigCategory.SYSTEM),
    GAME_VERSION_STRING                 ("GLOBAL",      String.class,   ConfigCategory.SYSTEM),
    IDLE_BEHAVIOR_STRING                ("CLOSE_EMULATOR", String.class, ConfigCategory.SYSTEM),
    // Allow explicit stop-policy selection for GUI stop action.
    STOP_BEHAVIOR_STRING                ("DO_NOTHING",  String.class,   ConfigCategory.SYSTEM),
    // Separate Telegram stop behavior from local GUI stop behavior.
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
    TRAIN_PRIORITIZE_PROMOTION_BOOL     ("false",   Boolean.class,  ConfigCategory.TRAINING),

    // Auto-heal injured troops (World map -> the "Heal Injured" panel
    // above My City) + tap Help to speed the queue up via alliance assistance.
    HEAL_INJURED_ENABLED_BOOL           ("false",   Boolean.class,  ConfigCategory.TRAINING),

    // "Explore the World" Atlas/Monument -- claim ready quest rows,
    // open owned Scene Fragment Packs, run daily Alliance Trade requests/sends.
    MONUMENT_ENABLED_BOOL               ("false",   Boolean.class,  ConfigCategory.CITY),

    // "event slop" claim toggles -- rotating limited-time Events-tab
    // events, one checkbox each so the operator can pick which ones the bot bothers with.
    EVENT_HALL_OF_CHIEFS_CLAIM_BOOL      ("false",   Boolean.class,  ConfigCategory.EVENTS),
    EVENT_DEFEAT_BEASTS_CLAIM_BOOL       ("false",   Boolean.class,  ConfigCategory.EVENTS),
    EVENT_HERO_RALLY_CLAIM_BOOL          ("false",   Boolean.class,  ConfigCategory.EVENTS),
    EVENT_LUCKY_CHIP_SUPPLY_CLAIM_BOOL   ("false",   Boolean.class,  ConfigCategory.EVENTS),
    EVENT_BROTHERS_IN_ARMS_CLAIM_BOOL    ("false",   Boolean.class,  ConfigCategory.EVENTS),

    // Top-right cart-icon Shop panel, built out tab by tab. Custom
    // Armament Chest's free "Claimable" chest badge is periodic (may not appear for
    // weeks) -- checked once a day, no-ops when nothing's there.
    SHOP_CUSTOM_ARMAMENT_CHEST_CLAIM_BOOL ("false",  Boolean.class,  ConfigCategory.SHOPS),
    SHOP_DAILY_DEALS_FREE_CHEST_CLAIM_BOOL ("false", Boolean.class,  ConfigCategory.SHOPS),

    // "the new labyrinth" -- Research Center + Gear Forge stage-raid claims.
    LABYRINTH_RAID_ENABLED_BOOL ("false", Boolean.class, ConfigCategory.DAILIES),

    // Bearguard: Berserk Cryptid rally HOSTING, distinct from the RALLY_* keys
    // above which configure joining someone else's. Appended at the end so
    // upstream's own additions merge cleanly instead of conflicting.
    // Flag 0 means "leave the deploy formation exactly as presented" - loading
    // a preset swaps heroes too, and an empty preset deploys hero-less.
    CRYPTID_HOST_ENABLED_BOOL           ("false",   Boolean.class,  ConfigCategory.EVENTS),
    CRYPTID_HOST_RUNS_INT               ("1",       Integer.class,  ConfigCategory.EVENTS),
    CRYPTID_HOST_USE_STAMINA_ITEMS_BOOL ("false",   Boolean.class,  ConfigCategory.EVENTS),
    CRYPTID_HOST_FLAG_INT               ("0",       Integer.class,  ConfigCategory.EVENTS),

    // Bearguard: Chat capture. World/Alliance/Personal are scraped on a
    // schedule and diffed against what was already captured, so repeat runs
    // only add genuinely new messages rather than re-saving the same history
    // every cycle.
    //
    // MODE and FILTER_NOISE are honoured downstream (the dashboard/website
    // side), not by this bot. The bot's own OCR has no way to understand
    // meaning well enough to write a real summary - that needs an LLM pass
    // over the captured transcript, which happens after capture, not during
    // it. Recording the preference now means the downstream pass does not
    // need its own separate settings surface.
    CHAT_CAPTURE_ENABLED_BOOL           ("false",           Boolean.class,  ConfigCategory.SYSTEM),
    CHAT_CAPTURE_FREQUENCY_MINUTES_INT  ("30",              Integer.class,  ConfigCategory.SYSTEM),
    CHAT_CAPTURE_INCLUDE_WORLD_BOOL     ("true",            Boolean.class,  ConfigCategory.SYSTEM),
    CHAT_CAPTURE_INCLUDE_ALLIANCE_BOOL  ("true",            Boolean.class,  ConfigCategory.SYSTEM),
    CHAT_CAPTURE_INCLUDE_PERSONAL_BOOL  ("false",           Boolean.class,  ConfigCategory.SYSTEM),
    // TRANSCRIPT = keep full readable message history; SUMMARY = downstream
    // should condense to "what happened today" instead of showing every line.
    CHAT_CAPTURE_MODE_STRING            ("TRANSCRIPT",      String.class,   ConfigCategory.SYSTEM),
    // Applied live, not just tagged: emote/sticker-only messages (no letters
    // or digits after stripping punctuation) are dropped before saving, since
    // that much is plain pattern matching and does not need an LLM.
    CHAT_CAPTURE_FILTER_NOISE_BOOL      ("true",            Boolean.class,  ConfigCategory.SYSTEM);

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
