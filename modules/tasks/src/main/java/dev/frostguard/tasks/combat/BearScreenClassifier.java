package dev.frostguard.tasks.combat;

import java.util.Objects;

/**
 * Pure precedence table for classifying one captured Bear frame. Production supplies only signals
 * measured in that frame; replay tests can supply the same signal record without pretending that
 * synthetic pixels validate template accuracy.
 */
final class BearScreenClassifier {

    record Evidence(
            boolean reconnect,
            boolean appLoading,
            boolean marchQueueFull,
            boolean deployConfirmation,
            boolean recallConfirmation,
            boolean formation,
            boolean rallyTimer,
            boolean bearPanel,
            boolean warList,
            boolean autojoinPanel,
            boolean allianceWar,
            boolean allianceMenu,
            boolean allianceTerritory,
            boolean specialBuildings,
            boolean petsOverview,
            boolean petRazorback,
            boolean petQuickUse,
            boolean marchSidebar,
            boolean sidebarOther,
            boolean worldRoot,
            boolean activeBearIcon,
            boolean verifiedBearAnchor,
            boolean transitioning) {
    }

    private BearScreenClassifier() {
    }

    static BearNavigationPolicy.Screen classify(Evidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        if (evidence.reconnect()) return BearNavigationPolicy.Screen.RECONNECT;
        if (evidence.appLoading()) return BearNavigationPolicy.Screen.APP_LOADING;
        if (evidence.marchQueueFull()) return BearNavigationPolicy.Screen.MARCH_QUEUE_FULL;
        if (evidence.deployConfirmation()) return BearNavigationPolicy.Screen.DEPLOY_CONFIRMATION;
        if (evidence.recallConfirmation()) return BearNavigationPolicy.Screen.RECALL_CONFIRMATION;
        if (evidence.formation()) return BearNavigationPolicy.Screen.FORMATION;
        if (evidence.rallyTimer()) return BearNavigationPolicy.Screen.RALLY_TIMER_PANEL;
        if (evidence.bearPanel()) return BearNavigationPolicy.Screen.BEAR_RALLY_PANEL;
        if (evidence.warList()) return BearNavigationPolicy.Screen.WAR_LIST;
        if (evidence.autojoinPanel()) return BearNavigationPolicy.Screen.AUTOJOIN_PANEL;
        if (evidence.allianceWar()) return BearNavigationPolicy.Screen.ALLIANCE_WAR;
        if (evidence.allianceMenu()) return BearNavigationPolicy.Screen.ALLIANCE_MENU;
        if (evidence.allianceTerritory()) return BearNavigationPolicy.Screen.ALLIANCE_TERRITORY;
        if (evidence.specialBuildings()) return BearNavigationPolicy.Screen.SPECIAL_BUILDINGS;
        if (evidence.petQuickUse()) return BearNavigationPolicy.Screen.PET_QUICK_USE;
        if (evidence.petRazorback()) return BearNavigationPolicy.Screen.PET_RAZORBACK;
        if (evidence.petsOverview()) return BearNavigationPolicy.Screen.PETS_OVERVIEW;
        if (evidence.marchSidebar()) return BearNavigationPolicy.Screen.MARCH_SIDEBAR;
        if (evidence.sidebarOther()) return BearNavigationPolicy.Screen.SIDEBAR_OTHER;
        if (evidence.worldRoot()) {
            if (evidence.verifiedBearAnchor()) return BearNavigationPolicy.Screen.WORLD_AT_VERIFIED_BEAR;
            if (evidence.activeBearIcon()) return BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY;
            return BearNavigationPolicy.Screen.WORLD;
        }
        return evidence.transitioning()
                ? BearNavigationPolicy.Screen.TRANSITIONING
                : BearNavigationPolicy.Screen.UNKNOWN;
    }
}
