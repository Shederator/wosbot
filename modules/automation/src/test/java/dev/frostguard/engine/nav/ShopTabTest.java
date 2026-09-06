package dev.frostguard.engine.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ShopTabTest {

    @Test
    void preservesMeasuredShopOrderAndOcrMarkers() {
        assertEquals(List.of(
                "Mystery", "eee", "Arena", "VIP", "Championship", "Labyrinth",
                "State", "Foundry", "Canyon", "Skin", "Gem"),
                java.util.Arrays.stream(ShopTab.values()).map(ShopTab::ocrMarker).toList());
    }

    @Test
    void matchesMarkersByCaseInsensitiveContainment() {
        for (ShopTab tab : ShopTab.values()) {
            String noisy = "prefix " + tab.ocrMarker().toLowerCase() + " suffix";
            assertEquals(tab, ShopTab.fromOcr(noisy).orElseThrow(), tab.displayName());
        }
    }

    @Test
    void rejectsBlankUnknownAndAmbiguousText() {
        assertTrue(ShopTab.fromOcr(null).isEmpty());
        assertTrue(ShopTab.fromOcr("unrecognized label").isEmpty());
        assertTrue(ShopTab.fromOcr("State Foundry").isEmpty());
    }
}
