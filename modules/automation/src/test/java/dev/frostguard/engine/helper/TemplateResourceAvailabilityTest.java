package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateResourceAvailabilityTest {

    @Test
    void resolvesBackpackButtonToBundledVisionAsset() {
        assertTrue(TemplatesEnum.GAME_HOME_BOTTOM_BAR_BACKPACK_BUTTON.existsAtPath());
    }

    // Pre-existing gaps outside Bear, tracked separately. This list may only shrink.
    private static final Set<TemplatesEnum> KNOWN_MISSING = EnumSet.of(
            TemplatesEnum.LEFT_MENU_CITY_TAB,
            TemplatesEnum.BUILDING_BUTTON_LABYRINTH,
            TemplatesEnum.INTEL_MASTER_BOUNTY,
            TemplatesEnum.POLAR_TERROR_LEVEL_SELECTOR,
            TemplatesEnum.POLAR_TERROR_FLAG_SELECTOR,
            TemplatesEnum.POLAR_TERROR_MODE_SELECTOR,
            TemplatesEnum.POLAR_TERROR_DEPLOY_BUTTON);

    @Test
    void everyDeclaredTemplateIsShipped() {
        // The pattern locator reports a missing asset as "not found", which silently turns a
        // classifier branch into dead code.
        List<String> missing = Arrays.stream(TemplatesEnum.values())
                .filter(template -> !KNOWN_MISSING.contains(template))
                .filter(template -> !template.existsAtPath())
                .map(template -> template.name() + "=" + template.resourcePath())
                .toList();

        assertEquals(List.of(), missing);
    }

    @Test
    void knownMissingTemplatesAreStillMissing() {
        List<TemplatesEnum> restored = KNOWN_MISSING.stream()
                .filter(TemplatesEnum::existsAtPath)
                .toList();

        assertEquals(List.of(), restored, "remove restored templates from KNOWN_MISSING");
    }
}
