package dev.frostguard.engine.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AreaData;

class ShopTabGeometryTest {

    @Test
    void computesThreeInsetTapAreasFromMeasuredButtonPitch() {
        assertEquals(AreaData.of(12, 1218, 181, 1263), CommonGameAreas.shopTabTapArea(0));
        assertEquals(AreaData.of(210, 1218, 379, 1263), CommonGameAreas.shopTabTapArea(1));
        assertEquals(AreaData.of(408, 1218, 577, 1263), CommonGameAreas.shopTabTapArea(2));
        assertThrows(IllegalArgumentException.class, () -> CommonGameAreas.shopTabTapArea(3));
    }

    @Test
    void mirrorsTrailingTapAreasFromTheRightEdge() {
        assertEquals(AreaData.of(539, 1218, 708, 1263), CommonGameAreas.shopTabTapAreaFromRight(0));
        assertEquals(AreaData.of(341, 1218, 510, 1263), CommonGameAreas.shopTabTapAreaFromRight(1));
        assertEquals(AreaData.of(143, 1218, 312, 1263), CommonGameAreas.shopTabTapAreaFromRight(2));
        assertEquals(AreaData.of(529, 1208, 718, 1273), CommonGameAreas.SHOP_RIGHTMOST_TAB_OCR_AREA);
        assertThrows(IllegalArgumentException.class, () -> CommonGameAreas.shopTabTapAreaFromRight(3));
    }
}
