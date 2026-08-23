package dev.frostguard.tasks.social;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * What the reader is allowed to call a message in another script.
 *
 * <p>Every string here came off a live pass. The accepted ones are messages players actually sent;
 * the rejected ones are what the reader returned when it was handed a bubble ornament or a piece of
 * a Latin sentence and asked to read it as Chinese.
 */
class ChatScriptRecoveryTest {

    @Test
    void acceptsAMessageWrittenInAnotherScript() {
        assertTrue(ChatScriptRecovery.isMostlyAnotherScript("내아내사랑해가안잊혀져"));
        assertTrue(ChatScriptRecovery.isMostlyAnotherScript("恭喜"));
    }

    @Test
    void rejectsGlyphsScatteredThroughLatinText() {
        // Stored and translated as written before this test existed.
        assertFalse(ChatScriptRecovery.isMostlyAnotherScript("sr 名 sr 名 大 missed you."));
        assertFalse(ChatScriptRecovery.isMostlyAnotherScript("Buen trabajo chicos ウラ I NFIXXX!"));
        assertFalse(ChatScriptRecovery.isMostlyAnotherScript("We are grateful to have you 付"));
    }

    @Test
    void rejectsASingleStrayGlyph() {
        assertFalse(ChatScriptRecovery.isMostlyAnotherScript("付"));
        assertFalse(ChatScriptRecovery.isMostlyAnotherScript("baz (た"));
    }

    @Test
    void stillSeesAWordThatNeedsNoSecondReading() {
        assertTrue(ChatScriptRecovery.hasReadableWord("congrats"));
        assertFalse(ChatScriptRecovery.hasReadableWord("g2 - E o"));
    }
    // ---- Cyrillic read as Latin ---------------------------------------------------------------
    // Every mangled string here is what the reader actually stored for a Russian message. None of
    // them reached the other-scripts pass, because they are full of things that look like words.

    @Test
    void suspectsCyrillicThatWasReadAsLatin() {
        assertTrue(ChatScriptRecovery.looksLikeMangledScript(
                "MH@ CPOUHO HyxeH gusaunep!!! q BOOOLIS He XOUY 3aHMMaTbCA OCTPOBOM"));
        assertTrue(ChatScriptRecovery.looksLikeMangledScript(
                "cneaytoLynh pas nepea perucTpaLWe Hago cHuMaTb BCE"));
    }

    @Test
    void leavesOrdinaryLatinAlone() {
        assertFalse(ChatScriptRecovery.looksLikeMangledScript(
                "En 1:45 hora batalla de la fundicion de la legion 2"));
        assertFalse(ChatScriptRecovery.looksLikeMangledScript(
                "Alright Legion 2. We have less than an hour."));
        assertFalse(ChatScriptRecovery.looksLikeMangledScript("Pq nossos participantes diminuiu"));
    }

    @Test
    void isNotFooledByShoutingOrByNames() {
        // All-caps is a person shouting, not a reader guessing at another alphabet.
        assertFalse(ChatScriptRecovery.looksLikeMangledScript("GG EVERYONE WELL PLAYED TONIGHT"));
        // One camel-cased player name is not evidence of anything.
        assertFalse(ChatScriptRecovery.looksLikeMangledScript("@AthenaRyu thanks for the rally"));
    }

    @Test
    void doesNotTakeAPlayerNameAsEvidenceOfAnotherAlphabet() {
        // All three were sent to the Cyrillic reader on a live pass and came back as Cyrillic
        // nonsense, because a camel-cased name has a capital in the middle just like mangled
        // Cyrillic does. The Spanish, English and Czech here are what the messages actually were.
        assertFalse(ChatScriptRecovery.looksLikeMangledScript(
                "@AthenaRyu dios mio, sumando aceleracion general? AthenaRyu: @CrisdeuS"));
        assertFalse(ChatScriptRecovery.looksLikeMangledScript(
                "@Candy Thursday Candy: @Mini TyTy"));
        assertFalse(ChatScriptRecovery.looksLikeMangledScript(
                "pro nej je tady vzdycky misto AthenaRyu: @Candy"));
    }

    @Test
    void stillSuspectsRealCyrillicBesideAMention() {
        assertTrue(ChatScriptRecovery.looksLikeMangledScript(
                "@CrisdeuS a Tome. 3TO Hao HyxeH gusaunep BCE nepecTaBnaTb"));
    }

    @Test
    void doesNotTakeATwoWordPlayerNameAsEvidence() {
        // All from one live pass. "Mini TyTy" is one player; only half of it carries the marker.
        assertFalse(ChatScriptRecovery.looksLikeMangledScript(
                "@Mini TyTy uz mi chybi jeho vtipkovani Mini TyTy: he's got a lot"));
        assertFalse(ChatScriptRecovery.looksLikeMangledScript(
                "@Mini moc mu chybi Szymon Mini TyTy: Though it's"));
        assertFalse(ChatScriptRecovery.looksLikeMangledScript(
                "@All Duz @Mini TyTy myslela v BAE Mini TyTy: same"));
    }

}
