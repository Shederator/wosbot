package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.SidebarDestination;

class SidebarNavigatorOpenPolicyTest {

    @Test
    void downwardScanUsesShortOverlappingGesturesAndWaitsForTheList() {
        assertEquals(SidebarNavigator.SCROLL_DISTANCE_PX,
                Math.abs(CommonGameAreas.SIDEBAR_SCROLL_TOWARD_BOTTOM_FROM.getY()
                        - CommonGameAreas.SIDEBAR_SCROLL_TOWARD_BOTTOM_TO.getY()));
        assertEquals(2_000, SidebarNavigator.SCROLL_SETTLE_MS);
    }

    @Test
    void everyDestinationUsesARealLeftIconRatherThanTheSharedGoArrow() {
        for (SidebarDestination destination : SidebarDestination.values()) {
            assertNotEquals(TemplatesEnum.GAME_HOME_SHORTCUTS_GO, destination.rowIcon(), destination.name());
            assertTrue(destination.actions().length > 0, destination.name());
        }
    }
}
