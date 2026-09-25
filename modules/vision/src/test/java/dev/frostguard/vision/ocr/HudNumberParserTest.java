package dev.frostguard.vision.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Parseability is what the collector uses as its OCR retry acceptor, so these cases decide whether a
 * bad frame is retried or silently consumes the attempt budget. A read that parses is accepted; one
 * that does not is retried and, if it never parses, recorded as a gap rather than a guess.
 */
class HudNumberParserTest {

    @Test
    void parsesTheFullPrecisionFormsTheHudPrints() {
        // Exactly the values on the committed 720x1280 fixture frame.
        assertEquals(25_967_881L, HudNumberParser.parseScaled("25,967,881"));
        assertEquals(66_545L, HudNumberParser.parseScaled("66,545"));
    }

    @Test
    void parsesTheAbbreviatedFormsTheHudPrints() {
        assertEquals(16_300_000L, HudNumberParser.parseScaled("16.3M"));
        assertEquals(6_700L, HudNumberParser.parseScaled("6.7K"));
        assertEquals(1_200_000_000L, HudNumberParser.parseScaled("1.2B"));
    }

    @Test
    void treatsPeriodsAsGroupSeparatorsOnUnabbreviatedValues() {
        // Tesseract frequently reads the HUD's thousands commas as periods. Only the abbreviated
        // form has a real decimal point, so on a full value a period is always a separator.
        // Without this, every full-precision power reading would be discarded as unparseable.
        assertEquals(12_552_372L, HudNumberParser.parseScaled("12.552.372"));
    }

    @Test
    void malformedReadsReturnNullSoTheyAreRetriedRatherThanTrusted() {
        // These are the shapes that must NOT be accepted: accepting any of them would end the retry
        // loop early and write a wrong number into a history meant for graphing.
        assertNull(HudNumberParser.parseScaled(""));
        assertNull(HudNumberParser.parseScaled("   "));
        assertNull(HudNumberParser.parseScaled("M"));
        assertNull(HudNumberParser.parseScaled("--"));
        assertNull(HudNumberParser.parseScaled("1.2.3M"));
        assertNull(HudNumberParser.parseScaled("abc"));
        assertNull(HudNumberParser.parseScaled(null));
    }

    @Test
    void whitespaceAndSeparatorsAreToleratedAroundARealValue() {
        assertEquals(25_967_881L, HudNumberParser.parseScaled("  25,967,881  "));
        assertEquals(16_300_000L, HudNumberParser.parseScaled("16.3 M".replace(" ", "")));
    }
}
