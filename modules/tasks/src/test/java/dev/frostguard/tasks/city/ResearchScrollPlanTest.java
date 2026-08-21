package dev.frostguard.tasks.city;

import dev.frostguard.api.configs.ResearchCategoryEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResearchScrollPlanTest {

    @Test
    void usesLiveCalibratedCategoryTraversalLimits() {
        assertEquals(9, ResearchRoutine.maximumDownSwipes(ResearchCategoryEnum.GROWTH));
        assertEquals(8, ResearchRoutine.maximumDownSwipes(ResearchCategoryEnum.ECONOMY));
        assertEquals(15, ResearchRoutine.maximumDownSwipes(ResearchCategoryEnum.BATTLE));
    }
}
