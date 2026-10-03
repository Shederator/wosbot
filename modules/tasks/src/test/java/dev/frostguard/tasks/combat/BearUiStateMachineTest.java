package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BearUiStateMachineTest {

    @Test
    void ambiguousDispatchIsTracedAndEscapesToRecoveryWithoutRetry() {
        List<String> diagnostics = new ArrayList<>();
        AtomicInteger sends = new AtomicInteger();
        BearFrameStream<BearNavigationPolicy.Screen> frames = new BearFrameStream<>(
                () -> BearNavigationPolicy.Screen.WORLD, state -> state, () -> false);
        BearUiStateMachine<BearNavigationPolicy.Screen> machine = new BearUiStateMachine<>(
                frames, Duration.ofMillis(100), diagnostics::add,
                (frame, action) -> {
                    assertEquals(1, frame.sequence());
                    action.run();
                });
        machine.observe();
        assertThrows(IllegalStateException.class, () -> machine.transition(
                BearUiAction.OPEN_ALLIANCE, frame -> {
                    sends.incrementAndGet();
                    throw new IllegalStateException("ambiguous delivery");
                }, frame -> true, 3));
        assertEquals(1, sends.get());
        assertTrue(machine.lastInputSent());
        assertTrue(diagnostics.stream().anyMatch(line -> line.contains("input-delivery-ambiguous")
                && line.contains("frame=1") && line.contains("terminal=NOT_CONFIRMED")));
        assertEquals(BearVerifiedActionExecutor.Outcome.STALE_AUTHORIZATION,
                machine.transition(BearUiAction.OPEN_ALLIANCE, frame -> sends.incrementAndGet()));
        assertEquals(1, sends.get(), "consumed frame cannot authorize another input");
    }

    @Test
    void legalTransitionRequiresNewerDestinationFrameAndEmitsProvenance() {
        Deque<BearNavigationPolicy.Screen> script = new ArrayDeque<>(List.of(
                BearNavigationPolicy.Screen.WORLD,
                BearNavigationPolicy.Screen.UNKNOWN,
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

        assertEquals(BearVerifiedActionExecutor.Outcome.NOT_AUTHORIZED, outcome,
                "an illegal source state sends no input");
        assertEquals(0, taps.get());
        assertFalse(machine.lastInputSent());
    }

    @Test
    void refusedInputIsAnEdgeOutcomeThatConsumesNothing() {
        Deque<BearNavigationPolicy.Screen> script = new ArrayDeque<>(List.of(
                BearNavigationPolicy.Screen.WORLD,
                BearNavigationPolicy.Screen.ALLIANCE_MENU));
        BearFrameStream<BearNavigationPolicy.Screen> frames = new BearFrameStream<>(
                script::removeFirst, state -> state, () -> false);
        BearUiStateMachine<BearNavigationPolicy.Screen> machine =
                new BearUiStateMachine<>(frames, Duration.ofSeconds(1), ignored -> { });
        AtomicInteger taps = new AtomicInteger();

        machine.observe();
        BearVerifiedActionExecutor.Outcome refused = machine.transition(
                BearUiAction.OPEN_ALLIANCE,
                ignored -> {
                    throw new BearInputRefusedException("target-missing-in-authorizing-frame");
                });

        assertEquals(BearVerifiedActionExecutor.Outcome.NOT_AUTHORIZED, refused);
        assertFalse(machine.lastInputSent());
        assertEquals("target-missing-in-authorizing-frame", machine.lastRefusal());

        BearVerifiedActionExecutor.Outcome retried = machine.transition(
                BearUiAction.OPEN_ALLIANCE,
                ignored -> taps.incrementAndGet());
        assertEquals(BearVerifiedActionExecutor.Outcome.CONFIRMED, retried,
                "the unused authorizing frame remains usable");
        assertEquals(1, taps.get());
        assertTrue(machine.lastInputSent());
    }

    @Test
    void missedPostconditionAfterInputReportsThatInputWasSent() {
        BearFrameStream<BearNavigationPolicy.Screen> frames = new BearFrameStream<>(
                () -> BearNavigationPolicy.Screen.WORLD, state -> state, () -> false);
        BearUiStateMachine<BearNavigationPolicy.Screen> machine =
                new BearUiStateMachine<>(frames, Duration.ofMillis(150), ignored -> { });

        machine.observe();
        BearVerifiedActionExecutor.Outcome outcome = machine.transition(
                BearUiAction.OPEN_ALLIANCE,
                ignored -> { });

        assertEquals(BearVerifiedActionExecutor.Outcome.NOT_CONFIRMED, outcome);
        assertTrue(machine.lastInputSent(), "an unconfirmed input may still have taken effect");
    }

    @Test
    void chainedTransitionRefreshesASupersededAuthorizationFrame() {
        Deque<BearNavigationPolicy.Screen> script = new ArrayDeque<>(List.of(
                BearNavigationPolicy.Screen.WORLD,
                BearNavigationPolicy.Screen.WORLD,
                BearNavigationPolicy.Screen.WORLD,
                BearNavigationPolicy.Screen.ALLIANCE_MENU));
        AtomicInteger taps = new AtomicInteger();
        BearFrameStream<BearNavigationPolicy.Screen> frames = new BearFrameStream<>(
                script::removeFirst, state -> state, () -> false);
        BearUiStateMachine<BearNavigationPolicy.Screen> machine =
                new BearUiStateMachine<>(frames, Duration.ofSeconds(1), ignored -> { });

        machine.observe();
        frames.next();
        BearVerifiedActionExecutor.Outcome outcome = machine.transition(
                BearUiAction.OPEN_ALLIANCE,
                ignored -> taps.incrementAndGet());

        assertEquals(BearVerifiedActionExecutor.Outcome.CONFIRMED, outcome);
        assertEquals(1, taps.get());
    }
}
