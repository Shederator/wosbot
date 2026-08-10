package dev.frostguard.api.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the matched-bounding-area extension of template search results.
 */
class ImageSearchResultDataTest {

    @Test
    void hitWithDimensionsExposesMatchedArea() {
        ImageSearchResultData r = ImageSearchResultData.hit(100, 200, 97.5, 40, 20);

        assertTrue(r.isFound());
        assertTrue(r.hasMatchedArea());

        AreaData area = r.getMatchedArea();
        assertNotNull(area);
        // Corners are inclusive (AreaData / emulator tap convention): a
        // 40 px wide region starting at column 80 ends at column 119.
        assertEquals(80, area.topLeft().getX());
        assertEquals(190, area.topLeft().getY());
        assertEquals(119, area.bottomRight().getX());
        assertEquals(209, area.bottomRight().getY());
    }

    @Test
    void matchedAreaIsCenteredOnHitPoint() {
        ImageSearchResultData r = ImageSearchResultData.hit(360, 640, 90.0, 33, 17);
        AreaData area = r.getMatchedArea();

        int centerX = (area.topLeft().getX() + area.bottomRight().getX()) / 2;
        int centerY = (area.topLeft().getY() + area.bottomRight().getY()) / 2;
        // Integer division may shift by at most one pixel.
        assertTrue(Math.abs(centerX - 360) <= 1);
        assertTrue(Math.abs(centerY - 640) <= 1);
        // Inclusive corners: a region of N pixels spans N coordinates,
        // so bottomRight - topLeft == N - 1.
        assertEquals(33 - 1, area.bottomRight().getX() - area.topLeft().getX());
        assertEquals(17 - 1, area.bottomRight().getY() - area.topLeft().getY());
    }

    @Test
    void singlePixelMatchCollapsesToItsOwnCoordinate() {
        ImageSearchResultData r = ImageSearchResultData.hit(360, 640, 99.0, 1, 1);
        assertTrue(r.hasMatchedArea());

        AreaData area = r.getMatchedArea();
        assertEquals(area.topLeft().getX(), area.bottomRight().getX());
        assertEquals(area.topLeft().getY(), area.bottomRight().getY());
        assertEquals(360, area.topLeft().getX());
        assertEquals(640, area.topLeft().getY());
    }

    @Test
    void legacyHitWithoutDimensionsHasNoMatchedArea() {
        ImageSearchResultData r = ImageSearchResultData.hit(100, 200, 97.5);
        assertTrue(r.isFound());
        assertFalse(r.hasMatchedArea());
        assertNull(r.getMatchedArea());
    }

    @Test
    void missHasNoMatchedArea() {
        ImageSearchResultData r = ImageSearchResultData.miss();
        assertFalse(r.isFound());
        assertFalse(r.hasMatchedArea());
        assertNull(r.getMatchedArea());
    }

    @Test
    void legacyConstructorStillWorks() {
        ImageSearchResultData r = new ImageSearchResultData(true, new PointData(10, 20), 88.0);
        assertTrue(r.isFound());
        assertEquals(10, r.getX());
        assertEquals(20, r.getY());
        assertFalse(r.hasMatchedArea());
    }

    @Test
    void negativeDimensionsAreNormalizedToZero() {
        ImageSearchResultData r = ImageSearchResultData.hit(10, 20, 90.0, -5, -3);
        assertEquals(0, r.getMatchWidth());
        assertEquals(0, r.getMatchHeight());
        assertFalse(r.hasMatchedArea());
    }

    @Test
    void equalityIncludesMatchedDimensions() {
        ImageSearchResultData a = ImageSearchResultData.hit(10, 20, 90.0, 30, 40);
        ImageSearchResultData b = ImageSearchResultData.hit(10, 20, 90.0, 30, 40);
        ImageSearchResultData c = ImageSearchResultData.hit(10, 20, 90.0, 31, 40);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(c));
    }

    @Test
    void matchedTemplateSizeIsOptionalAndPreserved() {
        ImageSearchResultData legacy = new ImageSearchResultData(true, new PointData(10, 20), 97.5);
        assertNull(legacy.getTemplateSize());

        ImageSearchResultData withSize = new ImageSearchResultData(
                true, new PointData(10, 20), 97.5, new SizeData(89, 14));

        assertEquals(new SizeData(89, 14), withSize.getTemplateSize());
        assertEquals(new PointData(10, 20), withSize.getPoint());
        assertEquals(97.5, withSize.getMatchPercentage());
        assertTrue(withSize.hasMatchedArea());
        assertEquals(89, withSize.getMatchWidth());
        assertEquals(14, withSize.getMatchHeight());
    }
}
