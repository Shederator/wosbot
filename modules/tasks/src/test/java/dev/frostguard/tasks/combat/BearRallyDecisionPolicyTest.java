package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class BearRallyDecisionPolicyTest {

    @Test
    public void testDisabledAdvancedJoinUsesDefaultPath() {
        AccountDescriptor account = new AccountDescriptor(1L);
        account.setConfig(ConfigurationKeyEnum.BEAR_TRAP_ADVANCED_JOIN_ENABLED_BOOL, false);

        BearRallyCandidate candidate = new BearRallyCandidate(
                new PointData(100, 100), new AreaData(new PointData(0, 0), new PointData(200, 200)),
                "Host1", 1, 6, 25_000L, 100_000L, 75_000L, Duration.ofMinutes(4), Instant.now(), true);

        BearRallyDecisionPolicy.Decision decision = BearRallyDecisionPolicy.evaluate(
                candidate, account, LocalDateTime.now(), Clock.systemUTC());

        assertEquals(BearRallyDecisionPolicy.DecisionResult.JOIN, decision.result());
        assertFalse(decision.frenzyActive());
    }

    @Test
    public void testFrenzyModeBypassesMemberThreshold() {
        AccountDescriptor account = new AccountDescriptor(1L);
        account.setConfig(ConfigurationKeyEnum.BEAR_TRAP_ADVANCED_JOIN_ENABLED_BOOL, true);
        account.setConfig(ConfigurationKeyEnum.BEAR_TRAP_FRENZY_MODE_ENABLED_BOOL, true);
        account.setConfig(ConfigurationKeyEnum.BEAR_TRAP_FRENZY_START_MINUTE_INT, 22);
        account.setConfig(ConfigurationKeyEnum.BEAR_TRAP_MIN_MEMBER_COUNT_INT, 5);

        Instant fixedNow = Instant.parse("2026-08-14T10:25:00Z");
        Clock fixedClock = Clock.fixed(fixedNow, ZoneId.of("UTC"));
        LocalDateTime trapStartTime = LocalDateTime.ofInstant(Instant.parse("2026-08-14T10:00:00Z"), ZoneId.of("UTC"));

        BearRallyCandidate candidate = new BearRallyCandidate(
                new PointData(100, 100), new AreaData(new PointData(0, 0), new PointData(200, 200)),
                "Host1", 1, 6, 25_000L, 100_000L, 75_000L, Duration.ofMinutes(4), fixedNow, true);

        BearRallyDecisionPolicy.Decision decision = BearRallyDecisionPolicy.evaluate(
                candidate, account, trapStartTime, fixedClock);

        assertEquals(BearRallyDecisionPolicy.DecisionResult.JOIN, decision.result());
        assertTrue(decision.frenzyActive());
    }

    @Test
    void frenzyStillEnforcesRemainingCapacity() {
        AccountDescriptor account = new AccountDescriptor(1L);
        account.setConfig(ConfigurationKeyEnum.BEAR_TRAP_ADVANCED_JOIN_ENABLED_BOOL, true);
        account.setConfig(ConfigurationKeyEnum.BEAR_TRAP_FRENZY_MODE_ENABLED_BOOL, true);
        account.setConfig(ConfigurationKeyEnum.BEAR_TRAP_FRENZY_START_MINUTE_INT, 22);
        account.setConfig(ConfigurationKeyEnum.BEAR_TRAP_MIN_MEMBER_COUNT_INT, 5);
        account.setConfig(ConfigurationKeyEnum.BEAR_TRAP_MIN_REMAINING_CAPACITY_INT, 80_000);
        Instant fixedNow = Instant.parse("2026-08-14T10:25:00Z");
        Clock fixedClock = Clock.fixed(fixedNow, ZoneId.of("UTC"));
        LocalDateTime start = LocalDateTime.ofInstant(
                Instant.parse("2026-08-14T10:00:00Z"), ZoneId.of("UTC"));
        BearRallyCandidate candidate = new BearRallyCandidate(
                new PointData(100, 100), new AreaData(new PointData(0, 0), new PointData(200, 200)),
                "Host1", 1, 6, 50_000L, 100_000L, 50_000L,
                Duration.ofMinutes(4), fixedNow, true);

        BearRallyDecisionPolicy.Decision decision = BearRallyDecisionPolicy.evaluate(
                candidate, account, start, fixedClock);

        assertEquals(BearRallyDecisionPolicy.DecisionResult.SKIP_MIN_REMAINING_CAPACITY_NOT_MET,
                decision.result());
    }

    @Test
    public void rejectsCandidateWhenCurrentMembersAreBelowThreshold() {
        AccountDescriptor account = new AccountDescriptor(1L);
        account.setConfig(ConfigurationKeyEnum.BEAR_TRAP_ADVANCED_JOIN_ENABLED_BOOL, true);
        account.setConfig(ConfigurationKeyEnum.BEAR_TRAP_MIN_MEMBER_COUNT_INT, 5);

        BearRallyCandidate candidate = new BearRallyCandidate(
                new PointData(100, 100), new AreaData(new PointData(0, 0), new PointData(200, 200)),
                "Host1", 1, 6, 25_000L, 100_000L, 75_000L, Duration.ofMinutes(4), Instant.now(), true);

        BearRallyDecisionPolicy.Decision decision = BearRallyDecisionPolicy.evaluate(
                candidate, account, null, Clock.systemUTC());

        assertEquals(BearRallyDecisionPolicy.DecisionResult.SKIP_MIN_MEMBERS_NOT_MET, decision.result());
    }

    @Test
    void evaluatesTroopCapacityIndependentlyFromMemberCount() {
        AccountDescriptor account = new AccountDescriptor(1L);
        account.setConfig(ConfigurationKeyEnum.BEAR_TRAP_ADVANCED_JOIN_ENABLED_BOOL, true);
        account.setConfig(ConfigurationKeyEnum.BEAR_TRAP_MIN_MEMBER_COUNT_INT, 2);
        account.setConfig(ConfigurationKeyEnum.BEAR_TRAP_MIN_RALLY_CAPACITY_INT, 90_000);
        account.setConfig(ConfigurationKeyEnum.BEAR_TRAP_MIN_REMAINING_CAPACITY_INT, 40_000);

        BearRallyCandidate candidate = new BearRallyCandidate(
                new PointData(100, 100), new AreaData(new PointData(0, 0), new PointData(200, 200)),
                "Host1", 3, 6, 80_000L, 100_000L, 20_000L, Duration.ofMinutes(4), Instant.now(), true);

        BearRallyDecisionPolicy.Decision decision = BearRallyDecisionPolicy.evaluate(
                candidate, account, null, Clock.systemUTC());

        assertEquals(BearRallyDecisionPolicy.DecisionResult.SKIP_MIN_REMAINING_CAPACITY_NOT_MET, decision.result());
    }

    @Test
    void rejectsInternallyInconsistentCandidate() {
        AccountDescriptor account = new AccountDescriptor(1L);
        account.setConfig(ConfigurationKeyEnum.BEAR_TRAP_ADVANCED_JOIN_ENABLED_BOOL, true);
        BearRallyCandidate candidate = new BearRallyCandidate(
                new PointData(100, 100), new AreaData(new PointData(0, 0), new PointData(200, 200)),
                "Host1", 7, 6, 120_000L, 100_000L, -20_000L, Duration.ofMinutes(4), Instant.now(), true);

        BearRallyDecisionPolicy.Decision decision = BearRallyDecisionPolicy.evaluate(
                candidate, account, null, Clock.systemUTC());

        assertEquals(BearRallyDecisionPolicy.DecisionResult.SKIP_INVALID_CANDIDATE, decision.result());
    }

    @Test
    void rejectsUnknownCapacityInsteadOfPrioritizingIt() {
        AccountDescriptor account = new AccountDescriptor(1L);
        account.setConfig(ConfigurationKeyEnum.BEAR_TRAP_ADVANCED_JOIN_ENABLED_BOOL, true);
        BearRallyCandidate candidate = new BearRallyCandidate(
                new PointData(100, 100), new AreaData(new PointData(0, 0), new PointData(200, 200)),
                "Host1", 1, 6, -1L, -1L, -1L,
                Duration.ofMinutes(4), Instant.now(), true);

        BearRallyDecisionPolicy.Decision decision = BearRallyDecisionPolicy.evaluate(
                candidate, account, null, Clock.systemUTC());

        assertEquals(BearRallyDecisionPolicy.DecisionResult.SKIP_INVALID_CANDIDATE, decision.result());
    }
}
