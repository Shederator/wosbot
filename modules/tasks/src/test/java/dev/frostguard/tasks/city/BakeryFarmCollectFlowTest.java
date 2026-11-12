package dev.frostguard.tasks.city;

import dev.frostguard.engine.helper.NavigationHelper.BuildingDestination;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BakeryFarmCollectFlowTest {

    @Test
    void collectsFarmThenBakeryWhenBothAreConfirmed() {
        ScriptedActions actions = new ScriptedActions(false,
                BakeryFarmCollectFlow.TargetResult.confirmed(),
                BakeryFarmCollectFlow.TargetResult.confirmed());

        BakeryFarmCollectFlow.VisitResult result = BakeryFarmCollectFlow.execute(actions);

        assertTrue(result.completedReliably());
        assertEquals(2, result.confirmedCollections());
        assertEquals(List.of(BuildingDestination.FARM, BuildingDestination.BAKERY), actions.visited);
    }

    @Test
    void treatsBothAbsentRewardsAsACompleteReliableVisit() {
        BakeryFarmCollectFlow.VisitResult result = BakeryFarmCollectFlow.execute(new ScriptedActions(false,
                BakeryFarmCollectFlow.TargetResult.absent(), BakeryFarmCollectFlow.TargetResult.absent()));

        assertTrue(result.completedReliably());
        assertEquals(0, result.confirmedCollections());
        assertFalse(result.hasUnconfirmedAction());
    }

    @Test
    void stopsBeforeBakeryWhenFarmCollectionIsUnconfirmed() {
        ScriptedActions actions = new ScriptedActions(false,
                BakeryFarmCollectFlow.TargetResult.failed(BakeryFarmCollectFlow.FailureReason.ACTION),
                BakeryFarmCollectFlow.TargetResult.confirmed());

        BakeryFarmCollectFlow.VisitResult result = BakeryFarmCollectFlow.execute(actions);

        assertFalse(result.completedReliably());
        assertTrue(result.hasUnconfirmedAction());
        assertEquals(BakeryFarmCollectFlow.FailureReason.ACTION, result.failureReason());
        assertEquals(List.of(BuildingDestination.FARM), actions.visited);
    }

    @Test
    void stopsBeforeAnyInteractionWhenVisitHasTimedOut() {
        ScriptedActions actions = new ScriptedActions(true,
                BakeryFarmCollectFlow.TargetResult.confirmed(), BakeryFarmCollectFlow.TargetResult.confirmed());

        BakeryFarmCollectFlow.VisitResult result = BakeryFarmCollectFlow.execute(actions);

        assertEquals(BakeryFarmCollectFlow.FailureReason.TIMEOUT, result.failureReason());
        assertTrue(actions.visited.isEmpty());
    }

    private static final class ScriptedActions implements BakeryFarmCollectFlow.Actions {
        private final boolean timedOut;
        private final BakeryFarmCollectFlow.TargetResult farm;
        private final BakeryFarmCollectFlow.TargetResult bakery;
        private final List<BuildingDestination> visited = new ArrayList<>();

        private ScriptedActions(boolean timedOut, BakeryFarmCollectFlow.TargetResult farm,
                                BakeryFarmCollectFlow.TargetResult bakery) {
            this.timedOut = timedOut;
            this.farm = farm;
            this.bakery = bakery;
        }

        @Override
        public boolean timedOut() {
            return timedOut;
        }

        @Override
        public BakeryFarmCollectFlow.TargetResult visit(BuildingDestination destination) {
            visited.add(destination);
            return destination == BuildingDestination.FARM ? farm : bakery;
        }
    }
}
