package dev.frostguard.api.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a row's raw OCR into an author and a body worth rendering.
 *
 * <p>Every rule here was measured against live captures rather than imagined. Across 1,929 real
 * messages the reader emits {@code |} 1,516 times, {@code =} 1,219 times and a scatter of
 * {@code ~ ® * "} -- none of which any player typed. They are the game's own chrome: bubble
 * borders, the per-message translate control, VIP crowns and emoji the OCR could not resolve.
 * Left in place they read as text, get counted as content, and get sent to a translator.
 *
 * <p>Sender strings are similarly shaped. Of the same sample, 164 begin with digits and 52 are
 * punctuation runs -- {@code 34 ERE}, {@code - in}, {@code ae} -- which are frame furniture caught
 * by the name strip, not people. A name that cannot be trusted is better reported as unknown than
 * rendered as a participant.
 */
public final class ChatLineCleaner {

    /** {@code VIP7 [THE]Phantom} -- the fullest sender form the game renders. */
    private static final Pattern SENDER = Pattern.compile(
            // A mood badge is drawn left of the VIP tag on some senders and reads as stray
            // punctuation. Anchoring on \s alone pushed the whole line into the name group, so
            // "& VIP6 [INF]CrisdeuS" was stored verbatim as the author.
            "^[^\\p{L}\\p{N}\\[\\]()]*(?:VIP\\s*(?<vip>\\d{1,2})\\s*)?"
                    + "(?:[\\[(](?<tag>[A-Za-z0-9]{2,4})[\\])]\\s*)?"
                    + "(?<name>.+?)\\s*$");

    /**
     * The sender line reduced to its one dependable landmark: the bracketed alliance tag.
     *
     * <p>Anything left of the tag is badge and decoration, whatever it happened to read as. The
     * last tag on the line wins, so a tag mentioned inside a name cannot end the match early.
     */
    private static final Pattern TAG_ANCHORED = Pattern.compile(
            "^.*\\[(?<tag>[A-Za-z0-9]{2,4})\\]\\s*(?<name>\\p{L}[^\\s]{0,23})");

    /**
     * Rewrites the alliance tag into a canonical {@code [TAG]} before anything else reads the line.
     *
     * <p>Measured over 232 rows from 50 live alliance frames: the closing bracket comes back as a
     * literal {@code ]} in only a handful of rows, and as {@code j}, {@code J} or {@code |} in most
     * of the rest -- it is a thin tall glyph sitting hard against the first letter of the name.
     * Worse, {@code |} was being deleted as an invented character before the tag was ever parsed,
     * so the closing bracket vanished entirely. Normalising first means the tag patterns and the
     * artifact strip see the same shape. Insisting on a literal {@code ]} recognised 2 sender lines
     * out of 213.
     */
    private static final Pattern TAG_ANY =
            Pattern.compile("[\\[({]\\s*([A-Za-z0-9]{2,4})\\s*[\\])}jJ|Ili1]");

    /** {@code @Name} and the spaced {@code @ [TAG]Name} the reader also produces. */
    private static final Pattern MENTION = Pattern.compile(
            "@\\s*(?:[\\[(][A-Za-z0-9]{2,4}[\\])])?\\s*([A-Za-z0-9_][A-Za-z0-9_ ]{1,20}?)(?=[\\s,.:!?]|$)");

    /**
     * Two characters that are usually chrome but sometimes real text, put back before stripping.
     *
     * <p>The artifact strip below removes the pipe and the copyright sign because they are normally
     * a bubble border and an unresolved emoji. Sometimes they are neither: a lone "|" standing
     * between spaces is the English word "I", and a "©" pressed up against a name is an "@".
     * Removing them silently deleted a word from the middle of a sentence -- "Ooooops. I missed it"
     * was stored as "Oooops. missed it" -- and cost "@Maki felicidades!" the mention saying who it
     * was addressed to.
     *
     * <p>Both are read narrowly, because guessing wrong invents text rather than merely losing it.
     * The pipe counts as a word only between spaces: at the start of a bubble it is the border, and
     * calling that an "I" prefixes a stray word. The copyright sign counts as an "@" only when it
     * is pressed straight against a capital -- with a space between them it is usually an emoji
     * opening a sentence, and "© Oooops. I missed it" was stored as "@Oooops. I missed it". A
     * mention occasionally lost is better than an "@" invented in front of an ordinary word.
     */
    private static final Pattern MISREAD_I = Pattern.compile("(?<=\\s)\\|(?=\\s)");
    private static final Pattern MISREAD_AT = Pattern.compile("[©®](?=\\p{Lu})");

    /**
     * A single letter stranded at the front of a message.
     *
     * <p>Bubble art on the left reads as one loose character: "@Maki felicidades!" arrived as
     * "b @Maki felicidades!". Letters that are words in their own right are left alone, because
     * "y", "o", "a" and "e" all open real sentences in Spanish and Portuguese, and "I" and "A"
     * do in English.
     */
    private static final Pattern LEADING_LONE_LETTER =
            Pattern.compile("^(?![aeouyiAEOUYI]\\b)\\p{L}\\s+(?=\\p{L}|@)");

    /** Characters the reader invents from bubble borders, crowns and unresolved emoji. */
    private static final Pattern ARTIFACTS = Pattern.compile("[|=~®©*“”„¦¬`^]+");

    /**
     * The per-message translate control, which the reader spells as a word.
     *
     * <p>It sits at the end of every bubble and comes back as a bare "tt" often enough to appear in
     * most messages. Matched only as a whole token so a real word containing those letters is
     * untouched.
     */
    private static final Pattern TRANSLATE_CONTROL =
            Pattern.compile("(?<!\\p{L})tt(?!\\p{L})");

    /** Game-generated cards, which are events rather than things a player said. */
    private static final Pattern SYSTEM_CARD = Pattern.compile(
            "(?i)\\b(share (coordinates|layout)|lucky pouch|new message\\(s\\)|tap to enter"
                    + "|help (request|needed)|has joined the alliance|alliance bomb"
                    // The alliance poll is pinned above the feed and carries a clipboard icon the
                    // segmenter reads as an avatar, so it arrives as a message on every pass. Its
                    // own wording is what identifies it: "Initiator:", "Participants: 49/98",
                    // "Vote in: 11:29:34".
                    + "|initiator|participants|vote in|selection"
                    + "|have not participated|alliance notice|alliance label|label set at"
                    // Gift and milestone cards the game posts on a player's behalf. They
                    // carry a player's name and an avatar, so without naming them they read
                    // as something that player said.
                    + "|upgrade pack|has reached lv|gift pack|lucky gift"
                    // Cards the game posts about the alliance rather than to it: rank changes,
                    // event countdowns and hunt announcements. Each carries a player's name and
                    // an avatar, so left unnamed they read as that player talking.
                    + "|has been (promoted|demoted) to|event reminder|respected chief|bear hunt"
                    + "|hunting trap|alliance rank)\\b");

    /**
     * A bubble that is nothing but the word "Vote".
     *
     * <p>The poll card's button, caught as a row of its own. Matched whole rather than as a word,
     * because "vote" inside a sentence is somebody actually talking about the vote.
     */
    private static final Pattern VOTE_BUTTON = Pattern.compile("(?i)^\s*vote\s*$");

    private static final Pattern EMOJI_ONLY = Pattern.compile("^[\\p{So}\\p{Cn}\\s]+$");

    /**
     * A player name as the game renders it at the head of a quoted reply.
     *
     * <p>Replying puts the original message inside the new bubble as {@code Name: text}. Read as
     * one region that lands in the body, so a third of all captured messages carried somebody
     * else's words appended to their own -- measured at 84 of 295 on a live pass.
     */
    private static final String QUOTED_NAME =
            "(?:\\[[A-Za-z0-9]{2,4}\\])?[A-Za-z][A-Za-z0-9_'!.-]{0,14}(?:\\s[A-Za-z0-9_'!.-]{1,12}){0,2}";

    /** The quote begins on its own line, so a newline boundary is the reliable one. */
    private static final Pattern QUOTE_AT_LINE = Pattern.compile("\\n\\s*(" + QUOTED_NAME + "):\\s");

    /** Fallback for when the reader loses the line break. */
    private static final Pattern QUOTE_INLINE = Pattern.compile("\\s(" + QUOTED_NAME + "):\\s");

    /**
     * Game-generated chatter rather than anything a player typed: recalled messages, shared layouts
     * and coordinates, rally and formation cards. Kept separable so the reader can hide it and be
     * left with the conversation.
     */
    private static final Pattern NON_SPEECH = Pattern.compile(
            "(?i)\\b(recalled a message|share (layout|coordinates)|state\\s*#\\s*\\d+"
                    + "|has joined the alliance|left your alliance|new message\\(s\\)"
                    + "|hold(ing)? a rally|join(ed)? the rally|rally (started|is starting)"
                    + "|defeat the beast|gather together|formation shared"
                    // The rally card's own wording. "Raging" is spelt loosely because the reader
                    // returns "Raqing" about as often as it returns the real thing, and a card
                    // that hides only when it happens to read cleanly is a card that does not
                    // hide.
                    + "|ra[gq]ing bear|defeat ra[gq]ing|rally together now)\\b");

    /**
     * The most common English function words. Enough of them in a message means it is English;
     * their absence means it is not, whatever alphabet it happens to use.
     */
    private static final java.util.Set<String> COMMON_ENGLISH = java.util.Set.of(
            "the", "be", "to", "of", "and", "a", "in", "that", "have", "i", "it", "for", "not", "on",
            "with", "he", "as", "you", "do", "at", "this", "but", "his", "by", "from", "they", "we",
            "say", "her", "she", "or", "an", "will", "my", "one", "all", "would", "there", "their",
            "what", "so", "up", "out", "if", "about", "who", "get", "which", "go", "me", "when",
            "make", "can", "like", "time", "no", "just", "him", "know", "take", "into", "year",
            "your", "good", "some", "could", "them", "see", "other", "than", "then", "now", "look",
            "only", "come", "its", "over", "think", "back", "after", "use", "two", "how", "our",
            "work", "first", "well", "way", "even", "new", "want", "because", "any", "these", "give",
            "day", "most", "us", "is", "are", "was", "were", "am", "been", "has", "had", "did",
            "does", "dont", "cant", "im", "ive", "youre", "yes", "yeah", "ok", "okay", "thanks",
            "thank", "please", "hi", "hello", "hey", "lol", "sorry", "rally", "join", "help", "need",
            "more", "bro", "man", "still", "here", "why", "much", "very", "really",
            // Short English that carries no function word at all. Before these were listed, the
            // only thing keeping "congrats" or "gg" from being shipped to a translator was the
            // blanket assumption that anything short was English -- which cost every short foreign
            // message its translation. Naming them is the cheaper half of that trade.
            "congrats", "congratulations", "gg", "nice", "done", "sure", "welcome", "ready",
            "soon", "later", "wait", "oops", "ty", "thx", "np", "gl", "hf", "afk", "omw",
            "sent", "sending", "got", "coming", "same", "true", "agree", "nope", "yep", "yup");

    private static final Pattern WORDS = Pattern.compile("[A-Za-z']+");

    /** Below this share of recognisable English words a message is treated as foreign. */
    private static final double ENGLISH_WORD_RATIO = 0.25;

    /**
     * Below this the function-word test has no power and is left alone.
     *
     * <p>Three was too low. "proton password manager" carries no function words at all, scored zero,
     * and was shipped to the translator to come back unchanged -- a wasted lookup on plain English.
     * Short messages now rely on the diacritic and script checks, which need no word evidence.
     */
    private static final int MIN_WORDS_TO_JUDGE = 5;

    /** How much English structure a short message has to show before it is left untranslated. */
    private static final int MIN_ENGLISH_WORDS_WHEN_SHORT = 2;

    /**
     * What a one- or two-word message needs to count as English.
     *
     * <p>One, because there is no room for two. "congrats", "ok ty" and "Thank you" are the whole
     * message, and demanding a second corroborating word means no greeting is ever English -- which
     * sent every one of them to a translator that returned it unchanged.
     */
    private static final int MIN_ENGLISH_WORDS_WHEN_TINY = 1;

    /** At or below this length, a message is judged by the tiny rule above. */
    private static final int TINY_MESSAGE_WORDS = 2;

    /**
     * English's own grammar, as opposed to its vocabulary.
     *
     * <p>Articles, pronouns, auxiliaries and prepositions. These are the words a language does not
     * borrow: an alliance writing Spanish still says "rally" and "gg", but it does not say "the" or
     * "have". That is what makes them the evidence worth counting -- and it is deliberately
     * narrower than {@link #COMMON_ENGLISH}, which stays as it is for other callers.
     */
    private static final java.util.Set<String> ENGLISH_STRUCTURE = java.util.Set.of(
            "a", "about", "after", "afternoon", "again", "all", "also", "always", "am", "an",
            "and", "any", "are", "around", "as", "ask", "asked", "at", "away", "back", "be",
            "because", "been", "before", "being", "best", "better", "birthday", "but", "by",
            "bye", "can", "cant", "come", "congrats", "congratulations", "could", "day", "days",
            "did", "do", "does", "doing", "done", "dont", "down", "early", "evening", "ever",
            "every", "everybody", "everyone", "first", "for", "from", "get", "give", "glad",
            "go", "goes", "going", "good", "got", "great", "guys", "had", "happy", "has", "have",
            "having", "he", "hello", "help", "helping", "her", "here", "hers", "hey", "hi",
            "him", "his", "hope", "how", "i", "if", "im", "in", "into", "is", "it", "its", "ive",
            "just", "keep", "kept", "know", "last", "late", "let", "little", "long", "luck",
            "lucky", "made", "make", "many", "maybe", "me", "mine", "more", "morning", "much",
            "my", "need", "never", "new", "next", "nice", "night", "no", "not", "now", "np",
            "of", "off", "ok", "okay", "on", "one", "only", "or", "our", "ours", "out", "over",
            "people", "please", "put", "right", "said", "same", "say", "see", "she", "should",
            "so", "some", "soon", "sorry", "still", "sure", "take", "tell", "than", "thank",
            "thanks", "thankyou", "that", "the", "their", "them", "then", "there", "these",
            "they", "think", "this", "those", "through", "thx", "time", "to", "today", "told",
            "tomorrow", "too", "two", "ty", "up", "us", "use", "used", "uses", "using", "very",
            "want", "was", "we", "week", "welcome", "well", "went", "were", "what", "when",
            "which", "while", "who", "whose", "why", "will", "with", "wont", "work", "working",
            "would", "wrong", "yeah", "yes", "yesterday", "you", "your", "youre", "yours"
    );
    private static final Pattern REPEATED_SPACE = Pattern.compile("\\s{2,}");

    private ChatLineCleaner() {
    }

    /** Sender name split into its parts, with {@code trusted} false when it is frame furniture. */
    public record Sender(String name, String allianceTag, int vipLevel, boolean trusted) {
    }

    /**
     * Splits a sender strip into VIP level, alliance tag and name.
     *
     * <p>A name is only trusted when it starts with a letter and carries at least two more
     * characters. That single rule rejects the whole observed garbage population -- digit-led runs
     * and punctuation fragments -- without a blocklist that would need maintaining per patch.
     */
    public static Sender parseSender(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Sender("", "", 0, false);
        }
        // Canonicalise the tag first. The artifact strip below deletes "|", which is one of the
        // shapes the closing bracket reads as, so leaving this until afterwards threw the bracket
        // away before anything could parse it.
        String normalised = TAG_ANY.matcher(raw).replaceAll("[$1]");
        String cleaned = collapse(ARTIFACTS.matcher(normalised).replaceAll(" "));

        // The alliance tag is the one anchor on this line that survives a bad read, so when it is
        // present everything before it is discarded outright. The VIP badge is why: it is drawn on
        // some senders and not others, and when the reader mangles "VIP6" into "ViIPO" the exact
        // VIP group stops matching and the wreckage falls through into the NAME -- which is where
        // authors like "ViIPO LINE PACHChanyu" and "SS} VirP/ TMejbreach" came from. Anchoring on
        // the tag means no spelling of the badge can reach the name. VIP rank is not worth keeping
        // in its own right.
        Matcher anchored = TAG_ANCHORED.matcher(cleaned);
        if (anchored.matches()) {
            String tagged = trimNameNoise(collapse(anchored.group("name")));
            return new Sender(tagged, anchored.group("tag"), 0, isPlausibleName(tagged));
        }

        Matcher m = SENDER.matcher(cleaned);
        if (!m.matches()) {
            return new Sender(cleaned, "", 0, false);
        }
        String name = trimNameNoise(collapse(m.group("name")));
        String tag = m.group("tag") == null ? "" : m.group("tag");
        int vip = m.group("vip") == null ? 0 : Integer.parseInt(m.group("vip"));
        return new Sender(name, tag, vip, isPlausibleName(name));
    }

    /** Characters that occur in real player names: letters, digits, and the usual name punctuation. */
    private static final Pattern NAME_ODDITY = Pattern.compile("[^\\p{L}\\p{N} _.'-]");

    /** More than one stray symbol is the reader inventing glyphs, not a player being creative. */
    private static final int MAX_ODD_CHARS_IN_A_NAME = 1;

    /**
     * Whether a name strip read cleanly enough to attribute a message to.
     *
     * <p>"Starts with a letter and is three characters long" was too permissive. It let through
     * "S} ViP/ TRejbreach" and "fe MPA) /UIBGeULClICr", which rendered in the transcript as
     * participants -- the reader mangling a VIP badge and an alliance tag into punctuation. Real
     * names carry at most an odd character or two; a scattering of braces and slashes is noise.
     */
    private static boolean isPlausibleName(String name) {
        if (name.length() < 3 || !Character.isLetter(name.charAt(0))) {
            return false;
        }
        // A sender line whose message ran into it: "[INF]Mojorisinfans se Tengo a Natalia y no la
        // he podido" is a name followed by what that person then said. Players use one word, or
        // two for the few with a space, and none of them are a sentence long.
        if (name.length() > MAX_NAME_LENGTH
                || name.trim().split("\\s+").length > MAX_WORDS_IN_A_NAME) {
            return false;
        }
        // A bracket surviving into the name means the alliance tag was not parsed off cleanly, so
        // what is left is the wreckage of the whole line rather than a player -- this is what
        // "VIPG [INF jAthenaRyu" looks like by the time it gets here.
        if (UNPARSED_TAG.matcher(name).find()) {
            return false;
        }
        // Likewise a VIP badge still sitting in the name: it is drawn on some senders and not
        // others, so any spelling of it here means the badge was misread rather than skipped, and
        // the rest of the line cannot be trusted either.
        if (STRAY_VIP.matcher(name).find()) {
            return false;
        }
        return NAME_ODDITY.matcher(name).results().count() <= MAX_ODD_CHARS_IN_A_NAME;
    }

    /** Drops the decorations the reader picks up past the end of the name, e.g. "CrisdeuS 7". */
    private static String trimNameNoise(String name) {
        String out = NAME_TRAILING_NOISE.matcher(name).replaceAll("");
        out = NAME_TRAILING_BADGE.matcher(out).replaceAll("");
        return NAME_TRAILING_NOISE.matcher(out).replaceAll("").trim();
    }

    /** Long enough for the longest real name seen, short enough to reject a sentence. */
    private static final int MAX_NAME_LENGTH = 20;

    /** A bracket left in the name means the tag was never separated from it. */
    private static final Pattern UNPARSED_TAG = Pattern.compile("[\\[\\]()]");

    /** The badge as the reader spells it when it fails: VIP, ViIPO, VIPG, VirP, VIPw. */
    private static final Pattern STRAY_VIP = Pattern.compile("(?i)\\bV[il1|]{0,2}[PR][0-9A-Za-z]?\\b");

    /**
     * Trailing decoration the reader picks up from beside the name.
     *
     * <p>Written as the Unicode punctuation and symbol categories, not as {@code \p{Punct}}, which
     * in Java covers only the ASCII marks. The reader's inventions are mostly not ASCII: an em
     * dash, a curly quote, an arrow. "Mojorisinfans —" was kept whole and stored as a player's
     * name because the dash on the end fell outside the class meant to strip it.
     */
    private static final Pattern NAME_TRAILING_NOISE =
            Pattern.compile("[\\s\\p{P}\\p{S}]+$");

    /**
     * A rank badge the reader picked up as a separate number after the name.
     *
     * <p>Split out from the rule above, which stripped every trailing digit and so took the digits
     * off the names that end in them -- "Blazed562" was stored as "Blazed", and "una116" and
     * "champ4u" were one rule away from the same. The badge is its own token with a space in front
     * of it ("CrisdeuS 7"); digits that are part of the name are not.
     */
    private static final Pattern NAME_TRAILING_BADGE =
            Pattern.compile("\\s+\\p{N}+$");

    /** Strips the reader's invented characters and collapses the whitespace they leave behind. */
    /**
     * The VIP badge, when the sender line bled into the body.
     *
     * <p>The badge is drawn immediately left of the name, and a segment boundary a few pixels out
     * carries it into the message instead. It is already parsed off the sender line, so in the body
     * it is not information -- it is the same fact in the wrong place, reading as though the player
     * had typed their own rank into what they said.
     */
    private static final Pattern LEAKED_VIP = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}])V[IiLl1|]{1,2}P\\s?\\d{1,2}(?![\\p{L}\\p{N}])");

    public static String cleanBody(String raw) {
        if (raw == null) {
            return "";
        }
        // Put back the two characters that are real text before anything is stripped.
        String restored = MISREAD_AT.matcher(
                MISREAD_I.matcher(raw).replaceAll("I")).replaceAll("@");
        String body = collapse(TRANSLATE_CONTROL.matcher(
                ARTIFACTS.matcher(restored).replaceAll(" ")).replaceAll(" "));
        body = collapse(HELP_NOTICE.matcher(body).replaceAll(" "));
        body = collapse(FEEDBACK_BUTTON.matcher(body).replaceAll(" "));
        return trimOrphanGlyphs(collapse(LEAKED_VIP.matcher(body).replaceAll(" ")));
    }

    /**
     * The report control the game draws under a message, caught as part of the message.
     *
     * <p>It sits at the tail of the bubble rather than beside it, so unlike the translate control
     * it is not an orphan glyph the edge-trimmer catches -- it is a whole English word, and it
     * arrives welded to the last sentence: "How did you guys do in the Frozen Mine event?
     * Feedback". Matched only at the end and only standing alone, because somebody asking the
     * alliance for feedback is saying a real thing.
     */
    /**
     * The alliance-help card the game posts when somebody speeds your build up.
     *
     * <p>It is drawn in the feed like a message and read as one, and when it lands in the same
     * region as real chat the two arrive welded together: "gave your Lv.27 Coal Mine a
     * construction Speedup! 20/48 @SvetLana muito tedio" is a notification with somebody's actual
     * message stuck to the end of it -- filed under the wrong author, because the name on the card
     * is whoever received the help rather than whoever spoke.
     *
     * <p>Removed rather than the whole line dropped, so the message riding on the back of it
     * survives. When the card was all there was, nothing is left and the line is discarded as
     * unreadable, which is what should happen to it anyway.
     *
     * <p>The middle is matched without allowing an @, so it cannot eat past the notification and
     * into the mention that begins the real message.
     */
    private static final Pattern HELP_NOTICE = Pattern.compile(
            "(?i)\\bgave your\\b[^@]{0,60}?\\bspeed\\s?ups?!?(\\s*\\d{1,3}\\s*/\\s*\\d{1,3})?");

    private static final Pattern FEEDBACK_BUTTON =
            Pattern.compile("(?i)(?<=[\\p{L}\\p{N}?!.,\\s])\\s+Feedback\\s*$");

    /**
     * Strips the single stray glyph the bubble's own edges leave at each end of a message.
     *
     * <p>The bubble border on the left and the per-message translate control on the right both fall
     * inside the read, and both survive as one orphan character: measured over 310 stored messages,
     * 123 of them carried one. It is what makes an otherwise readable line look like wreckage --
     * "Pero ahora somos pocos conectados" arrives as "ç Pero ahora somos pocos conectados", and
     * "jaaj" as "jaaj E".
     *
     * <p>Only shapes no message begins or ends with are removed. A leading letter is deliberately
     * left alone even though it is often junk, because "Y" and "y" open Spanish sentences and
     * "e" opens Portuguese ones, and deleting a real word to tidy a stray one is the worse trade.
     */
    private static String trimOrphanGlyphs(String body) {
        String out = body;
        for (int i = 0; i < 2; i++) {
            out = LEADING_ORPHAN.matcher(out).replaceAll("");
            out = LEADING_LONE_LETTER.matcher(out).replaceAll("");
        }
        out = TRAILING_ORPHAN.matcher(out).replaceAll("");
        out = TRAILING_LOOSE_CAPS.matcher(out).replaceAll("");
        out = TRAILING_GLYPH_WITH_PUNCT.matcher(out).replaceAll("");
        if (out.trim().split("\\s+").length >= WORDS_BEFORE_TRIMMING_A_LETTER) {
            out = TRAILING_LONE_LETTER.matcher(out).replaceAll("");
        }
        return out.strip();
    }

    /** One or two symbols before the first word: the bubble's left edge, never punctuation a
     *  player opens with, so @ # [ and ( are excluded. */
    private static final Pattern LEADING_ORPHAN =
            Pattern.compile("^[^\\w\\s@#\\[(]{1,2}\\s+");

    /** Symbols hanging off the end, where the translate control sits. */
    private static final Pattern TRAILING_ORPHAN =
            Pattern.compile("\\s+[^\\w\\s.!?)\\]]{1,3}\\s*$");

    /** The control also reads as one or two loose capitals, most often "E". */
    private static final Pattern TRAILING_LOOSE_CAPS =
            Pattern.compile("\\s+[A-Z]{1,2}\\s*$");

    /**
     * A single letter left hanging on the end of a sentence.
     *
     * <p>The controls and icons drawn at the end of a bubble come back as one stray character, and
     * which one depends on the icon: "En la 2 no te veo" arrived as "En la 2 no te veo q". Only
     * removed when the message has enough words to be a sentence, so a genuinely one-word reply is
     * never emptied, and never when the letter is a word in its own right -- "a", "y", "o" and "e"
     * all end real sentences in Spanish and Portuguese.
     */
    private static final Pattern TRAILING_LONE_LETTER =
            Pattern.compile("\\s+(?![aeouyiAEOUYI]\\b)\\p{L}\\s*$");

    /**
     * A single letter carrying punctuation, stranded on the end.
     *
     * <p>The lone-letter rule above deliberately spares "a", "e", "y" and "o", because all four end
     * real sentences in Spanish and Portuguese and deleting a word to tidy a stray one is the worse
     * trade. That sparing is what left "En la 2 no te veo e," in the transcript: the "e" is the
     * bubble's tail ornament, read as a letter and given a comma by the border beneath it. A real
     * sentence does not end on a bare letter with punctuation stuck to it, so this catches the
     * ornament without touching the words the other rule protects.
     */
    private static final Pattern TRAILING_GLYPH_WITH_PUNCT =
            Pattern.compile("\\s+\\p{L}[.,;:]+\\s*$");

    /** Below this a message is too short to risk taking a character off the end of. */
    private static final int WORDS_BEFORE_TRIMMING_A_LETTER = 3;

    /**
     * A key that holds two readings of the same message together.
     *
     * <p>Overlapping scroll steps mean a message is read on two or three screens, and the reader
     * does not return the same characters every time: "able to" comes back as "ableto", a line
     * break lands in a different place, an accent survives on one pass and not the next. Keyed on
     * the text as written, those are different messages and the transcript keeps them all --
     * measured at 129 near-duplicates in a single day's capture. Reduced to letters and digits
     * alone they are one message, which is what they are.
     */
    public static String mergeKey(String body) {
        if (body == null) {
            return "";
        }
        // The mention is dropped, not folded in. The game draws it as a pill rather than as words,
        // and the reader does not always place it where it sat last time: the same alliance notice
        // came back as "@AI para finalizar el dia recuerden actualizar" on one pass and as
        // "para finalizar el dia recuerden @AI actualizar" on the next. Keyed with the pill inline
        // those are two different messages, and the transcript kept both -- the same notice three
        // times across one evening. The words are what identify a message; who it was aimed at is
        // carried separately.
        String words = MENTION_PILL.matcher(body).replaceAll(" ");
        StringBuilder sb = new StringBuilder(words.length());
        for (int i = 0; i < words.length(); i++) {
            char c = Character.toLowerCase(words.charAt(i));
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** An @mention as the game draws it, including the ones the reader spells wrong (@AIl, @AI). */
    private static final Pattern MENTION_PILL =
            Pattern.compile("@[A-Za-z0-9_.-]+");

    /**
     * Whether two bodies are the same message read twice, allowing for what changed between reads.
     *
     * <p>{@link #mergeKey} catches a re-read only when it comes back character for character the
     * same after punctuation is dropped, and across overlapping screens it usually does not. Two
     * things break it. A long message is clipped by the bottom of the screen on one pass and whole
     * on the next, so one key is a prefix of the other and neither matches. And the reader drops
     * fragments of the bubble's furniture into the middle of a line -- "de la fundicion de la
     * legion 2" on one screen, "de la fundicion q a de la legion 2" on the next -- so the keys
     * differ in the middle. Measured over five consecutive live alliance screens, this left 20
     * entries standing for 13 actual messages: every one of the seven extras was one of these two
     * cases, including a truncated copy of the alliance's longest message sitting beside the whole
     * one.
     *
     * <p>Both are containment, not equality, so that is what this tests: the share of the shorter
     * body's four-character runs that also occur in the longer one. Truncation leaves that share
     * near 1 because the clipped copy is entirely inside the whole one, and inserted noise leaves
     * it near 1 because a few junk characters spoil only the runs they touch. Two genuinely
     * different messages score far below the threshold -- the pairs measured here landed at 0.11
     * or less, against 0.85 and up for every real re-read.
     *
     * <p>Short bodies are left to exact matching. Over a handful of characters the runs are too few
     * for the share to mean anything, and "congrats" would start swallowing "congrats!" from
     * somebody else -- which is a different message even though it reads the same.
     */
    public static boolean sameMessage(String keyA, String keyB) {
        if (keyA.equals(keyB)) {
            return true;
        }
        String shorter = keyA.length() <= keyB.length() ? keyA : keyB;
        String longer = shorter == keyA ? keyB : keyA;
        // Below the run threshold, the only re-read worth catching is one that picked up a stray
        // character off the end of the bubble -- "y congrats" against "y congrats e". Testing that
        // as a prefix rather than by shared runs keeps "congrats" from matching the "Congrats!"
        // inside somebody else's longer message, which shares almost all of its runs.
        if (shorter.length() < MIN_LENGTH_FOR_FUZZY_MERGE) {
            return shorter.length() >= MIN_LENGTH_FOR_PREFIX_MERGE
                    && longer.length() - shorter.length() <= TRAILING_ARTIFACT_SLACK
                    && longer.startsWith(shorter);
        }
        java.util.Set<String> longRuns = runsOf(longer);
        java.util.Set<String> shortRuns = runsOf(shorter);
        if (shortRuns.isEmpty()) {
            return false;
        }
        int shared = 0;
        for (String run : shortRuns) {
            if (longRuns.contains(run)) {
                shared++;
            }
        }
        return shared / (double) shortRuns.size() >= SAME_MESSAGE_SHARE;
    }

    /** Below this a body has too few runs for the shared-run share to say anything. */
    private static final int MIN_LENGTH_FOR_FUZZY_MERGE = 16;
    /**
     * The share of the shorter body that has to occur in the longer one.
     *
     * <p>Measured on the five-screen set this was built against. Real re-reads of the same message
     * scored 0.60, 0.92 and 1.00 -- the 0.60 being the alliance's longest message, clipped by the
     * screen edge on one pass, whose surviving tail the reader had also badly mangled. Pairs that
     * were genuinely different messages scored 0.00, 0.00 and 0.12. Anywhere in the gap separates
     * them; this sits in the middle of it rather than at either edge, so neither a worse reading of
     * a re-read nor a closer pair of real messages lands on the boundary.
     */
    private static final double SAME_MESSAGE_SHARE = 0.40;
    /** Short bodies still merge on an exact prefix; below this even that is noise. */
    private static final int MIN_LENGTH_FOR_PREFIX_MERGE = 6;
    /** How much a trailing artifact is allowed to add to a short body. */
    private static final int TRAILING_ARTIFACT_SLACK = 3;
    private static final int MERGE_RUN_LENGTH = 4;

    /**
     * The runs a key is made of, for anything that needs to index by them.
     *
     * <p>Exposed because comparing one message against every message already stored is quadratic
     * and gets slow at a few thousand: a caller holding many keys wants to look up the handful that
     * could possibly match rather than testing them all. The runs are exactly that lookup.
     */
    public static java.util.Set<String> mergeRuns(String key) {
        return runsOf(key);
    }

    private static java.util.Set<String> runsOf(String key) {
        java.util.Set<String> runs = new java.util.HashSet<>();
        for (int i = 0; i + MERGE_RUN_LENGTH <= key.length(); i++) {
            runs.add(key.substring(i, i + MERGE_RUN_LENGTH));
        }
        return runs;
    }

    /**
     * Repairs a mention the reader mangled, using the names the pass has already seen.
     *
     * <p>The "@" is the single worst glyph in the feed. The same two characters came back as "yy",
     * "GA", "(D" and "©" on one afternoon's captures, and whatever the reader makes of them it
     * fuses onto the name behind: {@code @Maki felicidades!} arrived as "yy Maki felicidades!" and
     * the message read as somebody called Maki being congratulated rather than congratulating.
     *
     * <p>An earlier attempt read the mention out of the pixels instead, on the grounds that the
     * game colours a mention and colour is not something Tesseract can mangle. That part was true
     * -- measured on live frames a mention scores 0.60 to 0.69 saturation against 0.02 to 0.04 for
     * body text, which is not a close call -- but it could not be anchored. The run has to be the
     * first thing on the line to be told apart from a coloured emoji mid-sentence, and the line's
     * own left edge is not where its text starts: bubble furniture is read as text, so the box
     * began 40px left of the "@" and the anchor missed every real mention while matching whole
     * lines of system-card text. The colour is real and the geometry to use it is not there.
     *
     * <p>So this uses what the pass already knows. Every sender line names a member of the
     * alliance, so by the end of a screen the reader is holding the roster the mentions are drawn
     * from, and a mangled mention is a known name with junk in front of it. That junk is required:
     * a name at the head of a sentence with nothing before it is somebody being talked about, not
     * somebody being addressed, and rewriting it would invent a mention that was never there.
     */
    public static String repairLeadingMention(String body, Collection<String> roster) {
        if (body == null || body.isEmpty() || roster.isEmpty()) {
            return body;
        }
        if (body.charAt(0) == '@') {
            return body;
        }
        int limit = Math.min(body.length(), MENTION_SEARCH_WINDOW);
        String head = body.substring(0, limit);
        String best = null;
        int at = -1;
        for (String name : roster) {
            if (name.length() < MIN_NAME_FOR_MENTION_REPAIR) {
                continue;
            }
            int i = indexOfIgnoreCase(head, name);
            // Only a name that something precedes. At position zero there is no mangled "@" to
            // explain, and the sentence is about them rather than to them.
            if (i > 0 && (at < 0 || i < at) && isJunk(body.substring(0, i))) {
                at = i;
                best = name;
            }
        }
        if (best == null) {
            return body;
        }
        return "@" + body.substring(at, at + best.length()) + body.substring(at + best.length());
    }

    /**
     * Whether everything before the name is the wreckage of an "@" rather than words.
     *
     * <p>Short and unword-like is the test, because that is what a misread "@" looks like: one or
     * two letters, or a bracket, or a stray symbol. Anything longer is a sentence, and a name
     * inside a sentence is not a mention.
     */
    private static boolean isJunk(String before) {
        String trimmed = before.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (trimmed.length() > MAX_MANGLED_AT_LENGTH) {
            return false;
        }
        long letters = trimmed.chars().filter(Character::isLetter).count();
        return letters <= MAX_MANGLED_AT_LETTERS;
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase(java.util.Locale.ROOT)
                .indexOf(needle.toLowerCase(java.util.Locale.ROOT));
    }

    /** How far into a message a mangled mention can still be sitting. */
    private static final int MENTION_SEARCH_WINDOW = 24;
    /** Shorter names match inside ordinary words and are not worth the false rewrites. */
    private static final int MIN_NAME_FOR_MENTION_REPAIR = 4;
    /** A misread "@" is a character or two, never a word. */
    private static final int MAX_MANGLED_AT_LENGTH = 4;
    private static final int MAX_MANGLED_AT_LETTERS = 2;

    /** Names this message addressed, in order, without duplicates. */
    public static List<String> mentions(String body) {
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher m = MENTION.matcher(body);
        while (m.find()) {
            String name = m.group(1).trim();
            if (isPlausibleName(name)) {
                found.add(name);
            }
        }
        return new ArrayList<>(found);
    }

    /** Decides what the bubble actually held, so the renderer can show it honestly. */
    public static ChatMessage.Kind classify(String cleanedBody) {
        if (cleanedBody == null || cleanedBody.isBlank()) {
            return ChatMessage.Kind.UNREADABLE;
        }
        if (SYSTEM_CARD.matcher(cleanedBody).find() || VOTE_BUTTON.matcher(cleanedBody).matches()) {
            return ChatMessage.Kind.SYSTEM;
        }
        if (EMOJI_ONLY.matcher(cleanedBody).matches()) {
            return ChatMessage.Kind.EMOJI;
        }
        // Under three letters there is nothing a reader or a translator can use, whatever the
        // reader emitted. Treating it as text puts noise in the transcript and burns a lookup.
        long letters = cleanedBody.chars().filter(Character::isLetter).count();
        return letters >= 3 ? ChatMessage.Kind.TEXT : ChatMessage.Kind.UNREADABLE;
    }

    /**
     * Whether the body already reads as English, decided locally so the common case never leaves
     * the machine.
     *
     * <p>The first version compared how much of the text was ASCII, which is not a language test at
     * all. Turkish and Spanish are written in plain ASCII, so "bana nazik bir sekilde maymun dedi"
     * scored as confidently English and was never translated. On a live pass that rule called every
     * one of the foreign messages English -- the translation feature had a 100% miss rate while
     * appearing to work.
     *
     * <p>What actually separates English from other Latin-script languages is its function words.
     * A message carrying none of them is not English regardless of its alphabet, and one carrying
     * several is, so the decision keys on those instead. Any non-Latin script still settles it
     * outright.
     *
     * <p>A short message is left alone: two words are not enough evidence either way, and sending
     * them all for translation would spend lookups on "one day" and "thanks bro".
     */
    public static boolean looksEnglish(String body) {
        if (body == null || body.isBlank()) {
            return true;
        }
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (!Character.isLetter(c) || c < 128) {
                continue;
            }
            // Any non-Latin script settles it outright. So does an accented Latin letter: English
            // effectively does not use them, so a single one is stronger evidence than a word count
            // -- and it catches short messages the word test is too small to judge.
            return false;
        }

        java.util.List<String> words = new ArrayList<>();
        Matcher m = WORDS.matcher(body.toLowerCase(Locale.ROOT));
        while (m.find()) {
            words.add(m.group());
        }
        // Counted against English's own structure rather than its vocabulary. The wider list holds
        // the game's jargon -- "rally", "gg", "join", "ready", "afk" -- and every language in the
        // alliance borrows those verbatim, so they are evidence of playing this game, not of
        // writing English. "Llenen los rally abiertos" is four Spanish words and one loanword, and
        // that loanword was enough to call it English and never translate it.
        long known = words.stream().filter(ENGLISH_STRUCTURE::contains).count();
        // Under the ratio's reach, English has to be shown rather than assumed. Returning true here
        // -- which is what this did -- meant no short foreign message was ever translated: measured
        // against a live day's transcript, "Hay evento horita ?", "Felicidades!" and "Ola" were all
        // called English and never sent, while the longer Spanish around them came back correctly.
        // The accent that would have settled it is exactly what the reader drops, so "Olá" arrives
        // as pure ASCII "Ola" and falls in here too.
        if (words.size() < MIN_WORDS_TO_JUDGE) {
            // Two, not one. A single structural word can turn up by chance in another language --
            // Dutch and German share several outright -- and the cost of the two calls is not
            // symmetric. Sending English text returns it unchanged and is discarded a line below;
            // failing to send foreign text leaves it in the transcript in a language the reader
            // does not speak, which is the one thing the translation is there to prevent.
            return known >= (words.size() <= TINY_MESSAGE_WORDS
                    ? MIN_ENGLISH_WORDS_WHEN_TINY : MIN_ENGLISH_WORDS_WHEN_SHORT);
        }
        return (known / (double) words.size()) >= ENGLISH_WORD_RATIO;
    }

    /** What a bubble held once the quoted reply is taken off it. */
    public record Body(String own, String quoted) {
    }

    /**
     * Separates the sender's own words from the message they were replying to.
     *
     * <p>Both are read as one region, so without this the transcript attributes another player's
     * sentence to whoever quoted it. The quote is rendered last, so the final boundary is the
     * right one -- taking the first match splits on a name mentioned mid-sentence instead.
     */
    public static Body splitQuotedReply(String body) {
        if (body == null || body.isBlank()) {
            return new Body("", "");
        }
        int start = quoteStart(body);
        if (start < 0) {
            return new Body(collapse(body), "");
        }
        return new Body(collapse(body.substring(0, start)), collapse(body.substring(start)));
    }

    /**
     * Where the quoted original begins, or -1 when the bubble is not a reply.
     *
     * <p>Found by locating the colon and then stepping back over the name, rather than by matching
     * a name pattern forwards. Matching forwards is what broke it: the name could run to three
     * words, so in "...trade them in for resource pouches Nicko: have so many..." the earliest
     * position that could satisfy the pattern was "resource", and the split handed "resource
     * pouches" to the quote. The sender lost the end of their own sentence, on 116 of 279 replies.
     *
     * <p>Stepping back from the colon cannot do that. A name is one word, or two for the handful of
     * players who have a space in theirs, so at most two are taken and everything before them stays
     * with the message.
     */
    private static int quoteStart(String body) {
        int found = -1;
        for (int colon = body.indexOf(':'); colon >= 0; colon = body.indexOf(':', colon + 1)) {
            // The game writes "Name: text", so a colon with no space after it is punctuation
            // inside a sentence, or a timestamp.
            if (colon + 1 >= body.length() || !Character.isWhitespace(body.charAt(colon + 1))) {
                continue;
            }
            int nameStart = walkBackOverName(body, colon);
            if (nameStart < 0) {
                continue;
            }
            // A quote with nothing before it is the whole bubble, not a reply.
            if (!body.substring(0, nameStart).isBlank()) {
                found = nameStart;
            }
        }
        return found;
    }

    /** Start of the one or two words immediately before {@code colon}, or -1 if they are not a name. */
    private static int walkBackOverName(String body, int colon) {
        int end = colon;
        int start = -1;
        for (int words = 0; words < MAX_WORDS_IN_A_QUOTED_NAME; words++) {
            int wordEnd = end;
            while (wordEnd > 0 && Character.isWhitespace(body.charAt(wordEnd - 1))) {
                wordEnd--;
            }
            int wordStart = wordEnd;
            while (wordStart > 0 && !Character.isWhitespace(body.charAt(wordStart - 1))) {
                wordStart--;
            }
            if (wordStart == wordEnd) {
                break;
            }
            String word = body.substring(wordStart, wordEnd);
            if (!isNameWord(word)) {
                break;
            }
            start = wordStart;
            end = wordStart;
            // One word is the normal case; only keep going for names that really do carry a space.
            if (words == 0 && !startsLikeContinuedName(body, wordStart)) {
                break;
            }
        }
        return start;
    }

    /** Player names are one word, or two for the few that carry a space. */
    private static final int MAX_WORDS_IN_A_QUOTED_NAME = 2;

    /**
     * Whether a word could be part of a player name rather than ordinary sentence text.
     *
     * <p>Deliberately tolerant of noise. A reply carries the message it answers inside the same
     * bubble as "Name: text", and that name is the only thing marking where the quote begins -- but
     * it is also the part of the bubble the reader mangles most, being small and often over
     * decoration. Requiring a clean set of characters meant "CrisdeuS:" read as "Сг!з4еи$:" and
     * "AthenaRyu:" read as "А{ПепаВуи:" were not recognised as names, the split never happened, and
     * the quoted message stayed glued to the reply. Measured over a live evening, 35 of 187 stored
     * messages carried somebody else's words that way.
     *
     * <p>So the test is the shape rather than the alphabet: name-length, starting with a letter,
     * and mostly made of letters and digits. What is left over is the reader's invention, and a
     * couple of stray brackets in the middle of a name does not stop it being one.
     */
    private static boolean isNameWord(String word) {
        String bare = word.startsWith("[") && word.contains("]")
                ? word.substring(word.indexOf(']') + 1) : word;
        if (bare.isEmpty() || bare.length() > 18 || !Character.isLetter(bare.charAt(0))) {
            return false;
        }
        long solid = bare.chars().filter(c -> Character.isLetterOrDigit(c)
                || c == '_' || c == '\'' || c == '.' || c == '-' || c == '!').count();
        long letters = bare.chars().filter(Character::isLetter).count();
        return letters >= LETTERS_TO_BE_A_NAME
                && solid / (double) bare.length() >= NAME_SOLID_SHARE;
    }

    /** A name has some letters in it, however badly they were read. */
    private static final int LETTERS_TO_BE_A_NAME = 3;
    /** The share of a name that has to be ordinary characters rather than the reader's invention. */
    private static final double NAME_SOLID_SHARE = 0.6;

    /**
     * Whether the word before this one also looks like part of the same name.
     *
     * <p>Only capitalised, so "Mini TyTy" is taken as one name while "for resource" is not -- the
     * second word of a two-word player name is written as a name, not as sentence text.
     */
    private static boolean startsLikeContinuedName(String body, int wordStart) {
        int end = wordStart;
        while (end > 0 && Character.isWhitespace(body.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && !Character.isWhitespace(body.charAt(start - 1))) {
            start--;
        }
        if (start == end) {
            return false;
        }
        String previous = body.substring(start, end);
        return isNameWord(previous) && Character.isUpperCase(previous.charAt(0));
    }

    /**
     * True when a body is actually a sender line the row split failed to separate.
     *
     * <p>The name sits on the first line of the row, so a missing line break sends it downstream as
     * the message. It shows up as a bubble containing nothing but a tag and a name -- "64
     * [HOD]TheFlyingDutch" -- which is not something anyone said.
     */
    /** A VIP badge with its digit misread: "VIPS" for VIP5, "VIPO" for VIP0. */
    private static final Pattern MANGLED_VIP =
            Pattern.compile("(?i)\\bVIP\\s*[0-9SOl]?\\b");
    /** A stray sender line is a badge, a tag and a name -- never a sentence. */
    private static final int MAX_WORDS_IN_A_SENDER_REMNANT = 3;

    public static boolean looksLikeSenderLine(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        // A VIP badge the reader did not get cleanly still marks the row as a name. "VIP5" comes
        // back as "VIPS" when the 5 is read as an S, which the badge parser does not recognise, so
        // tsubomi's sender line was stored as a message reading "FF VIPS" -- and being a row with a
        // gold badge on it, the badge was then rewritten as a mention of the whole alliance.
        if (MANGLED_VIP.matcher(body).find()
                && WORDS.matcher(body).results().count() <= MAX_WORDS_IN_A_SENDER_REMNANT) {
            return true;
        }
        Sender s = parseSender(body);
        // The giveaway is the decoration, not the name: a stray sender line still carries its
        // alliance tag or VIP badge, and no message is only that. Keying on "does this parse as a
        // name" instead would match almost any short sentence, since the parser will happily read
        // one as a name.
        if (s.allianceTag().isEmpty() && s.vipLevel() == 0) {
            return false;
        }
        long words = WORDS.matcher(s.name()).results().count();
        return words <= MAX_WORDS_IN_A_NAME;
    }

    /** A player name runs to a few words; a sentence does not stop there. */
    /** Some players carry a space or two in their name; none of them carry a sentence. */
    private static final int MAX_WORDS_IN_A_NAME = 3;

    /** True when the line is a game event rather than something a player said. */
    public static boolean isNonSpeech(String body) {
        return body != null && NON_SPEECH.matcher(body).find();
    }

    /** Lowercased key for caching a translation, so the same phrase is only ever fetched once. */
    public static String cacheKey(String body) {
        return collapse(body == null ? "" : body).toLowerCase(Locale.ROOT);
    }

    private static String collapse(String s) {
        return REPEATED_SPACE.matcher(s.trim()).replaceAll(" ").trim();
    }
}
