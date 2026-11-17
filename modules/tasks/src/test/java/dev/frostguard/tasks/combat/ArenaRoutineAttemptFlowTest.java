package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.engine.schedule.TaskOutcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaRoutineAttemptFlowTest {

    @BeforeAll
    static void initializeWorkspace() {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
    }

    @Test
    void earlyVisitDefersToTodaysActivationWithoutReportingSuccess() {
        assertDeferredUntil("2026-10-10T23:40Z", "2026-10-10T23:50Z");
    }

    @Test
    void lateVisitDefersToTomorrowsActivationWithoutReportingSuccess() {
        assertDeferredUntil("2026-10-10T23:57Z", "2026-10-11T23:50Z");
    }

    private static void assertDeferredUntil(String observedTime, String activation) {
        var profile = new AccountDescriptor(912501L, "Synthetic arena", "0", false, 100L, 30L);
        var routine = new ArenaRoutine(profile, TpDailyTaskEnum.ARENA);

        assertFalse(routine.isWithinExecutionWindow("23:50", ZonedDateTime.parse(observedTime)));
        assertEquals(TaskOutcome.DEFERRED, routine.getExecutionOutcome());
        assertEquals(ZonedDateTime.parse(activation).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(),
                routine.getScheduled());
    }

    @Test
    void skipsRefreshAfterDefeatWhenLastAttemptWasConsumed() {
        assertFalse(ArenaRoutine.shouldRefreshAfterDefeat(0));
    }

    @Test
    void keepsRefreshAfterDefeatWhenAnotherAttemptRemains() {
        assertTrue(ArenaRoutine.shouldRefreshAfterDefeat(1));
    }
}
