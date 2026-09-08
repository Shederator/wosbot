package dev.frostguard.tasks.social;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import dev.frostguard.api.chat.ChatLineCleaner;
import dev.frostguard.vision.ocr.TextLine;

/**
 * Takes the bubble's decoration back off the sentence it was read as part of.
 *
 * <p>Every bubble in the feed carries furniture: a snowman on one edge, a tail on the other, a
 * crown over a name, an emoji in the middle of a sentence. The reader has no notion of decoration,
 * so all of it arrives as text. Measured over five live alliance screens it damaged five of the
 * twelve messages held -- "congrats" was stored as "y congrats e", "En la 2 no te veo" as "En la 2
 * no te veo e,", and a row ending "de la legion 2" as "de la fundicion q a de la legion 2", which
 * then went to the translator and came back as "the foundry qa of the legion 2".
 *
 * <p>Colour does not separate them. That was the first thing tried, because a mention is coloured
 * and body text is not, but the ornaments are white -- the same white as the text -- so the
 * saturation test that finds a mention is blind to a snowman. What separates them is position: an
 * ornament is one or two characters sitting on its own at the end of a row, with a gap between it
 * and the last real word far wider than the space between words. That is a fact about where words
 * are, which is why this works on word boxes rather than on the line.
 *
 * <p>Emoji are handled by the same pass for a different reason. They are genuinely coloured, they
 * sit inside the sentence rather than at its edge, and the reader turns them into whatever letters
 * they resemble -- a grinning face became "by" in the middle of "I missed it (emoji) Congrats!".
 * The instruction was to drop emotes and leave a space, so a word that is mostly coloured pixels is
 * removed and the words either side keep their spacing.
 */
final class ChatOrnamentFilter {

    /**
     * A fragment this short at the edge of a row is a candidate for being furniture.
     *
     * <p>Two rather than one because the reader routinely makes two characters out of one ornament
     * ("tt", "e,", "&lt;9"). Real words this short do occur -- "a", "de", "in" -- which is why the
     * gap test below has to agree before anything is dropped.
     */
    private static final int ORNAMENT_MAX_CHARS = 2;

    /**
     * How much wider than the row's own word spacing a gap has to be before it separates rather
     * than joins. Measured on live rows, spaces inside a sentence run 8-14px at this size and the
     * gap to an ornament runs 30px and up, so the multiple does not have to be finely judged.
     */
    /** How far a short fragment's glyph width may sit either side of the row's own. */
    private static final double GLYPH_MIN_SHARE = 0.60;
    private static final double GLYPH_MAX_SHARE = 1.70;
    /** Words at least this long are trusted to say what a character is worth on this row. */
    private static final int MIN_CHARS_TO_MEASURE = 4;

    /** Above this share of coloured pixels a word is artwork rather than writing. */
    private static final double EMOJI_COLOURED_SHARE = 0.35;
    private static final double COLOURED_SATURATION = 0.30;
    private static final int TEXT_PIXEL = 150;
    /** How far from a word's own average hue a pixel may sit and still belong to it. */
    private static final double HUE_TOLERANCE_DEGREES = 25.0;
    /** The share of a word that has to agree on a hue before it counts as one flat colour. */
    private static final double HUE_AGREEMENT = 0.70;
    /** Below this there are too few coloured pixels for their agreement to mean anything. */
    private static final int MIN_PIXELS_TO_JUDGE_HUE = 40;
    /** Gold, as opposed to the blue of a mention of a person at 205.9 degrees. */
    private static final double ALL_MENTION_MAX_HUE = 60.0;
    /** What the game means by that gold. */
    private static final String ALL_MENTION = "@All";
    /** A mention of everyone is followed by the message it addresses to them. */
    private static final int MIN_WORDS_AFTER_ALL = 2;
    /** Enough words that the row is a sentence rather than a piece of bubble. */
    private static final int MIN_WORDS_IN_A_SENTENCE_ROW = 4;
    /** How far left of the text column a fragment must sit to be outside it. */
    private static final int OUTSIDE_COLUMN_SLACK = 15;

    private ChatOrnamentFilter() {
    }

    /**
     * Rewrites each row from its own words, leaving out the ones that are not writing.
     *
     * @param lines the rows the transcript is built from
     * @param words the same reading reported one word at a time
     * @param img   the frame, for deciding whether a word is coloured
     */
    static List<TextLine> clean(List<TextLine> lines, List<TextLine> words, BufferedImage img) {
        // Measured across the whole screen, so a row holding nothing but an ornament can still be
        // judged. Ornaments routinely land on a row of their own -- the tail fragments read as "e,"
        // and "e" each arrived as their own line -- and a row with one word has nothing of its own
        // to compare against. Skipping those, which the first version did, left every isolated
        // ornament in the transcript.
        double frameWide = typicalGlyphWidth(words);
        // Where the bubbles put their text, read off this screen rather than assumed. The
        // remaining furniture after the width test is the kind that happens to be letter-shaped --
        // a tail read as "Ll", a corner read as "a" -- and what gives it away is that it sits
        // outside the column every real word on the screen starts in.
        int textLeft = dominantTextLeft(lines, words);
        List<TextLine> out = new ArrayList<>(lines.size());
        for (TextLine line : lines) {
            // A line recovered in another script is left alone. Its text no longer corresponds
            // to the Latin word boxes this works from, and CJK glyphs are square where Latin ones
            // are not, so every measurement here would be against the wrong reference.
            if (ChatScriptRecovery.countScriptCharacters(line.text()) > 0) {
                out.add(line);
                continue;
            }
            // The dimmer strip under a bubble is the message being replied to. It is kept as it
            // reads, mentions and all, because it is shown as context rather than parsed as
            // somebody's words -- and stripping its mentions made the context useless.
            if (ChatQuoteBar.isQuoteRow(img, line)) {
                out.add(line);
                continue;
            }
            List<TextLine> mine = wordsOn(line, words);
            if (mine.isEmpty()) {
                out.add(line);
                continue;
            }
            // A sender line carries gold of its own -- a VIP badge, a rank crown, the little
            // aeroplane beside a name -- and the alliance tag is what says the row is a name rather
            // than something somebody wrote. Without this the aeroplane on tsubomi's sender line
            // was rewritten as "@All".
            boolean senderRow = ChatLineCleaner.parseSender(line.text()).trusted()
                    && !ChatLineCleaner.parseSender(line.text()).allianceTag().isEmpty();
            // Furniture goes first, then colour. A mention is kept because it opens the row, and an
            // artifact sitting in front of it takes that position away: "N @AthenaRyu dekuji" had
            // its mention dropped as though it were an emoji mid-sentence, because a one-character
            // scrap of bubble was standing where the mention should have been. Removing the scrap
            // before asking what opens the row puts the mention back.
            List<TextLine> kept = dropEmoji(dropOddGlyphs(mine, frameWide, img, senderRow, textLeft), img);
            if (!senderRow) {
                kept = dropOutsideTextColumn(kept, textLeft, img);
            }
            if (kept.isEmpty()) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (TextLine w : kept) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(w.text());
            }
            String rebuilt = sb.toString().trim();
            out.add(rebuilt.isEmpty() ? line
                    : new TextLine(rebuilt, line.left(), line.top(), line.width(), line.height(),
                            line.confidence()));
        }
        return out;
    }

    /**
     * The x the bubbles start their text at, taken from the rows that plainly hold sentences.
     *
     * <p>Rows with several words are used because a row that is mostly furniture would otherwise
     * help decide where furniture is not. Measured on live screens this lands on 171-172, with the
     * leftover ornaments at 132-157 and sender lines further left again -- but the number is read
     * from the screen each pass rather than written down, because it moves with the avatar column.
     */
    private static int dominantTextLeft(List<TextLine> lines, List<TextLine> words) {
        List<Integer> lefts = new ArrayList<>();
        for (TextLine line : lines) {
            List<TextLine> mine = wordsOn(line, words);
            if (mine.size() >= MIN_WORDS_IN_A_SENTENCE_ROW) {
                lefts.add(mine.get(0).left());
            }
        }
        if (lefts.isEmpty()) {
            return -1;
        }
        java.util.Collections.sort(lefts);
        return lefts.get(lefts.size() / 2);
    }

    /**
     * Drops a short fragment sitting left of where this screen's text begins.
     *
     * <p>Only the leading word, only when it is short, and only when it is clearly outside rather
     * than marginally so. A wrapped line of a bubble starts in the same column as every other line
     * of it; something 15 pixels or more to the left of that is not part of the sentence.
     */
    private static List<TextLine> dropOutsideTextColumn(List<TextLine> words, int textLeft,
                                                        BufferedImage img) {
        if (textLeft < 0 || words.size() < 2) {
            return words;
        }
        List<TextLine> kept = new ArrayList<>(words);
        while (kept.size() >= 2) {
            TextLine first = kept.get(0);
            if (first.text().length() > ORNAMENT_MAX_CHARS
                    || first.left() > textLeft - OUTSIDE_COLUMN_SLACK) {
                break;
            }
            // Only white furniture goes. Anything coloured out here is left exactly as it is,
            // for the two mechanisms that already read colour correctly: the emoji test above, and
            // the roster repair further downstream, which turns the reader's mangled "@" back into
            // a mention by recognising the name behind it. Deleting coloured fragments took
            // "@Maki felicidades!" back to "Maki felicidades!" -- Maki being congratulated rather
            // than congratulating -- and replacing them with an "@" instead was worse still,
            // scattering "@" through sentences wherever a border or an emote sat left of a wrapped
            // row. The furniture this rule is for is the white tail and the white corner.
            if (colouredShare(first, img) >= EMOJI_COLOURED_SHARE) {
                break;
            }
            kept.remove(0);
        }
        return kept;
    }

    /** The words printed on this row, in reading order. */
    private static List<TextLine> wordsOn(TextLine line, List<TextLine> words) {
        List<TextLine> mine = new ArrayList<>();
        for (TextLine w : words) {
            int top = Math.max(line.top(), w.top());
            int bottom = Math.min(line.bottom(), w.bottom());
            int shared = bottom - top;
            if (shared > 0 && shared >= 0.5 * Math.min(line.height(), w.height())
                    && w.left() >= line.left() - 4 && w.right() <= line.right() + 4) {
                mine.add(w);
            }
        }
        mine.sort((a, b) -> Integer.compare(a.left(), b.left()));
        return mine;
    }

    /**
     * Drops coloured words, keeping only the run of them that opens the row.
     *
     * <p>Colour alone cannot separate a mention from an emoji: measured, a mention runs 0.60 to
     * 0.69 saturation and an emoji is at least as strong, so dropping on colour took "@Romario
     * 0707", "@Maki" and "@All" out of messages that had them right. Hue does not separate them
     * either -- that was tried next, on the theory that text is one flat colour and a picture is
     * not, and a yellow smiley is as flat as anything.
     *
     * <p>What separates them is where they sit. The game puts a mention at the head of the message
     * and an emoji wherever the writer typed it, so a coloured word that opens the row is a name
     * and a coloured word further in is a picture. The run continues past the first word so that
     * "@Romario 0707" keeps its second half, and ends at the first ordinary word, after which
     * everything coloured is artwork.
     *
     * <p>This is the anchor an earlier attempt could not get from line boxes. A line's own left
     * edge is not where its text starts -- bubble furniture is read as text, so the box began 40px
     * left of the "@" -- but the first word box is exactly the first word.
     */
    private static List<TextLine> dropEmoji(List<TextLine> words, BufferedImage img) {
        List<TextLine> kept = new ArrayList<>(words.size());
        boolean stillLeading = true;
        for (TextLine w : words) {
            boolean coloured = colouredShare(w, img) >= EMOJI_COLOURED_SHARE;
            if (!coloured) {
                stillLeading = false;
                kept.add(w);
                continue;
            }
            if (stillLeading) {
                kept.add(w);
            }
        }
        return kept;
    }

    /** Whether a word's coloured pixels agree on a hue, which writing does and a picture does not. */
    private static boolean isOneFlatColour(TextLine w, BufferedImage img) {
        int x0 = Math.max(0, w.left());
        int y0 = Math.max(0, w.top());
        int x1 = Math.min(img.getWidth(), w.right());
        int y1 = Math.min(img.getHeight(), w.bottom());
        List<Double> hues = new ArrayList<>();
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int rgb = img.getRGB(x, y);
                if (isColoured(rgb)) {
                    hues.add(hueOf(rgb));
                }
            }
        }
        if (hues.size() < MIN_PIXELS_TO_JUDGE_HUE) {
            return false;
        }
        // Averaged on the circle, because hue wraps: reds either side of zero average to cyan if
        // they are added up as plain numbers.
        double sx = 0;
        double sy = 0;
        for (double h : hues) {
            sx += Math.cos(Math.toRadians(h));
            sy += Math.sin(Math.toRadians(h));
        }
        double mean = Math.toDegrees(Math.atan2(sy, sx));
        int agree = 0;
        for (double h : hues) {
            double d = Math.abs(h - mean) % 360.0;
            if (Math.min(d, 360.0 - d) <= HUE_TOLERANCE_DEGREES) {
                agree++;
            }
        }
        return agree / (double) hues.size() >= HUE_AGREEMENT;
    }

    private static boolean isColoured(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        if (max < TEXT_PIXEL) {
            return false;
        }
        int min = Math.min(r, Math.min(g, b));
        return (max - min) / (double) max >= COLOURED_SATURATION;
    }

    private static double hueOf(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int c = max - min;
        if (c == 0) {
            return 0.0;
        }
        double h;
        if (max == r) {
            h = 60.0 * (((g - b) / (double) c) % 6.0);
        } else if (max == g) {
            h = 60.0 * ((b - r) / (double) c + 2.0);
        } else {
            h = 60.0 * ((r - g) / (double) c + 4.0);
        }
        return h < 0 ? h + 360.0 : h;
    }

    /**
     * Drops short fragments whose glyphs are the wrong size to be letters.
     *
     * <p>Position was the first rule here and it does not hold. It assumed an ornament sits apart
     * from the sentence, and the snowman on the fundicion bubble sits 9px from the last word --
     * exactly the spacing of an ordinary space -- while the row's own median gap was dragged up to
     * 69px by the ornaments themselves, so the test compared a real gap against a threshold the
     * junk had set. Nothing was ever dropped.
     *
     * <p>What does hold is how wide the glyphs are. Measured across live rows, real words run 9 to
     * 14 pixels per character at this magnification and are strikingly consistent within a row --
     * "En" 14, "la" 9, "de" 12.5, "te" 9.5, "no" 10. The furniture is nowhere near: the snowman
     * read as a single "q" 44 pixels wide, a bubble tail as "=" at 22, and a tail fragment as a "y"
     * just 4 pixels across. A letter has a size; a picture the reader guessed a letter from does
     * not have to.
     *
     * <p>Only short fragments are judged. A long word that comes out slightly wide is still a word,
     * and there is nothing to gain from second-guessing it.
     */
    private static List<TextLine> dropOddGlyphs(List<TextLine> words, double frameWide,
                                                BufferedImage img, boolean senderRow, int textLeft) {
        double normal = typicalGlyphWidth(words);
        if (normal <= 0) {
            normal = frameWide;
        }
        if (normal <= 0) {
            return words;
        }
        List<TextLine> kept = new ArrayList<>(words.size());
        boolean first = true;
        for (TextLine w : words) {
            boolean leading = first;
            first = false;
            if (w.text().length() > ORNAMENT_MAX_CHARS) {
                kept.add(w);
                continue;
            }
            double per = w.width() / (double) Math.max(1, w.text().length());
            // The lower bound does not apply to characters that are genuinely thin. An "I" is a
            // stroke and measures a third of the row's average, so judging it by width alone
            // dropped it out of "Oooops. I missed it Congrats!" -- a real word lost to a rule aimed
            // at bubble furniture.
            boolean thin = isAllThinGlyphs(w.text());
            if ((thin || per >= normal * GLYPH_MIN_SHARE) && per <= normal * GLYPH_MAX_SHARE) {
                kept.add(w);
                continue;
            }
            // Everything else here is furniture and goes. The exception is the one mention that is
            // not a player: "@All" is drawn gold where a mention of a person is drawn blue, and the
            // two do not overlap -- measured across frames, @All sits at 28 degrees and every
            // player mention at 205.9. It is dropped rather than read because the reader cannot
            // make a word of it ("GA", "(DAI"), and because it is not a name there is no roster
            // entry to repair it from. So the colour says what it was and it is written back.
            // Gold at the head of a row is only "@All" when it is genuinely at the head of a
            // message: sitting where the text column starts, with a message after it. Without
            // those two conditions the gold crown drawn on the corner of a bubble was being read
            // as a mention of the whole alliance -- one message was stored as nothing but "@All",
            // and two others had it invented in front of what the player actually wrote.
            boolean atTextStart = Math.abs(w.left() - textLeft) <= OUTSIDE_COLUMN_SLACK;
            boolean somethingFollows = words.size() - words.indexOf(w) > MIN_WORDS_AFTER_ALL;
            if (leading && !senderRow && atTextStart && somethingFollows
                    && isAllMentionColour(w, img)) {
                kept.add(new TextLine(ALL_MENTION, w.left(), w.top(), w.width(), w.height(),
                        w.confidence()));
            }
        }
        return kept;
    }

    /**
     * Whether this is the gold the game reserves for addressing the whole alliance.
     *
     * <p>A VIP badge is gold too, at 39.8 degrees, and is deliberately not excluded by hue -- the
     * gap to 28 is too narrow to lean on. What separates them is that a badge is ordinary text and
     * measures a normal 13 pixels per character, so it never reaches this branch; only a fragment
     * the width test has already rejected does.
     */
    private static boolean isAllMentionColour(TextLine w, BufferedImage img) {
        int x0 = Math.max(0, w.left());
        int y0 = Math.max(0, w.top());
        int x1 = Math.min(img.getWidth(), w.right());
        int y1 = Math.min(img.getHeight(), w.bottom());
        double sx = 0;
        double sy = 0;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int rgb = img.getRGB(x, y);
                if (!isColoured(rgb)) {
                    continue;
                }
                double h = Math.toRadians(hueOf(rgb));
                sx += Math.cos(h);
                sy += Math.sin(h);
                n++;
            }
        }
        if (n < MIN_PIXELS_TO_JUDGE_HUE) {
            return false;
        }
        double mean = (Math.toDegrees(Math.atan2(sy, sx)) + 360) % 360;
        return mean <= ALL_MENTION_MAX_HUE;
    }

    /** Characters that are a stroke wide by design, and so cannot be judged on width. */
    private static boolean isAllThinGlyphs(String text) {
        for (int i = 0; i < text.length(); i++) {
            if ("IlietT1!.,'|:;".indexOf(text.charAt(i)) < 0) {
                return false;
            }
        }
        return !text.isEmpty();
    }

    /**
     * How wide one character is on this row, taken from its longer words.
     *
     * <p>Read from the row itself rather than assumed, because the feed is captured at one
     * magnification today and need not be tomorrow. Longer words only: a short word is the thing
     * being judged, and letting the junk set the standard is how the position rule failed.
     */
    private static double typicalGlyphWidth(List<TextLine> words) {
        List<Double> widths = new ArrayList<>();
        for (TextLine w : words) {
            if (w.text().length() >= MIN_CHARS_TO_MEASURE) {
                widths.add(w.width() / (double) w.text().length());
            }
        }
        if (widths.isEmpty()) {
            return -1;
        }
        java.util.Collections.sort(widths);
        return widths.get(widths.size() / 2);
    }

    /** How much of a word's ink the game drew in colour. */
    private static double colouredShare(TextLine w, BufferedImage img) {
        int x0 = Math.max(0, w.left());
        int y0 = Math.max(0, w.top());
        int x1 = Math.min(img.getWidth(), w.right());
        int y1 = Math.min(img.getHeight(), w.bottom());
        if (x1 <= x0 || y1 <= y0) {
            return 0;
        }
        int ink = 0;
        int coloured = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int max = Math.max(r, Math.max(g, b));
                if (max < TEXT_PIXEL) {
                    continue;
                }
                ink++;
                int min = Math.min(r, Math.min(g, b));
                if ((max - min) / (double) max >= COLOURED_SATURATION) {
                    coloured++;
                }
            }
        }
        return ink == 0 ? 0 : coloured / (double) ink;
    }
}
