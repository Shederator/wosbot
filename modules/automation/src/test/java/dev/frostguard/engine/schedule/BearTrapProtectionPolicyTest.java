package dev.frostguard.engine.schedule;

import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_BLOCK_RALLIES_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_ENABLED_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_PAUSE_ALL_TASKS_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_BLOCK_RALLIES_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_ENABLED_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_PAUSE_ALL_TASKS_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_SCHEDULE_DATETIME_STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;

class BearTrapProtectionPolicyTest {

    private static final LocalDateTime TRAP_1 = LocalDateTime.of(2026, 7, 20, 18, 0);
    private static final LocalDateTime TRAP_2 = LocalDateTime.of(2026, 7, 20, 18, 20);
    private static final DateTimeFormatter CONFIG_DATE_TIME = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    @AfterEach
    void clearVisualProtection() {
        BearTrapVisualProtection.clearForTests();
    }

    @Test
    void detectedBearIconAlwaysBlocksUnrelatedRallyStartsButNotOtherTasks() {
        AccountDescriptor profile = new AccountDescriptor(1L);
        Clock detectedAt = clockAt(TRAP_1);
        BearTrapVisualProtection.markDetected(profile.getId(), detectedAt);

        var rally = BearTrapProtectionPolicy.evaluateTask(
                profile, TpDailyTaskEnum.EVENT_POLAR_TERROR, detectedAt);
        var gather = BearTrapProtectionPolicy.evaluateTask(
                profile, TpDailyTaskEnum.GATHER_RESOURCES, detectedAt);
        var afterHold = BearTrapProtectionPolicy.evaluateTask(
                profile, TpDailyTaskEnum.EVENT_POLAR_TERROR, clockAt(TRAP_1.plusSeconds(36)));

        assertTrue(rally.blocked());
        assertEquals("icon", rally.trapNumbers());
        assertFalse(gather.blocked());
        assertFalse(afterHold.blocked());
    }

    @Test
    void rallyStartingTasksUseTheSharedPreparationAndProtectionLeadTime() {
        AccountDescriptor profile = profileWithTimer1();
        profile.setConfig(BEAR_TRAP_PREPARATION_TIME_INT, 7);

        var beforeWindow = BearTrapProtectionPolicy.evaluateTask(
                profile,
                TpDailyTaskEnum.EVENT_POLAR_TERROR,
                clockAt(TRAP_1.minusMinutes(7).minusSeconds(1)));
        var atWindowStart = BearTrapProtectionPolicy.evaluateTask(
                profile,
                TpDailyTaskEnum.EVENT_POLAR_TERROR,
                clockAt(TRAP_1.minusMinutes(7)));

        assertFalse(beforeWindow.blocked());
        assertTrue(atWindowStart.blocked());
        assertEquals(BearTrapProtectionPolicy.BlockReason.RALLY_START, atWindowStart.reason());
        assertEquals("1", atWindowStart.trapNumbers());
        assertEquals(TRAP_1.plusMinutes(30).plusSeconds(5).toInstant(ZoneOffset.UTC),
                atWindowStart.releaseAt());
    }

    @Test
    void nonRallyTasksRemainAllowedWhenPauseAllIsOff() {
        AccountDescriptor profile = profileWithTimer1();

        var decision = BearTrapProtectionPolicy.evaluateTask(
                profile,
                TpDailyTaskEnum.GATHER_RESOURCES,
                clockAt(TRAP_1));

        assertFalse(decision.blocked());
    }

    @Test
    void pauseAllBlocksNormalTasksButNotBearOrLifecycleTasks() {
        AccountDescriptor profile = profileWithTimer1();
        profile.setConfig(BEAR_TRAP_TIMER_1_PAUSE_ALL_TASKS_BOOL, true);
        Clock clock = clockAt(TRAP_1);

        var gather = BearTrapProtectionPolicy.evaluateTask(
                profile, TpDailyTaskEnum.GATHER_RESOURCES, clock);
        var bear = BearTrapProtectionPolicy.evaluateTask(
                profile, TpDailyTaskEnum.BEAR_TRAP, clock);
        var initialize = BearTrapProtectionPolicy.evaluateTask(
                profile, TpDailyTaskEnum.INITIALIZE, clock);

        assertTrue(gather.blocked());
        assertEquals(BearTrapProtectionPolicy.BlockReason.ALL_TASKS, gather.reason());
        assertFalse(bear.blocked());
        assertFalse(initialize.blocked());
    }

    @Test
    void pauseAllAlsoStopsAnAlreadyRunningTaskAtItsRallyGate() {
        AccountDescriptor profile = profileWithTimer1();
        profile.setConfig(BEAR_TRAP_TIMER_1_BLOCK_RALLIES_BOOL, false);
        profile.setConfig(BEAR_TRAP_TIMER_1_PAUSE_ALL_TASKS_BOOL, true);

        var decision = BearTrapProtectionPolicy.evaluateRallyStart(profile, clockAt(TRAP_1));

        assertTrue(decision.blocked());
        assertEquals(BearTrapProtectionPolicy.BlockReason.RALLY_START, decision.reason());
    }

    @Test
    void secondTimerWorksWithoutBearAutomationOrFirstTimer() {
        AccountDescriptor profile = new AccountDescriptor(1L);
        profile.setConfig(BEAR_TRAP_TIMER_2_ENABLED_BOOL, true);
        profile.setConfig(BEAR_TRAP_TIMER_2_BLOCK_RALLIES_BOOL, true);
        profile.setConfig(BEAR_TRAP_TIMER_2_PAUSE_ALL_TASKS_BOOL, false);
        profile.setConfig(BEAR_TRAP_TIMER_2_SCHEDULE_DATETIME_STRING, TRAP_2.format(CONFIG_DATE_TIME));

        var decision = BearTrapProtectionPolicy.evaluateRallyStart(profile, clockAt(TRAP_2));

        assertTrue(decision.blocked());
        assertEquals("2", decision.trapNumbers());
    }

    @Test
    void legacyBearAutomationKeepsTimerOneProtectionUntilSwitchIsPersisted() {
        AccountDescriptor profile = new AccountDescriptor(1L);
        profile.setConfig(BEAR_TRAP_EVENT_BOOL, true);
        profile.setConfig(BEAR_TRAP_SCHEDULE_DATETIME_STRING, TRAP_1.format(CONFIG_DATE_TIME));

        var legacyDecision = BearTrapProtectionPolicy.evaluateRallyStart(profile, clockAt(TRAP_1));
        profile.setConfig(BEAR_TRAP_TIMER_1_ENABLED_BOOL, false);
        var explicitlyDisabledDecision = BearTrapProtectionPolicy.evaluateRallyStart(profile, clockAt(TRAP_1));

        assertTrue(legacyDecision.blocked());
        assertFalse(explicitlyDisabledDecision.blocked());
    }

    @Test
    void overlappingWindowsUseLatestReleaseAndStrongestMode() {
        AccountDescriptor profile = profileWithTimer1();
        profile.setConfig(BEAR_TRAP_TIMER_2_ENABLED_BOOL, true);
        profile.setConfig(BEAR_TRAP_TIMER_2_BLOCK_RALLIES_BOOL, true);
        profile.setConfig(BEAR_TRAP_TIMER_2_PAUSE_ALL_TASKS_BOOL, true);
        profile.setConfig(BEAR_TRAP_TIMER_2_SCHEDULE_DATETIME_STRING, TRAP_2.format(CONFIG_DATE_TIME));

        var decision = BearTrapProtectionPolicy.evaluateTask(
                profile,
                TpDailyTaskEnum.EVENT_HERO_MISSION,
                clockAt(TRAP_2.minusMinutes(5)));

        assertTrue(decision.blocked());
        assertEquals(BearTrapProtectionPolicy.BlockReason.ALL_TASKS, decision.reason());
        assertEquals("1, 2", decision.trapNumbers());
        assertEquals(TRAP_2.plusMinutes(30).plusSeconds(5).toInstant(ZoneOffset.UTC),
                decision.releaseAt());
    }

    private static AccountDescriptor profileWithTimer1() {
        AccountDescriptor profile = new AccountDescriptor(1L);
        profile.setConfig(BEAR_TRAP_TIMER_1_ENABLED_BOOL, true);
        profile.setConfig(BEAR_TRAP_TIMER_1_BLOCK_RALLIES_BOOL, true);
        profile.setConfig(BEAR_TRAP_TIMER_1_PAUSE_ALL_TASKS_BOOL, false);
        profile.setConfig(BEAR_TRAP_SCHEDULE_DATETIME_STRING, TRAP_1.format(CONFIG_DATE_TIME));
        return profile;
    }

    private static Clock clockAt(LocalDateTime dateTime) {
        Instant instant = dateTime.toInstant(ZoneOffset.UTC);
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
