package dev.frostguard.app.panel.taskbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.SidebarNavigationMode;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.nav.SidebarSection;

class SidebarNavigationOptionsTest {

    @Test
    void preservesAValidTargetForTheSelectedMode() {
        assertEquals(SidebarSection.DAILY.name(),
                SidebarNavigationOptions.chooseTarget(
                        SidebarNavigationMode.SECTION, SidebarSection.DAILY.name()));
        assertEquals(SidebarDestination.ARENA.name(),
                SidebarNavigationOptions.chooseTarget(
                        SidebarNavigationMode.DESTINATION, SidebarDestination.ARENA.name()));
    }

    @Test
    void defaultsToAValidTargetWhenModeChanges() {
        assertEquals(SidebarSection.CITY.name(),
                SidebarNavigationOptions.chooseTarget(
                        SidebarNavigationMode.SECTION, SidebarDestination.ARENA.name()));
        assertEquals(SidebarDestination.RESEARCH_CENTER.name(),
                SidebarNavigationOptions.chooseTarget(
                        SidebarNavigationMode.DESTINATION, SidebarSection.DAILY.name()));
    }

    @Test
    void clearsTargetWhenModeIsMissing() {
        assertNull(SidebarNavigationOptions.chooseTarget(null, SidebarSection.CITY.name()));
    }

    @Test
    void displaysTargetNamesAsReadableText() {
        assertEquals("Lighthouse intel",
                SidebarNavigationOptions.displayTarget(SidebarDestination.LIGHTHOUSE_INTEL.name()));
    }
}
