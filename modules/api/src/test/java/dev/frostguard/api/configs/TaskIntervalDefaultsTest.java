package dev.frostguard.api.configs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AccountDescriptor;

class TaskIntervalDefaultsTest {

    @Test
    void recurringTaskDefaultsReflectTheirIndividualCadence() {
        assertMinutes(ConfigurationKeyEnum.ALLIANCE_CHESTS_OFFSET_INT, 240);
        assertMinutes(ConfigurationKeyEnum.ALLIANCE_TECH_OFFSET_INT, 200);
        assertMinutes(ConfigurationKeyEnum.ALLIANCE_TRIUMPH_OFFSET_INT, 240);
        assertMinutes(ConfigurationKeyEnum.CITY_ACCEPT_NEW_SURVIVORS_OFFSET_INT, 360);
        assertMinutes(ConfigurationKeyEnum.DAILY_MISSION_OFFSET_INT, 720);
        assertMinutes(ConfigurationKeyEnum.MAIL_REWARDS_OFFSET_INT, 720);
        assertMinutes(ConfigurationKeyEnum.LIFE_ESSENCE_OFFSET_INT, 360);
        assertMinutes(ConfigurationKeyEnum.INT_EXPLORATION_CHEST_OFFSET, 360);
    }

    @Test
    void allianceLifeEssenceKeepsItsAvailabilityRetryCadence() {
        assertMinutes(ConfigurationKeyEnum.ALLIANCE_LIFE_ESSENCE_OFFSET_INT, 60);
    }

    @Test
    void missingProfileSettingUsesTheRevisedDefault() {
        AccountDescriptor profile = new AccountDescriptor(1L);

        assertEquals(240, profile.getConfig(ConfigurationKeyEnum.ALLIANCE_CHESTS_OFFSET_INT, Integer.class));
    }

    @Test
    void explicitlySavedLegacyValueIsPreserved() {
        AccountDescriptor profile = new AccountDescriptor(1L);
        profile.setConfig(ConfigurationKeyEnum.ALLIANCE_CHESTS_OFFSET_INT, 60);

        assertEquals(60, profile.getConfig(ConfigurationKeyEnum.ALLIANCE_CHESTS_OFFSET_INT, Integer.class));
    }

    private void assertMinutes(ConfigurationKeyEnum key, int expected) {
        assertEquals(Integer.toString(expected), key.getDefaultValue(), key.name());
    }
}
