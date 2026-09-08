package dev.frostguard.tasks.social;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import dev.frostguard.api.chat.ChatGarble;
import dev.frostguard.api.chat.ChatLineCleaner;
import dev.frostguard.api.chat.ChatMessage;
import dev.frostguard.vision.ocr.TextLine;

/**
 * Builds messages from a whole frame that has already been recognised, using where each line sat.
 *
 * <p>This replaces reading the feed a region at a time. That approach had to decide the boundary
 * of every sender line and every bubble before recognising them, and each of those boundaries was
 * an offset from an avatar whose detected top moved with crowns and rank badges. Nine pixels of
 * drift clipped the tops of glyphs, and the reader returned "UNF jMini TyTy" for a line that plainly
 * reads {@code [INF]Mini TyTy}. Worse, when a band landed slightly high the sender line fell into
 * the bubble instead, and the transcript carried "VIP6 [INF]CrisdeuS a Acabo de enviar a farmear"
 * as somebody's message with no author at all.
 *
 * <p>Recognised whole, the same frame reports every line in reading order, and a sender line is
 * told from a message by carrying an alliance tag. The column looked like the obvious signal and is
 * not one: measured across live frames, sender lines start anywhere between x=139 and x=198 while
 * the bubbles under them start between x=168 and x=172, because the width of the tag moves the
 * name. It also costs one recognition per screen rather than one per region.
 */
final class ChatFrameReader {

    /**
     * The message feed, clear of the chrome above it and the input box below.
     *
     * <p>Measured on live alliance frames: the Chat / Alliance Notice sub-tabs and the notice
     * banner occupy down to about y=245, and the first real message begins at y=259. Starting at
     * 175 pulled the banner in, and because it is a line like any other it was absorbed into
     * whatever message followed -- "Milan Notice RO Awww. They had such lofty goals".
     */
    private static final int FEED_TOP = 250;
    private static final int FEED_BOTTOM = 1160;

    /**
     * How close to the crop edge a row may sit before it is assumed to have been cut by it.
     *
     * <p>Small on purpose. This is not a margin of safety around the feed -- it is the width of the
     * evidence that a row was sliced, and the two rows it was measured against ended at 1159 and
     * 1160 against a crop at 1160.
     */
    private static final int CLIPPED_ROW_MARGIN = 3;

    /** Past this gap the next line belongs to a different message, not the one above. */
    private static final int MESSAGE_GAP = 46;

    private ChatFrameReader() {
    }

    /**
     * @param lines     every line recognised on the frame, with positions
     * @param channel   world, alliance or personal
     * @param at        the capture instant to stamp this frame's messages with
     * @param translate resolves a foreign body to English; empty when already English
     */
    static List<ChatMessage> read(List<TextLine> lines, String channel, Instant at,
                                  Function<String, Optional<String>> translate,
                                  java.util.function.Predicate<TextLine> isQuoteRow) {
        List<TextLine> feed = new ArrayList<>();
        for (TextLine l : lines) {
            if (l.top() < FEED_TOP || l.bottom() > FEED_BOTTOM || l.text().isBlank()) {
                continue;
            }
            // A row lying flush against the crop edge was cut by it, and a cut row still reads --
            // that is what makes it dangerous. It does not fail, it returns something plausible
            // from the half of the glyphs that survived. Both instances on the frames this was
            // built from are damage the transcript kept: the bottom of one screen turned "your
            // troops home and stop auto" into "waiur craane hama and etan stan" and stored it as a
            // second, truncated copy of the alliance's longest message, and the sliver of the next
            // bubble below it became "ZA fraiel ....". Nothing is lost by dropping them: the scroll
            // overlaps by design, so a row cut here is read whole on the screen before or after.
            if (l.bottom() >= FEED_BOTTOM - CLIPPED_ROW_MARGIN
                    || l.top() <= FEED_TOP + CLIPPED_ROW_MARGIN) {
                continue;
            }
            // The game prints a bare time beside each group. It is not something anybody said, and
            // left in it becomes a message of its own. Recognised by being nothing but a time,
            // rather than by where it sits: a line's reported left edge includes whatever art sat
            // beside the text, so it is not a reliable thing to test.
            if (TIME_ONLY.matcher(l.text().trim()).matches()) {
                continue;
            }
            feed.add(l);
        }
        feed.sort((a, b) -> Integer.compare(a.top(), b.top()));

        List<ChatMessage> out = new ArrayList<>();
        String author = "";
        String tag = "";
        List<TextLine> pending = new ArrayList<>();
        List<TextLine> quoted = new ArrayList<>();
        TextLine previous = null;

        for (TextLine line : feed) {
            // Sender lines are settled first. A name is dimmer than message text and not far off a
            // quote, and losing the author of a message is worse than missing the message it was
            // answering, so the brightness question is only asked about rows that are not names.
            ChatLineCleaner.Sender sender = senderOn(line);
            // The dimmer strip under a bubble is the message being replied to, not a new one. It
            // belongs to the bubble above it, so it is held and handed to that message when it is
            // flushed rather than being read as text somebody wrote.
            if (sender == null && isQuoteRow.test(line)) {
                quoted.add(line);
                previous = line;
                continue;
            }
            if (sender != null) {
                // A sender line closes whatever was being collected and names what follows.
                flush(out, pending, quoted, author, tag, channel, at, translate);
                pending.clear();
                quoted.clear();
                author = sender.name();
                tag = sender.allianceTag();
                previous = line;
                continue;
            }
            // A long drop means the bubble above has ended, even without a new sender line: the
            // game prints the name once and omits it on the messages that follow from one person.
            if (previous != null && line.top() - previous.bottom() > MESSAGE_GAP) {
                flush(out, pending, quoted, author, tag, channel, at, translate);
                pending.clear();
                quoted.clear();
            }
            pending.add(line);
            previous = line;
        }
        flush(out, pending, quoted, author, tag, channel, at, translate);
        return out;
    }

    /** A timestamp the game prints beside a group, e.g. {@code 22:09}. */
    private static final java.util.regex.Pattern TIME_ONLY =
            java.util.regex.Pattern.compile("\\d{1,2}[:.]\\d{2}(\\s*[AaPp][Mm])?");

    /**
     * What goes between two rows of the same bubble: usually a space, sometimes nothing.
     *
     * <p>The game wraps prose at a space, so putting one back is right for almost everything. It
     * wraps a link wherever the line runs out, mid-word and mid-path, and a space put back there
     * breaks the address into pieces -- players post wiki links constantly, and half a link is
     * worse than none because it still looks like something you could follow.
     *
     * <p>Both conditions are needed. A row that stopped short of the margin ended because the
     * sentence did, and gluing the next one onto it would run two messages together.
     */
    private static String joiner(CharSequence soFar, TextLine previous, int widest) {
        if (previous == null || previous.right() < widest - WRAP_SLACK) {
            return " ";
        }
        int cut = lastBreak(soFar);
        return ChatGarble.looksLikeLink(soFar.subSequence(cut, soFar.length()).toString())
                ? "" : " ";
    }

    private static int lastBreak(CharSequence text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    /** How far short of the widest row a line can stop and still count as having wrapped. */
    private static final int WRAP_SLACK = 40;

    /**
     * Whether what came back is the same message it was given.
     *
     * <p>An exact match was the only case caught, and near-misses are worse than exact ones.
     * "bana garezi var rhaegarin" -- Turkish, and read correctly -- came back as "bana garage var
     * rhegarin": not translated, and two of its words broken in the attempt. Stored as the English
     * rendering, that is shown to a reader in place of text that was right to begin with, under a
     * label saying it has been translated.
     *
     * <p>Compared by shared runs of characters rather than by equality, because that is what
     * separates a rendering from a mangling. A real translation of a sentence shares very little
     * with its source; a failed one is the source with a word knocked out of it.
     */
    /**
     * Splits a bubble the game rendered twice: once as it was typed, once in its own English.
     *
     * <p>Tapping the translate control does not replace a message, it adds the translation beneath
     * it, and both halves fall inside one read. "ya desapareciste jajaj you disappeared already
     * haha" is one bubble holding one sentence twice. Stored whole it reads as nonsense in either
     * language, and it defeats translation: the text handed over is already half English, so what
     * comes back is barely changed and the sanity guard throws it away.
     *
     * <p>The seam is found by asking what the translator left alone. English handed to a translator
     * comes back as itself, so the words the source and its translation share at the end are
     * exactly the half that was already English -- no language guessing, and no extra call, because
     * the translation has been fetched anyway. Measured on the three in the transcript: the shared
     * tail was the English half every time.
     *
     * <p>Two shapes are rejected rather than guessed at. Sharing everything means the translator
     * returned the input, which is what it does with a message that was English to begin with.
     * Sharing a word or two at the end is a name or a laugh landing the same way in both languages,
     * not a second copy of the message.
     *
     * @return the source half and the game's English half, or empty when this is one message
     */
    private static Optional<String[]> splitDoubleRender(String body, String english) {
        if (body == null || english == null || english.isBlank()) {
            return Optional.empty();
        }
        String[] words = body.strip().split("\\s+");
        String[] rendered = english.strip().split("\\s+");
        int shared = 0;
        while (shared < words.length && shared < rendered.length
                && bare(words[words.length - 1 - shared])
                        .equalsIgnoreCase(bare(rendered[rendered.length - 1 - shared]))) {
            shared++;
        }
        int sourceWords = words.length - shared;
        if (shared < SHORTEST_ECHO || sourceWords < SHORTEST_SOURCE
                || (double) shared / words.length < SMALLEST_ECHO_SHARE) {
            return Optional.empty();
        }
        String head = String.join(" ", Arrays.copyOfRange(words, 0, sourceWords)).strip();
        String tail = String.join(" ", Arrays.copyOfRange(words, sourceWords, words.length)).strip();
        return head.isBlank() || tail.isBlank() ? Optional.empty()
                : Optional.of(new String[] {head, tail});
    }

    /** A word with its punctuation dropped, so "Mammoth!" and "Mammoth" line up. */
    private static String bare(String word) {
        return word.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** Fewer shared words than this at the end is a name or a laugh, not a second copy. */
    private static final int SHORTEST_ECHO = 3;

    /** What has to be left over to be a message in its own right. */
    private static final int SHORTEST_SOURCE = 2;

    /** Below this share, the tail is too small a part of the bubble to be half of it. */
    private static final double SMALLEST_ECHO_SHARE = 0.30;

    private static boolean didNotTranslate(String english, String body) {
        String a = english.trim();
        String b = body.trim();
        if (a.isEmpty() || a.equalsIgnoreCase(b)) {
            return true;
        }
        // Compare only the words. A mention survives translation unchanged -- it is somebody's
        // name -- so it is identical on both sides and counts as evidence that nothing was
        // translated, which is exactly backwards. On a short reply it is most of the text:
        // "@AthenaRyu no te preocupes" against "@AthenaRyu don't worry" is four fifths mention,
        // and a good translation was thrown away for looking too much like its source. The same
        // phrase with a shorter name attached was kept three times over.
        a = withoutNames(a);
        b = withoutNames(b);
        if (a.isEmpty() || b.isEmpty() || a.equalsIgnoreCase(b)) {
            return a.equalsIgnoreCase(b);
        }
        if (a.length() < SHORTEST_WORTH_COMPARING || b.length() < SHORTEST_WORTH_COMPARING) {
            return false;
        }
        return ChatLineCleaner.sameMessage(ChatLineCleaner.mergeKey(a),
                ChatLineCleaner.mergeKey(b));
    }

    /** Drops the parts a translator leaves alone: the alliance label and any @mention. */
    private static String withoutNames(String text) {
        return text.replaceAll("\\[[A-Za-z0-9]{2,4}\\]", " ")
                .replaceAll("@[A-Za-z0-9_.-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Below this, two strings share runs by coincidence rather than because one came from the other. */
    private static final int SHORTEST_WORTH_COMPARING = 16;

    /** The sender this line names, or null when it is ordinary message text. */
    private static ChatLineCleaner.Sender senderOn(TextLine line) {
        ChatLineCleaner.Sender sender = ChatLineCleaner.parseSender(line.text());
        return sender.trusted() && !sender.allianceTag().isEmpty() ? sender : null;
    }

    private static void flush(List<ChatMessage> out, List<TextLine> pending,
                              List<TextLine> quotedRows, String author,
                              String tag, String channel, Instant at,
                              Function<String, Optional<String>> translate) {
        if (pending.isEmpty()) {
            return;
        }
        // Joined with a space, not a line break. The breaks in a captured message are the phone's,
        // not the writer's: a bubble on a 720-wide screen wraps every few words, so "En 1:45 hora
        // batalla de la fundicion" and "de la legion 2" are one sentence the handset happened to
        // split. Carrying those breaks into a window ten times as wide made every message read as
        // a narrow ragged column with the rest of the panel empty beside it.
        // The widest row in the bubble is where the game wrapped, so a row reaching it ran out of
        // space rather than ending. That is what tells a wrap from a finished line.
        int widest = 0;
        for (TextLine l : pending) {
            widest = Math.max(widest, l.right());
        }
        StringBuilder sb = new StringBuilder();
        TextLine last = null;
        for (TextLine l : pending) {
            if (sb.length() > 0) {
                sb.append(joiner(sb, last, widest));
            }
            sb.append(l.text().trim());
            last = l;
        }
        StringBuilder q = new StringBuilder();
        for (TextLine l : quotedRows) {
            if (q.length() > 0) {
                q.append(' ');
            }
            q.append(l.text().trim());
        }

        String raw = ChatLineCleaner.cleanBody(sb.toString());
        ChatLineCleaner.Body split = ChatLineCleaner.splitQuotedReply(raw);
        String body = split.own();
        String quotedText = q.length() > 0
                ? ChatLineCleaner.cleanBody(q.toString())
                : split.quoted();

        ChatMessage.Kind kind = ChatLineCleaner.classify(body);
        if (kind == ChatMessage.Kind.UNREADABLE || ChatLineCleaner.looksLikeSenderLine(body)) {
            return;
        }
        // Cards the game posts are not chat. Alliance labels, help requests, gift pouches and
        // furnace-milestone packs arrive with a player's avatar and name on them, so they read as
        // something that player said, and they repeat on every pass -- three of the thirteen
        // messages held from one five-screen walk were the same label card. Nobody reads them in
        // the transcript and they are not what the feed is being captured for.
        if (kind == ChatMessage.Kind.SYSTEM || kind == ChatMessage.Kind.STICKER) {
            return;
        }
        String english = kind == ChatMessage.Kind.TEXT ? translate.apply(body).orElse("") : "";
        Optional<String[]> halves = splitDoubleRender(body, english);
        if (halves.isPresent()) {
            body = halves.get()[0];
            english = halves.get()[1];
        } else if (didNotTranslate(english, body)) {
            english = "";
        }
        out.add(new ChatMessage(at, channel, author, tag, 0, body, english,
                ChatLineCleaner.mentions(body), kind, quotedText));
    }
}
