package dev.frostguard.vision.ocr;

/**
 * One line of recognised text together with where it sat on the frame.
 *
 * <p>Position is the point of this type. Reading a screen by cropping regions first and recognising
 * them afterwards means every region boundary is a guess, and a guess that is a few pixels out
 * clips the tops of glyphs or swallows the line below. Recognising the frame whole and asking where
 * each line landed turns those guesses into measurements: a chat sender line and the message under
 * it are told apart by the column they start in, which the reader reports, rather than by an offset
 * somebody chose.
 */
public record TextLine(String text, int left, int top, int width, int height, float confidence) {

    public int bottom() {
        return top + height;
    }

    public int right() {
        return left + width;
    }
}
