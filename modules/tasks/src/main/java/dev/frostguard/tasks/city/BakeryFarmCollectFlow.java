package dev.frostguard.tasks.city;

import dev.frostguard.engine.helper.NavigationHelper.BuildingDestination;

/** Pure ordering and terminal-decision flow for the required Bakery then optional Farm visit. */
final class BakeryFarmCollectFlow {

    enum TargetState { CONFIRMED, ABSENT, FAILED }

    enum FailureReason { NONE, TIMEOUT, NAVIGATION, DETECTION, ACTION, UNEXPECTED }

    record TargetResult(TargetState state, FailureReason failureReason) {
        static TargetResult confirmed() { return new TargetResult(TargetState.CONFIRMED, FailureReason.NONE); }
        static TargetResult absent() { return new TargetResult(TargetState.ABSENT, FailureReason.NONE); }
        static TargetResult failed(FailureReason reason) { return new TargetResult(TargetState.FAILED, reason); }
    }

    record VisitResult(TargetResult farm, TargetResult bakery, FailureReason failureReason) {
        int confirmedCollections() {
            return count(TargetState.CONFIRMED);
        }

        int confirmedFarmCollections() {
            return farm != null && farm.state() == TargetState.CONFIRMED ? 1 : 0;
        }

        int confirmedBakeryCollections() {
            return bakery != null && bakery.state() == TargetState.CONFIRMED ? 1 : 0;
        }

        boolean completedReliably() {
            return failureReason == FailureReason.NONE;
        }

        boolean hasUnconfirmedAction() {
            return failureReason == FailureReason.ACTION;
        }

        private int count(TargetState state) {
            int count = farm != null && farm.state() == state ? 1 : 0;
            return bakery != null && bakery.state() == state ? count + 1 : count;
        }
    }

    interface Actions {
        boolean timedOut();
        TargetResult visit(BuildingDestination destination);
    }

    private BakeryFarmCollectFlow() {}

    static VisitResult execute(Actions actions, boolean farmExpected) {
        if (actions.timedOut()) {
            return new VisitResult(null, null, FailureReason.TIMEOUT);
        }
        TargetResult bakery = actions.visit(BuildingDestination.BAKERY);
        if (bakery.state() == TargetState.FAILED) {
            return new VisitResult(null, bakery, bakery.failureReason());
        }
        if (actions.timedOut()) {
            return new VisitResult(null, bakery, FailureReason.TIMEOUT);
        }

        TargetResult farm = actions.visit(BuildingDestination.FARM);
        if (!farmExpected && farm.state() == TargetState.FAILED
                && farm.failureReason() == FailureReason.NAVIGATION) {
            return new VisitResult(TargetResult.absent(), bakery, FailureReason.NONE);
        }
        if (farm.state() == TargetState.FAILED) {
            return new VisitResult(farm, bakery, farm.failureReason());
        }
        return new VisitResult(farm, bakery, FailureReason.NONE);
    }
}
