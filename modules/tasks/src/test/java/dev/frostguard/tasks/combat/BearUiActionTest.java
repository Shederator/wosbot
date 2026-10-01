package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.frostguard.tasks.combat.BearNavigationPolicy.Screen;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BearUiActionTest {

    @Test
    void everyBackSourceAcceptsOnlyItsOwnVerifiedParents() {
        Map<Screen, Set<Screen>> parents = new EnumMap<>(Screen.class);
        Set<Screen> world = EnumSet.of(
                Screen.WORLD, Screen.WORLD_AT_BEAR, Screen.WORLD_AT_VERIFIED_BEAR,
                Screen.WORLD_ACTIVE_BEAR_ICON_READY);
        parents.put(Screen.WAR_LIST, world);
        parents.put(Screen.FORMATION, EnumSet.of(
                Screen.WAR_LIST, Screen.BEAR_RALLY_PANEL, Screen.RALLY_TIMER_PANEL));
        parents.put(Screen.RALLY_TIMER_PANEL, EnumSet.of(Screen.BEAR_RALLY_PANEL));
        parents.put(Screen.BEAR_RALLY_PANEL, world);
        parents.put(Screen.ALLIANCE_MENU, EnumSet.of(Screen.WORLD));
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
