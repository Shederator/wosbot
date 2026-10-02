package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.tasks.combat.BearNavigationPolicy.Screen;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BearUiActionTest {

    /** Edges that still need a real-frame identity and therefore fail closed in production. */
    private static final Set<BearUiAction> EVIDENCE_GATED = EnumSet.of(
            BearUiAction.OPEN_ALLIANCE_WAR,
            BearUiAction.OPEN_AUTOJOIN,
            BearUiAction.STOP_AUTOJOIN,
            BearUiAction.OPEN_TERRITORY,
            BearUiAction.OPEN_SPECIAL_BUILDINGS,
            BearUiAction.GO_TO_CONFIGURED_TRAP,
            BearUiAction.SELECT_RAZORBACK,
            BearUiAction.OPEN_PET_QUICK_USE,
            BearUiAction.RECALL_MARCH,
            BearUiAction.CONFIRM_RECALL,
            BearUiAction.OPEN_ACTIVE_BEAR);

    @Test
    void everyReachableEdgeIsConfirmableByAProductionClassification() {
        for (BearUiAction action : BearUiAction.values()) {
            boolean reachable = action.sources().stream().anyMatch(BearProductionScreens.EMITTED::contains)
                    && action.destinations().stream().anyMatch(BearProductionScreens.EMITTED::contains);
            assertEquals(!EVIDENCE_GATED.contains(action), reachable,
                    () -> action + " reachability must match its documented evidence status");
        }
    }

    @Test
    void anEdgeThatAcceptsOneProductionWorldStateAcceptsEvery() {
        for (BearUiAction action : BearUiAction.values()) {
            // Tapping the active-Bear icon is only legal where the icon itself is visible.
            List<Set<Screen>> sides = action == BearUiAction.OPEN_ACTIVE_BEAR
                    ? List.of(action.destinations())
                    : List.of(action.sources(), action.destinations());
            for (Set<Screen> side : sides) {
                if (side.stream().anyMatch(BearProductionScreens.WORLD::contains)) {
                    assertTrue(side.containsAll(BearProductionScreens.WORLD),
                            () -> action + " must accept every production World state: " + side);
                }
            }
        }
        for (Screen source : Screen.values()) {
            boolean acceptsSome = BearProductionScreens.WORLD.stream()
                    .anyMatch(world -> BearUiAction.confirmsBackFrom(source, world));
            boolean acceptsAll = BearProductionScreens.WORLD.stream()
                    .allMatch(world -> BearUiAction.confirmsBackFrom(source, world));
            assertEquals(acceptsSome, acceptsAll, () -> "Back from " + source);
        }
    }

    @Test
    void everyBackSourceAcceptsOnlyItsOwnVerifiedParents() {
        Map<Screen, Set<Screen>> parents = new EnumMap<>(Screen.class);
        Set<Screen> world = EnumSet.of(
                Screen.WORLD,
                Screen.WORLD_ACTIVE_BEAR_ICON_READY);
        parents.put(Screen.WAR_LIST, world);
        parents.put(Screen.FORMATION, EnumSet.of(
                Screen.WAR_LIST, Screen.BEAR_RALLY_PANEL, Screen.RALLY_TIMER_PANEL));
        parents.put(Screen.RALLY_TIMER_PANEL, EnumSet.of(Screen.BEAR_RALLY_PANEL));
        parents.put(Screen.BEAR_RALLY_PANEL, world);
        parents.put(Screen.ALLIANCE_MENU, world);
        parents.put(Screen.ALLIANCE_WAR, EnumSet.of(Screen.ALLIANCE_MENU));
        parents.put(Screen.AUTOJOIN_PANEL, EnumSet.of(Screen.ALLIANCE_WAR));
        parents.put(Screen.PETS_OVERVIEW, world);
        parents.put(Screen.PET_RAZORBACK, EnumSet.of(Screen.PETS_OVERVIEW));
        parents.put(Screen.PET_QUICK_USE, EnumSet.of(Screen.PET_RAZORBACK));
        parents.put(Screen.PET_CONFIRMATION, EnumSet.of(Screen.PET_RAZORBACK));
        parents.put(Screen.MARCH_SIDEBAR, world);
        parents.put(Screen.SIDEBAR_OTHER, world);
        parents.put(Screen.DEPLOY_CONFIRMATION, EnumSet.of(Screen.FORMATION, Screen.WAR_LIST));
        parents.put(Screen.MARCH_QUEUE_FULL, EnumSet.of(Screen.FORMATION, Screen.WAR_LIST));

        for (Map.Entry<Screen, Set<Screen>> entry : parents.entrySet()) {
            for (Screen destination : Screen.values()) {
                assertEquals(entry.getValue().contains(destination),
                        BearUiAction.confirmsBackFrom(entry.getKey(), destination),
                        () -> entry.getKey() + " -> " + destination);
            }
        }
    }
}
