package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.nav.SidebarRowLookup;
import dev.frostguard.engine.schedule.LaunchPoint;

class NavigationHelperIntelPolicyTest {

    private static final AccountDescriptor PROFILE =
            new AccountDescriptor(999L, "Synthetic Intel", "review", true, 0L, 0L);

    @Test
    void lighthouseRequiresRowAndVerifiedWildernessOpeningWithoutTappingDailyGo() {
        RecordingNavigation navigation = new RecordingNavigation();

        assertTrue(navigation.navigateToSidebarDestination(SidebarDestination.LIGHTHOUSE_INTEL));
        assertEquals(1, navigation.rowLookups);
        assertEquals(1, navigation.sidebarCloses);
        assertEquals(1, navigation.intelOpens);
        assertEquals(0, navigation.directActions);
    }

    @Test
    void closingDailyCannotMasqueradeAsIntelScreenConfirmation() {
        RecordingNavigation navigation = new RecordingNavigation();
        navigation.intelVerified = false;

        assertFalse(navigation.navigateToSidebarDestination(SidebarDestination.LIGHTHOUSE_INTEL));
        assertEquals(1, navigation.sidebarCloses);
        assertEquals(1, navigation.intelOpens);
    }

    @Test
    void absentRowOrUnavailableSidebarNeverOpensIntel() {
        for (SidebarRowLookup lookup : new SidebarRowLookup[] {
                SidebarRowLookup.fromRow(ImageSearchResultData.miss()),
                SidebarRowLookup.sidebarUnavailable() }) {
            RecordingNavigation navigation = new RecordingNavigation();
            navigation.lookup = lookup;

            assertFalse(navigation.navigateToSidebarDestination(SidebarDestination.LIGHTHOUSE_INTEL));
            assertEquals(0, navigation.intelOpens);
            assertEquals(0, navigation.directActions);
        }
    }

    @Test
    void unsuccessfulSidebarClosePreventsWildernessOpening() {
        RecordingNavigation navigation = new RecordingNavigation();
        navigation.sidebarClosed = false;

        assertFalse(navigation.navigateToSidebarDestination(SidebarDestination.LIGHTHOUSE_INTEL));
        assertEquals(0, navigation.intelOpens);
    }

    @Test
    void ordinaryDestinationsRetainDirectRowActionPolicy() {
        for (SidebarDestination destination : SidebarDestination.values()) {
            if (destination == SidebarDestination.LIGHTHOUSE_INTEL) {
                continue;
            }
            RecordingNavigation navigation = new RecordingNavigation();

            assertTrue(navigation.navigateToSidebarDestination(destination));
            assertEquals(1, navigation.directActions, destination.name());
            assertEquals(0, navigation.rowLookups, destination.name());
            assertEquals(0, navigation.intelOpens, destination.name());
        }
    }

    @Test
    void missingShortcutExhaustsThreePassesWithoutTapping() {
        RecordingNavigation navigation = new RecordingNavigation();
        navigation.shortcutFound = false;

        assertFalse(navigation.openIntelFromWilderness());
        assertEquals(3, navigation.shortcutSearches);
        assertEquals(0, navigation.shortcutTaps);
        assertEquals(0, navigation.screenChecks);
    }

    @Test
    void visibleShortcutWithoutIntelScreenExhaustsThreePasses() {
        RecordingNavigation navigation = new RecordingNavigation();
        navigation.intelVerified = false;

        assertFalse(navigation.openIntelFromWilderness());
        assertEquals(3, navigation.shortcutSearches);
        assertEquals(3, navigation.shortcutTaps);
        assertEquals(3, navigation.screenChecks);
    }

    @Test
    void successfulWildernessOpeningTapsDetectedShortcutOnce() {
        RecordingNavigation navigation = new RecordingNavigation();

        assertTrue(navigation.openIntelFromWilderness());
        assertEquals(1, navigation.shortcutSearches);
        assertEquals(1, navigation.shortcutTaps);
        assertEquals(1, navigation.screenChecks);
    }

    private static final class RecordingNavigation extends NavigationHelper {
        private SidebarRowLookup lookup = SidebarRowLookup.fromRow(
                ImageSearchResultData.hit(46, 832, 99, 44, 44));
        private boolean sidebarClosed = true;
        private boolean intelVerified = true;
        private boolean shortcutFound = true;
        private int rowLookups;
        private int sidebarCloses;
        private int intelOpens;
        private int directActions;
        private int shortcutSearches;
        private int shortcutTaps;
        private int screenChecks;

        private RecordingNavigation() {
            super(null, "review", PROFILE);
        }

        @Override
        public void ensureCorrectScreenLocation(LaunchPoint target) {
        }

        @Override
        public SidebarRowLookup findSidebarDestinationRowWithStatus(SidebarDestination destination) {
            rowLookups++;
            return lookup;
        }

        @Override
        public boolean closeSidebar() {
            sidebarCloses++;
            return sidebarClosed;
        }

        @Override
        public boolean openIntelFromWilderness() {
            if (rowLookups == 0) {
                return super.openIntelFromWilderness();
            }
            intelOpens++;
            return intelVerified;
        }

        @Override
        ImageSearchResultData locateIntelShortcut() {
            shortcutSearches++;
            return shortcutFound ? ImageSearchResultData.hit(663, 862, 99, 44, 44)
                    : ImageSearchResultData.miss();
        }

        @Override
        void tapIntelShortcut(ImageSearchResultData button) {
            shortcutTaps++;
            assertEquals(663, button.getX());
        }

        @Override
        boolean isIntelScreenActive() {
            screenChecks++;
            return intelVerified;
        }

        @Override
        boolean waitForIntelTransition(long milliseconds) {
            return true;
        }

        @Override
        boolean navigateToDirectSidebarDestination(SidebarDestination destination) {
            directActions++;
            return true;
        }
    }
}
