package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BearWarListIdentityTest {

    @Test
    void verifiedWarRouteCanIdentifyAnEmptyListFromItsCloseControl() {
        assertTrue(BearWarListIdentity.isVisible(true, true, 0));
    }

    @Test
    void joinButtonsIdentifyListsWithOneOrManyRows() {
        assertTrue(BearWarListIdentity.isVisible(false, false, 1));
        assertTrue(BearWarListIdentity.isVisible(false, false, 3));
    }

    @Test
    void genericCloseControlWithoutVerifiedWarRouteIsNotEnough() {
        assertFalse(BearWarListIdentity.isVisible(false, true, 0));
    }
}
