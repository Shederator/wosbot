package dev.frostguard.vision.convert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompactGameNumberParserTest {

    @Test
    void parsesPlainGroupedAndCompactCounts() {
        assertEquals(57_785, CompactGameNumberParser.parse("57,785"));
        assertEquals(420_000, CompactGameNumberParser.parse("420K"));
        assertEquals(1_500_000, CompactGameNumberParser.parse("1.5M"));
    }

    @Test
    void rejectsMalformedCounts() {
        assertEquals(-1, CompactGameNumberParser.parse(null));
        assertEquals(-1, CompactGameNumberParser.parse("1,2,3"));
        assertEquals(-1, CompactGameNumberParser.parse("-20"));
    }
}
