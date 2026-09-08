package dev.frostguard.api.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Throws away what the reader could not actually read.
 *
 * <p>Chat is rendered over the game's artwork at a size the game never meant to be scraped, so a
 * proportion of every pass comes back as wreckage: a whole message that reduced to {@code
 * "SOreteict. 2"}, or a good sentence with a torn fragment of the bubble above it stuck to the
 * front. Storing that is worse than storing nothing. A reader scanning the transcript cannot tell
 * a misread from a player who types strangely, so every fragment quietly costs them trust in the
 * lines either side of it.
 *
 * <p>The judgement here is deliberately structural rather than a dictionary. The transcript carries
 * a dozen languages, alliance jargon, and player names that no word list would contain, so anything
 * checking words against a list would throw away more real messages than misread ones. What can be
 * said without knowing the language is how a token is *shaped*: whether it mixes scripts, whether
 * its capitals fall where writing puts them, whether it is a lone letter left over from a glyph
 * that half-read. Those hold whether the message is English, Spanish or Indonesian.
 */
public final class ChatGarble {

    private ChatGarble() {
    }

    /**
     * A message reduced to what was legibly read, or empty when that is nothing.
     *
     * <p>Empty means the caller should drop the message rather than store it: what came back was
     * not a short message, it was a failure to read one.
     */
    public static String repair(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        List<String> tokens = new ArrayList<>(List.of(WHITESPACE.split(normalise(body).strip())));
        stripTagPrefix(tokens);
        dropDuplicateMention(tokens);
        dropForeignRuns(tokens);
        trimEnd(tokens, true);
        trimEnd(tokens, false);
        dropTrailingScraps(tokens);
        if (tokens.isEmpty() || !worthKeeping(tokens)) {
            return "";
        }
        return String.join(" ", tokens).strip();
    }

    /**
     * Folds full-width punctuation onto its ASCII twin.
     *
     * <p>The reader emits these when it meets the CJK glyphs the game draws around a quoted strip,
     * and they are the same marks -- a colon is a colon. Left as they are they defeat every test
     * downstream that looks for a colon or a full stop, so a quote reading "wind:," parses as a
     * name with no punctuation in it rather than as a name followed by nothing.
     */
    public static String normalise(String body) {
        return body.replace('：', ':').replace('，', ',').replace('。', '.')
                .replace('！', '!').replace('？', '?').replace('．', '.');
    }

    /** True when nothing legible survived, so the message should not be stored at all. */
    public static boolean isGarbled(String body) {
        return repair(body).isEmpty();
    }

    /**
     * Drops the {@code [ABC]} alliance label the game prints against a name.
     *
     * <p>It is on the sender line already, so in the body it is duplication -- and it is duplication
     * that half-reads, which is where {@code "[INFDOOX!"} came from: the tag ran into the first word
     * of the message and the pair became one unreadable token.
     */
    private static void stripTagPrefix(List<String> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            String t = TAG_PREFIX.matcher(tokens.get(i)).replaceFirst("");
            if (t.isBlank()) {
                tokens.remove(i--);
            } else {
                tokens.set(i, t);
            }
        }
    }

    /**
     * Collapses the same name mentioned twice at the start of a message.
     *
     * <p>The game draws a reply's mention as a coloured pill, and the reader sees both the pill and
     * the plain text inside it -- so a message addressed to one person arrives naming them twice,
     * usually with a scrap of the pill's edge stranded between the two. One person addressed once
     * is what the player actually typed.
     */
    private static void dropDuplicateMention(List<String> tokens) {
        for (int i = 0; i < tokens.size() - 1 && i < 2; i++) {
            if (!tokens.get(i).startsWith("@")) {
                continue;
            }
            for (int j = i + 1; j < tokens.size() && j <= i + 2; j++) {
                if (tokens.get(j).equalsIgnoreCase(tokens.get(i))) {
                    // Everything from the first mention up to the second goes, keeping the second:
                    // whatever sat between them was the pill's edge, not a word.
                    tokens.subList(i, j).clear();
                    return;
                }
            }
        }
    }

    /**
     * Drops a tail of tiny fragments hanging off a finished sentence.
     *
     * <p>A message that ends in a full stop has ended. What follows is the top of the next bubble,
     * caught by a crop that does not know where one message stops -- and it arrives as two or three
     * one- and two-letter pieces, each individually shaped enough like a word to survive every
     * other test here. Three of them in a row after a full stop is not a sentence.
     */
    private static void dropTrailingScraps(List<String> tokens) {
        int end = -1;
        for (int i = tokens.size() - 1; i >= 0; i--) {
            String t = tokens.get(i);
            if (t.endsWith(".") || t.endsWith("!") || t.endsWith("?")) {
                end = i;
                break;
            }
        }
        if (end < 0 || tokens.size() - end - 1 < MIN_SCRAP_RUN) {
            return;
        }
        for (int i = end + 1; i < tokens.size(); i++) {
            if (strip(tokens.get(i)).length() > SCRAP_LENGTH) {
                return;
            }
        }
        tokens.subList(end + 1, tokens.size()).clear();
    }

    /** A word this short at the end of a finished sentence is a fragment of the next one. */
    private static final int SCRAP_LENGTH = 3;
    private static final int MIN_SCRAP_RUN = 3;

    /**
     * Cuts out a run written in a script the rest of the message is not in.
     *
     * <p>This is not a message that switched language mid-sentence. It is one script's reader
     * having been handed another script's pixels: Latin text read by a Cyrillic model comes back as
     * Cyrillic letters in the shape of the English words underneath, which is unreadable in either
     * language. A run of it inside an otherwise Latin message is the reader's mistake, not the
     * player's, so it goes.
     */
    private static void dropForeignRuns(List<String> tokens) {
        int latin = 0;
        int cyrillic = 0;
        for (String t : tokens) {
            latin += count(t, ChatGarble::isLatin);
            cyrillic += count(t, ChatGarble::isCyrillic);
        }
        if (latin == 0 || cyrillic == 0 || cyrillic > latin) {
            return;
        }
        tokens.removeIf(t -> count(t, ChatGarble::isCyrillic) > count(t, ChatGarble::isLatin));
    }

    /**
     * Eats the wreckage off one end of a message.
     *
     * <p>A whole run goes at once rather than a token at a time. Torn fragments come back as
     * several pieces with the odd readable-looking scrap among them -- {@code "j I aha 2"} at the
     * end of a finished sentence -- and stopping at the first thing that resembles a word leaves
     * the scrap sitting there looking like the player typed it. If most of a short run at the edge
     * is unreadable, none of it was really read.
     */
    private static void trimEnd(List<String> tokens, boolean fromFront) {
        while (!tokens.isEmpty()) {
            int run = Math.min(RUN_WINDOW, tokens.size());
            int bad = 0;
            int lastBad = -1;
            for (int i = 0; i < run; i++) {
                int at = fromFront ? i : tokens.size() - 1 - i;
                if (!readable(tokens.get(at))) {
                    bad++;
                    lastBad = i;
                }
            }
            // One bad token at the very edge is trimmed on its own; a scrap further in only goes
            // when the run around it is mostly wreckage too.
            int edge = fromFront ? 0 : tokens.size() - 1;
            if (!fromFront && bad == 1 && isNumber(tokens.get(edge))
                    && countReadable(tokens) >= WORDS_THAT_MAKE_A_SENTENCE) {
                // "i have never seen furnace 31" ends in a number because the number is the point
                // of it. A bare number is noise on its own and content at the end of a sentence,
                // and the sentence is what tells the two apart -- trimmed blindly, this quietly
                // changed what people said rather than merely tidying it.
                return;
            }
            int cut = bad * 2 > run ? lastBad + 1 : (readable(tokens.get(edge)) ? 0 : 1);
            if (cut == 0) {
                return;
            }
            for (int i = 0; i < cut; i++) {
                tokens.remove(fromFront ? 0 : tokens.size() - 1);
            }
        }
    }

    /** How far in from an edge a piece of wreckage can still drag its neighbours out with it. */
    private static final int RUN_WINDOW = 4;

    /** Enough words that a number after them is being said about something. */
    private static final int WORDS_THAT_MAKE_A_SENTENCE = 4;

    private static boolean isNumber(String token) {
        String bare = token.replaceAll("[^\\p{N}]", "");
        return !bare.isEmpty() && token.replaceAll("[\\p{N}\\p{Punct}]", "").isEmpty();
    }

    private static int countReadable(List<String> tokens) {
        int n = 0;
        for (String t : tokens) {
            if (readable(t)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Whether enough survived to be somebody's words.
     *
     * <p>What is left after trimming can still be nothing: a mention with no message on it, or one
     * short function word that a torn line happened to contain. The test is for something
     * *contentful* -- a word carrying meaning rather than grammar -- because "is" surviving out of
     * {@code "is dk <.,"} does not make that a message anybody sent.
     */
    private static boolean worthKeeping(List<String> tokens) {
        int content = 0;
        int words = 0;
        for (String t : tokens) {
            if (!readable(t)) {
                continue;
            }
            String bare = strip(t).toLowerCase(java.util.Locale.ROOT);
            // Anywhere in the token, not just at the front: the reader puts the game's punctuation
            // in front of a mention often enough that ":@wind." arrives as one token, and naming
            // somebody is not the same as saying something to them either way.
            if (bare.isEmpty() || t.indexOf('@') >= 0) {
                continue;
            }
            words++;
            // "dk" is not a word in any of these languages, and neither is any other two-letter
            // consonant pair the reader hands back; the few short words players really type are
            // named rather than inferred.
            if (!FUNCTION_WORDS.contains(bare)
                    && (bare.length() >= 3 || SHORT_WORDS.contains(bare))) {
                content++;
            }
        }
        // Numbers and a name are a real message when there is a sentence around them, and noise
        // when there is not; the sentence is what "contentful" is measuring.
        return content > 0 && words >= 1;
    }

    /**
     * Whether a token looks like something somebody wrote, in any language.
     *
     * <p>Every test here is about shape, not vocabulary, so it holds across the languages the
     * alliance actually speaks.
     */
    private static boolean readable(String token) {
        String bare = strip(token);
        if (token.startsWith("@") && bare.length() >= 2) {
            return true;
        }
        if (isLink(token)) {
            // A link is not a word and none of the tests below describe one. Judged as text it
            // fails nearly all of them -- it has no spaces, so stripping its punctuation leaves a
            // thirty-letter run that reads as two words the reader ran together, and the whole
            // address gets trimmed off the front of the message as wreckage. Players post links
            // constantly, and half a link is worse than none: it looks like something you could
            // follow.
            return true;
        }
        if (bare.isEmpty()) {
            // Punctuation on its own, or a number that lost the sentence it belonged to.
            return false;
        }
        if (bare.length() == 1) {
            // "I" and "a" are words; a lone "q" or "j" is the surviving half of a glyph.
            return "iao".indexOf(Character.toLowerCase(bare.charAt(0))) >= 0;
        }
        if (bare.length() > LONGEST_PLAUSIBLE_WORD) {
            // Nobody types a nineteen-letter word into alliance chat. Two misread words that ran
            // together do come back looking like one.
            return false;
        }
        if (UNBALANCED.matcher(token).find()) {
            // A bracket that opens and never closes is the edge of a game card the crop cut
            // through, not punctuation a player typed.
            return false;
        }
        if (INNER_SYMBOL.matcher(bare).find()) {
            return false;
        }
        if (SHOUT_THEN_WHISPER.matcher(bare).find()) {
            // Capitals stop where a word starts. "SOreteict" is a reader sliding between cases
            // over a run of pixels it could not resolve.
            return false;
        }
        if (!hasVowel(bare) && bare.length() >= 3) {
            return false;
        }
        return !mixesScripts(bare);
    }

    /** Longer than this and it is two words the reader ran together, not one word. */
    private static final int LONGEST_PLAUSIBLE_WORD = 16;

    /** Whether this is an address rather than a word. */
    public static boolean looksLikeLink(String token) {
        return isLink(token);
    }

    private static boolean isLink(String token) {
        return LINK.matcher(token).find();
    }

    private static final Pattern LINK = Pattern.compile(
            "(?i)^(https?://|www\\.)|\\.(com|net|org|wiki|io|gg|be|ly|co)([/?#]|$)");

    private static String strip(String token) {
        return EDGE_PUNCTUATION.matcher(token).replaceAll("");
    }

    private static boolean hasVowel(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if ("aeiouy".indexOf(c) >= 0 || !isLatin(c)) {
                return true;
            }
        }
        return false;
    }

    /** One word is written in one alphabet; a token in two is a reader that changed its mind. */
    private static boolean mixesScripts(String s) {
        return count(s, ChatGarble::isLatin) > 0 && count(s, ChatGarble::isCyrillic) > 0;
    }

    private static int count(String s, java.util.function.IntPredicate of) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (of.test(s.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    private static boolean isLatin(int c) {
        return Character.isLetter(c) && Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN;
    }

    private static boolean isCyrillic(int c) {
        return Character.isLetter(c)
                && Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC;
    }

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** The alliance label the game prints in front of a name, however badly it read. */
    private static final Pattern TAG_PREFIX = Pattern.compile("^\\[[A-Za-z0-9]{2,4}\\]");

    /** Anything that is not a letter, a digit, or punctuation that lives inside words. */
    private static final Pattern EDGE_PUNCTUATION = Pattern.compile("[^\\p{L}]");

    /** A bracket or brace with no partner: the edge of something the crop cut through. */
    private static final Pattern UNBALANCED = Pattern.compile("[\\[{(](?![^\\]})]*[\\]})])|(?<![\\[{(][^\\[{(]{0,20})[\\]})]");

    /** A symbol sitting inside a word, where writing does not put one. */
    private static final Pattern INNER_SYMBOL = Pattern.compile("\\p{L}[@#$%^&*+=<>]\\p{L}");

    /** Two or more capitals running straight into lowercase, which writing does not do. */
    private static final Pattern SHOUT_THEN_WHISPER = Pattern.compile("\\p{Lu}{2,}\\p{Ll}");

    /**
     * Words that carry grammar rather than meaning.
     *
     * <p>A torn line often leaves one of these behind, and on its own it is not a message. Kept
     * short and English-only on purpose: this decides whether something is worth showing, and a
     * longer list would start throwing away real one-word replies in other languages.
     */
    private static final Set<String> FUNCTION_WORDS = Set.of(
            "a", "an", "the", "is", "it", "of", "to", "in", "on", "at", "as", "be", "by",
            "or", "and", "if", "so", "we", "he", "i", "my", "me", "do", "no");

    /**
     * Short words that really are words.
     *
     * <p>Named one by one because the alternative -- accepting every two-letter token -- accepts
     * every two-letter piece of wreckage as well, and those outnumber these.
     */
    private static final Set<String> SHORT_WORDS = Set.of(
            "ok", "gg", "ty", "np", "yo", "hi", "si", "ja", "da", "xd", "gm", "gn", "ez", "wp");
}
