package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.frostguard.engine.nav.SidebarSection;

class SidebarNavigatorOpenPolicyTest {

    @Test
    void opensOnlyWhenThePanelIsClosed() {
        assertEquals(SidebarNavigator.NextOpenAction.OPEN_PANEL,
                SidebarNavigator.nextOpenAction(Optional.empty(), SidebarSection.CITY, false, false));
    }

    @Test
    void reusesAnAlreadySelectedSectionWithoutInteractions() {
        assertEquals(SidebarNavigator.NextOpenAction.DONE,
                SidebarNavigator.nextOpenAction(
                        Optional.of(SidebarSection.WILDERNESS), SidebarSection.WILDERNESS, false, false));
    }

    @Test
    void selectsOnlyWhenAnotherSectionIsVisible() {
        assertEquals(SidebarNavigator.NextOpenAction.SELECT_SECTION,
                SidebarNavigator.nextOpenAction(
                        Optional.of(SidebarSection.DAILY), SidebarSection.CITY, false, false));
    }

    @Test
    void resetsOnlyWhenTheCallerRequiresAKnownOrigin() {
        assertEquals(SidebarNavigator.NextOpenAction.RESET_TO_TOP,
                SidebarNavigator.nextOpenAction(
                        Optional.of(SidebarSection.DAILY), SidebarSection.DAILY, true, false));
        assertEquals(SidebarNavigator.NextOpenAction.DONE,
                SidebarNavigator.nextOpenAction(
                        Optional.of(SidebarSection.DAILY), SidebarSection.DAILY, true, true));
    }
}
