package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.error.HomeNotFoundException;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.nav.SidebarRowLookup;
import dev.frostguard.engine.schedule.LaunchPoint;

class IntelScreenHelperTest {

    private static final AccountDescriptor PROFILE =
            new AccountDescriptor(999L, "Synthetic Intel", "review", true, 0L, 0L);

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

    @Test
    void productionEntryRejectsAnUnavailableSidebar() {
        RecordingNavigationHelper navigation =
                new RecordingNavigationHelper(SidebarRowLookup.sidebarUnavailable());
        IntelScreenHelper helper = new IntelScreenHelper(
                null, "review", null, navigation, PROFILE);

        assertThrows(HomeNotFoundException.class, helper::enterIntelFromDailyIfAvailable);
        assertTrue(navigation.locationChecked);
        assertFalse(navigation.closeCalled);
    }

    private static final class RecordingNavigationHelper extends NavigationHelper {
        private final SidebarRowLookup lookup;
        private boolean locationChecked;
        private boolean closeCalled;

        private RecordingNavigationHelper(SidebarRowLookup lookup) {
            super((EmulatorController) null, "review", PROFILE);
            this.lookup = lookup;
        }

        @Override
        public void ensureCorrectScreenLocation(LaunchPoint launchPoint) {
            locationChecked = true;
        }

        @Override
        public SidebarRowLookup findSidebarDestinationRowWithStatus(
                SidebarDestination destination) {
            return lookup;
        }

        @Override
        public boolean closeSidebar() {
            closeCalled = true;
            return true;
        }
    }
}
