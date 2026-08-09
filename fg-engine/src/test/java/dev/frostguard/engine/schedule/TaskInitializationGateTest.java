package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;

class TaskInitializationGateTest {

    @Test
    void blocksProfileWorkUntilInitializationCompletes() {
        TaskInitializationGate gate = new TaskInitializationGate();

        assertTrue(gate.allows(TpDailyTaskEnum.INITIALIZE));
        assertFalse(gate.allows(TpDailyTaskEnum.GATHER_RESOURCES));

        gate.completeInitialization();

        assertTrue(gate.allows(TpDailyTaskEnum.GATHER_RESOURCES));
    }

    @Test
    void closesAgainAfterAProfileSwitchFailure() {
        TaskInitializationGate gate = new TaskInitializationGate();
        gate.completeInitialization();

        gate.requireInitialization();

        assertTrue(gate.isInitializationRequired());
        assertFalse(gate.allows(TpDailyTaskEnum.DAILY_MISSIONS));
    }
}
