package dev.frostguard.vision.ocr;

/**
 * Turns a raw HUD number reading into a value, or {@code null} when it isn't usable.
 *
 * <p>Returning {@code null} rather than a best guess is the point: in a history meant for graphing,
 * a wrong number is worse than a missing one. This also drives the collector's retry behaviour —
 * parseability is used as the OCR retry acceptor, so a malformed-but-non-empty read gets the full
 * retry budget instead of consuming it on one bad frame.
 *
 * <p>The telemetry routine delegates here so its parsing rules remain reusable
 * and independently covered by unit tests.
 */
public final class HudNumberParser {

    private HudNumberParser() {}

    /**
     * Parses the game's HUD number forms: full precision ("25,967,881") and abbreviated
     * ("16.3M", "6.7K", "1.2B"). Returns {@code null} for anything unparseable.
     */
    public static Long parseScaled(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().replace(",", "").replace(" ", "");
        if (s.isEmpty()) {
            return null;
        }

        long multiplier = 1L;
        char last = s.charAt(s.length() - 1);
        boolean abbreviated = last == 'K' || last == 'M' || last == 'B';
        if (abbreviated) {
            multiplier = last == 'K' ? 1_000L : last == 'M' ? 1_000_000L : 1_000_000_000L;
            s = s.substring(0, s.length() - 1);
        } else {
            // Tesseract frequently reads the HUD's thousands commas as periods ("12.552.372"). Only
            // the abbreviated form carries a real decimal point, so on an un-abbreviated value a
            // period is always a group separator and is safe to drop. Without this, every
            // full-precision power reading would be discarded.
            s = s.replace(".", "");
        }

        if (s.isEmpty()) {
            return null;
        }
        try {
            // Parsed as a double because the abbreviated form carries a decimal ("6.7M"); the
            // un-abbreviated form never does, so this is lossless for the values the HUD shows.
            return (long) (Double.parseDouble(s) * multiplier);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
