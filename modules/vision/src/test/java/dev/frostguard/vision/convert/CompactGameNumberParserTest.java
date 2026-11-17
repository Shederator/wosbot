package dev.frostguard.vision.convert;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CompactGameNumberParserTest {

    @Test
    public void testStandardIntegerNumbers() {
        assertEquals(0L, CompactGameNumberParser.parseCompactNumber("0"));
        assertEquals(500L, CompactGameNumberParser.parseCompactNumber("500"));
        assertEquals(1200L, CompactGameNumberParser.parseCompactNumber("1200"));
        assertEquals(1200L, CompactGameNumberParser.parseCompactNumber("1,200"));
    }

    @Test
    public void testCompactKAndMUnits() {
        assertEquals(1200L, CompactGameNumberParser.parseCompactNumber("1.2K"));
        assertEquals(1200L, CompactGameNumberParser.parseCompactNumber("1.2k"));
        assertEquals(1500000L, CompactGameNumberParser.parseCompactNumber("1.5M"));
        assertEquals(1500000L, CompactGameNumberParser.parseCompactNumber("1.5m"));
        assertEquals(10000L, CompactGameNumberParser.parseCompactNumber("10k"));
    }

    @Test
    public void testInvalidAndNegativeInputs() {
        assertEquals(-1L, CompactGameNumberParser.parseCompactNumber(""));
        assertEquals(-1L, CompactGameNumberParser.parseCompactNumber(null));
        assertEquals(-1L, CompactGameNumberParser.parseCompactNumber("-500"));
        assertEquals(-1L, CompactGameNumberParser.parseCompactNumber("abc"));
        assertEquals(-1L, CompactGameNumberParser.parseCompactNumber("1.2.3K"));
        assertEquals(-1L, CompactGameNumberParser.parseCompactNumber("1,2,3"));
        assertEquals(-1L, CompactGameNumberParser.parseCompactNumber("12,34"));
        assertEquals(-1L, CompactGameNumberParser.parseCompactNumber("9223372036854775808"));
        assertEquals(-1L, CompactGameNumberParser.parseCompactNumber("9223372036854.776M"));
    }
}
