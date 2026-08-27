package dev.frostguard.api.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Cases are the shapes measured across 1,929 live chat messages, rewritten with invented names so
 * no real player, alliance or account appears in the repository.
 *
 * <p>Evidence level: automated tests, with every pattern taken from live capture data.
 */
class ChatLineCleanerTest {

    @Test
    void splitsTheFullestSenderFormAndDiscardsTheVipBadge() {
        ChatLineCleaner.Sender s = ChatLineCleaner.parseSender("VIP7 [THE]Nightjar");

        assertEquals("Nightjar", s.name());
        assertEquals("THE", s.allianceTag());
        assertTrue(s.trusted());
        // VIP rank is not kept. It is shown on some senders and not others, carries nothing worth
        // recording, and reading it is what let a mangled badge reach the name.
        assertEquals(0, s.vipLevel());
    }

    @Test
    void aMangledVipBadgeCannotReachTheName() {
        // The exact shapes seen in the live transcript, where a misread badge became the author.
        for (String line : new String[] {
            "ViIPO LINE [INF]Chanyu", "SS} VirP/ [BAE]Mejbreach", "& VIP6 [INF]CrisdeuS"}) {
            ChatLineCleaner.Sender s = ChatLineCleaner.parseSender(line);
            assertTrue(s.trusted(), "should trust the name in " + line);
            assertEquals(0, s.vipLevel());
            assertFalse(s.name().toUpperCase(java.util.Locale.ROOT).contains("VIP"),
                    "badge leaked into the name: " + s.name());
        }
    }

    @Test
    void theNameIsWhateverFollowsTheAllianceTag() {
        assertEquals("CrisdeuS", ChatLineCleaner.parseSender("& VIP6 [INF]CrisdeuS").name());
        assertEquals("Chanyu", ChatLineCleaner.parseSender("ViIPO LINE [INF]Chanyu").name());
    }

    @Test
    void theClosingBracketIsAcceptedInEveryShapeTheReaderProduces() {
        // Measured across 232 rows from 50 live alliance frames: the closing bracket comes back as
        // a literal ] in only a handful and as j, J or | in most of the rest. Insisting on ]
        // recognised 2 sender lines out of 213; accepting the real shapes recognises 181.
        for (String line : new String[] {
            "VIP6 [INF]AthenaRyu", "VIPG [INF jAthenaRyu", "VIP6 [INF|Nicko .",
            "VIPG [INFJAthenaRyu", "VIP6 (INF]Nicko"}) {
            ChatLineCleaner.Sender s = ChatLineCleaner.parseSender(line);
            assertEquals("INF", s.allianceTag(), "tag not read from: " + line);
            assertTrue(s.trusted(), "should trust: " + line);
        }
    }

    @Test
    void trailingDecorationIsTrimmedOffTheName() {
        // "Nicko ." and "AthenaRyu 7" are the same two players with a speck of the avatar frame
        // caught on the end of the line.
        assertEquals("Nicko", ChatLineCleaner.parseSender("VIP6 [INF|Nicko .").name());
        assertEquals("AthenaRyu", ChatLineCleaner.parseSender("VIP6 [INF]AthenaRyu 7").name());
        assertEquals("AthenaRyu", ChatLineCleaner.parseSender("VIP6 [INF]AthenaRyu -").name());
    }

    @Test
    void readsATaggedSenderWhenTheReaderMistakesTheBracketForAParenthesis() {
        // Live captures carry both forms for the same tag; the leading bracket is thin enough that
        // the reader flips it, and a bracket-only pattern silently drops those senders.
        ChatLineCleaner.Sender s = ChatLineCleaner.parseSender("(INF]Marisol");

        assertEquals("Marisol", s.name());
        assertEquals("INF", s.allianceTag());
        assertTrue(s.trusted());
    }

    @Test
    void frameFurnitureCaughtByTheNameStripIsNotTrustedAsAPlayer() {
        // These are the observed garbage shapes: digit-led runs and punctuation fragments picked
        // up from borders and timers. Rendering them would invent participants.
        for (String junk : new String[] {"34 ERE", "- in", "11", "ae", "..."}) {
            assertFalse(ChatLineCleaner.parseSender(junk).trusted(),
                    "should not trust " + junk + " as a sender");
        }
    }

    @Test
    void aNameFullOfInventedPunctuationIsNotTrusted() {
        // Seen rendering as real participants in the live transcript: the reader turning a VIP badge
        // and an alliance tag into braces and slashes.
        for (String junk : new String[] {"S} ViP/ TRejbreach", "fe MPA) /UIBGeULClICr", "SS} VirP/ TMejbreach"}) {
            assertFalse(ChatLineCleaner.parseSender(junk).trusted(), "should not trust " + junk);
        }
    }

    @Test
    void aSenderLineWithTheMessageRunIntoItIsNotTrusted() {
        // Seen live: the reader joins a sender line to the words that follow it, and the whole
        // sentence was stored as the author. No player name is a sentence long.
        assertFalse(ChatLineCleaner.parseSender(
                "[INF]Mojorisinfans se Tengo a Natalia y no la he podido").trusted());
    }

    @Test
    void ordinaryNamesSurviveTheTighterCheck() {
        for (String ok : new String[] {"TheFlyingDutch", "Kim Jong Um", "Mrs_Lasanha", "Snoopy!R"}) {
            assertTrue(ChatLineCleaner.parseSender(ok).trusted(), "should trust " + ok);
        }
    }

    @Test
    void stripsTheReadersInventedCharactersFromTheBody() {
        // | and = are the two most common characters in the whole corpus and no player types them
        // here -- they are bubble borders and the per-message translate control.
        assertEquals("Thanks for the rally",
                ChatLineCleaner.cleanBody("| = Thanks for the rally ~ ®"));
    }

    @Test
    void aPipeBetweenWordsIsTheWordItRatherThanABorder() {
        // Live: "Ooooops. I missed it" was stored as "Oooops. missed it" because the I read as a
        // pipe and the pipe was stripped as a bubble border.
        assertEquals("Oooops. I missed it", ChatLineCleaner.cleanBody("Oooops. | missed it"));
    }

    @Test
    void aMentionSurvivesTheReaderSpellingItsAtSignAsCopyright() {
        // Live: "@Maki felicidades!" arrived as "© Maki Felicidades!" and lost the mention.
        assertEquals("@Maki Felicidades!", ChatLineCleaner.cleanBody("©Maki Felicidades!"));
        // With a space between them it is an emoji opening a sentence, not a mention, and must
        // not become an "@" -- "© Oooops. I missed it" was being stored as "@Oooops. I missed it".
        assertFalse(ChatLineCleaner.cleanBody("© Oooops. I missed it").startsWith("@"));
    }

    @Test
    void collectsMentionsIncludingTheSpacedTaggedFormTheReaderProduces() {
        List<String> mentions = ChatLineCleaner.mentions("@Nightjar and @ [INF]Marisol please rally");

        assertEquals(List.of("Nightjar", "Marisol"), mentions);
    }

    @Test
    void repeatedMentionOfTheSamePlayerIsListedOnce() {
        assertEquals(List.of("Nightjar"), ChatLineCleaner.mentions("@Nightjar hey @Nightjar"));
    }

    @Test
    void classifiesGameCardsAsSystemRatherThanPlayerSpeech() {
        assertEquals(ChatMessage.Kind.SYSTEM, ChatLineCleaner.classify("Lucky Pouch"));
        assertEquals(ChatMessage.Kind.SYSTEM, ChatLineCleaner.classify("Share Coordinates"));
    }

    @Test
    void bodyWithTooFewLettersIsUnreadableRatherThanText() {
        // A bubble that reduced to "&" or "." carries nothing to read or translate; calling it
        // text puts noise in the transcript and spends a lookup on it.
        assertEquals(ChatMessage.Kind.UNREADABLE, ChatLineCleaner.classify("&"));
        assertEquals(ChatMessage.Kind.UNREADABLE, ChatLineCleaner.classify("."));
    }

    @Test
    void englishBodyIsRecognisedLocallySoItNeverLeavesTheMachine() {
        assertTrue(ChatLineCleaner.looksEnglish("good morning everyone"));
        assertTrue(ChatLineCleaner.looksEnglish("Thank you"));
    }

    @Test
    void nonLatinScriptIsDecisiveOnItsOwn() {
        assertFalse(ChatLineCleaner.looksEnglish("윈드님 축하해요 좋다"));
        assertFalse(ChatLineCleaner.looksEnglish("二十四小时"));
        assertFalse(ChatLineCleaner.looksEnglish("مرحبا بالجميع"));
    }

    @Test
    void accentedLatinIsForeignEvenWhenTheMessageIsTooShortToScoreWords() {
        // Turkish appears in live world chat. It is Latin script, so the script test alone passes
        // it, and at two words it is below the threshold the word test needs -- the diacritics are
        // what catch it.
        assertFalse(ChatLineCleaner.looksEnglish("Teşekkürler işçi"));
    }

    @Test
    void unaccentedForeignTextIsCaughtByItsLackOfEnglishFunctionWords() {
        // The real miss: Turkish and Spanish written in plain ASCII scored as confidently English
        // under the old ratio test, so every foreign message on a live pass went untranslated.
        assertFalse(ChatLineCleaner.looksEnglish("bana nazik bir sekilde maymun dedi"));
        assertFalse(ChatLineCleaner.looksEnglish("Y de onde eres vos amigo"));
    }

    @Test
    void ordinaryEnglishStillReadsAsEnglishAndIsNeverSent() {
        assertTrue(ChatLineCleaner.looksEnglish("who uses outlook?"));
        assertTrue(ChatLineCleaner.looksEnglish("i pray for you"));
        assertTrue(ChatLineCleaner.looksEnglish("A quick google search told me Latin America"));
    }

    @Test
    void quotedReplyIsSeparatedFromTheSendersOwnWords() {
        // A reply renders the original inside the same bubble. Measured on a live pass, 84 of 295
        // messages carried someone else's sentence appended to their own.
        ChatLineCleaner.Body b = ChatLineCleaner.splitQuotedReply(
                String.join("\n", "@Rhaegar it still does",
                        "Rhaegar: JK i know outlook doesnt say that"));

        assertEquals("@Rhaegar it still does", b.own());
        assertTrue(b.quoted().startsWith("Rhaegar:"));
    }

    @Test
    void aNameMentionedMidSentenceDoesNotSplitTheMessage() {
        // Taking the FIRST name-colon match splits on a mention rather than the quote, which is why
        // the boundary is the last one.
        ChatLineCleaner.Body b = ChatLineCleaner.splitQuotedReply("TheFlyingDutch same");

        assertEquals("TheFlyingDutch same", b.own());
        assertTrue(b.quoted().isEmpty());
    }

    @Test
    void theAlliancePollCardIsNotMistakenForConversation() {
        // Pinned above the feed with a clipboard icon the segmenter reads as an avatar, so it
        // arrives as a message on every pass. These are its rows exactly as the reader returns them.
        for (String card : new String[] {
            "selection] Which Bear Trap do you...",
            "_ Participants: 49/98 (Have Not Participate",
            "Initiator: AthenaRyu",
            "Vote in: 11:29:34",
            "Vote"}) {
            assertEquals(ChatMessage.Kind.SYSTEM, ChatLineCleaner.classify(card), "should be furniture: " + card);
        }
    }

    @Test
    void talkingAboutTheVoteIsStillConversation() {
        assertEquals(ChatMessage.Kind.TEXT, ChatLineCleaner.classify("did everyone vote yet"));
    }

    @Test
    void gameChatterIsRecognisedSoItCanBeHidden() {
        assertTrue(ChatLineCleaner.isNonSpeech("[BAE]Qwert recalled a message"));
        assertTrue(ChatLineCleaner.isNonSpeech("Share layout"));
        assertFalse(ChatLineCleaner.isNonSpeech("who uses outlook?"));
    }

    @Test
    void cacheKeyIgnoresCasingAndSpacingSoARepeatedPhraseIsFetchedOnce() {
        assertEquals(ChatLineCleaner.cacheKey("Join   the RALLY"),
                ChatLineCleaner.cacheKey("join the rally"));
    }
    // ---- near-duplicate merging -------------------------------------------------------------
    // Every string here is a real pair from five consecutive live alliance screens, which stored
    // 20 entries for 13 actual messages before this existed.

    @Test
    void treatsAClippedCopyOfALongMessageAsTheSameMessage() {
        String clipped = ChatLineCleaner.mergeKey(
                "GA Alright Legion 2. We have less I than an hour. Make sure you recall all waiur "
                + "craane hama and etan stan");
        String whole = ChatLineCleaner.mergeKey(
                "(DAI Alright Legion 2. We have less than an hour. Make sure you recall all your "
                + "troops home and stop auto join before the battle begins. Let's prioritize "
                + "securing the prototype sites and repair facilities in the beginning.");
        assertTrue(ChatLineCleaner.sameMessage(clipped, whole));
    }

    @Test
    void treatsALineWithBubbleNoiseInItAsTheSameMessage() {
        assertTrue(ChatLineCleaner.sameMessage(
                ChatLineCleaner.mergeKey("En 1:45 hora batalla de la fundicion de la legion 2"),
                ChatLineCleaner.mergeKey("En 1:45 hora batalla de la fundicion q a de la legion 2")));
    }

    @Test
    void mergesAShortBodyThatPickedUpAStrayCharacter() {
        assertTrue(ChatLineCleaner.sameMessage(
                ChatLineCleaner.mergeKey("y congrats"), ChatLineCleaner.mergeKey("y congrats e")));
    }

    @Test
    void keepsGenuinelyDifferentMessagesApart() {
        assertFalse(ChatLineCleaner.sameMessage(
                ChatLineCleaner.mergeKey("En 1:45 hora batalla de la fundicion de la legion 2"),
                ChatLineCleaner.mergeKey("@Blazed562 creo que estabas en la legion 1")));
    }

    @Test
    void doesNotSwallowAShortMessageThatAppearsInsideALongerOne() {
        // "congrats" occurs inside "Congrats!" at the end of the longer message, which shares
        // almost all of the shorter one's runs. Only the prefix rule keeps them apart.
        assertFalse(ChatLineCleaner.sameMessage(
                ChatLineCleaner.mergeKey("y congrats e"),
                ChatLineCleaner.mergeKey("Oooops. I missed it by Congrats!")));
    }

    // ---- mention repair ---------------------------------------------------------------------

    @Test
    void putsBackAnAtSignTheReaderTurnedIntoLetters() {
        assertEquals("@Maki felicidades!", ChatLineCleaner.repairLeadingMention(
                "yy Maki felicidades!", List.of("Maki", "CrisdeuS", "AthenaRyu")));
    }

    @Test
    void leavesAMessageAloneWhenTheNameOpensIt() {
        // Nothing precedes the name, so there is no mangled "@" to explain and this is somebody
        // being talked about rather than addressed.
        assertEquals("Maki is afk", ChatLineCleaner.repairLeadingMention(
                "Maki is afk", List.of("Maki")));
    }

    @Test
    void leavesANameInTheMiddleOfASentenceAlone() {
        assertEquals("I think Maki already did", ChatLineCleaner.repairLeadingMention(
                "I think Maki already did", List.of("Maki")));
    }

    @Test
    void ignoresNamesThatAreNotInTheAlliance() {
        assertEquals("yy Maki felicidades!", ChatLineCleaner.repairLeadingMention(
                "yy Maki felicidades!", List.of("AthenaRyu")));
    }

    @Test
    void doesNotTouchAMentionTheReaderGotRight() {
        assertEquals("@Blazed562 creo que estabas", ChatLineCleaner.repairLeadingMention(
                "@Blazed562 creo que estabas", List.of("Blazed562")));
    }

    // ---- names, translation and sender remnants ---------------------------------------------

    @Test
    void keepsTheDigitsThatArePartOfAName() {
        assertEquals("Blazed562", ChatLineCleaner.parseSender("VIP8 [INF]Blazed562").name());
        assertEquals("una116", ChatLineCleaner.parseSender("[INF]una116").name());
    }

    @Test
    void stillDropsARankBadgeReadAsASeparateNumber() {
        assertEquals("CrisdeuS", ChatLineCleaner.parseSender("VIP6 [INF]CrisdeuS 7").name());
    }

    @Test
    void doesNotCallShortForeignTextEnglish() {
        assertFalse(ChatLineCleaner.looksEnglish("Hay evento horita ?"));
        assertFalse(ChatLineCleaner.looksEnglish("Felicidades!"));
        assertFalse(ChatLineCleaner.looksEnglish("Ola"));
    }

    @Test
    void stillCallsShortEnglishEnglish() {
        assertTrue(ChatLineCleaner.looksEnglish("congrats"));
        assertTrue(ChatLineCleaner.looksEnglish("Oooops. I missed it Congrats!"));
        assertTrue(ChatLineCleaner.looksEnglish("ok ty"));
    }

    @Test
    void treatsAGameCardAsSystemRatherThanSomethingSomebodySaid() {
        assertEquals(ChatMessage.Kind.SYSTEM,
                ChatLineCleaner.classify("Furnace Upgrade Pack My Furnace has reached Lv. 26!"));
        assertEquals(ChatMessage.Kind.SYSTEM,
                ChatLineCleaner.classify("Alliance Label A(n) Triangle label set at [X:457 Y:672]"));
    }

    @Test
    void recognisesASenderLineWhoseBadgeWasMisread() {
        // "VIP5" read as "VIPS", which the badge parser does not see, so the row was stored as a
        // message reading "FF VIPS".
        assertTrue(ChatLineCleaner.looksLikeSenderLine("FF VIPS"));
    }

    @Test
    void doesNotMistakeARealSentenceForASenderLine() {
        assertFalse(ChatLineCleaner.looksLikeSenderLine(
                "En 1:45 hora batalla de la fundicion de la legion 2"));
    }

    @Test
    void takesTheBubbleTailOffTheEndOfASentence() {
        assertEquals("En la 2 no te veo", ChatLineCleaner.cleanBody("En la 2 no te veo e,"));
    }

    // ---- quoted replies with a mangled quoted name ------------------------------------------
    // All of these were stored with the quote glued on, because the quoted name did not survive
    // the reader cleanly enough to be recognised as a name.

    @Test
    void splitsAQuoteWhoseAuthorNameWasMangled() {
        ChatLineCleaner.Body b = ChatLineCleaner.splitQuotedReply(
                "@AthenaRyu Yes ! А{пепаВуи: @Mini TyTy you better not go");
        assertEquals("@AthenaRyu Yes !", b.own());
        assertFalse(b.quoted().isBlank());
    }

    @Test
    void splitsAQuoteWhoseAuthorNameCarriesReaderNoise() {
        ChatLineCleaner.Body b = ChatLineCleaner.splitQuotedReply(
                "@CrisdeuS Ugg. Only 2 days worth of speedups Сг!$4еи$: @AthenaRyu jasjsaj");
        assertEquals("@CrisdeuS Ugg. Only 2 days worth of speedups", b.own());
        assertFalse(b.quoted().isBlank());
    }

    @Test
    void doesNotSplitAnOrdinarySentenceContainingAColon() {
        ChatLineCleaner.Body b = ChatLineCleaner.splitQuotedReply(
                "En 1:45 hora batalla de la fundicion de la legion 2");
        assertTrue(b.quoted().isBlank());
    }

}
