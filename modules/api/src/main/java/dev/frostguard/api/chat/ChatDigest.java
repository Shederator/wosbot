package dev.frostguard.api.chat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a stretch of chat adds up to, worked out by counting rather than by reading.
 *
 * <p>Deliberately extractive. Writing a paragraph about a conversation needs a language model, and
 * the one that would fit beside this application is small enough to produce fluent, confident and
 * wrong accounts of what the alliance said -- which for a log people make decisions from is worse
 * than no account at all. Everything here is arithmetic over the messages, so it is either right or
 * obviously empty, and none of it can invent a rally that never happened.
 *
 * <p>What it answers is what gets asked of a chat log after the fact: who is actually talking, when
 * is the alliance awake, what was being coordinated, and what did somebody ask that nobody picked
 * up. Those are countable. "Summarise the mood" is not, and is not attempted.
 */
public final class ChatDigest {

    private ChatDigest() {
    }

    /** One name and how often it came up. */
    public record Tally(String name, int count) {
    }

    /** A question that went by without an answer. */
    public record Unanswered(Instant at, String who, String text) {
    }

    /** A map reference somebody called out. */
    public record Callout(Instant at, String who, String coordinates) {
    }

    /**
     * The whole digest for one window.
     *
     * @param messages    how many messages were counted
     * @param people      how many distinct people spoke
     * @param busiestHour the hour of the day with the most traffic, or -1 when there is none
     * @param perHour     traffic by hour of day, always 24 long, for drawing
     * @param voices      who spoke most, descending
     * @param pairs       who addressed whom, descending, as "A -> B"
     * @param topics      the game's vocabulary, by how often it came up
     * @param callouts    map references, newest last
     * @param unanswered  questions nobody replied to
     * @param arrived     names that appear in this window and not before it
     * @param wentQuiet   names that spoke before this window and not in it
     */
    public record Result(
            int messages,
            int people,
            int busiestHour,
            int[] perHour,
            List<Tally> voices,
            List<Tally> pairs,
            List<Tally> topics,
            List<Callout> callouts,
            List<Unanswered> unanswered,
            List<String> arrived,
            List<String> wentQuiet,
            boolean comparable) {

        /** Whether {@link #arrived} and {@link #wentQuiet} were worked out at all. */
        public boolean comparable() {
            return comparable;
        }
    }

    /** How much transcript has to sit behind a window before "new" and "quiet" mean anything. */
    private static final Duration HISTORY_BEFORE_COMPARING = Duration.ofHours(24);

    /**
     * The game's own vocabulary, which is what alliance chat is mostly about.
     *
     * <p>A fixed list rather than whatever words happen to be frequent. Frequency alone surfaces
     * "the" and "ok"; what somebody wants to know is whether Frostfire came up, and that only works
     * if the thing being counted is named in advance. Each entry matches the ways the reader tends
     * to spell it, because a topic that misses half its mentions is worse than one that is absent.
     */
    private static final Map<String, Pattern> TOPICS = new LinkedHashMap<>();

    static {
        topic("Bear Hunt", "ra[gq]ing bear|bear hunt|bear trap");
        topic("Rally", "rally|rallies|集结");
        topic("Frostfire Mine", "frostfire|frozen mine|mine event");
        topic("Hall of Chiefs", "hall of chiefs|\\bhoc\\b");
        topic("Labyrinth", "labyrinth|land of heroes|cave of monsters|charm mine");
        topic("Alliance Championship", "championship|\\bac\\b prep|troop ratio");
        topic("Canyon Clash", "canyon clash|\\bcc\\b");
        topic("Brothers in Arms", "brothers in arms|\\bbia\\b");
        topic("Arena", "arena");
        topic("Gathering", "gather|gathering|resource tile|\\bvein\\b");
        topic("Reinforcement", "reinforce|reinforcement|garrison");
        topic("Speedups", "speedup|speed up|accelerator");
        topic("Research", "research|tech tree");
        topic("Troop Promotion", "promote|promotion|\\bt9\\b|\\bt10\\b|\\bt11\\b");
        topic("Recruitment", "recruit|joining|applied|invite");
    }

    private static void topic(String label, String pattern) {
        TOPICS.put(label, Pattern.compile("(?i)(" + pattern + ")"));
    }

    /** A map reference as the game writes it, however the reader spaced it out. */
    private static final Pattern COORDINATES =
            Pattern.compile("(?i)X\\s*[:.]?\\s*(\\d{1,4})\\s*[,;]?\\s*Y\\s*[:.]?\\s*(\\d{1,4})");

    /**
     * Builds the digest for everything inside the window.
     *
     * @param all     every message available, in any order
     * @param window  how far back to look from {@code now}
     * @param now     the end of the window, passed in rather than read so this stays testable
     * @param zone    the zone whose clock the "busiest hour" is expressed in
     */
    public static Result of(List<ChatMessage> all, Duration window, Instant now, ZoneId zone) {
        Instant from = now.minus(window);
        Map<String, String> names = foldNames(all);
        List<ChatMessage> inside = new ArrayList<>();
        Set<String> spokeBefore = new HashSet<>();
        Instant earliest = null;
        for (ChatMessage m : all) {
            if (!m.isRenderable() || m.capturedAt() == null) {
                continue;
            }
            String who = speaker(m);
            if (who.isEmpty()) {
                continue;
            }
            if (earliest == null || m.capturedAt().isBefore(earliest)) {
                earliest = m.capturedAt();
            }
            if (m.capturedAt().isBefore(from)) {
                spokeBefore.add(names.getOrDefault(who, who));
            } else if (!m.capturedAt().isAfter(now)) {
                inside.add(m);
            }
        }
        inside.sort(Comparator.comparing(ChatMessage::capturedAt));

        int[] perHour = new int[24];
        Map<String, Integer> voices = new HashMap<>();
        Map<String, Integer> pairs = new HashMap<>();
        Map<String, Integer> topics = new LinkedHashMap<>();
        List<Callout> callouts = new ArrayList<>();
        Set<String> spokeInside = new HashSet<>();

        for (ChatMessage m : inside) {
            String who = names.getOrDefault(speaker(m), speaker(m));
            spokeInside.add(who);
            voices.merge(who, 1, Integer::sum);
            perHour[LocalDateTime.ofInstant(m.capturedAt(), zone).getHour()]++;

            for (String to : m.mentions()) {
                String target = to.replaceFirst("^@", "").trim();
                target = names.getOrDefault(target, target);
                // Broadcasts are not coordination between two people, and left in they swamp the
                // list: one officer's nightly @All would outrank every real exchange in the window.
                if (target.isEmpty() || isEveryone(target) || target.equalsIgnoreCase(who)) {
                    continue;
                }
                pairs.merge(who + " → " + target, 1, Integer::sum);
            }

            String text = m.displayBody();
            for (Map.Entry<String, Pattern> e : TOPICS.entrySet()) {
                if (e.getValue().matcher(text).find()) {
                    topics.merge(e.getKey(), 1, Integer::sum);
                }
            }
            Matcher coords = COORDINATES.matcher(text);
            if (coords.find()) {
                callouts.add(new Callout(m.capturedAt(), who,
                        "X:" + coords.group(1) + " Y:" + coords.group(2)));
            }
        }

        // Both of these are claims about what changed, and they need something to have changed
        // from. With a transcript that only reaches back to the start of the window, every single
        // name is "new" and nobody has "gone quiet" -- which is not a finding, it is the absence
        // of one, and printing thirty-nine names under "arrived" states it as though it were.
        boolean enoughHistory = earliest != null
                && !earliest.isAfter(from.minus(HISTORY_BEFORE_COMPARING));
        List<String> arrived = new ArrayList<>();
        List<String> wentQuiet = new ArrayList<>();
        if (enoughHistory) {
            arrived.addAll(spokeInside);
            arrived.removeAll(spokeBefore);
            arrived.sort(String.CASE_INSENSITIVE_ORDER);
            wentQuiet.addAll(spokeBefore);
            wentQuiet.removeAll(spokeInside);
            wentQuiet.sort(String.CASE_INSENSITIVE_ORDER);
        }

        return new Result(
                inside.size(),
                spokeInside.size(),
                busiestHour(perHour),
                perHour,
                ranked(voices),
                ranked(pairs),
                ranked(topics),
                callouts,
                unanswered(inside),
                arrived,
                wentQuiet,
                enoughHistory);
    }

    /**
     * Questions that went by without anybody picking them up.
     *
     * <p>A question counts as answered if somebody else says anything at all within the window
     * below -- not because every reply is an answer, but because the useful signal is "this went
     * past in silence", and silence is the thing that can be measured. Judging whether a reply
     * actually answered the question is exactly the reading this class does not do.
     */
    private static List<Unanswered> unanswered(List<ChatMessage> inside) {
        List<Unanswered> out = new ArrayList<>();
        for (int i = 0; i < inside.size(); i++) {
            ChatMessage m = inside.get(i);
            String text = m.displayBody().strip();
            if (!text.endsWith("?") || text.length() < SHORTEST_REAL_QUESTION) {
                continue;
            }
            String asker = speaker(m);
            boolean answered = false;
            for (int j = i + 1; j < inside.size(); j++) {
                ChatMessage later = inside.get(j);
                if (Duration.between(m.capturedAt(), later.capturedAt())
                        .compareTo(ANSWER_WINDOW) > 0) {
                    break;
                }
                if (!speaker(later).equalsIgnoreCase(asker) && repliesTo(later, asker)) {
                    answered = true;
                    break;
                }
            }
            if (!answered) {
                out.add(new Unanswered(m.capturedAt(), asker, text));
            }
        }
        return out;
    }

    /**
     * Whether a later message is aimed at the person who asked.
     *
     * <p>Anybody saying anything at all used to count, which in a busy channel means every question
     * is answered within seconds and the section is permanently empty -- it found one unanswered
     * question in nine hundred. What makes a reply a reply is that it is addressed to the asker,
     * either by mentioning them or by quoting them, and both of those are already parsed.
     */
    private static boolean repliesTo(ChatMessage later, String asker) {
        for (String mention : later.mentions()) {
            if (mention.replaceFirst("^@", "").trim().equalsIgnoreCase(asker)) {
                return true;
            }
        }
        String quoted = later.quoted();
        return quoted != null && quoted.regionMatches(true, 0, asker, 0, asker.length());
    }

    /** Long enough after a question that nobody is going to answer it now. */
    private static final Duration ANSWER_WINDOW = Duration.ofMinutes(20);

    /** Shorter than this and a trailing question mark is punctuation, not a question. */
    private static final int SHORTEST_REAL_QUESTION = 12;

    private static boolean isEveryone(String target) {
        String bare = target.toLowerCase(Locale.ROOT).replace("i", "l");
        // The reader spells @All several ways -- @AIl, @AI, @Ali -- because a capital I and a
        // lower-case l are the same handful of pixels in this font.
        return bare.equals("all") || bare.equals("al") || bare.equals("everyone");
    }

    private static String speaker(ChatMessage m) {
        return m.author() == null ? "" : m.author().strip();
    }

    /**
     * One display name per person, however many ways the reader spelled them.
     *
     * <p>Without this the counts are wrong in the way that matters most: "CrisdeuS 194" and
     * "Crisdeus 63" is one person read twice, and split across two rows he looks like the second
     * and fifth busiest speaker instead of comfortably the first. Three shapes account for nearly
     * all of it -- a capital read as lower case, a name clipped short by the edge of the bubble
     * ("Uthre" for "Uthre Ramone"), and a single letter misread ("Syetl" for "Svetl").
     *
     * <p>The most frequently seen spelling wins, on the grounds that the reader gets it right more
     * often than it gets it wrong.
     */
    private static Map<String, String> foldNames(List<ChatMessage> messages) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (ChatMessage m : messages) {
            String who = speaker(m);
            if (!who.isEmpty()) {
                seen.merge(who, 1, Integer::sum);
            }
        }
        List<String> byPopularity = new ArrayList<>(seen.keySet());
        byPopularity.sort(Comparator.comparingInt((String n) -> seen.get(n)).reversed()
                .thenComparing(Comparator.naturalOrder()));

        Map<String, String> folded = new LinkedHashMap<>();
        List<String> canonical = new ArrayList<>();
        for (String name : byPopularity) {
            String match = null;
            for (String already : canonical) {
                if (samePerson(name, already)) {
                    match = already;
                    break;
                }
            }
            if (match == null) {
                canonical.add(name);
                folded.put(name, name);
            } else {
                folded.put(name, match);
            }
        }
        return folded;
    }

    private static boolean samePerson(String a, String b) {
        String x = a.toLowerCase(Locale.ROOT);
        String y = b.toLowerCase(Locale.ROOT);
        if (x.equals(y)) {
            return true;
        }
        // A name cut off by the edge of the bubble. Short prefixes are refused: "Jo" would swallow
        // every name beginning with it, and two real players often share their first few letters.
        int shortest = Math.min(x.length(), y.length());
        if (shortest >= SHORTEST_SAFE_PREFIX && (x.startsWith(y) || y.startsWith(x))) {
            return true;
        }
        return x.length() == y.length() && x.length() >= SHORTEST_SAFE_PREFIX && oneLetterApart(x, y);
    }

    private static boolean oneLetterApart(String a, String b) {
        int differences = 0;
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i) && ++differences > 1) {
                return false;
            }
        }
        return differences == 1;
    }

    /** Below this, two names sharing a start is a coincidence rather than one clipped read. */
    private static final int SHORTEST_SAFE_PREFIX = 5;

    private static int busiestHour(int[] perHour) {
        int best = -1;
        int most = 0;
        for (int h = 0; h < perHour.length; h++) {
            if (perHour[h] > most) {
                most = perHour[h];
                best = h;
            }
        }
        return best;
    }

    private static List<Tally> ranked(Map<String, Integer> counts) {
        List<Tally> out = new ArrayList<>();
        counts.forEach((name, n) -> out.add(new Tally(name, n)));
        out.sort(Comparator.comparingInt(Tally::count).reversed()
                .thenComparing(Tally::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }
}
