package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.domain.PointData;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class RepeatedResourceReplenishmentFlowTest {

    private static final PointData BUTTON = new PointData(350, 1135);

    @Test
    void handlesReplenishAllDialogsChainedDirectlyForEachResource() {
        ScriptedUi ui = new ScriptedUi(
                states(State.REPLENISH, State.REPLENISH, State.REPLENISH, State.REPLENISH, State.READY));

        var result = RepeatedResourceReplenishmentFlow.run(ui, 4);

        assertTrue(result.ready());
        assertEquals(4, result.replenishedResources());
        assertEquals(4, ui.confirmations);
    }

    @Test
    void handlesLegacyReturnToBuildingDetailsBetweenResources() {
        ScriptedUi ui = new ScriptedUi(states(
                State.OBTAIN, State.REPLENISH,
                State.OBTAIN, State.REPLENISH,
                State.READY));

        var result = RepeatedResourceReplenishmentFlow.run(ui, 4);

        assertTrue(result.ready());
        assertEquals(2, result.replenishedResources());
        assertEquals(2, ui.obtainScreensOpened);
        assertEquals(2, ui.confirmations);
    }

    @Test
    void refusesToContinueWhenObtainDoesNotOpenReplenishAll() {
        ScriptedUi ui = new ScriptedUi(states(State.OBTAIN, State.READY));

        var result = RepeatedResourceReplenishmentFlow.run(ui, 4);

        assertEquals(RepeatedResourceReplenishmentFlow.Outcome.REPLENISH_BUTTON_MISSING, result.outcome());
        assertEquals(0, ui.confirmations);
    }

    @Test
    void stopsInsteadOfLoopingForeverWhenResourceDialogRemains() {
        ScriptedUi ui = new ScriptedUi(states(
                State.REPLENISH, State.REPLENISH, State.REPLENISH, State.REPLENISH, State.REPLENISH));

        var result = RepeatedResourceReplenishmentFlow.run(ui, 4);

        assertEquals(RepeatedResourceReplenishmentFlow.Outcome.LIMIT_REACHED, result.outcome());
        assertEquals(4, ui.confirmations);
    }

    private static Queue<State> states(State... states) {
        return new ArrayDeque<>(Arrays.asList(states));
    }

    private enum State {
        REPLENISH,
        OBTAIN,
        READY
    }

    private static final class ScriptedUi implements RepeatedResourceReplenishmentFlow.Ui {

        private final Queue<State> states;
        private State current;
        private int obtainScreensOpened;
        private int confirmations;

        private ScriptedUi(Queue<State> states) {
            this.states = states;
            current = states.remove();
        }

        @Override
        public PointData findReplenishAll() {
            return current == State.REPLENISH ? BUTTON : null;
        }

        @Override
        public PointData findObtain() {
            return current == State.OBTAIN ? BUTTON : null;
        }

        @Override
        public void openObtain(PointData point) {
            obtainScreensOpened++;
            advance();
        }

        @Override
        public void replenishAndConfirm(PointData point) {
            confirmations++;
            advance();
        }

        private void advance() {
            current = states.isEmpty() ? State.READY : states.remove();
        }
    }
}
