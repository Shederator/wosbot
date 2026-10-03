package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Deterministic state replays. These deliberately do not claim pixel/template accuracy. */
class BearTransitionReplayTest {

    @Test
    void unknownBackIsAProvenancedSingleInputWithANewerPostcondition() {
        Replay replay = new Replay(BearNavigationPolicy.Screen.UNKNOWN, BearNavigationPolicy.Screen.WORLD);
        long before = replay.machine.observe().sequence();
        replay.confirm(BearUiAction.RECOVER_UNKNOWN_BACK);
        assertEquals(1, replay.inputs.get());
        assertTrue(replay.machine.current().sequence() > before);
        assertTrue(replay.diagnostics.stream().anyMatch(line -> line.contains("RECOVER_UNKNOWN_BACK")));
    }

    @Test
    void unknownBackCannotBeRepeatedAfterItsFrameWasConsumed() {
        Replay replay = new Replay(BearNavigationPolicy.Screen.UNKNOWN, BearNavigationPolicy.Screen.WORLD);
        replay.machine.observe();
        replay.confirm(BearUiAction.RECOVER_UNKNOWN_BACK);
        assertTrue(replay.machine.transition(BearUiAction.RECOVER_UNKNOWN_BACK,
                ignored -> replay.inputs.incrementAndGet()) != BearVerifiedActionExecutor.Outcome.CONFIRMED);
        assertEquals(1, replay.inputs.get());
    }

    @Test
    void preparationRouteFailsClosedAtTheUnverifiedConfiguredTrapGo() {
        Replay replay = new Replay(
                BearNavigationPolicy.Screen.WORLD,
                BearNavigationPolicy.Screen.ALLIANCE_MENU,
                BearNavigationPolicy.Screen.ALLIANCE_TERRITORY,
                BearNavigationPolicy.Screen.SPECIAL_BUILDINGS,
                BearNavigationPolicy.Screen.WORLD,
                BearNavigationPolicy.Screen.WORLD,
                BearNavigationPolicy.Screen.WORLD,
                BearNavigationPolicy.Screen.WORLD);

        assertEquals(BearNavigationPolicy.Screen.WORLD, replay.machine.observe().screen());
        replay.confirm(BearUiAction.OPEN_ALLIANCE);
        replay.confirm(BearUiAction.OPEN_TERRITORY);
        replay.confirm(BearUiAction.OPEN_SPECIAL_BUILDINGS);
        assertEquals(BearVerifiedActionExecutor.Outcome.NOT_CONFIRMED, replay.machine.transition(
                        BearUiAction.GO_TO_CONFIGURED_TRAP, ignored -> replay.inputs.incrementAndGet()),
                "arrival at the configured trap has no real-frame identity yet");
    }

    @Test
    void slowRenderIsSampledUntilTheNewerPostconditionFrame() {
        Replay replay = new Replay(
                BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY,
                BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY,
                BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY,
                BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY,
                BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY,
                BearNavigationPolicy.Screen.WAR_LIST);
        replay.machine.observe();

        assertEquals(BearVerifiedActionExecutor.Outcome.CONFIRMED,
                replay.machine.transition(BearUiAction.OPEN_WAR_LIST, ignored -> replay.inputs.incrementAndGet()));
        assertEquals(1, replay.inputs.get(), "slow rendering must not cause a second tap");
    }

    @Test
    void ownRallyNavigationRequiresCenteredTrapBeforeOpeningItsPanel() {
        Replay replay = new Replay(BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY,
                BearNavigationPolicy.Screen.WORLD_AT_CONFIGURED_BEAR,
                BearNavigationPolicy.Screen.BEAR_RALLY_PANEL);
        replay.machine.observe();
        replay.confirm(BearUiAction.OPEN_ACTIVE_BEAR);
        replay.confirm(BearUiAction.OPEN_CENTERED_BEAR);
        assertEquals(2, replay.inputs.get());
    }

    @Test
    void activeBearCentringFailsClosedUntilItHasARealPostcondition() {
        Replay replay = new Replay(
                BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY,
                BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY,
                BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY,
                BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY);
        replay.machine.observe();

        assertEquals(BearVerifiedActionExecutor.Outcome.NOT_CONFIRMED,
                replay.machine.transition(BearUiAction.OPEN_ACTIVE_BEAR, ignored -> replay.inputs.incrementAndGet()));
    }

    @Test
    void failedPostconditionEntersAnExplicitTerminalRecoveryOutcome() {
        Replay replay = new Replay(
                BearNavigationPolicy.Screen.WAR_LIST,
                BearNavigationPolicy.Screen.WAR_LIST,
                BearNavigationPolicy.Screen.WAR_LIST,
                BearNavigationPolicy.Screen.WAR_LIST);
        replay.machine.observe();

        assertEquals(BearVerifiedActionExecutor.Outcome.NOT_CONFIRMED,
                replay.machine.transition(BearUiAction.OPEN_JOIN_FORMATION,
                        ignored -> replay.inputs.incrementAndGet()));
        replay.machine.phase(BearUiStateMachine.Phase.RECOVERING);
        replay.machine.terminate(BearUiStateMachine.TerminalReason.RECOVERY_EXHAUSTED);
        assertEquals(BearUiStateMachine.TerminalReason.RECOVERY_EXHAUSTED,
                replay.machine.terminalReason());
        assertTrue(replay.diagnostics.stream().anyMatch(line -> line.contains("postcondition")));
    }

    private static final class Replay {
        private final ArrayDeque<BearNavigationPolicy.Screen> frames = new ArrayDeque<>();
        private final AtomicInteger inputs = new AtomicInteger();
        private final List<String> diagnostics = new ArrayList<>();
        private final BearUiStateMachine<BearNavigationPolicy.Screen> machine;

        private Replay(BearNavigationPolicy.Screen... frames) {
            this.frames.addAll(List.of(frames));
            BearFrameStream<BearNavigationPolicy.Screen> stream = new BearFrameStream<>(
                    () -> this.frames.size() > 1 ? this.frames.removeFirst() : this.frames.peekFirst(),
                    screen -> screen,
                    () -> false);
            this.machine = new BearUiStateMachine<>(stream, Duration.ofMillis(500), diagnostics::add);
        }

        private void confirm(BearUiAction action) {
            assertEquals(BearVerifiedActionExecutor.Outcome.CONFIRMED,
                    machine.transition(action, ignored -> inputs.incrementAndGet()));
        }
    }
}
