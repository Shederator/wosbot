package dev.frostguard.app.panel.emulator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.configs.IdleBehaviorEnum;
import org.junit.jupiter.api.Test;

class IdleBehaviorEnumTest {

    @Test
    void keepRunningDoesNotRequireAnIdleTimeout() {
        assertFalse(IdleBehaviorEnum.KEEP_RUNNING.requiresIdleTimeout());
        assertFalse(IdleBehaviorEnum.KEEP_RUNNING.terminatesSession());
        assertFalse(IdleBehaviorEnum.KEEP_RUNNING.backgroundsApp());
    }

    @Test
    void actionableBehaviorsRequireAnIdleTimeout() {
        assertTrue(IdleBehaviorEnum.CLOSE_EMULATOR.requiresIdleTimeout());
        assertTrue(IdleBehaviorEnum.SEND_TO_BACKGROUND.requiresIdleTimeout());
        assertTrue(IdleBehaviorEnum.PC_SLEEP.requiresIdleTimeout());
    }
}
