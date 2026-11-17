package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.data.entity.DailyTask;
import dev.frostguard.data.repository.DailyTaskRepository;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;
import dev.frostguard.engine.error.ActionRequiredContext;
import dev.frostguard.engine.error.ProfileCooldownException;
import dev.frostguard.engine.service.ActionRequiredIncidentService;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.service.ScheduleService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskQueueInitializeRecoveryTest {

    @BeforeAll
    static void initializeTestWorkspace() {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
    }

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
        assertNull(persisted.getPreviousRun(), "a first-run retry must not create a completion");
        var summary = DailyTaskRepository.getRepository().statusSummary(profile.getId()).stream()
                .filter(row -> row.getTaskTypeCode() == TpDailyTaskEnum.INITIALIZE.getId())
                .findFirst().orElseThrow();
        assertCloseTo(retryAt, summary.getScheduledAt());
        assertNull(summary.getLastCompletion());

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

    @Test
    void terminalSnapshotIsOnDiskBeforeCooldownStopsTheGame() throws Exception {
        Path snapshotWorkspace = Files.createTempDirectory("initialize-snapshot-order");
        try {
            DiagnosticSnapshotStore store = new DiagnosticSnapshotStore(snapshotWorkspace);
            AccountDescriptor profile = new AccountDescriptor(
                    null, "Initialize snapshot " + UUID.randomUUID(), "0", false, 100L, 30L);
            ProfileService.obtain().createAccount(profile);
            LocalDateTime retryAt = LocalDateTime.now().plusMinutes(15).truncatedTo(ChronoUnit.SECONDS);
            SnapshotBeforeReleaseInitialize task =
                    new SnapshotBeforeReleaseInitialize(profile, retryAt, store);
            RecordingQueue queue = new RecordingQueue(profile);
            queue.beforeGameStop = () -> assertTrue(
                    Files.isRegularFile(snapshotWorkspace.resolve(task.evidencePath)),
                    "the startup frame must exist before the game process is stopped");
            queue.enqueue(task);

            queue.runSchedulerTick();

            assertEquals(1, queue.gameStopCount);
            assertEquals(1, queue.slotReleaseCount);
            String diagnostics = ActionRequiredIncidentService.formatDiagnostics(
                    ActionRequiredIncidentService.obtain().findAll().stream()
                            .filter(incident -> task.evidencePath.equals(incident.evidencePath()))
                            .findFirst()
                            .orElseThrow());
            assertTrue(diagnostics.contains(task.evidencePath));
        } finally {
            deleteRecursively(snapshotWorkspace);
        }
    }

    @Test
    void blockedCharacterKeepsCompletionAndGatesWorkUntilInitializeRetriesSuccessfully() {
        AccountDescriptor profile = new AccountDescriptor(
                null, "Character retry " + UUID.randomUUID(), "0", false, 100L, 30L);
        assertTrue(ProfileService.obtain().createAccount(profile));
        LocalDateTime priorCompletion = LocalDateTime.of(2025, 1, 1, 12, 0);
        LocalDateTime retryAt = LocalDateTime.now().plusMinutes(15).truncatedTo(ChronoUnit.SECONDS);
        DailyTaskRepository repository = DailyTaskRepository.getRepository();
        ScheduleService.obtain().persistDailyCompletion(profile, TpDailyTaskEnum.INITIALIZE, LocalDateTime.now());
        DailyTask record = repository.findByAccountIdAndTaskType(profile.getId(), TpDailyTaskEnum.INITIALIZE);
        record.setPreviousRun(priorCompletion);
        repository.saveDailyTask(record);

        RecordingQueue queue = new RecordingQueue(profile);
        CharacterRetryInitialize initialize = new CharacterRetryInitialize(profile, retryAt);
        queue.enqueue(initialize);
        queue.runSchedulerTick();

        DailyTask persisted = repository.findByAccountIdAndTaskType(profile.getId(), TpDailyTaskEnum.INITIALIZE);
        assertEquals(priorCompletion, persisted.getPreviousRun());
        assertEquals(retryAt, persisted.getNextRunAt());
        assertTrue(queue.statusModel.isPaused());
        assertEquals(1, queue.gameStopCount);
        assertEquals(1, queue.slotReleaseCount);

        int[] workRuns = {0};
        DelayedTask work = new DelayedTask(profile, TpDailyTaskEnum.SKIP_TUTORIAL) {
            @Override
            protected void execute() {
                workRuns[0]++;
            }
        };
        work.reschedule(LocalDateTime.now().minusMinutes(1));
        queue.enqueue(work);
        queue.statusModel.setDelayUntil(LocalDateTime.now().minusSeconds(1));
        queue.runSchedulerTick();
        queue.runSchedulerTick();
        assertEquals(0, workRuns[0], "overdue work must wait for the deferred Initialize");
        assertEquals(1, initialize.executions);

        initialize.reschedule(LocalDateTime.now().minusSeconds(1));
        queue.runSchedulerTick();
        assertEquals(2, initialize.executions);
        assertEquals(0, workRuns[0], "Initialize must finish before ordinary work starts");

        queue.runSchedulerTick();
        assertEquals(1, workRuns[0]);
    }

    private static final class CharacterRetryInitialize extends DelayedTask {
        private final LocalDateTime retryAt;
        private int executions;

        private CharacterRetryInitialize(AccountDescriptor profile, LocalDateTime retryAt) {
            super(profile, TpDailyTaskEnum.INITIALIZE);
            this.retryAt = retryAt;
        }

        @Override
        protected void execute() {
            setRecurring(false);
            if (++executions == 1) {
                throw new ProfileCooldownException("Active character identity could not be read", retryAt);
            }
        }
    }


    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
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

    private static final class SnapshotBeforeReleaseInitialize extends DelayedTask {

        private final LocalDateTime retryAt;
        private final DiagnosticSnapshotStore store;
        private String evidencePath = "";

        private SnapshotBeforeReleaseInitialize(
                AccountDescriptor profile, LocalDateTime retryAt, DiagnosticSnapshotStore store) {
            super(profile, TpDailyTaskEnum.INITIALIZE);
            this.retryAt = retryAt;
            this.store = store;
        }

        @Override
        protected void execute() {
            evidencePath = store.write(
                    RawImageData.capture(new byte[2 * 2 * 4], 2, 2, 4),
                    "initialize",
                    "initialize-blocked",
                    Instant.parse("2026-09-24T00:03:34.136Z")).orElseThrow();
            throw new ProfileCooldownException(
                    "home/world remained unavailable after bounded in-game recovery",
                    retryAt,
                    new ActionRequiredContext(
                            "startup.home-unavailable-after-game-back",
                            "Startup remains blocked after automatic recovery",
                            "home/world",
                            "unsupported startup screen",
                            "One bounded Android Back in the foreground game",
                            "Stop the game, release the slot, and retry after fifteen minutes"),
                    evidencePath);
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
        private int gameStopCount;
        private int slotReleaseCount;
        private int idleTransitionCount;
        private Runnable beforeGameStop = () -> {
        };

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
            beforeGameStop.run();
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
