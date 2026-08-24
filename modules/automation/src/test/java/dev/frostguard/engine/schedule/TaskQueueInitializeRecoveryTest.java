package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.data.entity.DailyTask;
import dev.frostguard.data.repository.DailyTaskRepository;
import dev.frostguard.engine.error.ActionRequiredContext;
import dev.frostguard.engine.error.ProfileCooldownException;
import dev.frostguard.engine.service.ProfileService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskQueueInitializeRecoveryTest {

    @Test
    void unknownOverlayCooldownStopsOnlyGameAndDoesNotImmediatelyRetryInitialize() {
        AccountDescriptor profile = new AccountDescriptor(
                null, "Initialize recovery " + UUID.randomUUID(), "0", false, 100L, 30L);
        ProfileService.obtain().createAccount(profile);
        LocalDateTime retryAt = LocalDateTime.now().plusMinutes(15).truncatedTo(ChronoUnit.SECONDS);
        UnknownOverlayCooldownInitialize task = new UnknownOverlayCooldownInitialize(profile, retryAt);
        RecordingQueue queue = new RecordingQueue(profile);
        queue.enqueue(task);

        queue.runSchedulerTick();

        assertEquals(1, task.executionCount);
        assertTrue(task.isRecurring());
        assertTrue(queue.statusModel.isPaused());
        assertCloseTo(retryAt, task.getScheduled());
        assertCloseTo(retryAt, queue.statusModel.getDelayUntil());
        assertEquals(1, queue.getNextQueuedTaskTypes(1).size());
        assertEquals(1, queue.slotAcquisitionCount,
                "Initialize must acquire the queue session lease once");
        assertEquals(1, queue.gameStopCount,
                "cooldown must stop only the blocked game process");
        assertEquals(1, queue.slotReleaseCount,
                "cooldown must release the emulator lease for other profiles");
        assertEquals(0, queue.idleTransitionCount,
                "cooldown must bypass normal idle shutdown handling");
        DailyTask persisted = DailyTaskRepository.getRepository()
                .findByAccountIdAndTaskType(profile.getId(), TpDailyTaskEnum.INITIALIZE);
        assertCloseTo(retryAt, persisted.getNextRunAt());

        queue.runSchedulerTick();

        assertEquals(1, task.executionCount, "cooldown must prevent an immediate Initialize retry");
        assertEquals(1, queue.gameStopCount, "paused ticks must not repeat game cleanup");
        assertEquals(1, queue.slotReleaseCount, "paused ticks must not repeat lease cleanup");
        assertEquals(0, queue.idleTransitionCount, "paused ticks must not fall through to emulator shutdown");
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

    private static final class UnknownOverlayCooldownInitialize extends DelayedTask {

        private final LocalDateTime retryAt;
        private int executionCount;

        private UnknownOverlayCooldownInitialize(AccountDescriptor profile, LocalDateTime retryAt) {
            super(profile, TpDailyTaskEnum.INITIALIZE);
            this.retryAt = retryAt;
        }

        @Override
        protected void execute() {
            executionCount++;
            throw new ProfileCooldownException(
                    "home/world remained unavailable after bounded in-game recovery",
                    retryAt,
                    new ActionRequiredContext(
                            "startup.home-unavailable-after-game-back",
                            "Startup remains blocked after automatic recovery",
                            "home/world",
                            "unsupported startup screen",
                            "One bounded Android Back in the foreground game",
                            "Stop the game, release the slot, and retry after fifteen minutes"));
        }
    }

    private static void assertCloseTo(LocalDateTime expected, LocalDateTime actual) {
        assertTrue(Duration.between(expected, actual).abs().compareTo(Duration.ofSeconds(1)) < 0,
                () -> "expected " + expected + " but was " + actual);
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
        private int gameStopCount;
        private int slotReleaseCount;
        private int idleTransitionCount;

        private RecordingQueue(AccountDescriptor profile) {
            super(profile);
        }

        @Override
        protected void acquireSlot() {
            slotAcquisitionCount++;
            markSlotAcquired();
        }

        @Override
        protected boolean stopBlockedGameProcess(DelayedTask task) {
            gameStopCount++;
            return true;
        }

        @Override
        protected void releaseEmulatorSlotLease() {
            slotReleaseCount++;
        }

        @Override
        protected void handleIdleTransitions() {
            idleTransitionCount++;
        }

        @Override
        protected void sleepSchedulerTick(long millis) {
        }

        @Override
        protected void sleepPausedTick() {
        }
    }
}
