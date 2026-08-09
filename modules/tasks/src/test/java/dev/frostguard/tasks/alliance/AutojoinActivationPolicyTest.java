package dev.frostguard.tasks.alliance;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutojoinActivationPolicyTest {

    @Test
    void defersForIntelWithinNextFiveTasks() {
        List<TpDailyTaskEnum> queuedTasks = List.of(
                TpDailyTaskEnum.ALLIANCE_CHESTS,
                TpDailyTaskEnum.GATHER_RESOURCES,
                TpDailyTaskEnum.INTEL,
                TpDailyTaskEnum.DAILY_MISSIONS,
                TpDailyTaskEnum.ALLIANCE_SHOP);

        assertEquals(TpDailyTaskEnum.INTEL,
                AutojoinActivationPolicy.findUpcomingResetTask(queuedTasks).orElseThrow());
    }

    @Test
    void defersForBearTrapWithinNextFiveTasks() {
        assertEquals(TpDailyTaskEnum.BEAR_TRAP,
                AutojoinActivationPolicy.findUpcomingResetTask(List.of(TpDailyTaskEnum.BEAR_TRAP))
                        .orElseThrow());
    }

    @Test
    void doesNotDeferForResetTaskAfterLookaheadWindow() {
        List<TpDailyTaskEnum> queuedTasks = List.of(
                TpDailyTaskEnum.ALLIANCE_CHESTS,
                TpDailyTaskEnum.GATHER_RESOURCES,
                TpDailyTaskEnum.DAILY_MISSIONS,
                TpDailyTaskEnum.ALLIANCE_SHOP,
                TpDailyTaskEnum.ALLIANCE_TECH,
                TpDailyTaskEnum.INTEL);

        assertTrue(AutojoinActivationPolicy.findUpcomingResetTask(queuedTasks).isEmpty());
    }

    @Test
    void doesNotDeferWhenUpcomingTasksLeaveAutojoinUntouched() {
        assertTrue(AutojoinActivationPolicy.findUpcomingResetTask(List.of(
                TpDailyTaskEnum.ALLIANCE_CHESTS,
                TpDailyTaskEnum.GATHER_RESOURCES)).isEmpty());
    }
}
