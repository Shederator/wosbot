package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BearNavigationPolicyTest {

    @Test
    void preparationUsesAllianceTerritoryAndSpecialBuildingsInOrder() {
        assertEquals(BearNavigationPolicy.Action.OPEN_ALLIANCE,
                BearNavigationPolicy.next(
                        BearNavigationPolicy.Screen.WORLD,
                        BearNavigationPolicy.Goal.PREPARED_AT_BEAR,
                        BearNavigationPolicy.Phase.PREPARING));
        assertEquals(BearNavigationPolicy.Action.OPEN_TERRITORY,
                BearNavigationPolicy.next(
                        BearNavigationPolicy.Screen.ALLIANCE_MENU,
                        BearNavigationPolicy.Goal.PREPARED_AT_BEAR,
                        BearNavigationPolicy.Phase.PREPARING));
        assertEquals(BearNavigationPolicy.Action.OPEN_SPECIAL_BUILDINGS,
                BearNavigationPolicy.next(
                        BearNavigationPolicy.Screen.ALLIANCE_TERRITORY,
                        BearNavigationPolicy.Goal.PREPARED_AT_BEAR,
                        BearNavigationPolicy.Phase.PREPARING));
        assertEquals(BearNavigationPolicy.Action.TAP_CONFIGURED_GO,
                BearNavigationPolicy.next(
                        BearNavigationPolicy.Screen.SPECIAL_BUILDINGS,
                        BearNavigationPolicy.Goal.PREPARED_AT_BEAR,
                        BearNavigationPolicy.Phase.PREPARING));
    }

    @Test
    void activeEventUsesVisibleBearIconInsteadOfReopeningAlliance() {
        assertEquals(BearNavigationPolicy.Action.TAP_ACTIVE_BEAR_ICON,
                BearNavigationPolicy.next(
                        BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY,
                        BearNavigationPolicy.Goal.OWN_RALLY,
                        BearNavigationPolicy.Phase.ACTIVE));
        assertEquals(BearNavigationPolicy.Action.FAIL_CLOSED,
                BearNavigationPolicy.next(
                        BearNavigationPolicy.Screen.WORLD,
                        BearNavigationPolicy.Goal.OWN_RALLY,
                        BearNavigationPolicy.Phase.ACTIVE));
    }

    @Test
    void partialAllianceRenderWaitsForAnotherFrameInsteadOfRestartingTheRoute() {
        assertEquals(BearNavigationPolicy.Action.WAIT_FOR_FRAME,
                BearNavigationPolicy.next(
                        BearNavigationPolicy.Screen.TRANSITIONING,
                        BearNavigationPolicy.Goal.PREPARED_AT_BEAR,
                        BearNavigationPolicy.Phase.PREPARING));
    }

    @Test
    void alreadyAtBearContinuesWithoutEitherNavigationRoute() {
        assertEquals(BearNavigationPolicy.Action.TAP_BEAR_ANCHOR,
                BearNavigationPolicy.next(
                        BearNavigationPolicy.Screen.WORLD_AT_BEAR,
                        BearNavigationPolicy.Goal.OWN_RALLY,
                        BearNavigationPolicy.Phase.ACTIVE));
    }

    @Test
    void classifiesWorldFromTheRootAnchorWithoutRequiringTheWarIndicator() {
        assertEquals(BearNavigationPolicy.Screen.WORLD,
                BearNavigationPolicy.classify(new BearNavigationPolicy.Evidence(
                        false, false, false, true, false, false, false, false)));
        assertEquals(BearNavigationPolicy.Screen.WORLD_AT_VERIFIED_BEAR,
                BearNavigationPolicy.classify(new BearNavigationPolicy.Evidence(
                        false, false, false, true, true, false, false, false)));
    }

    @Test
    void knownWarListWinsOverTheWorldVisibleBehindItsOverlay() {
        assertEquals(BearNavigationPolicy.Screen.WAR_LIST,
                BearNavigationPolicy.classify(new BearNavigationPolicy.Evidence(
                        false, false, false, true, false, true, false, false)));
        assertEquals(BearNavigationPolicy.Screen.WAR_LIST,
                BearNavigationPolicy.classify(new BearNavigationPolicy.Evidence(
                        false, false, false, true, false, false, true, false)));
    }

    @Test
    void reusesTheVerifiedBearAnchorInsteadOfReopeningAlliance() {
        assertEquals(BearNavigationPolicy.Action.TAP_BEAR_ANCHOR,
                BearNavigationPolicy.next(
                        BearNavigationPolicy.Screen.WORLD_AT_VERIFIED_BEAR,
                        BearNavigationPolicy.Goal.OWN_RALLY));
    }

    @Test
    void onlyFallsBackToAllianceFromAConfirmedWorldWithoutAnAnchor() {
        assertEquals(BearNavigationPolicy.Action.ROUTE_TO_BEAR,
                BearNavigationPolicy.next(
                        BearNavigationPolicy.Screen.WORLD,
                        BearNavigationPolicy.Goal.OWN_RALLY));
        assertEquals(BearNavigationPolicy.Action.FAIL_CLOSED,
                BearNavigationPolicy.next(
                        BearNavigationPolicy.Screen.UNKNOWN,
                        BearNavigationPolicy.Goal.OWN_RALLY));
    }

    @Test
    void leavesKnownChildScreensWithOneVerifiedBackTransition() {
        assertEquals(BearNavigationPolicy.Action.BACK_ONCE,
                BearNavigationPolicy.next(
                        BearNavigationPolicy.Screen.FORMATION,
                        BearNavigationPolicy.Goal.WAR_LIST));
        assertEquals(BearNavigationPolicy.Action.BACK_ONCE,
                BearNavigationPolicy.next(
                        BearNavigationPolicy.Screen.BEAR_RALLY_PANEL,
                        BearNavigationPolicy.Goal.WAR_LIST));
    }

    @Test
    void opensWarDirectlyFromEitherVerifiedWorldState() {
        assertEquals(BearNavigationPolicy.Action.TAP_WAR,
                BearNavigationPolicy.next(
                        BearNavigationPolicy.Screen.WORLD_AT_VERIFIED_BEAR,
                        BearNavigationPolicy.Goal.WAR_LIST));
        assertEquals(BearNavigationPolicy.Action.TAP_WAR,
                BearNavigationPolicy.next(
                        BearNavigationPolicy.Screen.WORLD,
                        BearNavigationPolicy.Goal.WAR_LIST));
    }

    @Test
    void refreshesAnOpenWarListThroughWorldBeforeUsingItAgain() {
        assertEquals(BearNavigationPolicy.Action.BACK_ONCE,
                BearNavigationPolicy.next(
                        BearNavigationPolicy.Screen.WAR_LIST,
                        BearNavigationPolicy.Goal.FRESH_WAR_LIST));
        assertEquals(BearNavigationPolicy.Action.TAP_WAR,
                BearNavigationPolicy.next(
                        BearNavigationPolicy.Screen.WORLD_AT_BEAR,
                        BearNavigationPolicy.Goal.FRESH_WAR_LIST));
    }
}
