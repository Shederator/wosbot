package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.AccountDescriptor;

class BearTrapParticipationScheduleTest {

    @Test
    void selectedTrapTwoUsesTimerTwoRegardlessOfProtectionSwitches() {
        AccountDescriptor profile = new AccountDescriptor(1L);
        profile.setConfig(ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT, 2);
        profile.setConfig(ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT, 10);
        profile.setConfig(ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_ENABLED_BOOL, false);
        profile.setConfig(ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_ENABLED_BOOL, true);
        profile.setConfig(ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING, "28-07-2026 01:00");
        profile.setConfig(ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_SCHEDULE_DATETIME_STRING, "29-07-2026 19:00");

        BearTrapParticipationSchedule.Plan plan = BearTrapParticipationSchedule.resolve(
                profile,
                Clock.fixed(Instant.parse("2026-07-28T12:00:00Z"), ZoneId.of("UTC")),
                ZoneId.of("Europe/London")).orElseThrow();

        assertEquals(2, plan.trapNumber());
        assertEquals(ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_SCHEDULE_DATETIME_STRING, plan.scheduleKey());
        assertEquals(LocalDateTime.of(2026, 7, 29, 19, 50), plan.nextRun());
    }

    @Test
    void activeSelectedWindowRunsImmediately() {
        AccountDescriptor profile = profileWithTrapTwo();
        Clock insideWindow = Clock.fixed(
                Instant.parse("2026-07-29T18:55:00Z"),
                ZoneId.of("UTC"));

        BearTrapParticipationSchedule.Plan plan = BearTrapParticipationSchedule.resolve(
                profile,
                insideWindow,
                ZoneId.of("UTC")).orElseThrow();

        assertEquals(LocalDateTime.of(2026, 7, 29, 18, 55), plan.nextRun());
    }

    @Test
    void expiredSelectedWindowUsesItsNextFortyEightHourPreparationStart() {
        AccountDescriptor profile = profileWithTrapTwo();
        Clock afterWindow = Clock.fixed(
                Instant.parse("2026-07-30T00:00:00Z"),
                ZoneId.of("UTC"));

        BearTrapParticipationSchedule.Plan plan = BearTrapParticipationSchedule.resolve(
                profile,
                afterWindow,
                ZoneId.of("UTC")).orElseThrow();

        assertEquals(LocalDateTime.of(2026, 7, 31, 18, 50), plan.nextRun());
    }

    @Test
    void incompleteSelectedTimerDoesNotReplaceAPersistedSchedule() {
        AccountDescriptor profile = new AccountDescriptor(1L);
        profile.setConfig(ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT, 2);
        profile.setConfig(ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING, "28-07-2026 01:00");

        assertTrue(BearTrapParticipationSchedule.resolve(
                profile,
                Clock.systemUTC(),
                ZoneId.of("UTC")).isEmpty());
    }

    private static AccountDescriptor profileWithTrapTwo() {
        AccountDescriptor profile = new AccountDescriptor(1L);
        profile.setConfig(ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT, 2);
        profile.setConfig(ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT, 10);
        profile.setConfig(ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_SCHEDULE_DATETIME_STRING, "29-07-2026 19:00");
        return profile;
    }
}
