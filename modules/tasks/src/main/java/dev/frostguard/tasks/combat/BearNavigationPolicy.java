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
        WORLD_AT_BEAR,
        WORLD_ACTIVE_BEAR_ICON_READY,
        WORLD_OBSTRUCTED,
        WORLD,
        BEAR_RALLY_PANEL,
        RALLY_TIMER_PANEL,
        FORMATION,
        DEPLOY_CONFIRMATION,
        MARCH_QUEUE_FULL,
        WAR_LIST,
        SIDEBAR_OTHER,
        MARCH_SIDEBAR,
        RECALL_CONFIRMATION,
        ALLIANCE_MENU,
        ALLIANCE_WAR,
        AUTOJOIN_PANEL,
        ALLIANCE_TERRITORY,
        SPECIAL_BUILDINGS,
        PETS_OVERVIEW,
        PET_RAZORBACK,
        PET_QUICK_USE,
        PET_CONFIRMATION,
        RECONNECT,
        APP_LOADING,
        TRANSITIONING,
        UNKNOWN
    }

    enum Phase {
        PREPARING,
        ACTIVE,
        JOIN_ONLY,
        ENDED
    }

    enum Goal {
        PREPARED_AT_BEAR,
        OWN_RALLY,
        WAR_LIST,
        FRESH_WAR_LIST,
        WORLD_READY
    }

    enum Action {
        READY,
        TAP_BEAR_ANCHOR,
        TAP_WAR,
        BACK_ONCE,
        ROUTE_TO_BEAR,
        OPEN_ALLIANCE,
        OPEN_TERRITORY,
        OPEN_SPECIAL_BUILDINGS,
        TAP_CONFIGURED_GO,
        TAP_ACTIVE_BEAR_ICON,
        WAIT_FOR_FRAME,
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
        if (evidence.warListKnown() || evidence.joinButton()) {
            return Screen.WAR_LIST;
        }
        if (evidence.worldRoot()) {
            return evidence.bearAnchorFresh() ? Screen.WORLD_AT_VERIFIED_BEAR : Screen.WORLD;
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
                case WORLD_AT_VERIFIED_BEAR, WORLD_AT_BEAR -> Action.TAP_BEAR_ANCHOR;
                case WORLD -> Action.ROUTE_TO_BEAR;
                case RALLY_TIMER_PANEL, FORMATION, DEPLOY_CONFIRMATION, MARCH_QUEUE_FULL,
                        WAR_LIST, SIDEBAR_OTHER, MARCH_SIDEBAR, RECALL_CONFIRMATION -> Action.BACK_ONCE;
                case WORLD_ACTIVE_BEAR_ICON_READY, ALLIANCE_MENU, ALLIANCE_TERRITORY,
                        ALLIANCE_WAR, AUTOJOIN_PANEL, SPECIAL_BUILDINGS, PETS_OVERVIEW,
                        PET_RAZORBACK, PET_QUICK_USE, PET_CONFIRMATION, WORLD_OBSTRUCTED,
                        RECONNECT, APP_LOADING, TRANSITIONING, UNKNOWN ->
                        Action.FAIL_CLOSED;
            };
        }
        if (goal == Goal.FRESH_WAR_LIST) {
            return switch (screen) {
                case WORLD_AT_VERIFIED_BEAR, WORLD_AT_BEAR, WORLD_ACTIVE_BEAR_ICON_READY,
                        WORLD -> Action.TAP_WAR;
                case WAR_LIST, FORMATION, DEPLOY_CONFIRMATION, MARCH_QUEUE_FULL,
                        SIDEBAR_OTHER, MARCH_SIDEBAR, RECALL_CONFIRMATION, RALLY_TIMER_PANEL,
                        BEAR_RALLY_PANEL -> Action.BACK_ONCE;
                case ALLIANCE_MENU, ALLIANCE_WAR, AUTOJOIN_PANEL, ALLIANCE_TERRITORY,
                        SPECIAL_BUILDINGS, PETS_OVERVIEW, PET_RAZORBACK, PET_QUICK_USE,
                        PET_CONFIRMATION, WORLD_OBSTRUCTED,
                        RECONNECT, APP_LOADING, TRANSITIONING, UNKNOWN -> Action.FAIL_CLOSED;
            };
        }
        if (goal == Goal.WORLD_READY) {
            return switch (screen) {
                case WORLD_AT_VERIFIED_BEAR, WORLD_AT_BEAR, WORLD_ACTIVE_BEAR_ICON_READY,
                        WORLD -> Action.READY;
                case WAR_LIST -> Action.BACK_ONCE;
                case APP_LOADING, TRANSITIONING, UNKNOWN -> Action.WAIT_FOR_FRAME;
                case RECONNECT -> Action.FAIL_CLOSED;
                case BEAR_RALLY_PANEL, RALLY_TIMER_PANEL, FORMATION, DEPLOY_CONFIRMATION,
                        MARCH_QUEUE_FULL, SIDEBAR_OTHER, MARCH_SIDEBAR, RECALL_CONFIRMATION, ALLIANCE_MENU,
                        ALLIANCE_WAR, AUTOJOIN_PANEL, ALLIANCE_TERRITORY, SPECIAL_BUILDINGS,
                        PETS_OVERVIEW, PET_RAZORBACK, PET_QUICK_USE, PET_CONFIRMATION,
                        WORLD_OBSTRUCTED -> Action.FAIL_CLOSED;
            };
        }
        return switch (screen) {
            case WAR_LIST -> Action.READY;
            case WORLD_AT_VERIFIED_BEAR, WORLD_AT_BEAR, WORLD_ACTIVE_BEAR_ICON_READY,
                    WORLD -> Action.TAP_WAR;
            case FORMATION, DEPLOY_CONFIRMATION, MARCH_QUEUE_FULL, SIDEBAR_OTHER, MARCH_SIDEBAR,
                    RECALL_CONFIRMATION, RALLY_TIMER_PANEL, BEAR_RALLY_PANEL -> Action.BACK_ONCE;
            case ALLIANCE_MENU, ALLIANCE_WAR, AUTOJOIN_PANEL, ALLIANCE_TERRITORY,
                    SPECIAL_BUILDINGS, PETS_OVERVIEW, PET_RAZORBACK, PET_QUICK_USE,
                    PET_CONFIRMATION, WORLD_OBSTRUCTED,
                    RECONNECT, APP_LOADING, TRANSITIONING, UNKNOWN -> Action.FAIL_CLOSED;
        };
    }

    static Action next(Screen screen, Goal goal, Phase phase) {
        if (screen == Screen.TRANSITIONING || screen == Screen.APP_LOADING) {
            return Action.WAIT_FOR_FRAME;
        }
        if (phase == Phase.ENDED) {
            return Action.FAIL_CLOSED;
        }
        if (goal == Goal.PREPARED_AT_BEAR) {
            if (phase != Phase.PREPARING) {
                return Action.FAIL_CLOSED;
            }
            return switch (screen) {
                case WORLD -> Action.OPEN_ALLIANCE;
                case ALLIANCE_MENU -> Action.OPEN_TERRITORY;
                case ALLIANCE_TERRITORY -> Action.OPEN_SPECIAL_BUILDINGS;
                case SPECIAL_BUILDINGS -> Action.TAP_CONFIGURED_GO;
                case WORLD_AT_BEAR, WORLD_AT_VERIFIED_BEAR -> Action.READY;
                default -> Action.FAIL_CLOSED;
            };
        }
        if (goal == Goal.OWN_RALLY) {
            return switch (screen) {
                case BEAR_RALLY_PANEL -> Action.READY;
                case WORLD_AT_BEAR, WORLD_AT_VERIFIED_BEAR -> Action.TAP_BEAR_ANCHOR;
                case WORLD_ACTIVE_BEAR_ICON_READY -> phase == Phase.ACTIVE
                        ? Action.TAP_ACTIVE_BEAR_ICON
                        : Action.FAIL_CLOSED;
                case RALLY_TIMER_PANEL, FORMATION, DEPLOY_CONFIRMATION, MARCH_QUEUE_FULL,
                        WAR_LIST, SIDEBAR_OTHER, MARCH_SIDEBAR, RECALL_CONFIRMATION -> Action.BACK_ONCE;
                default -> Action.FAIL_CLOSED;
            };
        }
        return switch (screen) {
            case WAR_LIST -> Action.READY;
            case WORLD, WORLD_AT_BEAR, WORLD_AT_VERIFIED_BEAR,
                    WORLD_ACTIVE_BEAR_ICON_READY -> Action.TAP_WAR;
            case FORMATION, RALLY_TIMER_PANEL, BEAR_RALLY_PANEL -> Action.BACK_ONCE;
            default -> Action.FAIL_CLOSED;
        };
    }
}
