package dev.frostguard.api.configs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConfigurationKeyEnumTest {

    @Test
    void retiredIntelEraSettingRemainsReadableButIsNotExposed() {
        assertTrue(ConfigurationKeyEnum.INTEL_FC_ERA_BOOL.isLegacyOnly());
        assertFalse(ConfigurationKeyEnum.byCategory(ConfigurationKeyEnum.ConfigCategory.INTEL)
                .contains(ConfigurationKeyEnum.INTEL_FC_ERA_BOOL));
    }
}
