package dev.frostguard.tasks.combat;

import dev.frostguard.tasks.combat.BearNavigationPolicy.Screen;
import java.util.EnumSet;
import java.util.Set;

/**
 * Legal UI edges for the Bear session. A production input must name one of these edges and may
 * execute only from a current frame in {@link #sources}; completion requires a newer frame in
 * {@link #destinations}. Edges whose screens still lack real-frame identity stay declared so
 * production fails closed instead of falling back to coordinate timing; those without any
 * confirmable destination never complete.
 */
enum BearUiAction {
    OPEN_ALLIANCE(set(Screen.WORLD, Screen.WORLD_ACTIVE_BEAR_ICON_READY),
            set(Screen.ALLIANCE_MENU)),
    OPEN_ALLIANCE_WAR(set(Screen.ALLIANCE_MENU), set(Screen.ALLIANCE_WAR)),
    OPEN_AUTOJOIN(set(Screen.ALLIANCE_WAR), set(Screen.AUTOJOIN_PANEL)),
    STOP_AUTOJOIN(set(Screen.AUTOJOIN_PANEL), set(Screen.ALLIANCE_WAR)),
    OPEN_TERRITORY(set(Screen.ALLIANCE_MENU), set(Screen.ALLIANCE_TERRITORY)),
    OPEN_SPECIAL_BUILDINGS(set(Screen.ALLIANCE_TERRITORY), set(Screen.SPECIAL_BUILDINGS)),
    GO_TO_CONFIGURED_TRAP(set(Screen.SPECIAL_BUILDINGS),
            unconfirmable()),
    OPEN_PETS(set(Screen.WORLD, Screen.WORLD_ACTIVE_BEAR_ICON_READY),
            set(Screen.PETS_OVERVIEW)),
    SELECT_RAZORBACK(set(Screen.PETS_OVERVIEW), set(Screen.PET_RAZORBACK)),
    OPEN_PET_QUICK_USE(set(Screen.PET_RAZORBACK), set(Screen.PET_QUICK_USE)),
    CONFIRM_PET_USE(set(Screen.PET_QUICK_USE, Screen.PET_CONFIRMATION),
            set(Screen.PET_RAZORBACK, Screen.PETS_OVERVIEW)),
    OPEN_MARCH_SIDEBAR(set(Screen.WORLD, Screen.WORLD_ACTIVE_BEAR_ICON_READY),
            set(Screen.MARCH_SIDEBAR, Screen.SIDEBAR_OTHER)),
    SELECT_MARCH_SIDEBAR(set(Screen.SIDEBAR_OTHER), set(Screen.MARCH_SIDEBAR)),
    CLOSE_MARCH_SIDEBAR(set(Screen.MARCH_SIDEBAR, Screen.SIDEBAR_OTHER),
            set(Screen.WORLD, Screen.WORLD_ACTIVE_BEAR_ICON_READY)),
    RECALL_MARCH(set(Screen.MARCH_SIDEBAR), set(Screen.RECALL_CONFIRMATION)),
    CONFIRM_RECALL(set(Screen.RECALL_CONFIRMATION), set(Screen.MARCH_SIDEBAR)),
    OPEN_ACTIVE_BEAR(set(Screen.WORLD_ACTIVE_BEAR_ICON_READY),
            unconfirmable()),
    OPEN_RALLY_TIMER(set(Screen.BEAR_RALLY_PANEL), set(Screen.RALLY_TIMER_PANEL)),
    SELECT_RALLY_TIME(set(Screen.RALLY_TIMER_PANEL), set(Screen.RALLY_TIMER_PANEL)),
    CONFIRM_RALLY_TIMER(set(Screen.RALLY_TIMER_PANEL), set(Screen.FORMATION)),
    SELECT_FORMATION(set(Screen.FORMATION), set(Screen.FORMATION)),
    DEPLOY_OWN_RALLY(set(Screen.FORMATION),
            set(Screen.WORLD, Screen.WORLD_ACTIVE_BEAR_ICON_READY,
                    Screen.WAR_LIST, Screen.MARCH_QUEUE_FULL, Screen.DEPLOY_CONFIRMATION)),
    OPEN_WAR_LIST(set(Screen.WORLD, Screen.WORLD_ACTIVE_BEAR_ICON_READY), set(Screen.WAR_LIST)),
    OPEN_JOIN_FORMATION(set(Screen.WAR_LIST), set(Screen.FORMATION)),
    SCROLL_RALLY_LIST(set(Screen.WAR_LIST), set(Screen.WAR_LIST)),
    DEPLOY_JOIN(set(Screen.FORMATION),
            set(Screen.WAR_LIST, Screen.MARCH_QUEUE_FULL, Screen.DEPLOY_CONFIRMATION)),
    DISMISS_DEPLOY_DIALOG(set(Screen.DEPLOY_CONFIRMATION, Screen.MARCH_QUEUE_FULL),
            set(Screen.FORMATION, Screen.WAR_LIST)),
    BACK_TO_PARENT(set(Screen.WAR_LIST, Screen.FORMATION, Screen.RALLY_TIMER_PANEL,
                    Screen.BEAR_RALLY_PANEL, Screen.ALLIANCE_MENU, Screen.ALLIANCE_WAR,
                    Screen.AUTOJOIN_PANEL, Screen.PETS_OVERVIEW, Screen.PET_RAZORBACK,
                    Screen.PET_QUICK_USE, Screen.SIDEBAR_OTHER, Screen.MARCH_SIDEBAR),
            set(Screen.WORLD, Screen.WORLD_ACTIVE_BEAR_ICON_READY, Screen.WAR_LIST,
                    Screen.BEAR_RALLY_PANEL, Screen.RALLY_TIMER_PANEL, Screen.ALLIANCE_MENU,
                    Screen.ALLIANCE_WAR, Screen.PETS_OVERVIEW, Screen.MARCH_SIDEBAR));

    private final Set<Screen> sources;
    private final Set<Screen> destinations;

    BearUiAction(Set<Screen> sources, Set<Screen> destinations) {
        this.sources = Set.copyOf(sources);
        this.destinations = Set.copyOf(destinations);
    }

    Set<Screen> sources() {
        return sources;
    }

    Set<Screen> destinations() {
        return destinations;
    }

    boolean legalFrom(Screen screen) {
        return sources.contains(screen);
    }

    boolean confirms(Screen screen) {
        return destinations.contains(screen);
    }

    String expectedPostcondition() {
        return destinations.toString();
    }

    static boolean confirmsBackFrom(Screen source, Screen destination) {
        return switch (source) {
            case WAR_LIST -> world(destination);
            case FORMATION -> destination == Screen.WAR_LIST
                    || destination == Screen.BEAR_RALLY_PANEL
                    || destination == Screen.RALLY_TIMER_PANEL;
            case RALLY_TIMER_PANEL -> destination == Screen.BEAR_RALLY_PANEL;
            case BEAR_RALLY_PANEL -> world(destination);
            case ALLIANCE_MENU -> world(destination);
            case ALLIANCE_WAR -> destination == Screen.ALLIANCE_MENU;
            case AUTOJOIN_PANEL -> destination == Screen.ALLIANCE_WAR;
            case PETS_OVERVIEW, MARCH_SIDEBAR, SIDEBAR_OTHER -> world(destination);
            case PET_RAZORBACK -> destination == Screen.PETS_OVERVIEW;
            case PET_QUICK_USE, PET_CONFIRMATION -> destination == Screen.PET_RAZORBACK;
            case DEPLOY_CONFIRMATION, MARCH_QUEUE_FULL -> destination == Screen.FORMATION
                    || destination == Screen.WAR_LIST;
            default -> false;
        };
    }

    private static boolean world(Screen screen) {
        return screen == Screen.WORLD
                || screen == Screen.WORLD_ACTIVE_BEAR_ICON_READY;
    }

    /**
     * The destination of an edge that has no real-frame identity yet (configured-trap Go and Bear
     * centring). It never confirms, so the edge fails closed until M3 defines it from recordings.
     */
    private static Set<Screen> unconfirmable() {
        return EnumSet.noneOf(Screen.class);
    }

    private static Set<Screen> set(Screen first, Screen... rest) {
        EnumSet<Screen> values = EnumSet.of(first, rest);
        return values;
    }

}
