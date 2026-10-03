package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BearTrapPreparationTest {
    record Scene(BearNavigationPolicy.Screen screen, boolean configuredTrap) { }

    @Test
    void navigatesOneVerifiedEdgeAtATimeToTheConfiguredTrap() {
        var scene = new AtomicReference<>(new Scene(BearNavigationPolicy.Screen.WORLD, false));
        var frames = new BearFrameStream<>(scene::get, Scene::screen, () -> false);
        var ui = new BearUiStateMachine<>(frames, Duration.ofMillis(20), ignored -> { });
        var actions = new ArrayList<BearUiAction>();
        assertTrue(BearTrapPreparation.navigate(ui, frame -> frame.frame().configuredTrap(), (action, frame) -> {
            assertSame(frames.latest(), frame);
            assertTrue(action.legalFrom(frame.screen()));
            actions.add(action);
            scene.set(switch (action) {
                case OPEN_ALLIANCE -> new Scene(BearNavigationPolicy.Screen.ALLIANCE_MENU, false);
                case OPEN_TERRITORY -> new Scene(BearNavigationPolicy.Screen.ALLIANCE_TERRITORY, false);
                case OPEN_SPECIAL_BUILDINGS -> new Scene(BearNavigationPolicy.Screen.SPECIAL_BUILDINGS, false);
                case GO_TO_CONFIGURED_TRAP -> new Scene(BearNavigationPolicy.Screen.WORLD, true);
                default -> throw new AssertionError(action);
            });
        }));
        assertEquals(List.of(BearUiAction.OPEN_ALLIANCE, BearUiAction.OPEN_TERRITORY,
                BearUiAction.OPEN_SPECIAL_BUILDINGS, BearUiAction.GO_TO_CONFIGURED_TRAP), actions);
    }

    @Test
    void worldArrivalWithoutConfiguredIdentityFailsWithoutRepeatingGo() {
        var scene = new AtomicReference<>(new Scene(BearNavigationPolicy.Screen.SPECIAL_BUILDINGS, false));
        var frames = new BearFrameStream<>(scene::get, Scene::screen, () -> false);
        var ui = new BearUiStateMachine<>(frames, Duration.ofMillis(5), ignored -> { });
        var actions = new ArrayList<BearUiAction>();
        assertFalse(BearTrapPreparation.navigate(ui, frame -> frame.frame().configuredTrap(), (action, frame) -> {
            actions.add(action);
            scene.set(new Scene(BearNavigationPolicy.Screen.WORLD, false));
        }));
        assertEquals(List.of(BearUiAction.GO_TO_CONFIGURED_TRAP), actions);
    }
}
