package dev.frostguard.tasks.social;

import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import dev.frostguard.api.chat.ChatGarble;
import dev.frostguard.api.chat.ChatLineCleaner;
import dev.frostguard.api.chat.ChatMessage;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.TextLine;

/**
 * One walk back through a chat channel: everything that happens to a screen after it is read, and
 * everything that happens to the pass once every screen has been.
 *
 * <p>This exists because the bench and the live task had drifted apart. Both walked a set of
 * screens and turned them into messages, but each had its own copy of the walk, and the copies
 * stopped matching: the bench did not run the repair rules the routine ran, so a change measured at
 * 90% clean on a fixed corpus scored 72% the first time it met production. The measurement was not
 * wrong about the code it ran -- it was running different code. A number from a bench that does not
 * exercise the shipping path is not evidence, and twice in one evening it was reported as though it
 * were.
 *
 * <p>So the walk lives here once. The live task supplies screens from an emulator and the bench
 * supplies them from PNG files, and past that point there is nothing left to diverge: the same
 * grouping, the same repairs, the same de-duplication, the same decision about what is worth
 * storing. Anything a bench measures is now something production does.
 */
final class ChatPass {

    /**
     * Two screens in a row contributing nothing means this walk has reached history it has already
     * read, and going further only spends captures re-reading it.
     */
    private static final int BARREN_SCREENS_BEFORE_STOP = 2;

    private final String channel;
    private final Function<String, Optional<String>> translate;
    private final OcrSettingsData latin;
    private final OcrSettingsData cjk;
    private final OcrSettingsData cyrillic;
    private final int textColumnRight;

    private final LinkedHashMap<String, ChatMessage> collected = new LinkedHashMap<>();
    private final Set<String> roster = new LinkedHashSet<>();
    private int knownBefore;
    private int barrenScreens;

    ChatPass(String channel, Function<String, Optional<String>> translate,
             OcrSettingsData latin, OcrSettingsData cjk, OcrSettingsData cyrillic,
             int textColumnRight) {
        this.channel = channel;
        this.translate = translate;
        this.latin = latin;
        this.cjk = cjk;
        this.cyrillic = cyrillic;
        this.textColumnRight = textColumnRight;
    }

    /** What one screen contributed, for the caller to log. */
    record Screen(int lines, int readable, int fresh) {
    }

    /**
     * Folds one screen into the pass.
     *
     * @param fromService whether the rows came from the OCR service rather than the built-in
     *                    reader. The repair rules below exist to patch up the built-in reader and
     *                    must not be run over the other one's rows: the word boxes they measure
     *                    against come from the built-in reader, so matching them to rows the
     *                    service produced compares two different readings of the same screen and
     *                    acts on the difference. Live, that interleaved words into each other and
     *                    put CJK into a Spanish name. The service reports its own confidence, which
     *                    is what those rules were reconstructing by inference, and it reads other
     *                    scripts without being asked twice.
     */
    Screen addScreen(RawImageData frame, BufferedImage image, List<TextLine> rows,
                     boolean fromService) {
        List<TextLine> lines = rows;
        if (fromService) {
            lines = rereadOtherScripts(image, lines);
        } else {
            List<TextLine> words = readWords(frame);
            lines = ChatScriptRecovery.reread(frame, lines, words, textColumnRight, cjk, cyrillic);
            lines = ChatOrnamentFilter.clean(lines, words, image);
        }

        // Every sender line names a member, whether or not their message survived reading, so the
        // roster is built from the rows rather than from the messages they became.
        for (TextLine line : lines) {
            ChatLineCleaner.Sender sender = ChatLineCleaner.parseSender(line.text());
            if (sender.trusted() && !sender.allianceTag().isEmpty() && !sender.name().isBlank()) {
                roster.add(sender.name());
            }
        }

        List<ChatMessage> read = ChatFrameReader.read(lines, channel, Instant.now(), translate,
                line -> ChatQuoteBar.isQuoteRow(image, line));
        for (ChatMessage m : read) {
            keep(m);
        }

        int fresh = unseen - knownBefore;
        knownBefore = unseen;
        barrenScreens = fresh == 0 ? barrenScreens + 1 : 0;
        return new Screen(lines.size(), read.size(), fresh);
    }

    /** Messages found that the transcript did not already hold. */
    private int unseen;

    /** What the transcript already holds, when the caller can say. */
    private java.util.function.Predicate<ChatMessage> known;

    /**
     * Tells the walk what has already been stored, so it can stop when it reaches it.
     *
     * <p>Without this the walk only knows what it has seen since it started, and "nothing new on
     * this screen" means the scroll stalled rather than that the conversation has been caught up
     * with.
     */
    void useKnownHistory(java.util.function.Predicate<ChatMessage> alreadyStored) {
        this.known = alreadyStored;
    }

    /** Whether this walk has reached history it has already read. */
    boolean reachedKnownHistory() {
        return barrenScreens >= BARREN_SCREENS_BEFORE_STOP;
    }

    /** Everything worth storing from this walk, in the order it was read. */
    List<ChatMessage> messages() {
        List<ChatMessage> out = new ArrayList<>(collected.size());
        for (ChatMessage m : collected.values()) {
            if (!m.author().isBlank()) {
                roster.add(m.author());
            }
            roster.addAll(m.mentions());
        }
        for (ChatMessage m : collected.values()) {
            // A message the walk never attributed to anybody was never seen with a name on it,
            // because overlapping scroll steps mean a real message is seen two or three times and
            // the attributed copy wins. Those are game announcements or a strip of a message whose
            // readable half was elsewhere; neither is anybody's words.
            if (m.author().isBlank()) {
                continue;
            }
            String repaired = ChatLineCleaner.repairLeadingMention(m.body(), roster);
            // What the reader could not actually read is worse than nothing: a fragment is
            // indistinguishable from a player who types strangely, so it costs the reader trust in
            // the lines either side of it as well as its own.
            String legible = ChatGarble.repair(repaired);
            if (legible.isBlank()) {
                continue;
            }
            ChatMessage kept = legible.equals(m.body()) ? m : m.withBody(legible);
            if (!legible.equals(m.body())) {
                // The English was made from the body as it read before the repair, so it carries
                // the same wreckage. Read again from the repaired text rather than patched.
                kept = kept.withTranslated(translate.apply(legible).orElse(""));
            }
            out.add(englishQuote(kept));
        }
        return out;
    }

    /**
     * Reads a row again in another alphabet when the first reading was in the wrong one.
     *
     * <p>A recogniser is built for one script and does not decline to read another -- it returns
     * the nearest shapes it knows. Handed Cyrillic, the Latin model answers confidently in Latin
     * lookalikes: "Bcem npnBet" for "Всем привет", "KpncTannoB" for "кристаллов". The result is
     * unreadable to a person and, worse, invisible to everything downstream, because it is
     * well-formed Latin text that no language test can place. It is not translated either, since
     * there is nothing there to translate.
     *
     * <p>Only the rows that look wrong are read again, and only they pay for it. Reading every
     * frame in every alphabet would multiply the pass by the number of languages the alliance
     * speaks, for the handful of rows that need it.
     *
     * <p>The second reading has to prove itself: it is kept only if it comes back in the script it
     * was asked for. A model given a row that really was Latin all along will answer in Latin
     * lookalikes of its own, and swapping good text for that would be the same fault in reverse.
     */
    private List<TextLine> rereadOtherScripts(BufferedImage image, List<TextLine> lines) {
        if (rereader == null) {
            return lines;
        }
        boolean[] suspect = new boolean[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            TextLine line = lines.get(i);
            suspect[i] = ChatScriptRecovery.looksLikeMangledScript(line.text())
                    || barelyRead(line)
                    || nameFailedToRead(line);
        }
        spreadToNeighbours(lines, suspect);

        List<TextLine> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            out.add(suspect[i] ? inAnotherScript(image, lines.get(i)) : lines.get(i));
        }
        return out;
    }

    /**
     * Carries suspicion from a flagged row to the rest of its bubble.
     *
     * <p>The test needs two words it cannot account for, which a wrapped line often does not hold:
     * "Bce BpaueHng" is two words and reads as one, and "Oooo.Cnacn6o.A Hakonnnn" hides behind a
     * Latin opening. Judged alone they pass as Latin and stay unreadable, while the line above them
     * in the same bubble is caught.
     *
     * <p>Nobody writes half a message in another alphabet. So the unit of judgement is the bubble,
     * not the line: one confidently wrong row makes its neighbours suspect too. Lowering the word
     * threshold instead would have bought the same rows at the cost of every short Latin line in
     * the transcript.
     *
     * <p>It stops at a sender line and at a vertical gap, which is what separates one person's
     * bubble from the next. Without that a single Cyrillic message would drag the whole screen into
     * being read again.
     */
    private static void spreadToNeighbours(List<TextLine> lines, boolean[] suspect) {
        boolean[] seeded = suspect.clone();
        for (int i = 0; i < lines.size(); i++) {
            if (!seeded[i]) {
                continue;
            }
            for (int step = -1; step <= 1; step += 2) {
                for (int j = i + step; j >= 0 && j < lines.size(); j += step) {
                    TextLine near = lines.get(j);
                    TextLine from = lines.get(j - step);
                    if (endsTheBubble(near) || gapBetween(near, from) > SAME_BUBBLE_GAP) {
                        break;
                    }
                    suspect[j] = true;
                }
            }
        }
    }

    /**
     * Whether a row came back with far less text than its box can hold.
     *
     * <p>Two ways a reader fails on a script it does not know, and they look nothing alike. Handed
     * Cyrillic it answers in Latin lookalikes -- confident, wrong, and detectable because the
     * letters fall where writing does not put them. Handed Korean it answers with almost nothing:
     * the box is there, three hundred pixels of it, and four characters come out. Nothing about
     * those four characters is suspicious on its own; what gives it away is the emptiness around
     * them.
     *
     * <p>So this measures the box rather than the text. A line of chat runs about nine to fourteen
     * pixels per character, and a row spending five times that on each one has not been read.
     */
    private static boolean barelyRead(TextLine line) {
        int characters = line.text().trim().length();
        if (characters == 0) {
            return line.width() > EMPTY_BOX_WIDTH;
        }
        return line.width() / (double) characters > PIXELS_PER_CHARACTER_WHEN_UNREAD
                && line.width() > EMPTY_BOX_WIDTH;
    }

    /**
     * A sender line whose alliance tag read but whose name did not.
     *
     * <p>The tag is Latin and always reads; the name is whatever the player chose, and a Korean one
     * handed to a Latin model comes back as nothing at all. What is left is "[INF]" on its own,
     * which is not a name anybody has -- and left that way the message underneath it is filed
     * against whoever spoke last, so one person's words end up attributed to another.
     */
    private static boolean nameFailedToRead(TextLine line) {
        String text = line.text().trim();
        return TAG_WITHOUT_NAME.matcher(text).matches();
    }

    private static final java.util.regex.Pattern TAG_WITHOUT_NAME =
            java.util.regex.Pattern.compile("^\\W*\\[?[A-Za-z0-9]{2,4}\\]?\\W*$");

    /** Narrower than this and there was never much to read, whatever came back. */
    private static final int EMPTY_BOX_WIDTH = 90;

    /** Writing runs 9-14 pixels a character; five times that is a box with nothing in it. */
    private static final double PIXELS_PER_CHARACTER_WHEN_UNREAD = 50.0;

    /** A sender line belongs to nobody's message text; it names the person about to speak. */
    private static boolean endsTheBubble(TextLine line) {
        ChatLineCleaner.Sender sender = ChatLineCleaner.parseSender(line.text());
        return sender.trusted() && !sender.allianceTag().isEmpty();
    }

    private static int gapBetween(TextLine a, TextLine b) {
        return a.top() > b.top() ? a.top() - b.bottom() : b.top() - a.bottom();
    }

    /** Wrapped lines of one bubble sit within a few pixels; the next bubble is far below. */
    private static final int SAME_BUBBLE_GAP = 46;

    private TextLine inAnotherScript(BufferedImage image, TextLine line) {
        String best = null;
        double bestScore = 0;
        for (String language : OTHER_SCRIPTS) {
            List<TextLine> again = rereader.read(
                    Math.max(0, line.left() - REREAD_PADDING),
                    Math.max(0, line.top() - REREAD_PADDING),
                    Math.min(image.getWidth(), line.right() + REREAD_PADDING),
                    Math.min(image.getHeight(), line.bottom() + REREAD_PADDING),
                    language);
            String text = keepMention(joined(again), line.text());
            if (text.isBlank() || !ChatScriptRecovery.isMostlyAnotherScript(withoutLatinFurniture(text))) {
                continue;
            }
            double score = confidence(again) * lettersIn(text);
            if (score > bestScore) {
                bestScore = score;
                best = text;
            }
        }
        if (best == null) {
            return line;
        }
        return new TextLine(best, line.left(), line.top(), line.width(), line.height(),
                line.confidence());
    }

    /**
     * The text with the parts that are always Latin taken out.
     *
     * <p>The alliance tag and the "@" names are drawn in Latin whatever the message is written in,
     * so counting them when asking "is this mostly another script" answers a question about the
     * game's furniture rather than about the player's words. A sender line is the worst case:
     * "[INF]왕눈이" is three Latin letters and three Korean ones, which is exactly half and half,
     * so a test wanting a clear majority rejected every Korean name -- and a name that will not
     * read files that person's messages against whoever spoke last.
     */
    private static String withoutLatinFurniture(String text) {
        return text.replaceAll("\\[[A-Za-z0-9]{2,4}\\]", " ")
                .replaceAll("@[A-Za-z0-9_.-]+", " ");
    }

    /**
     * Every alphabet is tried and the most convincing answer wins, rather than the first that is
     * merely not Latin.
     *
     * <p>Taking the first was wrong in a way that only showed on Korean names. Asked to re-read
     * "[INF]왕눈이", the Cyrillic model answers before Korean is ever tried -- it produces Cyrillic,
     * which passes a test for "some other script", and the walk stops there holding nonsense. The
     * model that actually knows the script says more and is far surer of it, so how much it read
     * and how sure it was, multiplied, separates the two cleanly.
     */
    private static double confidence(List<TextLine> parts) {
        if (parts.isEmpty()) {
            return 0;
        }
        double total = 0;
        for (TextLine p : parts) {
            total += p.confidence();
        }
        return total / parts.size();
    }

    private static int lettersIn(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    /**
     * Puts the addressed player's name back as it was first read.
     *
     * <p>A Cyrillic recogniser reads a Latin name in Cyrillic: "@CrisdeuS" came back as
     * "@Crisdеu S" with a Cyrillic "е" in the middle of it. The letter looks identical and is not,
     * so the name stops matching the person -- it drops out of the roster, breaks the mention
     * colouring, and cannot be searched for. The name is the one part of the row the first reading
     * had right, because the game draws it in Latin whatever the message is written in.
     */
    private static String keepMention(String reread, String original) {
        java.util.regex.Matcher was = LEADING_MENTION.matcher(original);
        java.util.regex.Matcher now = LEADING_MENTION.matcher(reread);
        if (!was.find() || !now.find()) {
            return reread;
        }
        return was.group() + reread.substring(now.end());
    }

    private static final java.util.regex.Pattern LEADING_MENTION =
            java.util.regex.Pattern.compile("^\\s*@\\S+");

    private static String joined(List<TextLine> parts) {
        StringBuilder sb = new StringBuilder();
        for (TextLine p : parts) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(p.text().trim());
        }
        return sb.toString().strip();
    }

    /**
     * Alphabets to try, in the order the alliance actually uses them.
     *
     * <p>Russian first because it is who is in this alliance. The list is short deliberately: each
     * entry is another request for every suspicious row, and a language nobody here writes costs
     * that on every pass forever.
     */
    private static final String[] OTHER_SCRIPTS = {"ru", "ko", "ja", "th", "ar"};

    /** A row's own box clips its tallest glyphs; a little air around it reads better. */
    private static final int REREAD_PADDING = 4;

    /** Reads one region of the current frame in a named language, or nothing. */
    interface Rereader {
        List<TextLine> read(int left, int top, int right, int bottom, String language);
    }

    private Rereader rereader;

    void useRereader(Rereader reader) {
        this.rereader = reader;
    }

    /**
     * The message being replied to, in English.
     *
     * <p>A reply and the thing it answers are one exchange, and a reader who needs the reply
     * translated needs the quote translated too -- otherwise the half of the conversation that
     * explains the other half is the half they cannot read. The game writes a quote as
     * "Name: what they said", and only the second part is language.
     */
    private ChatMessage englishQuote(ChatMessage m) {
        if (!m.hasQuote()) {
            return m;
        }
        // Folded before the name is looked for, not after: the reader writes the game's CJK colon
        // for the one between a quoted name and its text, and searching the raw string for ":"
        // finds nothing, so the whole strip is read as the thing that was said.
        String quote = ChatGarble.normalise(m.quoted()).strip();
        int colon = quote.indexOf(':');
        String who = colon > 0 && colon < MAX_QUOTED_NAME ? quote.substring(0, colon + 1) : "";
        // The name is judged separately from what was said, because it is not evidence that
        // anything was said. A strip that read as nothing but "CrisdeuS:" used to survive on the
        // strength of the name alone, and showed the reader a reply to an empty message.
        String said = ChatGarble.repair(quote.substring(who.length()));
        if (said.isBlank()) {
            return m.withQuoted("");
        }
        return m.withQuoted(translate.apply(said).map(en -> (who + " " + en).strip())
                .orElse((who + " " + said).strip()));
    }

    /** Longer than this before a colon and it is a sentence, not the name being answered. */
    private static final int MAX_QUOTED_NAME = 24;

    private List<TextLine> readWords(RawImageData frame) {
        try {
            return OcrEngine.recognizeWords(frame, new PointData(0, 0),
                    new PointData(frame.getWidth(), frame.getHeight()), latin);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Files one message against what the walk has already read, treating a re-read as the same
     * message rather than a new one.
     *
     * <p>Where two copies disagree the fuller one wins -- a message clipped by the bottom of the
     * screen is still a correct reading of its first half -- except that an attributed copy always
     * beats an unattributed one. A mention is carried across either way: the copy with the fuller
     * body is not always the copy that kept its mention.
     */
    private void keep(ChatMessage m) {
        String key = ChatLineCleaner.mergeKey(m.body());
        String match = collected.containsKey(key) ? key : null;
        if (match == null) {
            for (String seen : collected.keySet()) {
                if (ChatLineCleaner.sameMessage(seen, key)) {
                    match = seen;
                    break;
                }
            }
        }
        if (match == null) {
            collected.put(key, m);
            // Counted separately from the walk's own novelty. A message the transcript already
            // holds is not news, however new it is to this scroll, and treating it as news is what
            // kept a busy channel reading all seventy-six of its screens every pass for a night.
            if (known == null || !known.test(m)) {
                unseen++;
            }
            return;
        }
        ChatMessage held = collected.get(match);
        ChatMessage winner = better(m, held) ? m : held;
        ChatMessage other = winner == m ? held : m;
        if (!winner.body().startsWith("@") && other.body().startsWith("@")) {
            int end = other.body().indexOf(' ');
            String mention = end < 0 ? other.body() : other.body().substring(0, end);
            winner = winner.withBody(mention + " " + winner.body());
        }
        collected.remove(match);
        collected.put(ChatLineCleaner.mergeKey(winner.body()), winner);
    }

    private static boolean better(ChatMessage candidate, ChatMessage held) {
        if (held.author().isEmpty() != candidate.author().isEmpty()) {
            return held.author().isEmpty();
        }
        return candidate.body().length() > held.body().length();
    }
}
