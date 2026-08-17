package dev.frostguard.tasks.economy;

import static dev.frostguard.tasks.economy.GatherRotationSelectionPolicy.RefillMode.DUPLICATES;
import static dev.frostguard.tasks.economy.GatherRotationSelectionPolicy.RefillMode.MISSING_TYPES;
import static dev.frostguard.tasks.economy.GatherRotationSelectionPolicy.RefillMode.UNAVAILABLE_MISSING_TYPES;
import static dev.frostguard.tasks.economy.GatherRoutine.GatherType.COAL;
import static dev.frostguard.tasks.economy.GatherRoutine.GatherType.IRON;
import static dev.frostguard.tasks.economy.GatherRoutine.GatherType.MEAT;
import static dev.frostguard.tasks.economy.GatherRoutine.GatherType.WOOD;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GatherRotationSelectionPolicyTest {

    private static final List<GatherRoutine.GatherType> ALL_TYPES = List.of(MEAT, WOOD, COAL, IRON);

    @Test
    void prioritizesTypesMissingAfterPersistedPoolIsConsumed() {
        GatherRotationSelectionPolicy.Refill refill = GatherRotationSelectionPolicy.refill(
                ALL_TYPES, List.of(MEAT), Set.of());

        assertEquals(MISSING_TYPES, refill.mode());
        assertEquals(List.of(WOOD, COAL, IRON), refill.candidates());
    }

    @Test
    void allowsDuplicatesOnlyAfterEveryEnabledTypeIsActive() {
        GatherRotationSelectionPolicy.Refill refill = GatherRotationSelectionPolicy.refill(
                ALL_TYPES, ALL_TYPES, Set.of());

        assertEquals(DUPLICATES, refill.mode());
        assertEquals(ALL_TYPES, refill.candidates());
    }

    @Test
    void supportsDuplicateMarchesWhenOnlyOneTypeIsEnabled() {
        GatherRotationSelectionPolicy.Refill refill = GatherRotationSelectionPolicy.refill(
                List.of(IRON), List.of(IRON), Set.of());

        assertEquals(DUPLICATES, refill.mode());
        assertEquals(List.of(IRON), refill.candidates());
    }

    @Test
    void doesNotReplaceAnUnavailableMissingTypeWithADuplicate() {
        GatherRotationSelectionPolicy.Refill refill = GatherRotationSelectionPolicy.refill(
                ALL_TYPES, List.of(MEAT, WOOD, COAL), Set.of(IRON));

        assertEquals(UNAVAILABLE_MISSING_TYPES, refill.mode());
        assertEquals(List.of(), refill.candidates());
    }
}
