package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BearUiStateMachineTest {

    @Test
    void legalTransitionRequiresNewerDestinationFrameAndEmitsProvenance() {
        Deque<BearNavigationPolicy.Screen> script = new ArrayDeque<>(List.of(
                BearNavigationPolicy.Screen.WORLD,
                BearNavigationPolicy.Screen.TRANSITIONING,
                BearNavigationPolicy.Screen.ALLIANCE_MENU));
        List<String> diagnostics = new ArrayList<>();
        AtomicInteger taps = new AtomicInteger();
        BearFrameStream<BearNavigationPolicy.Screen> frames = new BearFrameStream<>(
                script::removeFirst, state -> state, () -> false);
        BearUiStateMachine<BearNavigationPolicy.Screen> machine =
                new BearUiStateMachine<>(frames, Duration.ofSeconds(1), diagnostics::add);

        machine.observe();
        BearVerifiedActionExecutor.Outcome outcome = machine.transition(
                BearUiAction.OPEN_ALLIANCE,
                ignored -> taps.incrementAndGet());

        assertEquals(BearVerifiedActionExecutor.Outcome.CONFIRMED, outcome);
        assertEquals(1, taps.get());
        assertTrue(diagnostics.stream().anyMatch(line -> line.contains("action=OPEN_ALLIANCE")
                && line.contains("frame=1")
                && line.contains("actual=ALLIANCE_MENU")));
    }

    @Test
    void illegalStateCannotExecuteInput() {
        AtomicInteger taps = new AtomicInteger();
        BearFrameStream<BearNavigationPolicy.Screen> frames = new BearFrameStream<>(
                () -> BearNavigationPolicy.Screen.WAR_LIST, state -> state, () -> false);
        BearUiStateMachine<BearNavigationPolicy.Screen> machine =
                new BearUiStateMachine<>(frames, Duration.ofMillis(100), ignored -> { });

        machine.observe();
        BearVerifiedActionExecutor.Outcome outcome = machine.transition(
                BearUiAction.OPEN_ALLIANCE,
                ignored -> taps.incrementAndGet());

        assertEquals(BearVerifiedActionExecutor.Outcome.NOT_CONFIRMED, outcome);
        assertEquals(0, taps.get());
    }
}
