package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.TaskFailureReport;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.data.entity.DailyTask;
import dev.frostguard.data.repository.DailyTaskRepository;
import dev.frostguard.data.repository.TaskFailureStreakRepository;
import dev.frostguard.engine.error.TaskPreemptedException;
import dev.frostguard.engine.service.ActionRequiredIncidentService;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.engine.service.TaskFailureIncidentService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskCompletionPersistenceTest {

    @BeforeAll
    static void initializeTestWorkspace() {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
    }

    @Test
    @DisplayName("Successful execution updates previous completion and next schedule")
    void successUpdatesPreviousCompletionAndNextSchedule() {
        AccountDescriptor profile = createTestProfile();
        LocalDateTime priorCompletion = LocalDateTime.of(2025, 1, 1, 12, 0);
        LocalDateTime nextScheduled = LocalDateTime.now().plusHours(4).truncatedTo(ChronoUnit.SECONDS);
        initTaskRecord(profile, TpDailyTaskEnum.HERO_RECRUITMENT, priorCompletion);

        RecordingQueue queue = new RecordingQueue(profile);
        SuccessfulTask task = new SuccessfulTask(profile, TpDailyTaskEnum.HERO_RECRUITMENT, nextScheduled);
        queue.enqueue(task);
        queue.runSchedulerTick();

        DailyTask persisted = DailyTaskRepository.getRepository()
                .findByAccountIdAndTaskType(profile.getId(), TpDailyTaskEnum.HERO_RECRUITMENT);
        assertNotNull(persisted.getPreviousRun());
        assertCloseTo(LocalDateTime.now(), persisted.getPreviousRun());
        assertCloseTo(nextScheduled, persisted.getNextRunAt());
    }

    @Test
    @DisplayName("Unhandled failure preserves previous completion and persists bounded retry")
    void unhandledFailurePreservesPreviousCompletionAndPersistsBoundedRetry() {
        AccountDescriptor profile = createTestProfile();
        LocalDateTime priorCompletion = LocalDateTime.of(2025, 1, 1, 12, 0);
        initTaskRecord(profile, TpDailyTaskEnum.ARENA, priorCompletion);

        RecordingQueue queue = new RecordingQueue(profile);
        FailingTask task = new FailingTask(profile, TpDailyTaskEnum.ARENA,
                new RuntimeException("Transient connection timeout"));
        queue.enqueue(task);
        queue.runSchedulerTick();

        DailyTask persisted = DailyTaskRepository.getRepository()
                .findByAccountIdAndTaskType(profile.getId(), TpDailyTaskEnum.ARENA);
        assertEquals(priorCompletion, persisted.getPreviousRun(),
                "Unhandled failure must NOT overwrite previous completion timestamp");
        assertCloseTo(LocalDateTime.now().plus(TaskFailureIncidentService.DEFAULT_UNHANDLED_RETRY_DELAY),
                persisted.getNextRunAt());

        int streak = TaskFailureStreakRepository.getRepository()
                .find(profile.getId(), TpDailyTaskEnum.ARENA.name())
                .map(dev.frostguard.api.domain.TaskFailureStreakData::consecutiveFailures)
                .orElse(0);
        assertEquals(1, streak, "Failure streak must be recorded");
    }

    @Test
    @DisplayName("Preemption preserves previous completion and persists immediate retry")
    void preemptionPreservesPreviousCompletionAndPersistsImmediateRetry() {
        AccountDescriptor profile = createTestProfile();
        LocalDateTime priorCompletion = LocalDateTime.of(2025, 1, 1, 12, 0);
        initTaskRecord(profile, TpDailyTaskEnum.INTEL, priorCompletion);

        RecordingQueue queue = new RecordingQueue(profile);
        PreemptedTask task = new PreemptedTask(profile, TpDailyTaskEnum.INTEL, "Bear Trap rally joined");
        queue.enqueue(task);
        queue.runSchedulerTick();

        DailyTask persisted = DailyTaskRepository.getRepository()
                .findByAccountIdAndTaskType(profile.getId(), TpDailyTaskEnum.INTEL);
        assertEquals(priorCompletion, persisted.getPreviousRun(),
                "Preemption must NOT overwrite previous completion timestamp");
        assertCloseTo(LocalDateTime.now(), persisted.getNextRunAt());
    }

    @Test
    @DisplayName("Handled failure preserves previous completion and escalates streak to Action Required")
    void handledFailurePreservesPreviousCompletionAndEscalatesStreak() {
        AccountDescriptor profile = createTestProfile();
        LocalDateTime priorCompletion = LocalDateTime.of(2025, 1, 1, 12, 0);
        initTaskRecord(profile, TpDailyTaskEnum.BANK, priorCompletion);

        RecordingQueue queue = new RecordingQueue(profile);
        LocalDateTime retryAt = LocalDateTime.now().plusMinutes(5).truncatedTo(ChronoUnit.SECONDS);

        for (int attempt = 1; attempt <= 3; attempt++) {
            TaskFailureReport report = new TaskFailureReport(
                    profile.getId(),
                    profile.getName(),
                    TpDailyTaskEnum.BANK.name(),
                    "Bank Deposit",
                    "bank.deposit-option-unavailable",
                    "Deposit option unavailable",
                    "1-day deposit option is not available",
                    "Bank deposits successfully",
                    "Deposit option not found in menu",
                    "Rescheduled bank routine",
                    "Retry in 5 minutes",
                    "No emulator restart needed",
                    retryAt,
                    3,
                    "");

            HandledFailureTask task = new HandledFailureTask(profile, TpDailyTaskEnum.BANK, report);
            queue.enqueue(task);
            queue.runSchedulerTick();

            DailyTask persisted = DailyTaskRepository.getRepository()
                    .findByAccountIdAndTaskType(profile.getId(), TpDailyTaskEnum.BANK);
            assertEquals(priorCompletion, persisted.getPreviousRun(),
                    "Attempt " + attempt + " must not advance completed timestamp");
            assertCloseTo(retryAt, persisted.getNextRunAt());

            int streak = TaskFailureStreakRepository.getRepository()
                    .find(profile.getId(), TpDailyTaskEnum.BANK.name())
                    .map(dev.frostguard.api.domain.TaskFailureStreakData::consecutiveFailures)
                    .orElse(0);
            assertEquals(attempt, streak);
        }

        // On the 3rd failure, an incident must have escalated
        var incidents = ActionRequiredIncidentService.obtain().findAll();
        boolean hasBankIncident = incidents.stream()
                .anyMatch(i -> i.profileId().equals(profile.getId())
                        && i.taskKey().equals(TpDailyTaskEnum.BANK.name())
                        && i.recoveredAt() == null);
        assertTrue(hasBankIncident, "The third consecutive failure must escalate an Action Required incident");

        // Subsequent success recovers incident and advances completion
        LocalDateTime successNext = LocalDateTime.now().plusDays(1).truncatedTo(ChronoUnit.SECONDS);
        SuccessfulTask successTask = new SuccessfulTask(profile, TpDailyTaskEnum.BANK, successNext);
        queue.enqueue(successTask);
        queue.runSchedulerTick();

        DailyTask afterSuccess = DailyTaskRepository.getRepository()
                .findByAccountIdAndTaskType(profile.getId(), TpDailyTaskEnum.BANK);
        assertCloseTo(LocalDateTime.now(), afterSuccess.getPreviousRun());
        assertCloseTo(successNext, afterSuccess.getNextRunAt());

        int streakAfterSuccess = TaskFailureStreakRepository.getRepository()
                .find(profile.getId(), TpDailyTaskEnum.BANK.name())
                .map(dev.frostguard.api.domain.TaskFailureStreakData::consecutiveFailures)
                .orElse(0);
        assertEquals(0, streakAfterSuccess, "Streak must be reset upon verified success");

        boolean incidentStillActive = ActionRequiredIncidentService.obtain().findAll().stream()
                .anyMatch(i -> i.profileId().equals(profile.getId())
                        && i.taskKey().equals(TpDailyTaskEnum.BANK.name())
                        && i.recoveredAt() == null);
        assertTrue(!incidentStillActive, "Incident must be recovered upon verified success");
    }

    @Test
    @DisplayName("Unknown outcome preserves completion and does not reset streak")
    void unknownOutcomePreservesCompletionAndDoesNotResetStreak() {
        AccountDescriptor profile = createTestProfile();
        LocalDateTime priorCompletion = LocalDateTime.of(2025, 1, 1, 12, 0);
        initTaskRecord(profile, TpDailyTaskEnum.PET_ADVENTURE, priorCompletion);

        // Record 1 failure in streak first
        TaskFailureStreakRepository.getRepository().recordFailure(
                profile.getId(), TpDailyTaskEnum.PET_ADVENTURE.name(), "sig-1", LocalDateTime.now());

        RecordingQueue queue = new RecordingQueue(profile);
        LocalDateTime retryAt = LocalDateTime.now().plusMinutes(15).truncatedTo(ChronoUnit.SECONDS);
        UnknownOutcomeTask task = new UnknownOutcomeTask(profile, TpDailyTaskEnum.PET_ADVENTURE,
                "Chest start status unknown", retryAt);
        queue.enqueue(task);
        queue.runSchedulerTick();

        DailyTask persisted = DailyTaskRepository.getRepository()
                .findByAccountIdAndTaskType(profile.getId(), TpDailyTaskEnum.PET_ADVENTURE);
        assertEquals(priorCompletion, persisted.getPreviousRun(),
                "Unknown outcome must not advance completion");
        assertCloseTo(retryAt, persisted.getNextRunAt());

        int streak = TaskFailureStreakRepository.getRepository()
                .find(profile.getId(), TpDailyTaskEnum.PET_ADVENTURE.name())
                .map(dev.frostguard.api.domain.TaskFailureStreakData::consecutiveFailures)
                .orElse(0);
        assertEquals(1, streak, "Unknown outcome must not reset or advance failure streak");
    }

    @Test
    @DisplayName("Stamina deferral preserves previous completion and persists stamina targets")
    void staminaDeferralPreservesPreviousCompletion() {
        AccountDescriptor profile = createTestProfile();
        LocalDateTime priorCompletion = LocalDateTime.of(2025, 1, 1, 12, 0);
        initTaskRecord(profile, TpDailyTaskEnum.EVENT_POLAR_TERROR, priorCompletion);
        TaskFailureStreakRepository streaks = TaskFailureStreakRepository.getRepository();
        streaks.recordFailure(profile.getId(), TpDailyTaskEnum.EVENT_POLAR_TERROR.name(),
                "earlier-failure", LocalDateTime.now());

        RecordingQueue queue = new RecordingQueue(profile);
        LocalDateTime retryAt = LocalDateTime.now().plusMinutes(30).truncatedTo(ChronoUnit.SECONDS);
        StaminaDeferredTask task = new StaminaDeferredTask(profile, TpDailyTaskEnum.EVENT_POLAR_TERROR, 20, 50, retryAt);
        queue.enqueue(task);
        queue.runSchedulerTick();

        DailyTask persisted = DailyTaskRepository.getRepository()
                .findByAccountIdAndTaskType(profile.getId(), TpDailyTaskEnum.EVENT_POLAR_TERROR);
        assertEquals(priorCompletion, persisted.getPreviousRun(),
                "Stamina deferral must not advance completion");
        assertCloseTo(retryAt, persisted.getNextRunAt());
        assertEquals(20, persisted.getStaminaMinimumRequired());
        assertEquals(50, persisted.getStaminaRegenerationTarget());
        assertEquals(1, streaks.find(profile.getId(), TpDailyTaskEnum.EVENT_POLAR_TERROR.name())
                .orElseThrow().consecutiveFailures(), "deferring must preserve the existing failure streak");
    }

    // ── Helper fixtures ──────────────────────────────────────────────

    private static AccountDescriptor createTestProfile() {
        AccountDescriptor profile = new AccountDescriptor(
                null, "Persistence test " + UUID.randomUUID(), "0", false, 100L, 30L);
        ProfileService.obtain().createAccount(profile);
        return profile;
    }

    private static void initTaskRecord(AccountDescriptor profile, TpDailyTaskEnum taskType, LocalDateTime priorCompletion) {
        DailyTaskRepository repository = DailyTaskRepository.getRepository();
        ScheduleService.obtain().persistDailyCompletion(profile, taskType, LocalDateTime.now());
        DailyTask record = repository.findByAccountIdAndTaskType(profile.getId(), taskType);
        record.setPreviousRun(priorCompletion);
        repository.saveDailyTask(record);
    }

    private static void assertCloseTo(LocalDateTime expected, LocalDateTime actual) {
        assertTrue(Duration.between(expected, actual).abs().compareTo(Duration.ofSeconds(2)) < 0,
                () -> "expected " + expected + " but was " + actual);
    }

    private static final class SuccessfulTask extends DelayedTask {
        private final LocalDateTime nextSchedule;

        private SuccessfulTask(AccountDescriptor profile, TpDailyTaskEnum tpTask, LocalDateTime nextSchedule) {
            super(profile, tpTask);
            this.nextSchedule = nextSchedule;
        }

        @Override
        public void run() {
            setRecurring(true);
            reschedule(nextSchedule);
        }

        @Override
        protected void execute() {
        }
    }

    private static final class FailingTask extends DelayedTask {
        private final RuntimeException failure;

        private FailingTask(AccountDescriptor profile, TpDailyTaskEnum tpTask, RuntimeException failure) {
            super(profile, tpTask);
            this.failure = failure;
        }

        @Override
        public void run() {
            throw failure;
        }

        @Override
        protected void execute() {
        }
    }

    private static final class PreemptedTask extends DelayedTask {
        private final String reasoning;

        private PreemptedTask(AccountDescriptor profile, TpDailyTaskEnum tpTask, String reasoning) {
            super(profile, tpTask);
            this.reasoning = reasoning;
        }

        @Override
        public void run() {
            throw new TaskPreemptedException(reasoning);
        }

        @Override
        protected void execute() {
        }
    }

    private static final class HandledFailureTask extends DelayedTask {
        private final TaskFailureReport report;

        private HandledFailureTask(AccountDescriptor profile, TpDailyTaskEnum tpTask, TaskFailureReport report) {
            super(profile, tpTask);
            this.report = report;
        }

        @Override
        public void run() {
            recordHandledFailure(report);
        }

        @Override
        protected void execute() {
        }
    }

    private static final class UnknownOutcomeTask extends DelayedTask {
        private final String reason;
        private final LocalDateTime retryAt;

        private UnknownOutcomeTask(AccountDescriptor profile, TpDailyTaskEnum tpTask, String reason, LocalDateTime retryAt) {
            super(profile, tpTask);
            this.reason = reason;
            this.retryAt = retryAt;
        }

        @Override
        public void run() {
            recordUnknownOutcome(reason, retryAt);
        }

        @Override
        protected void execute() {
        }
    }

    private static final class StaminaDeferredTask extends DelayedTask {
        private final int min;
        private final int target;
        private final LocalDateTime retryAt;

        private StaminaDeferredTask(AccountDescriptor profile, TpDailyTaskEnum tpTask, int min, int target, LocalDateTime retryAt) {
            super(profile, tpTask);
            this.min = min;
            this.target = target;
            this.retryAt = retryAt;
        }

        @Override
        public void run() {
            deferForStamina(min, target, retryAt);
        }

        @Override
        protected void execute() {
        }
    }

    private static final class RecordingQueue extends TaskQueue {
        private RecordingQueue(AccountDescriptor profile) {
            super(profile);
        }

        @Override
        protected void acquireSlot() {
            markSlotAcquired();
        }

        @Override
        protected boolean stopBlockedGameProcess(DelayedTask task) {
            return true;
        }

        @Override
        protected void releaseEmulatorSlotLease() {
        }

        @Override
        protected void handleIdleTransitions() {
        }

        @Override
        protected void sleepSchedulerTick(long millis) {
        }

        @Override
        protected void sleepPausedTick() {
        }
    }
}
