package dev.frostguard.tasks.social;

import java.awt.image.BufferedImage;

import dev.frostguard.vision.ocr.TextLine;

/**
 * Tells the message somebody wrote from the message they were replying to.
 *
 * <p>A reply does not put the quoted message inside its own bubble, which is what the transcript
 * assumed for a long time. The game draws the quote as its own strip immediately <em>below</em> the
 * bubble, in dimmer text: the bubble says what this player wrote, and the strip under it says what
 * they were answering. Read as ordinary rows, those strips became messages in their own right --
 * "AthenaRyu: This as far as I got" was stored as something somebody said -- or were swept into
 * whatever message followed. Either way another player's words ended up attributed to the wrong
 * person, in 19% of an evening's transcript.
 *
 * <p>Trying to separate them by their text was the wrong approach and never worked well. The quote
 * is marked by how it is drawn, not by what it says, and the game is emphatic about it. Measured on
 * a live frame, taking the brightest tenth of the ink on each row:
 *
 * <pre>
 *   quote strips   162  162  162  162     never brighter, whatever they contain
 *   bubbles        254  255  255          plain white
 *   sender lines   201
 * </pre>
 *
 * <p>Nothing sits between 162 and 201, so the threshold is not a close call, and it holds for a
 * quote in any language -- which the text-shaped tests never did, because the quoted name is the
 * part of the bubble the reader mangles most.
 */
final class ChatQuoteBar {

    /**
     * Above this a row is something a player wrote; below it, something they were answering.
     *
     * <p>Set between the quotes at 180 and the sender lines at 192. That is a narrower gap than it
     * looks, which is why the reader checks a row for a sender line before asking this: losing an
     * author is worse than missing a quote, so the question is only put about rows that are not
     * names.
     */
    private static final int QUOTE_MAX_INK = 186;

    /** Bright enough to be a glyph rather than the panel behind it. */
    private static final int INK_THRESHOLD = 140;

    /** Below this there is not enough ink on the row to judge how bright it is. */
    private static final int MIN_INK_PIXELS = 60;

    /** The share of the ink used to measure brightness -- the strokes, not their soft edges. */
    private static final double BRIGHTEST_SHARE = 0.10;

    private ChatQuoteBar() {
    }

    /** Whether this row is the dimmer strip carrying the message being replied to. */
    static boolean isQuoteRow(BufferedImage img, TextLine line) {
        int x0 = Math.max(0, line.left());
        int y0 = Math.max(0, line.top());
        int x1 = Math.min(img.getWidth(), line.right());
        int y1 = Math.min(img.getHeight(), line.bottom());
        if (x1 <= x0 || y1 <= y0) {
            return false;
        }

        java.util.List<Integer> ink = new java.util.ArrayList<>();
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int rgb = img.getRGB(x, y);
                // The brightest channel, not the luminance. Luminance counts blue barely at all,
                // so a blue mention -- which the game draws at full strength -- scores about 153
                // and fell below the threshold meant for grey quotes. Whole messages lost their
                // "@AthenaRyu" that way, the mention being taken for a quote and filed as one.
                // By channel a mention reads 234, plain white 255, and a quote 162.
                int peak = Math.max((rgb >> 16) & 0xFF,
                        Math.max((rgb >> 8) & 0xFF, rgb & 0xFF));
                if (peak > INK_THRESHOLD) {
                    ink.add(peak);
                }
            }
        }
        if (ink.size() < MIN_INK_PIXELS) {
            return false;
        }
        // The middle of the ink, not the brightest of it. The brightest tenth was the first thing
        // tried and it only works on a quote with no mention in it: the game draws a mention at
        // full strength even inside a quote, so a quoted reply carrying one peaks at 249 and is
        // indistinguishable from ordinary writing. The median is not moved by those few bright
        // pixels -- measured across frames, quotes sit at 172-180 whether or not they carry a
        // mention, sender lines at 192, and message text at 218-222.
        java.util.Collections.sort(ink);
        return ink.get(ink.size() / 2) < QUOTE_MAX_INK;
    }
}
