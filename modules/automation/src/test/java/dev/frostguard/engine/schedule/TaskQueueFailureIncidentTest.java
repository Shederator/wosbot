package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ActionRequiredIncidentData;
import dev.frostguard.api.domain.ActionRequiredIncidentState;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.LogMessageData;
import dev.frostguard.data.repository.TaskFailureStreakRepository;
import dev.frostguard.engine.error.StartupCaptureException;
import dev.frostguard.engine.service.ActionRequiredIncidentService;
import dev.frostguard.engine.service.LoggingService;
import dev.frostguard.engine.service.ProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskQueueFailureIncidentTest {

    @AfterEach
    void detachLogObserver() {
        LoggingService.obtain().attachObserver(null);
    }

    @Test
    void schedulerEscalatesThirdUnhandledFailureWithoutImmediateRetryOrCleanupAndRecovers() {
        AccountDescriptor profile = new AccountDescriptor(
                null, "Failure sequence " + UUID.randomUUID(), "0", false, 100L, 30L);
        assertTrue(ProfileService.obtain().createAccount(profile));

        AlternatingFailureTask task = new AlternatingFailureTask(profile);
        RecordingQueue queue = new RecordingQueue(profile);
        List<LogMessageData> logs = new ArrayList<>();
        LoggingService.obtain().attachObserver(logs::add);
        queue.enqueue(task);

        queue.runSchedulerTick();
        assertEquals(1, task.executionCount);
        assertTrue(incidents(profile).isEmpty(), "first failure must stay transient");
        assertEquals(0, queue.gameStopCount);
        assertEquals(0, queue.slotReleaseCount);

        int logsAfterFirstFailure = logs.size();
        queue.runSchedulerTick();
        assertEquals(1, task.executionCount, "future retry must not execute on the next scheduler tick");
        assertEquals(logsAfterFirstFailure, logs.size(), "waiting must not hot-loop task logs");

        runDue(queue, task);
        assertEquals(2, task.executionCount);
        assertTrue(incidents(profile).isEmpty(), "second failure must stay below the threshold");

        runDue(queue, task);
        ActionRequiredIncidentData active = incidents(profile).getFirst();
        assertEquals(3, task.executionCount);
        assertEquals(ActionRequiredIncidentState.ACTIVE, active.state());
        assertTrue(active.isUnread());
        assertEquals(1, active.occurrenceCount());
        assertEquals(0, queue.gameStopCount);
        assertEquals(0, queue.slotReleaseCount);

        task.fail = false;
        runDue(queue, task);
        assertEquals(4, task.executionCount);
        assertEquals(ActionRequiredIncidentState.RECOVERED, incidents(profile).getFirst().state());

        AlternatingFailureTask nextSequence = new AlternatingFailureTask(profile);
        RecordingQueue resumedQueue = new RecordingQueue(profile);
        resumedQueue.enqueue(nextSequence);
        resumedQueue.runSchedulerTick();
        assertEquals(1, nextSequence.executionCount);
        assertEquals(ActionRequiredIncidentState.RECOVERED, incidents(profile).getFirst().state(),
                "a new sequence must not reopen history before reaching the threshold again");
    }

    @Test
    void exhaustedStartupCaptureRetriesBecomeOneBoundedFailureAttempt() {
        AccountDescriptor profile = new AccountDescriptor(
                null, "Capture failure " + UUID.randomUUID(), "1", false, 100L, 30L);
        assertTrue(ProfileService.obtain().createAccount(profile));
        ExhaustedCaptureTask task = new ExhaustedCaptureTask(profile);
        RecordingQueue queue = new RecordingQueue(profile);
        List<LogMessageData> logs = new ArrayList<>();
        LoggingService.obtain().attachObserver(logs::add);
        queue.enqueue(task);

        LocalDateTime before = LocalDateTime.now();
        queue.runSchedulerTick();

        assertEquals(1, task.executionCount);
        assertTrue(task.isRecurring());
        assertTrue(task.getScheduled().isAfter(before.plusMinutes(4)));
        assertTrue(incidents(profile).isEmpty(), "one exhausted capture sequence must stay transient");
        assertTrue(TaskFailureStreakRepository.getRepository().clear(profile.getId(), "INITIALIZE"),
                "the exhausted bounded capture sequence must count as one failed execution");
        String diagnosticLog = logs.stream().map(LogMessageData::getMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(diagnosticLog.contains("Unexpected StartupCaptureException"));
        assertTrue(diagnosticLog.contains("emulator=1"));
        assertTrue(diagnosticLog.contains("serial=127.0.0.1:16416"));
        assertTrue(diagnosticLog.contains("captureAttempt=3/3"));
        assertTrue(diagnosticLog.contains("lastVerifiedState=game launch requested"));
        assertTrue(diagnosticLog.contains("bounded task-failure reschedule"));
        assertTrue(diagnosticLog.contains("consecutiveFailures=1"));
    }

    private static void runDue(RecordingQueue queue, AlternatingFailureTask task) {
        task.reschedule(LocalDateTime.now().minusSeconds(1));
        queue.runSchedulerTick();
    }

    private static List<ActionRequiredIncidentData> incidents(AccountDescriptor profile) {
        return ActionRequiredIncidentService.obtain().findAll().stream()
                .filter(incident -> profile.getId().equals(incident.profileId()))
                .filter(incident -> "INITIALIZE".equals(incident.taskKey()))
                .toList();
    }

    private static final class AlternatingFailureTask extends DelayedTask {

        private int executionCount;
        private boolean fail = true;

        private AlternatingFailureTask(AccountDescriptor profile) {
            super(profile, TpDailyTaskEnum.INITIALIZE);
        }

        @Override
        protected void execute() {
            executionCount++;
            if (fail) {
                throw new IllegalStateException("stable simulated failure 42");
            }
            setRecurring(false);
        }
    }

    private static final class ExhaustedCaptureTask extends DelayedTask {

        private int executionCount;

        private ExhaustedCaptureTask(AccountDescriptor profile) {
            super(profile, TpDailyTaskEnum.INITIALIZE);
        }

        @Override
        protected void execute() {
            executionCount++;
            throw new StartupCaptureException(
                    "Startup screen capture failed; emulator=1; serial=127.0.0.1:16416; "
                            + "inspection=home/world pattern inspection; captureAttempt=3/3; "
                            + "lastVerifiedState=game launch requested; "
                            + "retryDecision=bounded task-failure reschedule after capture retry limit",
                    new RuntimeException("ddmlib screencap timeout; exit code unavailable"));
        }
    }

    private static final class RecordingQueue extends TaskQueue {

        private int gameStopCount;
        private int slotReleaseCount;

        private RecordingQueue(AccountDescriptor profile) {
            super(profile);
        }

        @Override
        protected void acquireSlot() {
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
        }

        @Override
        protected void sleepSchedulerTick(long millis) {
        }
    }
}
