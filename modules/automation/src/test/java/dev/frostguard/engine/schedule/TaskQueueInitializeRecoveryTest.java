package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.service.ProfileService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TaskQueueInitializeRecoveryTest {

    @Test
    void schedulerRunsBoundedInitializeRetryWithoutAnotherImminentTask() {
        AccountDescriptor profile = new AccountDescriptor(
                null, "Initialize recovery " + UUID.randomUUID(), "0", false, 100L, 30L);
        ProfileService.obtain().createAccount(profile);
        BoundedRecoveryInitialize task = new BoundedRecoveryInitialize(profile);
        RecordingQueue queue = new RecordingQueue(profile);
        queue.enqueue(task);

        queue.runSchedulerTick();

        assertEquals(1, task.executionCount);
        assertEquals(1, queue.getNextQueuedTaskTypes(1).size());

        queue.runSchedulerTick();

        assertEquals(2, task.executionCount,
                "the recovery Initialize must bypass the no-imminent-task guard once");
        assertFalse(task.isRecurring());
        assertEquals(0, queue.getNextQueuedTaskTypes(1).size());
        assertEquals(1, queue.slotAcquisitionCount,
                "the immediate retry must retain the existing queue session lease");

        queue.runSchedulerTick();

        assertEquals(2, task.executionCount, "successful Initialize must not be repeated");
    }

    @Test
    void verifiedOverlayRecoveryCompletesInitializeWithoutQueueRetry() {
        AccountDescriptor profile = new AccountDescriptor(
                null, "Initialize overlay " + UUID.randomUUID(), "0", false, 100L, 30L);
        ProfileService.obtain().createAccount(profile);
        VerifiedOverlayInitialize task = new VerifiedOverlayInitialize(profile);
        RecordingQueue queue = new RecordingQueue(profile);
        queue.enqueue(task);

        queue.runSchedulerTick();

        assertEquals(1, task.executionCount);
        assertEquals(1, task.dismissalCount);
        assertFalse(task.isRecurring());
        assertEquals(0, queue.getNextQueuedTaskTypes(1).size());
        assertEquals(1, queue.slotAcquisitionCount,
                "successful verified overlay recovery must retain the queue session lease");

        queue.runSchedulerTick();

        assertEquals(1, task.executionCount,
                "verified overlay recovery must not create an Initialize retry");
    }

    private static final class BoundedRecoveryInitialize extends DelayedTask {

        private int executionCount;

        private BoundedRecoveryInitialize(AccountDescriptor profile) {
            super(profile, TpDailyTaskEnum.INITIALIZE);
        }

        @Override
        protected void execute() {
            executionCount++;
            setRecurring(executionCount == 1);
        }
    }

    private static final class VerifiedOverlayInitialize extends DelayedTask {

        private int executionCount;
        private int dismissalCount;

        private VerifiedOverlayInitialize(AccountDescriptor profile) {
            super(profile, TpDailyTaskEnum.INITIALIZE);
        }

        @Override
        protected void execute() {
            executionCount++;
            dismissalCount++;
            setRecurring(false);
        }
    }

    private static final class RecordingQueue extends TaskQueue {

        private int slotAcquisitionCount;
        private RecordingQueue(AccountDescriptor profile) {
            super(profile);
        }

        @Override
        protected void acquireSlot() {
            slotAcquisitionCount++;
            markSlotAcquired();
        }

        @Override
        protected void handleIdleTransitions() {
        }

        @Override
        protected void sleepSchedulerTick(long millis) {
        }
    }
}
