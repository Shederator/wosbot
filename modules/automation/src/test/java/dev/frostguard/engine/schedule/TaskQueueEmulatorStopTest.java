package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.IdleBehaviorEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.engine.emulator.EmulatorStopResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskQueueEmulatorStopTest {

    @BeforeAll
    static void initializeTestWorkspace() {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
    }

    @Test
    void idleKeepsSlotWhenShutdownIsUnconfirmed() {
        RecordingQueue queue = new RecordingQueue(EmulatorStopResult.Status.UNCONFIRMED);
        queue.markSlotAcquired();

        assertFalse(queue.suspendDevice(LocalDateTime.now().plusMinutes(10), true));

        assertEquals(1, queue.stopRequests);
        assertEquals(0, queue.slotReleases);
    }

    @Test
    void pcSleepIsCancelledWhenShutdownIsUnconfirmed() {
        RecordingQueue queue = new RecordingQueue(EmulatorStopResult.Status.UNCONFIRMED);
        queue.markSlotAcquired();

        assertFalse(queue.triggerPcSleep(LocalDateTime.now().plusMinutes(10)));

        assertEquals(1, queue.stopRequests);
        assertEquals(0, queue.slotReleases);
    }

    @Test
    void confirmedIdleShutdownReleasesSlot() {
        RecordingQueue queue = new RecordingQueue(EmulatorStopResult.Status.CONFIRMED_STOPPED);
        queue.markSlotAcquired();

        assertTrue(queue.suspendDevice(LocalDateTime.now().plusMinutes(10), true));

        assertEquals(1, queue.stopRequests);
        assertEquals(1, queue.slotReleases);
    }

    private static final class RecordingQueue extends TaskQueue {
        private final EmulatorStopResult.Status stopStatus;
        private int stopRequests;
        private int slotReleases;

        private RecordingQueue(EmulatorStopResult.Status stopStatus) {
            super(new AccountDescriptor(1L, "Stop test", "1", true, 100L, 30L));
            this.stopStatus = stopStatus;
        }

        @Override
        protected IdleBehaviorEnum resolveIdleBehavior() {
            return IdleBehaviorEnum.CLOSE_EMULATOR;
        }

        @Override
        protected EmulatorStopResult requestIdleEmulatorStop() {
            stopRequests++;
            return new EmulatorStopResult(stopStatus, EmulatorStopResult.Method.VENDOR, "observed state");
        }

        @Override
        protected void releaseEmulatorSlotLease() {
            slotReleases++;
        }
    }
}
