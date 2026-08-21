package dev.frostguard.tasks.dailies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class IntelCyclePolicyTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Test
    void beastFollowUpBypassesMissingDailyGainUntilCooldownCompletes() {
        IntelCyclePolicy policy = new IntelCyclePolicy();
        LocalDateTime firstRun = LocalDateTime.of(2026, 8, 21, 13, 59);

        IntelCyclePolicy.Decision available = policy.evaluateDailyAvailability(true, firstRun, BERLIN);
        assertEquals(IntelCyclePolicy.Action.START_AVAILABLE_CYCLE, available.action());
        assertNull(available.nextRun());
        assertTrue(policy.isCycleInProgress());

        IntelCyclePolicy.Decision followUp = policy.evaluateDailyAvailability(
                false, firstRun.plusMinutes(4), BERLIN);
        assertEquals(IntelCyclePolicy.Action.RESUME_ACTIVE_CYCLE, followUp.action());
        assertNull(followUp.nextRun());
        assertTrue(policy.isCycleInProgress());

        policy.completeCycle();
        IntelCyclePolicy.Decision empty = policy.evaluateDailyAvailability(
                false, firstRun.plusMinutes(5), BERLIN);
        assertEquals(IntelCyclePolicy.Action.WAIT_FOR_NEXT_REFRESH, empty.action());
        assertEquals(LocalDateTime.of(2026, 8, 21, 18, 0, 1), empty.nextRun());
        assertFalse(policy.isCycleInProgress());
    }

    @Test
    void emptyRunMovesAcrossMidnightUsingUtcRefreshBoundaries() {
        IntelCyclePolicy policy = new IntelCyclePolicy();

        IntelCyclePolicy.Decision decision = policy.evaluateDailyAvailability(
                false, LocalDateTime.of(2026, 8, 21, 18, 0, 1), BERLIN);

        assertEquals(IntelCyclePolicy.Action.WAIT_FOR_NEXT_REFRESH, decision.action());
        assertEquals(LocalDateTime.of(2026, 8, 22, 2, 0, 1), decision.nextRun());
    }
}
