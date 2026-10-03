package dev.frostguard.engine.nav;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;

/**
 * Coordinate catalogue for interactive game regions and OCR zones.
 * All values target the standard 720 × 1280 viewport.
 */
public final class CommonGameAreas {

    private CommonGameAreas() {}

    // ── micro-factories ──────────────────────────────────────────────

    private static AreaData region(int x1, int y1, int x2, int y2) {
        return new AreaData(new PointData(x1, y1), new PointData(x2, y2));
    }

    private static PointData point(int x, int y) {
        return new PointData(x, y);
    }

    // ── account / energy panel ───────────────────────────────────────

    public static final AreaData PROFILE_AVATAR        = region(24, 24, 61, 61);
    public static final AreaData STAMINA_BUTTON        = region(223, 1101, 244, 1123);
    public static final AreaData STAMINA_OCR_AREA      = region(324, 255, 477, 289);
    public static final AreaData SPENT_STAMINA_OCR_AREA = region(540, 1215, 590, 1245);

    // The cooldown stays in the top banner while Intel markers remain, then moves to the center
    // after every completed Intel reward has been claimed.
    public static final AreaData INTEL_COOLDOWN_WITH_MARKERS_OCR_AREA = region(378, 103, 508, 146);
    public static final AreaData INTEL_COOLDOWN_EMPTY_MAP_OCR_AREA    = region(378, 580, 530, 640);
    public static final AreaData INTEL_CLAIM_ALL_AREA                 = region(190, 1050, 530, 1200);

    // Furnace detail entry, distinct from the final building confirmation dialog.
    public static final AreaData FURNACE_PANEL_TITLE = region(0, 650, 245, 760);
    public static final AreaData FURNACE_PANEL_UPGRADE = region(475, 650, 715, 760);

    // ── side panel navigation ────────────────────────────────────────

    // The collapsed panel exposes only a thin handle at the left edge. A trigger tap is allowed only
    // after the caller has proved that no selected tab is visible, and the result is then verified.
    // Keep the trigger inside the thin arrow that remains exposed beside an expanded World march
    // panel, but away from the absolute display edge where MuMu can discard an otherwise valid tap.
    public static final AreaData LEFT_MENU_TRIGGER        = region(6, 546, 16, 554);
    public static final AreaData LEFT_MENU_CITY_TAB       = region(9, 246, 146, 293);
    public static final AreaData LEFT_MENU_WILDERNESS_TAB = region(155, 246, 293, 293);
    public static final AreaData LEFT_MENU_DAILY_TAB      = region(302, 246, 438, 293);
    public static final AreaData LEFT_MENU_CLOSE          = region(450, 510, 482, 594);

    // Interior samples intentionally avoid rounded borders. The selected tab is substantially
    // brighter than both unselected tabs in every supplied post-update frame.
    private static final AreaData LEFT_MENU_CITY_TAB_SAMPLE       = region(20, 252, 135, 288);
    private static final AreaData LEFT_MENU_WILDERNESS_TAB_SAMPLE = region(165, 252, 286, 288);
    private static final AreaData LEFT_MENU_DAILY_TAB_SAMPLE      = region(310, 252, 430, 288);

    public static final AreaData SIDEBAR_CONTENT = region(10, 300, 440, 880);
    public static final AreaData SIDEBAR_ROW_ICON_COLUMN = region(15, 300, 80, 880);
    public static final PointData SIDEBAR_RESET_FROM = point(360, 350);
    public static final PointData SIDEBAR_RESET_TO = point(360, 820);
    public static final PointData SIDEBAR_SCROLL_FROM = point(360, 800);
    public static final PointData SIDEBAR_SCROLL_TO = point(360, 350);
    public static final PointData SIDEBAR_SCROLL_TOWARD_BOTTOM_FROM = point(360, 720);
    public static final PointData SIDEBAR_SCROLL_TOWARD_BOTTOM_TO = point(360, 600);

    public static AreaData sidebarTab(SidebarSection section) {
        return switch (section) {
            case CITY -> LEFT_MENU_CITY_TAB;
            case WILDERNESS -> LEFT_MENU_WILDERNESS_TAB;
            case DAILY -> LEFT_MENU_DAILY_TAB;
        };
    }

    public static AreaData sidebarTabSample(SidebarSection section) {
        return switch (section) {
            case CITY -> LEFT_MENU_CITY_TAB_SAMPLE;
            case WILDERNESS -> LEFT_MENU_WILDERNESS_TAB_SAMPLE;
            case DAILY -> LEFT_MENU_DAILY_TAB_SAMPLE;
        };
    }

    // ── shop footer navigation ──────────────────────────────────────

    public static final int SHOP_TAB_VISIBLE_COUNT = 3;
    private static final int SHOP_TAB_LEFT = 2;
    private static final int SHOP_TAB_TOP = 1208;
    private static final int SHOP_TAB_WIDTH = 189;
    private static final int SHOP_TAB_HEIGHT = 65;
    private static final int SHOP_TAB_PITCH = 198;
    private static final int SHOP_TAB_TAP_MARGIN = 10;
    private static final int SHOP_VIEWPORT_WIDTH = 720;

    public static final AreaData SHOP_LEFTMOST_TAB_OCR_AREA =
            region(SHOP_TAB_LEFT, SHOP_TAB_TOP,
                    SHOP_TAB_LEFT + SHOP_TAB_WIDTH, SHOP_TAB_TOP + SHOP_TAB_HEIGHT);
    public static final AreaData SHOP_RIGHTMOST_TAB_OCR_AREA =
            region(SHOP_VIEWPORT_WIDTH - SHOP_TAB_LEFT - SHOP_TAB_WIDTH, SHOP_TAB_TOP,
                    SHOP_VIEWPORT_WIDTH - SHOP_TAB_LEFT, SHOP_TAB_TOP + SHOP_TAB_HEIGHT);

    public static AreaData shopTabTapArea(int visibleSlot) {
        if (visibleSlot < 0 || visibleSlot >= SHOP_TAB_VISIBLE_COUNT) {
            throw new IllegalArgumentException("Shop tab slot must be between 0 and 2");
        }
        int left = SHOP_TAB_LEFT + visibleSlot * SHOP_TAB_PITCH;
        return insetShopTabArea(left);
    }

    public static AreaData shopTabTapAreaFromRight(int visibleSlotFromRight) {
        if (visibleSlotFromRight < 0 || visibleSlotFromRight >= SHOP_TAB_VISIBLE_COUNT) {
            throw new IllegalArgumentException("Shop tab slot from right must be between 0 and 2");
        }
        int rightmostLeft = SHOP_VIEWPORT_WIDTH - SHOP_TAB_LEFT - SHOP_TAB_WIDTH;
        int left = rightmostLeft - visibleSlotFromRight * SHOP_TAB_PITCH;
        return insetShopTabArea(left);
    }

    private static AreaData insetShopTabArea(int left) {
        return region(
                left + SHOP_TAB_TAP_MARGIN,
                SHOP_TAB_TOP + SHOP_TAB_TAP_MARGIN,
                left + SHOP_TAB_WIDTH - SHOP_TAB_TAP_MARGIN,
                SHOP_TAB_TOP + SHOP_TAB_HEIGHT - SHOP_TAB_TAP_MARGIN);
    }

    // ── march slot grid (top-left / bottom-right, slot 6→1) ─────────

    public static final PointData[] MARCH_SLOTS_TOP_LEFT = {
            point(189, 740), point(189, 667), point(189, 594),
            point(189, 521), point(189, 448), point(189, 375)
    };

    public static final PointData[] MARCH_SLOTS_BOTTOM_RIGHT = {
            point(258, 768), point(258, 695), point(258, 622),
            point(258, 549), point(258, 476), point(258, 403)
    };

    // ── wilderness March Queue rows (index 0 → queue 1, top) ─────────
    //
    // Each row carries an activity icon on the left and, below its title, a status line holding
    // either a word ("Idle"/"Unlock"/"Unavailable") or a countdown. Stationed troops show no status
    // line at all. The timer window matches the geometry GatherRoutine has been reading for a while.

    private static final int[] MARCH_QUEUE_ROW_Y = { 375, 448, 521, 594, 667, 740 };

    public static final AreaData[] MARCH_QUEUE_STATUS = marchQueueRows(150, 0, 300, 28);
    public static final AreaData[] MARCH_QUEUE_TIMER  = marchQueueRows(152, 3, 292, 22);
    public static final AreaData[] MARCH_QUEUE_TITLE  = marchQueueRows(70, -29, 340, -1);
    // padded past the 46x46 icon so template matching has room to slide
    public static final AreaData[] MARCH_QUEUE_ICON   = marchQueueRows(18, -27, 72, 27);

    private static AreaData[] marchQueueRows(int x1, int offsetY1, int x2, int offsetY2) {
        AreaData[] rows = new AreaData[MARCH_QUEUE_ROW_Y.length];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = region(x1, MARCH_QUEUE_ROW_Y[i] + offsetY1, x2, MARCH_QUEUE_ROW_Y[i] + offsetY2);
        }
        return rows;
    }

    // ── alliance war controls ────────────────────────────────────────

    public static final AreaData BOTTOM_MENU_ALLIANCE_BUTTON       = region(493, 1187, 561, 1240);
    public static final AreaData ALLIANCE_WAR_RALLY_TAB            = region(81, 114, 195, 152);
    public static final AreaData ALLIANCE_AUTOJOIN_MENU_BUTTON     = region(260, 1200, 450, 1240);
    public static final AreaData ALLIANCE_AUTOJOIN_DISABLE_BUTTON  = region(120, 1069, 249, 1122);

    // ── rally flag & deployment ──────────────────────────────────────

    // The whole flag tab strip. Slot centres drift a few pixels between profiles, so padlocks are
    // located across the strip and mapped to the nearest slot rather than searched slot by slot.
    public static final AreaData RALLY_FLAG_BAR         = region(0, 88, 700, 158);
    // Equalize sits in the bottom button bar, but its x shifts once a profile unlocks the Balance
    // button beside it, so it is matched inside the bar rather than tapped at a fixed point.
    public static final AreaData RALLY_BOTTOM_BUTTON_BAR      = region(0, 1130, 460, 1279);
    public static final AreaData RALLY_TROOP_TRAINING_AREA    = region(190, 900, 530, 1060);
    public static final AreaData RALLY_SELECTED_TROOPS_OCR_AREA = region(38, 168, 235, 216);
    public static final AreaData RALLY_MARCH_QUEUE_FULL_AREA  = region(220, 300, 500, 380);
    public static final PointData RALLY_MARCH_QUEUE_FULL_CLOSE = point(640, 338);
    // Rally-card OCR regions are anchored to the top edge of a matched green join button.
    public static final int BEAR_RALLY_MEMBERS_X1 = 626;
    public static final int BEAR_RALLY_MEMBERS_X2 = 688;
    public static final int BEAR_RALLY_MEMBERS_DY1 = -57;
    public static final int BEAR_RALLY_MEMBERS_DY2 = -24;
    public static final int BEAR_RALLY_TROOPS_X1 = 284;
    public static final int BEAR_RALLY_TROOPS_X2 = 521;
    public static final int BEAR_RALLY_TROOPS_DY1 = -57;
    public static final int BEAR_RALLY_TROOPS_DY2 = -25;
    public static final int BEAR_RALLY_COUNTDOWN_X1 = 571;
    public static final int BEAR_RALLY_COUNTDOWN_X2 = 691;
    public static final int BEAR_RALLY_COUNTDOWN_DY1 = -163;
    public static final int BEAR_RALLY_COUNTDOWN_DY2 = -124;
    public static final int BEAR_RALLY_LEADER_X1 = 280;
    public static final int BEAR_RALLY_LEADER_X2 = 570;
    public static final int BEAR_RALLY_LEADER_DY1 = -101;
    public static final int BEAR_RALLY_LEADER_DY2 = -65;
    // Body of the "Other Troops are marching toward the same target" confirmation.
    public static final AreaData SAME_TARGET_DIALOG_AREA      = region(60, 555, 680, 650);

    // ── Hold-a-rally preparation time ────────────────────────────────
    //
    // The dialog remembers the last preparation time. Automation that requires an exact duration
    // taps the matching checkbox and accepts it only after a fresh frame shows its green tick.

    public static final int[] RALLY_SET_TIME_MINUTES = { 3, 5, 10, 15 };
    public static final AreaData[] RALLY_SET_TIME_CHECKBOXES = {
            region(110, 592, 152, 634), region(375, 592, 417, 634),
            region(110, 670, 152, 712), region(375, 670, 417, 712)
    };
    // Bear Hunt exposes only 5 and 10 minutes on the first row. Treating this as the generic
    // four-option grid maps the right-hand 10-minute tick to 5 minutes and launches the wrong rally.
    public static final int[] BEAR_RALLY_SET_TIME_MINUTES = { 5, 10 };
    public static final AreaData[] BEAR_RALLY_SET_TIME_CHECKBOXES = {
            region(110, 592, 152, 634), region(375, 592, 417, 634)
    };

    // Close cross of the Alliance War rally list. Provisional: no saved real War-list frame yet,
    // so this is the upper-right header band shared with other closeable overlays.
    public static final AreaData BEAR_WAR_LIST_CLOSE_SEARCH_AREA = region(540, 0, 719, 240);

    // 720x1280 recorded Bear layout. Page identity is separate from action/control readiness.
    public static final AreaData BEAR_PAGE_TITLE = region(85, 5, 410, 75);
    public static final AreaData BEAR_PAGE_TABS = region(15, 75, 610, 165);
    public static final AreaData BEAR_BACK_ARROW = region(0, 0, 85, 78);
    public static final AreaData BEAR_BACK_ARROW_TARGET = region(30, 25, 60, 50);
    public static final AreaData BEAR_TRAP_1_TITLE = region(190, 250, 395, 300);
    public static final AreaData BEAR_TRAP_2_TITLE = region(190, 435, 395, 485);
    public static final AreaData BEAR_TRAP_1_STATUS = region(195, 300, 490, 343);
    public static final AreaData BEAR_TRAP_2_STATUS = region(195, 480, 490, 528);
    // Bear body above the centered numbered nameplate; never the generic rally indicator.
    public static final AreaData BEAR_CENTER_BODY = region(335, 475, 390, 520);

    /** Narrow only detectors whose positions have recorded evidence; others retain full search. */
    public static AreaData bearClassifierSearchArea(dev.frostguard.api.configs.TemplatesEnum template) {
        return switch (template) {
            case BEAR_CENTER_TRAP_1, BEAR_CENTER_TRAP_1_PANEL -> region(335, 560, 450, 592);
            case BEAR_CENTER_ACTIVE, BEAR_CENTER_ACTIVE_PANEL -> region(298, 535, 365, 565);
            case BEAR_PANEL_TITLE -> region(300, 815, 470, 865);
            case BEAR_PET_TITLE -> region(280, 120, 445, 185);
            case BEAR_PET_BATTLE_NAME -> region(50, 765, 280, 825);
            case BEAR_PET_BATTLE_ICON -> region(65, 375, 215, 530);
            case BEAR_PET_QUICK_USE -> region(40, 1035, 360, 1140);
            case BEAR_PET_CONFIRMATION -> region(140, 475, 580, 550);
            case BEAR_PET_CONFIRM_USE -> region(370, 765, 655, 865);
            case BEAR_PET_CANCEL -> region(65, 765, 350, 865);
            case BEAR_PET_ACTIVE -> region(245, 1060, 360, 1115);
            case BEAR_PET_CLOSE -> region(635, 120, 700, 185);
            case GAME_HOME_PETS -> region(610, 895, 720, 1025);
            case BEAR_WAR_TITLE, BEAR_TERRITORY_TITLE -> BEAR_PAGE_TITLE;
            case BEAR_RALLY_TAB, BEAR_TERRITORY_TAB, BEAR_SPECIAL_TAB -> BEAR_PAGE_TABS;
            case BEAR_BACK_ARROW -> BEAR_BACK_ARROW;
            case BEAR_TRAP_1_TITLE -> BEAR_TRAP_1_TITLE;
            case BEAR_TRAP_2_TITLE -> BEAR_TRAP_2_TITLE;
            case BEAR_TRAP_ACTIVE, BEAR_TRAP_COOLDOWN -> region(195, 300, 490, 528);
            case GAME_START_WELCOME_BACK_TITLE -> region(120, 180, 600, 310);
            case RALLY_MARCH_QUEUE_FULL -> region(190, 285, 535, 390);
            case DEPLOY_CONFIRMATION_DIALOG, TROOPS_ALREADY_MARCHING -> region(40, 390, 685, 870);
            case BEAR_DEPLOY_BUTTON -> region(375, 1140, 715, 1280);
            case RALLY_HOLD_BUTTON -> region(195, 750, 520, 870);
            case BEAR_RALLY_BUTTON -> region(140, 650, 580, 1100);
            case GAME_HOME_WORLD, BEAR_WORLD_CITY -> region(580, 1155, 720, 1280);
            case BEAR_HUNT_IS_RUNNING -> region(490, 560, 630, 950);
            default -> region(0, 0, 720, 1280);
        };
    }

    // Polar Terror search panel: the level number sits in the pill right of the slider, not on the
    // slider bar itself.
    public static final AreaData POLAR_LEVEL_DISPLAY = region(565, 1030, 665, 1078);
    public static final AreaData POLAR_SEARCH_BUTTON = region(301, 1200, 412, 1229);
    // A newly revealed section heading enters this strip before more reward rows are swiped into view.
    public static final AreaData POLAR_SPECIAL_REWARDS_HEADER = region(35, 1090, 685, 1185);
    public static final PointData POLAR_REWARD_DETAILS_CLOSE = point(665, 195);

    // ── stamina "Obtain more" dialog ─────────────────────────────────
    //
    // Reachable both from a red deploy cost and straight from the profile stamina bar.

    public static final AreaData STAMINA_DIALOG_CURRENT     = region(340, 248, 470, 292);
    public static final AreaData STAMINA_DIALOG_ITEM_COUNT  = region(116, 535, 154, 570);
    public static final AreaData STAMINA_DIALOG_USE_BUTTON  = region(490, 480, 670, 565);
    public static final PointData STAMINA_DIALOG_CLOSE      = point(665, 135);
    // Includes the complete first digit. Starting at x=510 clipped its left half on the Intel
    // formation layout, turning "00:00:15" into the valid but false ten-hour value "10:00:15".
    public static final AreaData TRAVEL_TIME_OCR_AREA   = region(500, 1134, 622, 1162);

    // ── character identity ───────────────────────────────────────────

    public static final AreaData CHARACTER_ID_OCR_AREA   = region(300, 940, 465, 980);
    public static final AreaData CHARACTER_NAME_OCR_AREA = region(280, 890, 600, 930);

    public static final AreaData PROFILE_SETTINGS_BUTTON_AREA =
            region(540, 1150, 720, 1250);
    public static final AreaData PROFILE_SETTINGS_SWITCH_CHARACTER_BUTTON_AREA =
            region(30, 280, 340, 380);
    public static final AreaData PROFILE_SETTINGS_SWITCH_CHARACTER_CHARACTER_LIST_AREA =
            region(60, 380, 660, 1100);
    public static final AreaData PROFILE_SETTINGS_SWITCH_CHARACTER_PROMPT_BUTTON_AREA =
            region(50, 750, 670, 850);
    public static final AreaData PROFILE_SETTINGS_SWITCH_CHARACTER_CONFIRM_DIALOG_NAME_OCR_AREA =
            region(170, 650, 550, 700);

    // ── character name above furnace template (relative offsets) ─────

    public static final int CHARACTER_NAME_ABOVE_FURNACE_TOP_OFFSET_Y    = 60;
    public static final int CHARACTER_NAME_ABOVE_FURNACE_BOTTOM_OFFSET_Y = 10;
    public static final int CHARACTER_NAME_ABOVE_FURNACE_X_START          = 210;
    public static final int CHARACTER_NAME_ABOVE_FURNACE_X_END            = 500;
}
