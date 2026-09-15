package dev.frostguard.engine.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.ImageSearchResultData;

class SidebarRowLookupTest {

    @Test
    void distinguishesAnUnavailableSidebarFromAnAbsentDestination() {
        SidebarRowLookup unavailable = SidebarRowLookup.sidebarUnavailable();
        SidebarRowLookup absent = SidebarRowLookup.fromRow(ImageSearchResultData.miss());

        assertEquals(SidebarRowLookup.Status.SIDEBAR_UNAVAILABLE, unavailable.status());
        assertEquals(SidebarRowLookup.Status.DESTINATION_ABSENT, absent.status());
        assertFalse(unavailable.isFound());
        assertFalse(absent.isFound());
    }

    @Test
    void preservesTheLocatedRowWhenTheDestinationWasFound() {
        ImageSearchResultData row = ImageSearchResultData.hit(46, 720, 97.0, 44, 44);

        SidebarRowLookup found = SidebarRowLookup.fromRow(row);

        assertEquals(SidebarRowLookup.Status.FOUND, found.status());
        assertEquals(row, found.rowIcon());
        assertTrue(found.isFound());
    }
}
