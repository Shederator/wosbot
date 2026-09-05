package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ActionRequiredIncidentData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.LogMessageData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.data.entity.DailyTask;
import dev.frostguard.data.repository.DailyTaskRepository;
import dev.frostguard.engine.error.ProfileCooldownException;
import dev.frostguard.engine.error.ActionRequiredContext;
import dev.frostguard.engine.service.LoggingService;
import dev.frostguard.engine.service.ActionRequiredIncidentService;
import dev.frostguard.engine.service.ProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskQueueProfileCooldownTest {

    @BeforeAll
    static void initializeTestWorkspace() {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
    }

    @AfterEach
    void detachLogObserver() {
        LoggingService.obtain().attachObserver(null);
    }

    @Test
    void schedulerTickPersistsCooldownAndAvoidsIdleRetryCleanupAndLogLoops() {
        AccountDescriptor profile = persistedProfile();
        LocalDateTime retryAt = LocalDateTime.now().plusHours(1).truncatedTo(ChronoUnit.SECONDS);
        CooldownTask task = new CooldownTask(profile, retryAt);
        RecordingTaskQueue queue = new RecordingTaskQueue(profile);
        List<LogMessageData> logEntries = new ArrayList<>();
        LoggingService.obtain().attachObserver(logEntries::add);
        queue.enqueue(task);

        queue.runSchedulerTick();

        assertTrue(queue.statusModel.isPaused());
        assertFalse(queue.statusModel.isUserPaused());
        assertCloseTo(retryAt, queue.statusModel.getDelayUntil());
        assertTrue(task.isRecurring());
        assertCloseTo(retryAt, task.getScheduled());
        assertEquals(List.of(TpDailyTaskEnum.INITIALIZE), queue.getNextQueuedTaskTypes(1));
        assertEquals(1, task.executionCount);
        assertEquals(1, queue.slotAcquisitionCount);
        assertEquals(1, queue.gameStopCount);
        assertEquals(1, queue.slotReleaseCount);
        assertEquals(0, queue.idleTransitionCount);
        assertEquals("ACTION REQUIRED - operator intervention required - retry "
                + retryAt.format(TaskQueue.TS_FMT), queue.getProfileCooldownStatus());

        DailyTask persisted = DailyTaskRepository.getRepository()
                .findByAccountIdAndTaskType(profile.getId(), TpDailyTaskEnum.INITIALIZE);
        assertNotNull(persisted);
        assertCloseTo(retryAt, persisted.getNextRunAt());
        ActionRequiredIncidentData incident = ActionRequiredIncidentService.obtain().findAll().stream()
                .filter(candidate -> profile.getId().equals(candidate.profileId()))
                .filter(candidate -> "INITIALIZE".equals(candidate.taskKey()))
                .filter(candidate -> "operator intervention required".equals(candidate.cause()))
                .findFirst()
                .orElseThrow();
        assertTrue(incident.isUnread());
        assertCloseTo(retryAt, incident.retryAt());
        assertEquals("gameStopped=true; slotReleased=true", incident.resourceOutcome());

        int logsAfterCooldown = logEntries.size();
        queue.runSchedulerTick();

        assertEquals(1, task.executionCount, "the paused queue must not retry immediately");
        assertEquals(1, queue.gameStopCount, "the game must only be stopped once");
        assertEquals(1, queue.slotReleaseCount, "the device lease must only be released once");
        assertEquals(0, queue.idleTransitionCount, "cooldown must bypass idle shutdown handling");
        assertEquals(logsAfterCooldown, logEntries.size(), "paused cooldown ticks must not emit hot-loop logs");
    }

    @Test
    void failedSlotReleaseKeepsLeaseOwnershipAndPreventsReacquisition() {
        AccountDescriptor profile = persistedProfile();
        LocalDateTime retryAt = LocalDateTime.now().plusHours(1).truncatedTo(ChronoUnit.SECONDS);
        CooldownTask task = new CooldownTask(profile, retryAt);
        RecordingTaskQueue queue = new RecordingTaskQueue(profile);
        queue.failSlotRelease = true;
        queue.enqueue(task);

        queue.runSchedulerTick();

        assertEquals(1, queue.slotAcquisitionCount);
        assertEquals(1, queue.slotReleaseCount);
        assertTrue(queue.statusModel.isPaused());

        queue.statusModel.setDelayUntil(LocalDateTime.now().minusSeconds(1));
        queue.runSchedulerTick();

        assertFalse(queue.statusModel.isPaused());
        assertEquals(1, queue.slotAcquisitionCount,
                "a failed release still owns the lease and must not acquire a second slot");
        assertEquals(1, queue.slotReleaseCount, "resume must not repeat cooldown cleanup");
    }

    @Test
    void releasedSessionRequiresSlotReacquisition() {
        assertTrue(TaskQueue.requiresSlotAcquisition(null));
        assertFalse(TaskQueue.requiresSlotAcquisition(LocalDateTime.now()));
    }

    private static AccountDescriptor persistedProfile() {
        AccountDescriptor profile = new AccountDescriptor(
                null, "Profile cooldown " + UUID.randomUUID(), "0", false, 100L, 30L);
        assertTrue(ProfileService.obtain().createAccount(profile));
        return profile;
    }

    private static void assertCloseTo(LocalDateTime expected, LocalDateTime actual) {
        assertTrue(Duration.between(expected, actual).abs().compareTo(Duration.ofSeconds(1)) < 0,
                () -> "expected " + expected + " but was " + actual);
    }

    private static final class CooldownTask extends DelayedTask {

        private final LocalDateTime retryAt;
        private int executionCount;

        private CooldownTask(AccountDescriptor profile, LocalDateTime retryAt) {
            super(profile, TpDailyTaskEnum.INITIALIZE);
            this.retryAt = retryAt;
        }

        @Override
        protected void execute() {
            executionCount++;
            throw new ProfileCooldownException("operator intervention required", retryAt,
                    new ActionRequiredContext(
                            "test.operator-intervention",
                            "Operator intervention required",
                            "Task can continue",
                            "Operator-owned blocker",
                            "Bounded recovery exhausted",
                            "Pause and retry"));
        }
    }

    private static final class RecordingTaskQueue extends TaskQueue {

        private int gameStopCount;
        private int slotReleaseCount;
        private int slotAcquisitionCount;
        private int idleTransitionCount;
        private boolean failSlotRelease;

        private RecordingTaskQueue(AccountDescriptor profile) {
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
            if (failSlotRelease) {
                throw new IllegalStateException("simulated release failure");
            }
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
