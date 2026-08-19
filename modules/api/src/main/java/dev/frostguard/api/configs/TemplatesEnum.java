package dev.frostguard.api.configs;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Comprehensive catalogue of PNG template assets consumed by the
 * computer-vision pipeline for screen-state detection, button
 * location, and OCR region extraction.
 *
 * <p>Each constant is logically associated with a {@link TemplateArea}
 * that is inferred from the resource path at first access. Constants
 * are grouped by engine workflow priority and loaded lazily from a
 * bundled properties file.</p>
 */
public enum TemplatesEnum {
    GAME_HOME_FURNACE, GAME_HOME_WORLD, GAME_HOME_PETS, GAME_HOME_INTEL, GAME_HOME_INTEL_DONE,
    GAME_HOME_RECONNECT, GAME_START_DOWNLOAD_NOW, GAME_HOME_NEW_SURVIVORS, GAME_HOME_NEW_SURVIVORS_WELCOME_IN, GAME_HOME_NEW_SURVIVORS_PLUS_BUTTON, GAME_HOME_BOTTOM_BAR_SHOP_BUTTON,
    GAME_HOME_BOTTOM_BAR_BACKPACK_BUTTON, HOME_DEALS_BUTTON, HOME_EVENTS_BUTTON, EVENTS_SUNFIRE_TAB, SUNFIRE_MINISTRY_APPLY_BUTTON,
    LEFT_MENU_CITY_TAB, LEFT_MENU_LABYRINTH_BUTTON, LEFT_MENU_TUNDRA_TREK_BUTTON, LEFT_MENU_EXPERT_TRAINING_BUTTON,
    SIDEBAR_DAILY_ARENA, SIDEBAR_DAILY_PET_ADVENTURE, SIDEBAR_DAILY_LAND_OF_HEROES,
    SIDEBAR_DAILY_LIFE_ESSENCE, LIGHTHOUSE_INTEL_BUBBLE,
    GAME_HOME_SHORTCUTS_INFANTRY,
    GAME_HOME_SHORTCUTS_LANCER, GAME_HOME_SHORTCUTS_MARKSMAN, GAME_HOME_SHORTCUTS_RESEARCH_CENTER, GAME_HOME_SHORTCUTS_HELP_REQUEST, GAME_HOME_SHORTCUTS_HELP_REQUEST1,
    GAME_HOME_SHORTCUTS_HELP_REQUEST2, GAME_HOME_SHORTCUTS_HELP_REQUEST3, GAME_HOME_SHORTCUTS_HELP_REQUEST4, GAME_HOME_SHORTCUTS_UPGRADE, GAME_HOME_SHORTCUTS_OBTAIN, GAME_HOME_SHORTCUTS_ATTACK,
    GAME_HOME_SHORTCUTS_GO, GAME_HOME_SHORTCUTS_UPGRADE_TEXT, FURNACE_UPGRADE_PACK, FURNACE_UPGRADE_CLAIM, GAME_HOME_CITY_STATUS_GO_BUTTON, SHIELD_MY_CITY,
    GAME_HOME_CITY_STATUS_COOKHOUSE, GAME_HOME_WAR, GAME_HOME_SHORTCUTS_MEAT, GAME_HOME_SHORTCUTS_WOOD, GAME_HOME_SHORTCUTS_COAL,
    GAME_HOME_SHORTCUTS_IRON, GAME_HOME_SHORTCUTS_FARM_MEAT, GAME_HOME_SHORTCUTS_FARM_WOOD, GAME_HOME_SHORTCUTS_FARM_COAL, GAME_HOME_SHORTCUTS_FARM_IRON,
    GAME_HOME_SHORTCUTS_FARM_TICK, GAME_HOME_SHORTCUTS_FARM_GATHER, GATHER_DEPLOY_BUTTON, GATHER_MEAT_HERO, GATHER_WOOD_HERO,
    GATHER_COAL_HERO, GATHER_IRON_HERO, BUILDING_BUTTON_TRAIN, BUILDING_BUTTON_SPEED, BUILDING_BUTTON_UPGRADE,
    BUILDING_BUTTON_DETAILS, BUILDING_BUTTON_RESEARCH, BUILDING_BUTTON_LABYRINTH, BUILDING_BUTTON_INFO, BUILDING_DETAILS_INFANTRY,
    BUILDING_DETAILS_LANCER, BUILDING_DETAILS_MARKSMAN, BUILDING_SURVIVOR_BUTTON_UPGRADE, BUILDING_SURVIVOR_BUTTON_FURNITURE_UPGRADE, DAILY_MISSION_DAILY_TAB,
    DAILY_MISSION_SCREEN_TITLE, DAILY_MISSION_DAILY_TAB_BUTTON, DAILY_MISSION_CLAIMALL_BUTTON, DAILY_MISSION_CLAIM_BUTTON, DAILY_MISSION_CLAIM_BUTTON_DISABLED,
    ALLIANCE_CHEST_CLAIM_BUTTON, ALLIANCE_CHEST_CLAIM_ALL_BUTTON, ALLIANCE_CHAMPIONSHIP_TAB,
    ALLIANCE_CHAMPIONSHIP_TROOPS_BUTTON, ALLIANCE_CHAMPIONSHIP_REGISTER_BUTTON, ALLIANCE_CHAMPIONSHIP_SWITCH_LINE_BUTTON, ALLIANCE_CHAMPIONSHIP_UPDATE_TROOPS_BUTTON, ALLIANCE_CHAMPIONSHIP_DISPATCH_TROOPS_BUTTON,
    STOREHOUSE_CHEST, STOREHOUSE_CHEST_2, STOREHOUSE_STAMINA, STOREHOUSE_SELECTED_CURRENT,
    MAIL_UNCLAIMED_REWARDS, MAIL_UNREAD_REPORT_SHORTCUT, MAIL_MENU_OPEN,
    MAIL_MENU, ARENA_CHALLENGE_BUTTON, ARENA_CHALLENGE_BUTTON_CURRENT, ARENA_FREE_REFRESH_BUTTON,
    ARENA_GEMS_REFRESH_BUTTON, ARENA_GEMS_REFRESH_CONFIRM_BUTTON,
    ARENA_GEMS_EXTRA_ATTEMPTS_BUTTON, ARENA_VICTORY, GAME_HOME_CAMP_TRAIN, TRAINING_TRAIN_BUTTON, REPLENISH_ALL_BUTTON, TROOPS_ALREADY_MARCHING,
    RALLY_BUTTON, RALLY_REMOVE_HERO_BUTTON, RALLY_EQUALIZE_BUTTON, RALLY_HOLD_BUTTON, RALLY_LOCKED_FLAG_SLOT,
    MARCH_QUEUE_RETURNING_ICON, MARCH_QUEUE_SLOT_FLAG_ICON, MARCH_QUEUE_RALLY_ICON, MARCH_QUEUE_ATTACK_ICON,
    MARCH_QUEUE_ENCAMPMENT_ICON, MARCH_QUEUE_REINFORCEMENT_ICON, MARCH_QUEUE_GARRISONED_ICON, MARCH_QUEUE_TEXT_GO_TO,
    MARCH_QUEUE_TEXT_GATHERING, MARCH_QUEUE_TEXT_ATTACK, MARCH_QUEUE_STATUS_IDLE,
    MARCH_QUEUE_STATUS_IDLE_CURRENT, MARCH_QUEUE_STATUS_UNLOCK,
    MARCH_QUEUE_STATUS_UNAVAILABLE, MARCH_QUEUE_MEAT_ICON, MARCH_QUEUE_WOOD_ICON, MARCH_QUEUE_COAL_ICON,
    MARCH_QUEUE_IRON_ICON,
    RALLY_TROOP_TRAINING_BUTTON, RALLY_MARCH_QUEUE_FULL, STAMINA_ITEM_USE_BUTTON,
    TRAINING_TROOP_PROMOTE,
    TRAINING_TROOP_LOCKED, MARCHES_AREA_RECALL_BUTTON, MARCHES_AREA_SPEEDUP_BUTTON, MARCHES_AREA_VIEW_BUTTON, TRAINING_INFANTRY_T11,
    TRAINING_INFANTRY_T10, TRAINING_INFANTRY_T9, TRAINING_INFANTRY_T8, TRAINING_INFANTRY_T7, TRAINING_INFANTRY_T6,
    TRAINING_INFANTRY_T5, TRAINING_INFANTRY_T4, TRAINING_INFANTRY_T3, TRAINING_INFANTRY_T2, TRAINING_INFANTRY_T1,
    TRAINING_LANCER_T11, TRAINING_LANCER_T10, TRAINING_LANCER_T9, TRAINING_LANCER_T8, TRAINING_LANCER_T7,
    TRAINING_LANCER_T6, TRAINING_LANCER_T5, TRAINING_LANCER_T4, TRAINING_LANCER_T3, TRAINING_LANCER_T2,
    TRAINING_LANCER_T1, TRAINING_MARKSMAN_T11, TRAINING_MARKSMAN_T10, TRAINING_MARKSMAN_T9, TRAINING_MARKSMAN_T8,
    TRAINING_MARKSMAN_T7, TRAINING_MARKSMAN_T6, TRAINING_MARKSMAN_T5, TRAINING_MARKSMAN_T4, TRAINING_MARKSMAN_T3,
    TRAINING_MARKSMAN_T2, TRAINING_MARKSMAN_T1, ALLIANCE_CHEST_BUTTON, ALLIANCE_HONOR_CHEST, ALLIANCE_TECH_BUTTON,
    ALLIANCE_TRIUMPH_BUTTON, ALLIANCE_TRIUMPH_DAILY_CLAIMED, ALLIANCE_TRIUMPH_DAILY,
    ALLIANCE_TRIUMPH_WEEKLY, ALLIANCE_TECH_THUMB_UP, ALLIANCE_TECH_THUMB_UP_CURRENT,
    ALLIANCE_WAR_BUTTON, ALLIANCE_TERRITORY_BUTTON, ALLIANCE_SHOP_BUTTON, ALLIANCE_HELP_BUTTON, ALLIANCE_HELP_REQUESTS,
    ALLIANCE_SHOP_EXPERT_ICON, ALLIANCE_SHOP_SOLD_OUT, ALLIANCE_SHOP_100_VIP_XP, ALLIANCE_SHOP_10_VIP_XP, ALLIANCE_SHOP_RECALL_MARCH,
    ALLIANCE_SHOP_ADVANCED_TELEPORT, ALLIANCE_SHOP_TERRITORY_TELEPORT, ALLIANCE_MOBILIZATION_TAB, ALLIANCE_MOBILIZATION_UNSELECTED_TAB, AM_120_PERCENT,
    AM_200_PERCENT, AM_BAR_X, AM_COMPLETED, AM_PLUS_1_FREE_MISSION, AM_ALLIANCE_MONUMENTS,
    AM_BUILD_SPEEDUPS, AM_BUY_PACKAGE, AM_CHIEF_GEAR_CHARM, AM_CHIEF_GEAR_SCORE, AM_DEFEAT_BEASTS,
    AM_FIRE_CRYSTAL, AM_GATHER_RESOURCES, AM_HERO_GEAR_STONE, AM_MYTHIC_SHARD, AM_RALLY,
    AM_TRAIN_TROOPS, AM_TRAINING_SPEEDUPS, AM_USE_GEMS, AM_USE_SPEEDUPS, CRYSTAL_LAB_FC_BUTTON,
    CRYSTAL_LAB_REFINE_BUTTON, CRYSTAL_LAB_DAILY_DISCOUNTED_RFC, CRYSTAL_LAB_RFC_REFINE_BUTTON, VIP_UNLOCK_BUTTON, VIP_MENU,
    INTEL_COMPLETED, INTEL_VIEW, INTEL_ATTACK, INTEL_RESCUE, INTEL_EXPLORE,
    INTEL_FIRE_BEAST, INTEL_SCREEN_1, INTEL_SCREEN_2, INTEL_AGNES, DEPLOY_BUTTON,
    DEPLOY_CONFIRMATION_DIALOG, INTEL_BEAST_GRAYSCALE, INTEL_BEAST_GRAYSCALE_FC, INTEL_BEAST_GRAYSCALE_FC1, INTEL_SURVIVOR_GRAYSCALE,
    INTEL_SURVIVOR_GRAYSCALE_FC, INTEL_JOURNEY_GRAYSCALE, INTEL_JOURNEY_GRAYSCALE_FC, INTEL_MASTER_BOUNTY, PETS_BEAST_CAGE,
    PETS_BEAST_ALLIANCE_CLAIM, PETS_ALLY_TREASURE, PETS_INFO_SKILLS, PETS_SKILL_USE, PETS_UNLOCK_TEXT,
    PETS_CHEST_COMPLETED,
    PETS_CHEST_SELECT, PETS_CHEST_START, PETS_CHEST_ATTEMPT, PETS_CHEST_SHARE, PETS_CHEST_RED,
    PETS_CHEST_PURPLE, PETS_CHEST_BLUE, LIFE_ESSENCE_MENU, LIFE_ESSENCE_CLAIM,
    LIFE_ESSENCE_CLAIM_CURRENT, LIFE_ESSENCE_DAILY_CARING_AVAILABLE,
    LIFE_ESSENCE_DAILY_CARING_GOTO_ISLAND, LIFE_ESSENCE_DAILY_CARING_BUTTON,
    LIFE_ESSENCE_DAILY_CARING_BUTTON_CURRENT, ISLAND_WEEKLY_FREE_SCROLL,
    ISLAND_WEEKLY_FREE_SCROLL_BUY_BUTTON, ISLAND_LIKE_BUTTON,
    NOMADIC_MERCHANT_COAL, NOMADIC_MERCHANT_WOOD, NOMADIC_MERCHANT_MEAT, NOMADIC_MERCHANT_STONE, NOMADIC_MERCHANT_VIP,
    NOMADIC_MERCHANT_REFRESH, SHOP_MYSTERY_BUTTON, MYSTERY_SHOP_FREE_REWARD, MYSTERY_SHOP_DAILY_REFRESH, MYSTERY_SHOP_250_BADGES_BUTTON,
    MYSTERY_SHOP_MYTHIC_SHARDS_BUTTON, EVENTS_DEALS_BANK, EVENTS_DEALS_BANK_INDEPOSIT, EVENTS_DEALS_BANK_DEPOSIT, EVENTS_DEALS_BANK_WITHDRAW,
    EXPLORATION_CLAIM, EXPLORATION_CLAIM_DISABLED, EXPLORATION_BUTTON, EXPLORATION_VICTORY, EXPLORATION_DEFEAT, HERO_RECRUIT_CLAIM,
    LABYRINTH_DUNGEON_1, LABYRINTH_DUNGEON_2, LABYRINTH_DUNGEON_3, LABYRINTH_DUNGEON_4, LABYRINTH_DUNGEON_5,
    LABYRINTH_DUNGEON_6, LABYRINTH_QUICK_CHALLENGE, LABYRINTH_QUICK_CHALLENGE_CURRENT,
    LABYRINTH_NORMAL_CHALLENGE, LABYRINTH_RAID_CHALLENGE, LABYRINTH_QUICK_DEPLOY,
    LABYRINTH_DEPLOY, VALIDATION_WAR_ACADEMY_UI, VALIDATION_CRYSTAL_LAB_UI, TUNDRA_TRUCK_TAB, TUNDRA_TRUCK_ARRIVED,
    TUNDRA_TRUCK_YELLOW, TUNDRA_TRUCK_PURPLE, TUNDRA_TRUCK_BLUE, TUNDRA_TRUCK_GREEN, TUNDRA_TRUCK_REFRESH,
    TUNDRA_TRUCK_REFRESH_GEMS, TUNDRA_TRUCK_TIPS_POPUP, TUNDRA_TRUCK_YELLOW_RAID, TUNDRA_TRUCK_ESCORT, TUNDRA_TRUCK_DEPARTED,
    TUNDRA_TRUCK_ENDED, TUNDRA_TREK_SUPPLIES, TUNDRA_TREK_CLAIM_BUTTON, TUNDRA_TREK_AUTO_BUTTON, TUNDRA_TREK_BAG_BUTTON,
    TUNDRA_TREK_SKIP_BUTTON, TUNDRA_TREK_BLUE_BUTTON, TUNDRA_TREK_CHECK_ACTIVE, TUNDRA_TREK_CHECK_INACTIVE, JOURNEY_OF_LIGHT_TAB,
    JOURNEY_OF_LIGHT_UNSELECTED_TAB, JOURNEY_OF_LIGHT_FREE_WATCHES, JOURNEY_OF_LIGHT_CLAIM_WATCHES, ROMULUS_CLAIM_TROOPS_BUTTON, ROMULUS_CLAIM_TAG_BUTTON,
    AGNES_CLAIM_INTEL, EXPERT_TRAINING_SPEEDUP_ICON, EXPERT_TRAINING_CYRILLE_BADGE, EXPERT_TRAINING_AGNES_BADGE, EXPERT_TRAINING_ROMULUS_BADGE,
    EXPERT_TRAINING_HOLGER_BADGE, EXPERT_TRAINING_BALDUR_BADGE, EXPERT_TRAINING_FABIAN_BADGE, EXPERT_TRAINING_LEARN_BUTTON, MERCENARY_EVENT_TAB,
    MERCENARY_SCOUT_BUTTON, MERCENARY_CHALLENGE_BUTTON, MERCENARY_ATTACK_BUTTON, MERCENARY_DEPLOY_BUTTON, MERCENARY_DIFFICULTY_CHALLENGE,
    MERCENARY_EPIC_INITIATION_SELECTED, MERCENARY_EPIC_INITIATION_UNSELECTED, MERCENARY_CHAMPIONS_INITIATION_SELECTED, MERCENARY_CHAMPIONS_INITIATION_UNSELECTED, MERCENARY_LEGENDS_INITIATION_SELECTED,
    MERCENARY_LEGENDS_INITIATION_UNSELECTED, HERO_MISSION_EVENT_TAB, HERO_MISSION_EVENT_TRACE_BUTTON, HERO_MISSION_EVENT_CAPTURE_BUTTON, HERO_MISSION_EVENT_CHEST,
    POLAR_TERROR_SEARCH_ICON, POLAR_TERROR_LEVEL_SLIDER, POLAR_TERROR_TAB_MAGNIFYING_GLASS_ICON, POLAR_TERROR_TAB_SPECIAL_REWARDS, POLAR_TERROR_LEVEL_SELECTOR,
    POLAR_TERROR_FLAG_SELECTOR, POLAR_TERROR_MODE_SELECTOR, POLAR_TERROR_DEPLOY_BUTTON, BEAR_HUNT_IS_RUNNING, BEAR_RALLY_BUTTON,
    BEAR_DEPLOY_BUTTON, BEAR_JOIN_PLUS_ICON, RALLY_INDICATOR, BERSERK_CRYPTID_TARGET, CAVE_LION_TARGET,
    SNOW_APE_TARGET, BEAST_SEARCH_ICON, RALLY_JOIN, RALLY_DEPLOY_BUTTON, EVENTS_MYRIAD_BAZAAR_ICON, NUMBER_1,
    NUMBER_2, NUMBER_3, NUMBER_4, NUMBER_5, NUMBER_6,
    NUMBER_7, NUMBER_8, NUMBER_9, NUMBER_10, FISHING_HOOK,
    FISHING_PUFFERFISH, FISHING_REDFISH, FISHING_SMALLFISH, FISHING_STRIPEFISH, CHIEF_ORDER_MENU_BUTTON,
    CHIEF_ORDER_RUSH_JOB, CHIEF_ORDER_URGENT_MOBILISATION, CHIEF_ORDER_PRODUCTIVITY_DAY, CHIEF_ORDER_ENACT_BUTTON, GAME_PROFILE_SETTINGS_BUTTON,
    GAME_PROFILE_SETTINGS_SWITCH_CHARACTER_BUTTON, GAME_PROFILE_SETTINGS_CHARACTER_FURNACE_LEVEL_ACTIVE, GAME_PROFILE_SETTINGS_CHARACTER_FURNACE_LEVEL_INACTIVE, GAME_PROFILE_SETTINGS_CHARACTER_ACTIVE_CHECKMARK, GAME_PROFILE_SETTINGS_SWITCH_CHARACTER_CONFIRM_BUTTON,
    GAME_PROFILE_SETTINGS_SWITCH_CHARACTER_CANCEL_BUTTON, RESEARCH_TEXT,
    RESEARCH_HELP_BUTTON, RESEARCH_SPEEDUP_BUTTON,
    SKIP_TUTORIAL_HAND, SKIP_TUTORIAL_HAND_MIRROR, SKIP_TUTORIAL_BUTTON;

    /* ================================================================
     *  Functional area taxonomy for batch queries and filtering.
     * ================================================================ */

    /** High-level functional classification of a template asset. */
    public enum TemplateArea {
        NAVIGATION, SHORTCUT, RESOURCE, BUILDING, TRAINING,
        DAILY, ALLIANCE, INTEL, PET, ISLAND, SHOP,
        EVENT, COMBAT, VALIDATION, TUTORIAL, RESEARCH, NUMBER
    }

    /* ---- bundled path registry ---- */

    private static final Properties ASSET_PATHS = new Properties();

    static {
        try (InputStream stream = TemplatesEnum.class
                .getResourceAsStream("/config/templates.properties")) {
            if (stream != null) {
                ASSET_PATHS.load(stream);
            } else {
                System.err.println(
                        "Warning: templates.properties resource is missing!");
            }
        } catch (Exception ex) {
            throw new RuntimeException(
                    "Unable to initialise template asset paths", ex);
        }
    }

    /* ---- reverse-lookup from resource path to constant ---- */

    private static final Map<String, TemplatesEnum> BY_RESOURCE_PATH;

    static {
        Map<String, TemplatesEnum> temp = new HashMap<>();
        for (TemplatesEnum tpl : values()) {
            String path = tpl.resourcePath();
            if (path != null && !temp.containsKey(path)) {
                temp.put(path, tpl);
            }
        }
        BY_RESOURCE_PATH = Collections.unmodifiableMap(temp);
    }

    /* ---- per-constant mutable state (lazy init) ---- */

    private String resourcePath;
    private String assetFolder;
    private String assetFile;
    private TemplateArea area;
    private boolean initialized = false;

    TemplatesEnum() {
        // Deferred: enum constants exist before the static block runs
    }

    /**
     * Performs one-time derivation of path components and area.
     * Thread-safe via double-checked locking.
     */
    private void ensureInitialized() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    this.resourcePath = ASSET_PATHS.getProperty(name());
                    if (this.resourcePath == null) {
                        this.resourcePath = "/templates/"
                                + name().toLowerCase() + ".png";
                    }
                    this.area = deduceArea(this.resourcePath);
                    int lastSlash = this.resourcePath.lastIndexOf('/');
                    if (lastSlash > 0) {
                        this.assetFolder = this.resourcePath.substring(
                                0, lastSlash);
                        this.assetFile = this.resourcePath.substring(
                                lastSlash + 1);
                    } else {
                        this.assetFolder = "/templates";
                        this.assetFile   = this.resourcePath;
                    }
                    this.initialized = true;
                }
            }
        }
    }

    /* ---- primary accessors ---- */

    /** Classpath-relative location of the PNG asset. */
    public String resourcePath() { ensureInitialized(); return resourcePath; }

    /** Parent directory portion of the resource path. */
    public String assetFolder()  { ensureInitialized(); return assetFolder; }

    /** Filename portion of the resource path. */
    public String assetFile()    { ensureInitialized(); return assetFile; }

    /** Inferred functional classification. */
    public TemplateArea area()   { ensureInitialized(); return area; }

    /* ---- query helpers ---- */

    /** Whether this template belongs to the specified area. */
    public boolean belongsTo(TemplateArea target)  { return area() == target; }

    /** Shorthand check for navigation-related templates. */
    public boolean isNavigationTemplate()          { return area() == TemplateArea.NAVIGATION; }

    /** Shorthand check for combat-related templates. */
    public boolean isCombatTemplate()              { return area() == TemplateArea.COMBAT; }

    /** Shorthand check for event-related templates. */
    public boolean isEventTemplate()               { return area() == TemplateArea.EVENT; }

    /** Shorthand check for alliance-related templates. */
    public boolean isAllianceTemplate()            { return area() == TemplateArea.ALLIANCE; }

    /** Shorthand check for shop-related templates. */
    public boolean isShopTemplate()                { return area() == TemplateArea.SHOP; }

    /** True for training tier templates (e.g. TRAINING_INFANTRY_T5). */
    public boolean isTrainingTier() {
        return area() == TemplateArea.TRAINING
                && name().startsWith("TRAINING_");
    }

    /**
     * Whether a classpath resource actually exists at this
     * template's declared path.
     *
     * @return {@code true} when the resource can be located
     */
    public boolean existsAtPath() {
        return TemplatesEnum.class.getResource(resourcePath()) != null;
    }

    /** Returns the total number of template constants defined. */
    public static int totalCount() {
        return values().length;
    }

    /**
     * Gathers every template assigned to the specified area.
     *
     * @param target the area to filter by
     * @return an unmodifiable list of matching templates
     */
    public static List<TemplatesEnum> inArea(TemplateArea target) {
        List<TemplatesEnum> collected = new ArrayList<>();
        for (TemplatesEnum tpl : values()) {
            if (tpl.area() == target) {
                collected.add(tpl);
            }
        }
        return Collections.unmodifiableList(collected);
    }

    /**
     * Reverse lookup by resource path string.
     *
     * @param path the classpath-relative PNG path
     * @return the matching constant, or {@code null} when unrecognised
     */
    public static TemplatesEnum fromPath(String path) {
        if (path == null) {
            return null;
        }
        // Force static initialisation so BY_RESOURCE_PATH is populated
        values();
        return BY_RESOURCE_PATH.get(path);
    }

    /* ---------- backward-compatible accessor shims ---------- */

    public String imagePath()   { return resourcePath(); }
    public String getTemplate() { return resourcePath(); }

    @Override
    public String toString() {
        return name() + "[" + area() + "]";
    }

    /* ---- area deduction from path segments ---- */

    private static TemplateArea deduceArea(String path) {
        if (containsSegment(path, "/training/"))     return TemplateArea.TRAINING;
        if (containsSegment(path, "/shortcuts/"))    return TemplateArea.SHORTCUT;
        if (containsSegment(path, "/alliance/"))     return TemplateArea.ALLIANCE;
        if (containsSegment(path, "/home/"))         return TemplateArea.NAVIGATION;
        if (containsSegment(path, "/intel/"))        return TemplateArea.INTEL;
        if (containsSegment(path, "/pets/"))         return TemplateArea.PET;
        if (containsSegment(path, "/building/"))     return TemplateArea.BUILDING;
        if (containsSegment(path, "/gathering/"))    return TemplateArea.RESOURCE;
        if (containsSegment(path, "/events/"))       return TemplateArea.EVENT;
        if (containsSegment(path, "/rally/"))        return TemplateArea.COMBAT;
        if (containsSegment(path, "/arena/"))        return TemplateArea.COMBAT;
        if (containsSegment(path, "/polarterror/"))  return TemplateArea.COMBAT;
        if (containsSegment(path, "/island/"))       return TemplateArea.ISLAND;
        if (containsSegment(path, "/shop/"))         return TemplateArea.SHOP;
        if (containsSegment(path, "/validation/"))   return TemplateArea.VALIDATION;
        if (containsSegment(path, "/skip/"))         return TemplateArea.TUTORIAL;
        if (containsSegment(path, "/research/"))     return TemplateArea.RESEARCH;
        if (containsSegment(path, "/numbers/"))      return TemplateArea.NUMBER;
        if (containsSegment(path, "/labyrinth/"))    return TemplateArea.COMBAT;
        if (containsSegment(path, "/mercenary/"))    return TemplateArea.EVENT;
        if (containsSegment(path, "/chieforder/"))   return TemplateArea.DAILY;
        if (containsSegment(path, "/dailymission/")) return TemplateArea.DAILY;
        if (containsSegment(path, "/storehouse/"))   return TemplateArea.DAILY;
        if (containsSegment(path, "/mail/"))         return TemplateArea.DAILY;
        if (containsSegment(path, "/vip/"))          return TemplateArea.SHOP;
        if (containsSegment(path, "/nomadicmerchant/")) return TemplateArea.SHOP;
        if (containsSegment(path, "/crystallab/"))   return TemplateArea.BUILDING;
        if (containsSegment(path, "/tundra"))        return TemplateArea.EVENT;
        if (containsSegment(path, "/exploration/"))  return TemplateArea.EVENT;
        if (containsSegment(path, "/herorecruitment/")) return TemplateArea.SHOP;
        if (containsSegment(path, "/experts/"))      return TemplateArea.INTEL;
        if (containsSegment(path, "/marches/"))      return TemplateArea.COMBAT;
        if (containsSegment(path, "/profile/"))      return TemplateArea.NAVIGATION;
        if (containsSegment(path, "/fishing"))       return TemplateArea.EVENT;
        if (containsSegment(path, "/myriadbazaar/")) return TemplateArea.EVENT;
        return TemplateArea.NAVIGATION;
    }

    /**
     * Checks whether the given path contains the specified segment.
     * Extracted to its own method for clarity and potential reuse.
     */
    private static boolean containsSegment(String path, String segment) {
        return path.contains(segment);
    }
}
