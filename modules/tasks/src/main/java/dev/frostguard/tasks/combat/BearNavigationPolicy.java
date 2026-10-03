package dev.frostguard.tasks.combat;

/** Small fail-closed transition table for the time-critical Bear screens. */
final class BearNavigationPolicy {

    enum Screen {
        WORLD_ACTIVE_BEAR_ICON_READY,
        WORLD_AT_CONFIGURED_BEAR,
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
        PET_SKILL_PANEL,
        PET_BATTLE_SELECTED,
        PET_BATTLE_ACTIVE,
        PET_CONFIRMATION,
        RECONNECT,
        APP_LOADING,
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
        TAP_WAR,
        BACK_ONCE,
        ROUTE_TO_BEAR,
        OPEN_ALLIANCE,
        OPEN_TERRITORY,
        OPEN_SPECIAL_BUILDINGS,
        TAP_CONFIGURED_GO,
        TAP_ACTIVE_BEAR_ICON,
        TAP_CENTERED_BEAR,
        WAIT_FOR_FRAME,
        FAIL_CLOSED
    }

    private BearNavigationPolicy() {}

    static Action next(Screen screen, Goal goal) {
        if (goal == Goal.OWN_RALLY) {
            return switch (screen) {
                case BEAR_RALLY_PANEL -> Action.READY;
                case WORLD_AT_CONFIGURED_BEAR -> Action.TAP_CENTERED_BEAR;
                case WORLD -> Action.ROUTE_TO_BEAR;
                case RALLY_TIMER_PANEL, FORMATION, DEPLOY_CONFIRMATION, MARCH_QUEUE_FULL,
                        WAR_LIST, SIDEBAR_OTHER, MARCH_SIDEBAR, RECALL_CONFIRMATION -> Action.BACK_ONCE;
                case WORLD_ACTIVE_BEAR_ICON_READY, ALLIANCE_MENU, ALLIANCE_TERRITORY,
                        ALLIANCE_WAR, AUTOJOIN_PANEL, SPECIAL_BUILDINGS, PET_SKILL_PANEL,
                        PET_BATTLE_SELECTED, PET_BATTLE_ACTIVE, PET_CONFIRMATION, WORLD_OBSTRUCTED,
                        RECONNECT, APP_LOADING, UNKNOWN ->
                        Action.FAIL_CLOSED;
            };
        }
        if (goal == Goal.FRESH_WAR_LIST) {
            return switch (screen) {
                case WORLD_ACTIVE_BEAR_ICON_READY,
                        WORLD_AT_CONFIGURED_BEAR,
                        WORLD -> Action.TAP_WAR;
                case WAR_LIST, FORMATION, DEPLOY_CONFIRMATION, MARCH_QUEUE_FULL,
                        SIDEBAR_OTHER, MARCH_SIDEBAR, RECALL_CONFIRMATION, RALLY_TIMER_PANEL,
                        BEAR_RALLY_PANEL -> Action.BACK_ONCE;
                case ALLIANCE_MENU, ALLIANCE_WAR, AUTOJOIN_PANEL, ALLIANCE_TERRITORY,
                        SPECIAL_BUILDINGS, PET_SKILL_PANEL, PET_BATTLE_SELECTED, PET_BATTLE_ACTIVE,
                        PET_CONFIRMATION, WORLD_OBSTRUCTED,
                        RECONNECT, APP_LOADING, UNKNOWN -> Action.FAIL_CLOSED;
            };
        }
        if (goal == Goal.WORLD_READY) {
            return switch (screen) {
                case WORLD_ACTIVE_BEAR_ICON_READY,
                        WORLD_AT_CONFIGURED_BEAR,
                        WORLD -> Action.READY;
                case WAR_LIST -> Action.BACK_ONCE;
                case APP_LOADING, UNKNOWN -> Action.WAIT_FOR_FRAME;
                case RECONNECT -> Action.FAIL_CLOSED;
                case BEAR_RALLY_PANEL, RALLY_TIMER_PANEL, FORMATION, DEPLOY_CONFIRMATION,
                        MARCH_QUEUE_FULL, SIDEBAR_OTHER, MARCH_SIDEBAR, RECALL_CONFIRMATION, ALLIANCE_MENU,
                        ALLIANCE_WAR, AUTOJOIN_PANEL, ALLIANCE_TERRITORY, SPECIAL_BUILDINGS,
                        PET_SKILL_PANEL, PET_BATTLE_SELECTED, PET_BATTLE_ACTIVE, PET_CONFIRMATION,
                        WORLD_OBSTRUCTED -> Action.FAIL_CLOSED;
            };
        }
        return switch (screen) {
            case WAR_LIST -> Action.READY;
            case WORLD_ACTIVE_BEAR_ICON_READY,
                    WORLD_AT_CONFIGURED_BEAR,
                    WORLD -> Action.TAP_WAR;
            case FORMATION, DEPLOY_CONFIRMATION, MARCH_QUEUE_FULL, SIDEBAR_OTHER, MARCH_SIDEBAR,
                    RECALL_CONFIRMATION, RALLY_TIMER_PANEL, BEAR_RALLY_PANEL -> Action.BACK_ONCE;
            case ALLIANCE_MENU, ALLIANCE_WAR, AUTOJOIN_PANEL, ALLIANCE_TERRITORY,
                    SPECIAL_BUILDINGS, PET_SKILL_PANEL, PET_BATTLE_SELECTED, PET_BATTLE_ACTIVE,
                    PET_CONFIRMATION, WORLD_OBSTRUCTED,
                    RECONNECT, APP_LOADING, UNKNOWN -> Action.FAIL_CLOSED;
        };
    }

    static Action next(Screen screen, Goal goal, Phase phase) {
        if (screen == Screen.APP_LOADING) {
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
                default -> Action.FAIL_CLOSED;
            };
        }
        if (goal == Goal.OWN_RALLY) {
            return switch (screen) {
                case BEAR_RALLY_PANEL -> Action.READY;
                case WORLD_AT_CONFIGURED_BEAR -> phase == Phase.ACTIVE
                        ? Action.TAP_CENTERED_BEAR : Action.FAIL_CLOSED;
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
            case WORLD,
                    WORLD_AT_CONFIGURED_BEAR,
                    WORLD_ACTIVE_BEAR_ICON_READY -> Action.TAP_WAR;
            case FORMATION, RALLY_TIMER_PANEL, BEAR_RALLY_PANEL -> Action.BACK_ONCE;
            default -> Action.FAIL_CLOSED;
        };
    }
}
