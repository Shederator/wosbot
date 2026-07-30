package dev.frostguard.api.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ImageSearchResultDataTest {

    @Test
    void matchedTemplateSizeIsOptionalAndPreserved() {
        ImageSearchResultData legacy = new ImageSearchResultData(true, new PointData(10, 20), 97.5);
        assertNull(legacy.getTemplateSize());

        ImageSearchResultData withSize = new ImageSearchResultData(
                true, new PointData(10, 20), 97.5, new SizeData(89, 14));

        assertEquals(new SizeData(89, 14), withSize.getTemplateSize());
        assertEquals(new PointData(10, 20), withSize.getPoint());
        assertEquals(97.5, withSize.getMatchPercentage());
    }
}
