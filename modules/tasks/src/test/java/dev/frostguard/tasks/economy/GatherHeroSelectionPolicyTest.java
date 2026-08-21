package dev.frostguard.tasks.economy;

import static dev.frostguard.tasks.economy.GatherHeroSelectionPolicy.Action.KEEP_DEFAULT;
import static dev.frostguard.tasks.economy.GatherHeroSelectionPolicy.Action.REMOVE_ADDITIONAL;
import static dev.frostguard.tasks.economy.GatherHeroSelectionPolicy.Action.REMOVE_ALL;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GatherHeroSelectionPolicyTest {

    @Test
    void removesAllHeroesWhenPreferredHeroIsMissingAndFallbackIsEnabled() {
        assertEquals(REMOVE_ALL, GatherHeroSelectionPolicy.select(false, false, true));
        assertEquals(REMOVE_ALL, GatherHeroSelectionPolicy.select(false, true, true));
    }

    @Test
    void keepsExistingBehaviorWhenNoHeroFallbackIsDisabled() {
        assertEquals(KEEP_DEFAULT, GatherHeroSelectionPolicy.select(false, false, false));
        assertEquals(REMOVE_ADDITIONAL, GatherHeroSelectionPolicy.select(false, true, false));
    }

    @Test
    void preservesPreferredHeroWhenItIsAvailable() {
        assertEquals(KEEP_DEFAULT, GatherHeroSelectionPolicy.select(true, false, true));
        assertEquals(REMOVE_ADDITIONAL, GatherHeroSelectionPolicy.select(true, true, true));
    }
}
