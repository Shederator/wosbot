package dev.frostguard.engine.nav;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;

/**
 * Touch targets for fixed UI chrome (menus, tabs, alliance bar).
 * Coordinates assume the standard 720 × 1280 game viewport.
 */
public final class ButtonConstants {

    private ButtonConstants() {}

    private static AreaData area(int x1, int y1, int x2, int y2) {
        return new AreaData(new PointData(x1, y1), new PointData(x2, y2));
    }

    public static final AreaData LEFT_MENU                  = area(6, 535, 6, 565);
    public static final AreaData LEFT_MENU_CITY_TAB         = area(90, 260, 140, 280);
    public static final AreaData LEFT_MENU_WILDERNESS_TAB   = area(280, 260, 390, 280);
    public static final AreaData BOTTOM_MENU_ALLIANCE_BUTTON = area(512, 1202, 547, 1230);
}
