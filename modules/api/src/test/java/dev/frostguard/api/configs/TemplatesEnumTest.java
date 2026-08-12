package dev.frostguard.api.configs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TemplatesEnumTest {

    @Test
    void mapsBackpackButtonToShippedAssetName() {
        assertEquals(
                "/templates/home/bottombar/backpackButton.png",
                TemplatesEnum.GAME_HOME_BOTTOM_BAR_BACKPACK_BUTTON.resourcePath());
    }
}
