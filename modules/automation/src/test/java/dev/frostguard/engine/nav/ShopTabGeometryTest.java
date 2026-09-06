package dev.frostguard.engine.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;

class ShopTabGeometryTest {

    @Test
    void computesThreeInsetTapAreasFromMeasuredButtonPitch() {
        assertEquals(AreaData.of(12, 1218, 181, 1263), CommonGameAreas.shopTabTapArea(0));
        assertEquals(AreaData.of(210, 1218, 379, 1263), CommonGameAreas.shopTabTapArea(1));
        assertEquals(AreaData.of(408, 1218, 577, 1263), CommonGameAreas.shopTabTapArea(2));
        assertThrows(IllegalArgumentException.class, () -> CommonGameAreas.shopTabTapArea(3));
    }

    @Test
    void usesMeasuredPageSwipeAndItsReverse() {
        assertEquals(new PointData(600, 1240), CommonGameAreas.SHOP_TABS_TOWARD_LATER_FROM);
        assertEquals(new PointData(350, 1240), CommonGameAreas.SHOP_TABS_TOWARD_LATER_TO);
        assertEquals(CommonGameAreas.SHOP_TABS_TOWARD_LATER_TO,
                CommonGameAreas.SHOP_TABS_TOWARD_EARLIER_FROM);
        assertEquals(CommonGameAreas.SHOP_TABS_TOWARD_LATER_FROM,
                CommonGameAreas.SHOP_TABS_TOWARD_EARLIER_TO);
    }
}
