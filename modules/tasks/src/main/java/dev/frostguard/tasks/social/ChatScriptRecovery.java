package dev.frostguard.tasks.social;

import java.util.ArrayList;
import java.util.List;

import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.OcrException;
import dev.frostguard.vision.ocr.TextLine;

/**
 * Gives a second reading, in another script, to the lines a Latin reader could make no word out of.
 *
 * <p>Reading the whole feed with the Chinese, Japanese and Korean packs alongside the Latin ones
 * measured clearly worse, not better: Tesseract hedges across the alphabets and mangles Latin it had
 * been reading correctly, and sender lines found on a fixed set of frames fell from 85 to 59 while
 * the pass took two and a half times as long. So the Latin languages stay the default and the other
 * scripts are spent only where the first pass came back with nothing word-shaped -- a Chinese
 * message read by a Latin-only reader comes back as a handful of stray punctuation, which is cheap
 * to recognise and cheap to read again.
 *
 * <p>Lives outside {@link ChatCaptureRoutine} so a bench can run it over saved frames. As a private
 * method on the routine it could only be exercised by driving the whole task against a live
 * emulator, which is why its first version shipped unmeasured.
 *
 * <p><b>Known limit.</b> Measured against a live Chinese message ("恭喜～", read by a human off the
 * frame), this recovers nothing, and the reason is the model rather than anything here. The
 * bundled chi_sim pack loads and works -- it reads timestamps out of the same feed -- but on the
 * message itself it returns nothing above the reader's confidence floor, and on a clean synthetic
 * rendering of the same three characters at 48px it returns "东 ANY 山". Neither the page
 * segmentation mode (all five measured), nor the bubble's polarity (that message is grey on a white
 * bubble, a contrast ratio of 1.68 against 3-plus everywhere else), nor contrast normalisation
 * moves it. The pack in tools/tesseract is the "fast" variant; reading CJK properly means the
 * "best" variant, which is an order of magnitude larger, and that is a file-size decision rather
 * than a code one. This class is left wired because it costs nothing on a Latin feed and starts
 * working the day a better model is dropped in.
 */
final class ChatScriptRecovery {

    /** A run this long of letters is a word; anything shorter is what noise looks like. */
    private static final int LETTERS_THAT_MAKE_A_WORD = 3;

    /** Fewer script characters than this is a stray glyph, not a message in another script. */
    private static final int MIN_SCRIPT_CHARS_TO_ACCEPT = 2;

    /** Room around the glyphs, so the reader is not working against its own edge. */
    private static final int INK_PADDING = 6;

    private ChatScriptRecovery() {
    }

    /**
     * @param frame     the screen the lines were read from
     * @param lines     the Latin pass's result
     * @param rightEdge the right edge of the text column, so a re-read cannot wander into the
     *                  bubble furniture
     * @param cjk       the non-Latin reader settings
     */
    static List<TextLine> reread(RawImageData frame, List<TextLine> lines, List<TextLine> words,
                                 int rightEdge, OcrSettingsData cjk, OcrSettingsData cyrillic) {
        List<TextLine> out = new ArrayList<>(lines.size());
        for (TextLine line : lines) {
            boolean unreadable = !hasReadableWord(line.text());
            boolean mangled = !unreadable && looksLikeMangledScript(line.text());
            if (!unreadable && !mangled) {
                out.add(line);
                continue;
            }
            // Which reader to spend depends on what the Latin pass produced. Nothing word-shaped at
            // all is what a CJK message looks like through a Latin reader. Words that are the wrong
            // shape -- capitals in the middle of them -- is what Cyrillic looks like, because its
            // letters have Latin lookalikes and the reader happily returns them.
            OcrSettingsData reader = unreadable ? cjk : cyrillic;
            TextLine recovered = readAgain(frame, line, inkBoxOn(line, words), rightEdge, reader);
            out.add(recovered != null ? recovered : line);
        }
        return out;
    }

    /**
     * The box the ink actually occupies on this row, rather than the row's own box.
     *
     * <p>A line box is generous: it runs to the edge of the bubble and takes in the border, the
     * rounded corner and the translate button beside it. A Latin reader copes, but the CJK reader
     * does not -- given the full box it reports an empty page, and given a box trimmed to the ink
     * it reads the same message correctly. Verified against the CLI on a live frame: the 194x77
     * line box returned nothing, the 182x82 box trimmed to the glyphs returned the characters.
     *
     * <p>Taken from the word boxes rather than measured from the pixels, because the reader has
     * already found the ink -- that is what a word box is -- and its answer needs no threshold.
     */
    private static int[] inkBoxOn(TextLine line, List<TextLine> words) {
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (TextLine w : words) {
            int overlap = Math.min(line.bottom(), w.bottom()) - Math.max(line.top(), w.top());
            if (overlap <= 0 || overlap < 0.5 * Math.min(line.height(), w.height())
                    || w.left() < line.left() - 4 || w.right() > line.right() + 4) {
                continue;
            }
            left = Math.min(left, w.left());
            top = Math.min(top, w.top());
            right = Math.max(right, w.right());
            bottom = Math.max(bottom, w.bottom());
        }
        if (left == Integer.MAX_VALUE) {
            return null;
        }
        return new int[] {left, top, right, bottom};
    }

    /** How many lines {@link #reread} would replace, for logging. */
    static int recoveredCount(List<TextLine> before, List<TextLine> after) {
        int changed = 0;
        for (int i = 0; i < before.size() && i < after.size(); i++) {
            if (!before.get(i).text().equals(after.get(i).text())) {
                changed++;
            }
        }
        return changed;
    }

    private static TextLine readAgain(RawImageData frame, TextLine line, int[] ink, int rightEdge,
                                      OcrSettingsData cjk) {
        int x0 = ink != null ? ink[0] : line.left();
        int y0 = ink != null ? ink[1] : line.top();
        int x1 = ink != null ? ink[2] : line.right();
        int y1 = ink != null ? ink[3] : line.bottom();
        try {
            List<TextLine> again = OcrEngine.recognizeLines(frame,
                    new PointData(Math.max(0, x0 - INK_PADDING), Math.max(0, y0 - INK_PADDING)),
                    new PointData(Math.min(rightEdge, x1 + INK_PADDING), y1 + INK_PADDING),
                    cjk);
            TextLine best = null;
            for (TextLine candidate : again) {
                if (!candidate.text().isBlank()
                        && (best == null || candidate.text().length() > best.text().length())) {
                    best = candidate;
                }
            }
            // Accepted only when the second reading actually found another script. Without this the
            // CJK pass is free to replace a line of noise with a different line of noise, which
            // reads as progress and is not.
            // Two characters, not one. With the stronger model a single stray glyph is easy to
            // come by, and one was enough to get a fragment of a Portuguese message replaced --
            // "participantes diminuiu para 9?" became "participantes diminuiu 付 para 9?", which
            // then went to the translator with the character still in it. A real message in
            // another script is not one character long; a hallucination often is.
            if (best != null && isMostlyAnotherScript(best.text())) {
                return new TextLine(best.text(), line.left(), line.top(),
                        line.width(), line.height(), best.confidence());
            }
        } catch (OcrException | RuntimeException e) {
            // A failed second look is not worth abandoning the screen for.
        }
        return null;
    }

    /**
     * Whether a line reads like Cyrillic that a Latin reader has guessed its way through.
     *
     * <p>Russian is one of the sixteen languages the game ships in, and it never reached the other
     * scripts pass, because that pass triggers on a line the Latin reader made no word of and
     * Cyrillic does not look like that. Half its alphabet has Latin lookalikes, so the reader
     * returns confident nonsense instead: "MH@ CPOUHO HyxeH gusaunep!!!" for "Мне срочно нужен
     * дизайнер!!!". Every Russian message in a day's transcript was stored that way, and none was
     * ever offered to a Cyrillic reader.
     *
     * <p>What gives it away is the shape of the words rather than the characters. Latin text puts
     * capitals at the front of a word or across the whole of it; these have them in the middle --
     * "HyxeH", "3aHMMaTbCA", "gusaunep" -- because the reader is matching Cyrillic letterforms one
     * at a time and does not care where a capital belongs. Two such words on a line is enough to be
     * worth a second reading, and a wrong guess costs only that reading: the result still has to be
     * mostly Cyrillic to be accepted.
     */
    static boolean looksLikeMangledScript(String text) {
        String[] words = text.split("\s+");
        boolean[] isName = new boolean[words.length];
        // A name can be more than one word, and only one of its words carries the marker. "@Mini
        // TyTy" marks "@Mini" and leaves "TyTy"; "Mini TyTy:" marks "TyTy:" and leaves "Mini".
        // Marking only the marked word left one capitalised half of every two-word name standing as
        // evidence, which was enough to send eleven of Candy's Czech messages to the Cyrillic reader
        // on a live pass.
        for (int i = 0; i < words.length; i++) {
            if (words[i].isEmpty()) {
                continue;
            }
            if (words[i].charAt(0) == '@') {
                isName[i] = true;
                if (i + 1 < words.length && startsCapitalised(words[i + 1])) {
                    isName[i + 1] = true;
                }
            }
            if (words[i].endsWith(":")) {
                isName[i] = true;
                if (i > 0 && startsCapitalised(words[i - 1])) {
                    isName[i - 1] = true;
                }
            }
        }

        int odd = 0;
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (isName[i]) {
                continue;
            }
            // Tested before the all-capitals skip, because the words this catches are usually all
            // capitals: "MH@" is "мне". A capitalised Latin word is ordinary and says nothing, but
            // an @ in the middle of one is not a word at all.
            if (atInsideWord(word)) {
                odd++;
                continue;
            }
            if (word.length() < MIN_WORD_TO_JUDGE_SHAPE || word.equals(word.toUpperCase())) {
                continue;
            }
            if (capitalInside(word) || digitInside(word)) {
                odd++;
            }
        }
        return odd >= MANGLED_WORDS_TO_SUSPECT;
    }

    /**
     * An @ anywhere but the front of a word.
     *
     * <p>Leading, it is a mention. Inside, it is the reader reaching for a Latin shape to stand in
     * for a Cyrillic one and finding no letter that fits -- "MH@" for "мне". Nothing anybody types
     * into game chat puts an @ in the middle of a word, so unlike the capital and digit tells this
     * one needs no corroboration to be worth counting.
     */
    private static boolean atInsideWord(String word) {
        return word.lastIndexOf('@') > 0;
    }

    private static boolean capitalInside(String word) {
        for (int c = 1; c < word.length(); c++) {
            if (Character.isUpperCase(word.charAt(c))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A digit with letters either side of it -- the other half of the same tell.
     *
     * <p>Counting mid-word capitals alone was one short on real messages. Cyrillic б and ч have no
     * Latin letter that resembles them, so the reader answers with the digits that do: "Te6g" for
     * "тебя", "Cky4HO" for "скучно". Neither word carries a capital anywhere but its first letter,
     * so a line reading "y MeHg xopowo) y Te6g Irotoba?" scored a single oddity and was never
     * offered to the Cyrillic reader -- one below the threshold, in a message that is Russian from
     * end to end.
     *
     * <p>Letters are required on both sides. A digit at either end of a word is ordinary in a game
     * where people write "Lv.27", "500k" and "T9", and counting those would send half the chat for
     * a second reading.
     */
    private static boolean digitInside(String word) {
        for (int c = 1; c < word.length() - 1; c++) {
            if (Character.isDigit(word.charAt(c))
                    && Character.isLetter(word.charAt(c - 1))
                    && Character.isLetter(word.charAt(c + 1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsCapitalised(String word) {
        return !word.isEmpty() && Character.isUpperCase(word.charAt(0));
    }

    /** Shorter than this and a stray capital says nothing. */
    private static final int MIN_WORD_TO_JUDGE_SHAPE = 3;
    /**
     * How many oddly-shaped words before the line is worth reading again.
     *
     * <p>Two. Three was tried and rejected: real Russian messages measured here score exactly two
     * once their all-capital words are set aside, so the stricter count stopped recognising the
     * thing this exists for. The false triggers are dealt with by not counting names, which is what
     * they were.
     */
    private static final int MANGLED_WORDS_TO_SUSPECT = 2;

    /** Whether a line holds anything a Latin reader would call a word. */
    static boolean hasReadableWord(String text) {
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                if (++run >= LETTERS_THAT_MAKE_A_WORD) {
                    return true;
                }
            } else {
                run = 0;
            }
        }
        return false;
    }

    /**
     * Whether the second reading found a message in another script, rather than a few glyphs it
     * liked the look of.
     *
     * <p>Counting script characters is not enough on its own. Measured over a live pass, the reader
     * returns two or three CJK glyphs scattered through Latin text often enough to matter -- an
     * English sentence came back as "sr 名 sr 名 大 missed you." and a Spanish one as "Buen trabajo
     * chicos ウラ", and both were stored and sent to the translator that way. A message actually
     * written in another script is almost entirely in it: the Korean lines this recovers correctly
     * score 1.0 here. So the test is the share, not the count.
     */
    static boolean isMostlyAnotherScript(String text) {
        int script = 0;
        int letters = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetter(c)) {
                continue;
            }
            letters++;
            if (c > 127 && Character.UnicodeScript.of(c) != Character.UnicodeScript.LATIN) {
                script++;
            }
        }
        return script >= MIN_SCRIPT_CHARS_TO_ACCEPT
                && script / (double) Math.max(1, letters) >= SCRIPT_SHARE_TO_ACCEPT;
    }

    /** The share of a recovered reading's letters that have to be in the other script. */
    private static final double SCRIPT_SHARE_TO_ACCEPT = 0.60;

    /** Characters outside the Latin alphabet, which is what the second reading is looking for. */
    static int countScriptCharacters(String text) {
        int found = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c) && c > 127
                    && Character.UnicodeScript.of(c) != Character.UnicodeScript.LATIN) {
                found++;
            }
        }
        return found;
    }
}
