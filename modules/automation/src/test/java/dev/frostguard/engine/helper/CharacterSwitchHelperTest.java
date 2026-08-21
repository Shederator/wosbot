package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CharacterSwitchHelperTest {

    @Test
    void treatsMissingLiveNameAndIdAsUnreadable() {
        assertEquals(
                CharacterSwitchHelper.CharacterVerificationResult.UNREADABLE,
                CharacterSwitchHelper.evaluateCharacterEvidence("Alpha", "12345", "", ""));
    }

    @Test
    void requiresBothSignalsWhenBothNameAndIdAreConfiguredAndObserved() {
        assertEquals(
                CharacterSwitchHelper.CharacterVerificationResult.MISMATCHED,
                CharacterSwitchHelper.evaluateCharacterEvidence("Alpha", "12345", "Alpha", "99999"));
    }

    @Test
    void acceptsConfiguredNameWhenOnlyNameIsAvailable() {
        assertEquals(
                CharacterSwitchHelper.CharacterVerificationResult.MATCHED,
                CharacterSwitchHelper.evaluateCharacterEvidence("Alpha One", null, "alphaone", null));
    }

    @Test
    void defersWhenOnlyOneOfTwoConfiguredSignalsIsReadable() {
        assertEquals(
                CharacterSwitchHelper.CharacterVerificationResult.UNREADABLE,
                CharacterSwitchHelper.evaluateCharacterEvidence("Alpha", "12345", "Alpha", null));
    }
}
