package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateResourceAvailabilityTest {

    @Test
    void resolvesBackpackButtonToBundledVisionAsset() {
        assertTrue(TemplatesEnum.GAME_HOME_BOTTOM_BAR_BACKPACK_BUTTON.existsAtPath());
    }
}
