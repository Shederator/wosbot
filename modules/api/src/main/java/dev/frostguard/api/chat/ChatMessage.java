package dev.frostguard.api.chat;

import java.time.Instant;
import java.util.List;

/**
 * One parsed chat message, in the shape a Discord-style renderer needs: an identified author and a
 * body, rather than a run of OCR text with several people's lines in it.
 *
 * @param capturedAt when the frame this came from was taken
 * @param channel    world, alliance or personal
 * @param author     the sender's name with the game's decorations stripped off
 * @param allianceTag the {@code [ABC]} tag when the sender carried one, otherwise empty
 * @param vipLevel   the sender's VIP number when shown, otherwise 0
 * @param body       the message text, artifacts removed, in its original language
 * @param translated the English rendering when the body was not already English, otherwise empty
 * @param mentions   names this message addressed with {@code @}
 * @param kind       what the bubble actually contained
 * @param quoted     the message being replied to, when the bubble carried one, otherwise empty
 */
public record ChatMessage(
        Instant capturedAt,
        String channel,
        String author,
        String allianceTag,
        int vipLevel,
        String body,
        String translated,
        List<String> mentions,
        Kind kind,
        String quoted) {

    /** Not every bubble is text; rendering has to say so rather than showing OCR noise. */
    public enum Kind {
        /** Ordinary text a player typed. */
        TEXT,
        /** Emoji or a reaction only -- nothing for OCR to read and nothing to translate. */
        EMOJI,
        /** An image or sticker bubble. */
        STICKER,
        /** A game-generated card: gift pouches, help requests, shared coordinates. */
        SYSTEM,
        /** Segmented as a message but nothing legible survived cleaning. */
        UNREADABLE
    }

    /** The same message with its body rewritten, keeping the mentions that body now names. */
    public ChatMessage withBody(String rewritten) {
        return new ChatMessage(capturedAt, channel, author, allianceTag, vipLevel, rewritten,
                translated, ChatLineCleaner.mentions(rewritten), kind, quoted);
    }

    /** The same message with its English rendering replaced. */
    public ChatMessage withTranslated(String english) {
        return new ChatMessage(capturedAt, channel, author, allianceTag, vipLevel, body,
                english, mentions, kind, quoted);
    }

    /**
     * The same message with the quoted original replaced by its English rendering.
     *
     * <p>The quote is replaced rather than carried alongside its source, unlike the body. Nobody
     * reading this speaks every language in the alliance, and a strip of untranslated text set
     * above a translated reply is the one part of the exchange the reader cannot follow.
     */
    public ChatMessage withQuoted(String rewritten) {
        return new ChatMessage(capturedAt, channel, author, allianceTag, vipLevel, body,
                translated, mentions, kind, rewritten);
    }

    /** The text a reader should see: the English rendering when there is one, else the original. */
    public String displayBody() {
        return translated == null || translated.isBlank() ? body : translated;
    }

    /** A reply carries the quoted original; the reader shows it above the new text. */
    public boolean hasQuote() {
        return quoted != null && !quoted.isBlank();
    }

    /** True when this carries a usable body worth storing or rendering. */
    public boolean isRenderable() {
        return kind != Kind.UNREADABLE && !(body == null || body.isBlank());
    }
}
