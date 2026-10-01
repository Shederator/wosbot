package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BearTerritoryTransitionTest {

    @Test
    void confirmsTransitionWhenIconLeavesItsTriggerRegionButStillMatchesElsewhere() {
        ImageSearchResultData trigger = ImageSearchResultData.hit(120, 400, 0.97, 56, 56);
        AtomicReference<AreaData> searched = new AtomicReference<>();

        boolean confirmed = BearTerritoryTransition.hasLeftTriggerRegion(trigger, area -> {
            searched.set(area);
            return false;
        });

        assertTrue(confirmed);
        assertEquals(84, searched.get().topLeft().getX());
        assertEquals(364, searched.get().topLeft().getY());
        assertEquals(155, searched.get().bottomRight().getX());
        assertEquals(435, searched.get().bottomRight().getY());
    }
}
