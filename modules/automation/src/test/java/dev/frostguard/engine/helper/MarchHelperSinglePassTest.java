package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.nav.CommonGameAreas;

class MarchHelperSinglePassTest {

    @Test
    void opensWildernessPanelWithOneTapPerControl() {
        List<PointData> taps = new ArrayList<>();
        TapInteractionService interactions = new TapInteractionService(taps::add, null, ignored -> { });

        MarchHelper.openLeftMenuCitySectionOnce(interactions, false);

        assertEquals(2, taps.size());
        assertInside(CommonGameAreas.LEFT_MENU_TRIGGER, taps.get(0));
        assertInside(CommonGameAreas.LEFT_MENU_WILDERNESS_TAB, taps.get(1));
    }

    @Test
    void opensCityPanelWithOneTapPerControl() {
        List<PointData> taps = new ArrayList<>();
        TapInteractionService interactions = new TapInteractionService(taps::add, null, ignored -> { });

        MarchHelper.openLeftMenuCitySectionOnce(interactions, true);

        assertEquals(2, taps.size());
        assertInside(CommonGameAreas.LEFT_MENU_TRIGGER, taps.get(0));
        assertInside(CommonGameAreas.LEFT_MENU_CITY_TAB, taps.get(1));
    }

    private void assertInside(AreaData area, PointData point) {
        assertTrue(point.getX() >= area.topLeft().getX() && point.getX() <= area.bottomRight().getX());
        assertTrue(point.getY() >= area.topLeft().getY() && point.getY() <= area.bottomRight().getY());
    }
}
