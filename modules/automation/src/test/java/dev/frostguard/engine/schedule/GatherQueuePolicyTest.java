package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatherQueuePolicyTest {

    // Gather may be configured above the four resource types; deployment still avoids duplicate
    // resources until every enabled type is already active.
    @Test
    void keepsConfiguredQueueCount() {
        assertEquals(8, GatherQueuePolicy.resolveActiveQueueLimit(8));
        assertEquals(4, GatherQueuePolicy.resolveActiveQueueLimit(4));
        assertEquals(1, GatherQueuePolicy.resolveActiveQueueLimit(0));
    }

    // Changed by pernerch | Date: 2026-07-02 | Why: verify duplicate resource marches remain blocked.
    @Test
    void preventsDuplicateMarchDeploymentsPerResource() {
        List<String> activeMarches = List.of("MEAT");

        assertFalse(GatherQueuePolicy.allowMarchDeployment(activeMarches, "MEAT"));
        assertTrue(GatherQueuePolicy.allowMarchDeployment(activeMarches, "WOOD"));
    }

    // Changed by pernerch | Date: 2026-07-02 | Why: verify gather defer behavior when Bear Trap/Intel is pending.
    @Test
    void defersGatherWhenHigherPriorityMarchTasksArePending() {
        assertTrue(GatherQueuePolicy.shouldDeferGatherForPendingTasks(List.of(TpDailyTaskEnum.BEAR_TRAP)));
        assertTrue(GatherQueuePolicy.shouldDeferGatherForPendingTasks(List.of(TpDailyTaskEnum.INTEL)));
        assertFalse(GatherQueuePolicy.shouldDeferGatherForPendingTasks(List.of(TpDailyTaskEnum.GATHER_RESOURCES)));
    }
}
