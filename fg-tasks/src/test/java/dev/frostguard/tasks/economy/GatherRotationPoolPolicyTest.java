package dev.frostguard.tasks.economy;

import static dev.frostguard.tasks.economy.GatherRoutine.GatherType.COAL;
import static dev.frostguard.tasks.economy.GatherRoutine.GatherType.IRON;
import static dev.frostguard.tasks.economy.GatherRoutine.GatherType.MEAT;
import static dev.frostguard.tasks.economy.GatherRoutine.GatherType.WOOD;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class GatherRotationPoolPolicyTest {

    @Test
    void addsOnlyNewlyEnabledTypesToCurrentRotation() {
        GatherRotationPoolPolicy.Reconciliation result = GatherRotationPoolPolicy.reconcile(
                List.of(WOOD, COAL),
                List.of(MEAT, WOOD, COAL),
                List.of(MEAT, WOOD, COAL, IRON));

        assertEquals(List.of(WOOD, COAL, IRON), result.pool());
        assertEquals(List.of(IRON), result.newlyEnabled());
        assertEquals(List.of(), result.disabled());
    }

    @Test
    void preservesPreviouslyConsumedTypesWhenConfigurationIsUnchanged() {
        GatherRotationPoolPolicy.Reconciliation result = GatherRotationPoolPolicy.reconcile(
                List.of(COAL, IRON),
                List.of(MEAT, WOOD, COAL, IRON),
                List.of(MEAT, WOOD, COAL, IRON));

        assertEquals(List.of(COAL, IRON), result.pool());
        assertEquals(List.of(), result.newlyEnabled());
        assertEquals(List.of(), result.disabled());
    }

    @Test
    void removesDisabledTypesWithoutResettingRemainingRotation() {
        GatherRotationPoolPolicy.Reconciliation result = GatherRotationPoolPolicy.reconcile(
                List.of(WOOD, COAL, IRON),
                List.of(MEAT, WOOD, COAL, IRON),
                List.of(MEAT, COAL, IRON));

        assertEquals(List.of(COAL, IRON), result.pool());
        assertEquals(List.of(), result.newlyEnabled());
        assertEquals(List.of(WOOD), result.disabled());
    }

    @Test
    void initialBaselineOnlyPrunesDisabledAndDuplicateEntries() {
        assertEquals(
                List.of(COAL, IRON),
                GatherRotationPoolPolicy.initialize(
                        List.of(WOOD, COAL, IRON, COAL),
                        List.of(MEAT, COAL, IRON)).pool());
    }
}
