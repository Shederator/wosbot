package dev.frostguard.api.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Evidence level: automated tests. The rendered page has not been reviewed on screen.
 */
class ChatDiscordRendererTest {

    private static final Instant AT = Instant.parse("2026-08-21T22:15:00Z");

    private static ChatMessage msg(String author, String body) {
        return new ChatMessage(AT, "world", author, "", 0, body, "", List.of(),
                ChatMessage.Kind.TEXT, "");
    }

    private static String render(ChatMessage... messages) {
        return ChatDiscordRenderer.render(List.of(messages), ZoneOffset.UTC);
    }

    @Test
    void consecutiveMessagesFromOneAuthorAreGroupedUnderASingleHeader() {
        String html = render(msg("Nightjar", "first"), msg("Nightjar", "second"));

        assertEquals(1, countOf(html, "class=\"author\""),
                "a burst from one player should print the name once, as Discord does");
        assertTrue(html.contains("grouped"));
    }

    @Test
    void aDifferentAuthorStartsAFreshHeader() {
        String html = render(msg("Nightjar", "first"), msg("Marisol", "second"));

        assertEquals(2, countOf(html, "class=\"author\""));
    }

    @Test
    void anAuthorKeepsTheSameColourEverywhereSoPlayersStayRecognisable() {
        String first = render(msg("Nightjar", "one"));
        String later = render(msg("Marisol", "x"), msg("Nightjar", "two"));

        String colour = colourIn(first);
        assertTrue(later.contains(colour), "the same name should resolve to the same colour");
    }

    @Test
    void translatedMessageShowsEnglishWithTheOriginalKeptBeneathIt() {
        // A reader who speaks the language should not have to trust the machine rendering, and a
        // bad translation is obvious next to its source.
        ChatMessage m = new ChatMessage(AT, "world", "Sec", "THE", 9, "Teşekkürler", "Thank you",
                List.of(), ChatMessage.Kind.TEXT, "");

        String html = ChatDiscordRenderer.render(List.of(m), ZoneOffset.UTC);

        assertTrue(html.contains("Thank you"));
        assertTrue(html.contains("Teşekkürler"), "the original must remain visible");
        assertTrue(html.contains("class=\"orig\""));
    }

    @Test
    void mentionsAreMarkedUpSoTheyReadAsMentions() {
        ChatMessage m = new ChatMessage(AT, "world", "Nightjar", "", 0, "@Marisol bring lancers",
                "", List.of("Marisol"), ChatMessage.Kind.TEXT, "");

        assertTrue(ChatDiscordRenderer.render(List.of(m), ZoneOffset.UTC)
                .contains("class=\"mention\">@Marisol"));
    }

    @Test
    void systemCardsAreStyledAsEventsRatherThanSpeech() {
        ChatMessage m = new ChatMessage(AT, "world", "", "", 0, "Lucky Pouch", "", List.of(),
                ChatMessage.Kind.SYSTEM, "");

        assertTrue(ChatDiscordRenderer.render(List.of(m), ZoneOffset.UTC).contains("body system"));
    }

    @Test
    void markupInAMessageIsEscapedRatherThanRendered() {
        // Chat text is arbitrary player input arriving through OCR, so it is never trusted as HTML.
        String html = render(msg("Nightjar", "<script>alert(1)</script>"));

        assertFalse(html.contains("<script>alert"), "player text must not become live markup");
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    void emptyTranscriptExplainsItselfRatherThanRenderingBlank() {
        assertTrue(ChatDiscordRenderer.render(List.of(), ZoneOffset.UTC).contains("No chat captured yet"));
    }

    private static int countOf(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    private static String colourIn(String html) {
        int i = html.indexOf("class=\"author\" style=\"color:");
        return html.substring(i + 27, i + 34);
    }
}
