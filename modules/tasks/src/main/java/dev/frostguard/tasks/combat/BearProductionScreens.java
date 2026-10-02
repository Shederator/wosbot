package dev.frostguard.tasks.combat;

import dev.frostguard.tasks.combat.BearNavigationPolicy.Screen;
import java.util.EnumSet;
import java.util.Set;

/**
 * The states the live Bear classifier can actually produce. Other {@link Screen} values have no
 * real-frame identity yet; an edge that depends on them can never be confirmed in production.
 */
final class BearProductionScreens {

    static final Set<Screen> EMITTED = Set.copyOf(EnumSet.of(
            Screen.RECONNECT,
            Screen.APP_LOADING,
            Screen.MARCH_QUEUE_FULL,
            Screen.DEPLOY_CONFIRMATION,
            Screen.FORMATION,
            Screen.RALLY_TIMER_PANEL,
            Screen.BEAR_RALLY_PANEL,
            Screen.WAR_LIST,
            Screen.ALLIANCE_MENU,
            Screen.SPECIAL_BUILDINGS,
            Screen.PET_QUICK_USE,
            Screen.PETS_OVERVIEW,
            Screen.MARCH_SIDEBAR,
            Screen.SIDEBAR_OTHER,
            Screen.WORLD_ACTIVE_BEAR_ICON_READY,
            Screen.WORLD,
            Screen.UNKNOWN));

    /** During an active event the World root is classified with the Bear icon visible. */
    static final Set<Screen> WORLD = Set.copyOf(EnumSet.of(
            Screen.WORLD, Screen.WORLD_ACTIVE_BEAR_ICON_READY));

    private BearProductionScreens() {
    }
}
