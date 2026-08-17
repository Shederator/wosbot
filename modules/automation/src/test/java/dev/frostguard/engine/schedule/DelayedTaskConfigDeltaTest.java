package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.AccountDescriptor;

class DelayedTaskConfigDeltaTest {

    @Test
    void persistsOnlyKeysChangedByTheRunningTask() {
        AccountDescriptor profile = new AccountDescriptor(7L);
        profile.setConfig(ConfigurationKeyEnum.GATHER_COAL_BOOL, false);
        profile.setConfig(ConfigurationKeyEnum.GATHER_MEAT_BOOL, false);
        Map<String, String> before = snapshot(profile);

        profile.setConfig(ConfigurationKeyEnum.GATHER_MEAT_BOOL, true);

        assertEquals(Map.of(ConfigurationKeyEnum.GATHER_MEAT_BOOL, "true"),
                DelayedTask.changedProfileSettings(before, profile));
    }

    @Test
    void unchangedStaleValuesAreNotIncludedInRuntimeWriteback() {
        AccountDescriptor profile = new AccountDescriptor(7L);
        profile.setConfig(ConfigurationKeyEnum.GATHER_COAL_BOOL, false);
        profile.setConfig(ConfigurationKeyEnum.GATHER_MEAT_BOOL, false);
        Map<String, String> before = snapshot(profile);

        profile.setConfig(ConfigurationKeyEnum.GATHER_MEAT_BOOL, true);

        Map<ConfigurationKeyEnum, String> changed = DelayedTask.changedProfileSettings(before, profile);
        assertEquals(1, changed.size());
        assertEquals(null, changed.get(ConfigurationKeyEnum.GATHER_COAL_BOOL));
    }

    private Map<String, String> snapshot(AccountDescriptor profile) {
        Map<String, String> values = new LinkedHashMap<>();
        profile.getConfigs().forEach(config ->
                values.put(config.getConfigurationName(), config.getValue()));
        return values;
    }
}
