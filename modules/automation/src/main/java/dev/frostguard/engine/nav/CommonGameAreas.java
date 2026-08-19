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

    // ── side panel navigation ────────────────────────────────────────

    // The collapsed panel exposes only a thin handle at the left edge. A trigger tap is allowed only
    // after the caller has proved that no selected tab is visible, and the result is then verified.
    public static final AreaData LEFT_MENU_TRIGGER        = region(0, 520, 22, 580);
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
    public static final AreaData RALLY_MARCH_QUEUE_FULL_AREA  = region(220, 300, 500, 380);
    public static final PointData RALLY_MARCH_QUEUE_FULL_CLOSE = point(640, 338);
    // Body of the "Other Troops are marching toward the same target" confirmation.
    public static final AreaData SAME_TARGET_DIALOG_AREA      = region(60, 555, 680, 650);

    // ── Hold-a-rally preparation time ────────────────────────────────
    //
    // The dialog keeps whatever preparation time was last picked in game and the bot never sets it,
    // so the ticked option is read per rally. Only a green tick marks the active option.

    public static final int[] RALLY_SET_TIME_MINUTES = { 3, 5, 10, 15 };
    public static final AreaData[] RALLY_SET_TIME_CHECKBOXES = {
            region(110, 592, 152, 634), region(375, 592, 417, 634),
            region(110, 670, 152, 712), region(375, 670, 417, 712)
    };

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
