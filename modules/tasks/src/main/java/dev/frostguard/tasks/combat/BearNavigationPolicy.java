package dev.frostguard.tasks.combat;

/** Small fail-closed transition table for the time-critical Bear screens. */
final class BearNavigationPolicy {

    record Evidence(
            boolean formation,
            boolean bearRallyPanel,
            boolean rallyTimerPanel,
            boolean worldRoot,
            boolean bearAnchorFresh,
            boolean warListKnown,
            boolean joinButton,
            boolean allianceMenu) {}

    enum Screen {
        WORLD_AT_VERIFIED_BEAR,
        WORLD,
        BEAR_RALLY_PANEL,
        RALLY_TIMER_PANEL,
        FORMATION,
        WAR_LIST,
        ALLIANCE_MENU,
        UNKNOWN
    }

    enum Goal {
        OWN_RALLY,
        WAR_LIST
    }

    enum Action {
        READY,
        TAP_BEAR_ANCHOR,
        TAP_WAR,
        BACK_ONCE,
        ROUTE_TO_BEAR,
        FAIL_CLOSED
    }

    private BearNavigationPolicy() {}

    static Screen classify(Evidence evidence) {
        if (evidence.formation()) {
            return Screen.FORMATION;
        }
        if (evidence.bearRallyPanel()) {
            return Screen.BEAR_RALLY_PANEL;
        }
        if (evidence.rallyTimerPanel()) {
            return Screen.RALLY_TIMER_PANEL;
        }
        if (evidence.worldRoot()) {
            return evidence.bearAnchorFresh() ? Screen.WORLD_AT_VERIFIED_BEAR : Screen.WORLD;
        }
        if (evidence.warListKnown() || evidence.joinButton()) {
            return Screen.WAR_LIST;
        }
        if (evidence.allianceMenu()) {
            return Screen.ALLIANCE_MENU;
        }
        return Screen.UNKNOWN;
    }

    static Action next(Screen screen, Goal goal) {
        if (goal == Goal.OWN_RALLY) {
            return switch (screen) {
                case BEAR_RALLY_PANEL -> Action.READY;
                case WORLD_AT_VERIFIED_BEAR -> Action.TAP_BEAR_ANCHOR;
                case WORLD -> Action.ROUTE_TO_BEAR;
                case RALLY_TIMER_PANEL, FORMATION, WAR_LIST -> Action.BACK_ONCE;
                case ALLIANCE_MENU, UNKNOWN -> Action.FAIL_CLOSED;
            };
        }
        return switch (screen) {
            case WAR_LIST -> Action.READY;
            case WORLD_AT_VERIFIED_BEAR, WORLD -> Action.TAP_WAR;
            case FORMATION, RALLY_TIMER_PANEL, BEAR_RALLY_PANEL -> Action.BACK_ONCE;
            case ALLIANCE_MENU, UNKNOWN -> Action.FAIL_CLOSED;
        };
    }
}
