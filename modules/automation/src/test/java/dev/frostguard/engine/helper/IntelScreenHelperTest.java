package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import dev.frostguard.engine.error.HomeNotFoundException;
import dev.frostguard.engine.nav.SidebarRowLookup;

class IntelScreenHelperTest {

    @Test
    void sidebarTransitionFailureCannotMasqueradeAsAnAbsentIntelRow() {
        assertThrows(HomeNotFoundException.class,
                () -> IntelScreenHelper.requireSidebarReady(
                        SidebarRowLookup.sidebarUnavailable()));
    }

    @Test
    void completedScanCanStillReportThatTheIntelRowIsAbsent() {
        assertFalse(IntelScreenHelper.requireSidebarReady(
                SidebarRowLookup.fromRow(null)).isFound());
    }
}
