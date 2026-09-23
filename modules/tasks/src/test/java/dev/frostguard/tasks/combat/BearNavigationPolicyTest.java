package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BearNavigationPolicyTest {

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
}
