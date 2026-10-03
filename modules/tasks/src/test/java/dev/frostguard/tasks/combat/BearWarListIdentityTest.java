package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BearWarListIdentityTest {

    @Test
    void titleAndSelectedTabIdentifyAListEvenWithoutJoinButtons() {
        assertTrue(BearWarListIdentity.isVisible(true, true));
    }

    @Test
    void aWarDetailHeadingWithoutRallyTabIsNotTheList() {
        assertFalse(BearWarListIdentity.isVisible(true, false));
    }

    @Test
    void genericTabWithoutWarHeadingIsNotEnough() {
        assertFalse(BearWarListIdentity.isVisible(false, true));
        assertFalse(BearWarListIdentity.isVisible(false, false));
    }
}
