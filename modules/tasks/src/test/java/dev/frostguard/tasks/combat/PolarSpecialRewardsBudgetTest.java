package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PolarSpecialRewardsBudgetTest {

    @Test
    void retainsTheInitialReadForTheWholeVisit() {
        PolarSpecialRewardsBudget budget = new PolarSpecialRewardsBudget();

        assertTrue(budget.requiresInitialRead());
        budget.recordInitialRead(3);

        assertFalse(budget.requiresInitialRead());
        assertEquals(3, budget.remaining());
    }

    @Test
    void decrementsOnlyWhenAConfirmedRallyConsumesTheBudget() {
        PolarSpecialRewardsBudget budget = new PolarSpecialRewardsBudget();
        budget.recordInitialRead(2);

        assertEquals(1, budget.consumeConfirmedRally());
        assertEquals(1, budget.remaining());
        assertEquals(0, budget.consumeConfirmedRally());
        assertTrue(budget.isExhausted());
    }

    @Test
    void recognizesAnInitiallyExhaustedBudget() {
        PolarSpecialRewardsBudget budget = new PolarSpecialRewardsBudget();
        budget.recordInitialRead(0);

        assertTrue(budget.isExhausted());
    }
}
