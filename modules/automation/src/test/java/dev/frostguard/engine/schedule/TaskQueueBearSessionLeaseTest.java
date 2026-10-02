package dev.frostguard.engine.schedule;

import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.GATHER_TASK_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.ALLIANCE_AUTOJOIN_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.PROFILE_MAX_ACTIVE_TIME_ENABLED_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.PROFILE_MAX_ACTIVE_TIME_MINUTES_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.data.repository.DailyTaskRepository;
import dev.frostguard.engine.error.BearSessionExecutionException;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.ProfileService;

class TaskQueueBearSessionLeaseTest {

    private static final DateTimeFormatter CONFIG_DATE_TIME =
            DateTimeFormatter.ofPattern("dd-MM-uuuu HH:mm");

    @BeforeAll
    static void initializeTestWorkspace() {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
    }

    @AfterEach
    void clearLease() {
        BearTrapSessionLease.clearForTests();
    }

    @Test
    void bearFailureCannotDispatchNormalTaskAfterRoutineAdvancesMutableTimer() {
        AccountDescriptor profile = new AccountDescriptor(
                null, "Bear lease " + UUID.randomUUID(), "0", false, 100L, 30L);
        assertTrue(ProfileService.obtain().createAccount(profile));
        LocalDateTime activationUtc = LocalDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0);
        assertTrue(ConfigService.obtain().writeAccountSetting(profile, BEAR_TRAP_EVENT_BOOL, "true"));
        assertTrue(ConfigService.obtain().writeAccountSetting(profile, BEAR_TRAP_NUMBER_INT, "1"));
        assertTrue(ConfigService.obtain().writeAccountSetting(profile, BEAR_TRAP_PREPARATION_TIME_INT, "10"));
        assertTrue(ConfigService.obtain().writeAccountSetting(profile, BEAR_TRAP_SCHEDULE_DATETIME_STRING,
                activationUtc.format(CONFIG_DATE_TIME)));

        RecordingQueue queue = new RecordingQueue(profile);
        AdvancingFailureBearTask bear = new AdvancingFailureBearTask(profile, activationUtc);
        RecordingNormalTask chests = new RecordingNormalTask(profile);
        queue.enqueue(bear);

        queue.runSchedulerTick();
        assertEquals(1, bear.executionCount);
        assertTrue(BearTrapSessionLease.active(profile.getId()).isPresent());
        assertTrue(bear.getScheduled().isBefore(LocalDateTime.now().plusSeconds(10)),
                "an active Bear failure must retain the fast retry cadence");

        // Keep the Bear retry queued but outside this deterministic scheduler tick. Persistence
        // work in the test JVM can otherwise consume the two-second production retry interval.
        bear.reschedule(LocalDateTime.now().plusMinutes(1));
        queue.enqueue(chests);
        queue.runSchedulerTick();

        assertEquals(0, chests.executionCount,
                "normal work must remain locked after the Bear execution fails");
        assertTrue(chests.getScheduled().isAfter(LocalDateTime.now().plusMinutes(20)),
                "blocked work must be deferred to the immutable event deadline");
    }

    @Test
    void bearCannotExecuteWithoutAResolvedSessionDeadline() {
        AccountDescriptor profile = new AccountDescriptor(
                null, "Bear missing lease " + UUID.randomUUID(), "0", false, 100L, 30L);
        assertTrue(ProfileService.obtain().createAccount(profile));
        RecordingBearTask bear = new RecordingBearTask(profile);
        RecordingQueue queue = new RecordingQueue(profile);
        queue.enqueue(bear);

        queue.runSchedulerTick();

        assertEquals(0, bear.executionCount);
        assertTrue(BearTrapSessionLease.active(profile.getId()).isEmpty());
        assertTrue(bear.getScheduled().isAfter(LocalDateTime.now().plusSeconds(20)));
    }

    @Test
    void typedRecoveryDirectivesExecuteInsteadOfSharingOneBlindRetry() {
        AccountDescriptor profile = configuredActiveProfile("Bear recovery directives ");
        RecordingQueue queue = new RecordingQueue(profile);
        RecordingBearTask bear = new RecordingBearTask(profile);
        assertTrue(BearTrapSessionLease.acquireForBearExecution(profile).isPresent());

        queue.routeError(bear, failure(
                BearSessionExecutionException.FailureKind.DEVICE_OFFLINE,
                BearSessionExecutionException.RecoveryDirective.REBIND_DEVICE));
        assertEquals(1, queue.deviceProbes);
        assertTrue(bear.isRecurring());

        queue.routeError(bear, failure(
                BearSessionExecutionException.FailureKind.RECONNECT_SCREEN,
                BearSessionExecutionException.RecoveryDirective.RESTART_APP));
        assertEquals(1, queue.appRestarts);
        assertTrue(bear.isRecurring());

        queue.routeError(bear, failure(
                BearSessionExecutionException.FailureKind.FATAL_CONFIGURATION,
                BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION));
        assertTrue(bear.isRecurring(), "operator action must not drop the active event");
        assertFalse(bear.getScheduled().isAfter(LocalDateTime.now().plusSeconds(31)),
                "operator action alerts but keeps retrying inside the window");
        assertEquals(0, queue.gatherRestores);
        assertEquals(0, queue.autojoinRestores);

        Instant afterEvent = BearTrapSessionLease.active(profile.getId()).orElseThrow()
                .eventEnd().plusSeconds(1);
        assertTrue(queue.finalizeBearRecoveryIfDue(bear, afterEvent));
        assertEquals(1, queue.gatherRestores);
        assertEquals(1, queue.autojoinRestores);
        assertTrue(bear.isRecurring());
        assertTrue(bear.getScheduled().isAfter(LocalDateTime.ofInstant(
                afterEvent, ZoneId.systemDefault())));
    }

    @Test
    void degradedRecoveryExhaustionKeepsRetryingAndArmsTheEventEndFinalizer() {
        AccountDescriptor profile = configuredActiveProfile("Bear degraded budget ");
        RecordingQueue queue = new RecordingQueue(profile);
        RecordingBearTask bear = new RecordingBearTask(profile);
        BearTrapSessionLease.Lease lease =
                BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        BearSessionExecutionException failure = failure(
                BearSessionExecutionException.FailureKind.CAPTURE_TRANSIENT,
                BearSessionExecutionException.RecoveryDirective.DEGRADED_WAIT);

        for (int attempt = 0; attempt < 5; attempt++) {
            queue.routeError(bear, failure);
        }

        assertTrue(bear.isRecurring());
        assertFalse(bear.getScheduled().isAfter(LocalDateTime.now().plusSeconds(31)),
                "an exhausted budget limits the retry rate inside the window");
        assertEquals(lease.eventEnd(), BearRecoveryFinalization.deadline(profile).orElseThrow(),
                "the finalizer still owns cleanup after the immutable event deadline");
        assertEquals(0, queue.gatherRestores);
        assertEquals(0, queue.autojoinRestores);
    }

    @Test
    void alternatingRecoveryDirectivesShareOneDurableBudgetAcrossRestart() {
        AccountDescriptor profile = configuredActiveProfile("Bear alternating durable budget ");
        RecordingBearTask firstTask = new RecordingBearTask(profile);
        RecordingQueue firstQueue = new RecordingQueue(profile);
        BearTrapSessionLease.Lease lease =
                BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();

        firstQueue.routeError(firstTask, failure(
                BearSessionExecutionException.FailureKind.DEVICE_OFFLINE,
                BearSessionExecutionException.RecoveryDirective.REBIND_DEVICE));
        firstQueue.routeError(firstTask, failure(
                BearSessionExecutionException.FailureKind.RECONNECT_SCREEN,
                BearSessionExecutionException.RecoveryDirective.RESTART_APP));
        assertEquals(2, BearSessionCheckpoint.load(profile).orElseThrow().recoveryAttempts());

        BearTrapSessionLease.releaseForQueueStop(profile.getId());
        AccountDescriptor reloaded = reload(profile.getId());
        assertEquals(lease.eventEnd(),
                BearTrapSessionLease.acquireForBearExecution(reloaded).orElseThrow().eventEnd());
        RecordingQueue restartedQueue = new RecordingQueue(reloaded);
        RecordingBearTask restartedTask = new RecordingBearTask(reloaded);

        restartedQueue.routeError(restartedTask, failure(
                BearSessionExecutionException.FailureKind.DEVICE_OFFLINE,
                BearSessionExecutionException.RecoveryDirective.REBIND_DEVICE));
        restartedQueue.routeError(restartedTask, failure(
                BearSessionExecutionException.FailureKind.RECONNECT_SCREEN,
                BearSessionExecutionException.RecoveryDirective.RESTART_APP));
        restartedQueue.routeError(restartedTask, failure(
                BearSessionExecutionException.FailureKind.CAPTURE_TRANSIENT,
                BearSessionExecutionException.RecoveryDirective.DEGRADED_WAIT));

        assertEquals(1, restartedQueue.deviceProbes);
        assertEquals(1, restartedQueue.appRestarts);
        assertEquals(4, BearSessionCheckpoint.load(reloaded).orElseThrow().recoveryAttempts());
        assertEquals(lease.eventEnd(), BearRecoveryFinalization.deadline(reloaded).orElseThrow());
    }

    @Test
    void durableFinalizerSurvivesQueueReconstructionAndRunsExactlyOnce() {
        AccountDescriptor profile = configuredActiveProfile("Bear durable finalizer ");
        RecordingQueue originalQueue = new RecordingQueue(profile);
        RecordingBearTask originalTask = new RecordingBearTask(profile);
        BearTrapSessionLease.Lease lease =
                BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();

        originalQueue.routeError(originalTask, failure(
                BearSessionExecutionException.FailureKind.FATAL_CONFIGURATION,
                BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION));

        AccountDescriptor reloaded = reload(profile.getId());
        RecordingQueue restartedQueue = new RecordingQueue(reloaded);
        RecordingBearTask restoredTask = new RecordingBearTask(reloaded);
        Instant afterEvent = lease.eventEnd().plusSeconds(1);

        assertEquals(lease.eventEnd(),
                BearRecoveryFinalization.deadline(reloaded).orElseThrow());
        assertTrue(restartedQueue.finalizeBearRecoveryIfDue(restoredTask, afterEvent));
        assertEquals(1, restartedQueue.gatherRestores);
        assertEquals(1, restartedQueue.autojoinRestores);
        assertTrue(BearRecoveryFinalization.deadline(reloaded).isEmpty());
        assertFalse(restartedQueue.finalizeBearRecoveryIfDue(restoredTask, afterEvent.plusSeconds(1)));
    }

    @Test
    void manualRunNowPreservesFinalizerUntilActiveExecutionOwnsDurableCheckpoint() {
        AccountDescriptor profile = configuredActiveProfile("Bear manual resume ");
        RecordingQueue queue = new RecordingQueue(profile);
        RecordingBearTask failedTask = new RecordingBearTask(profile);
        BearTrapSessionLease.Lease lease =
                BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        queue.routeError(failedTask, failure(
                BearSessionExecutionException.FailureKind.FATAL_CONFIGURATION,
                BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION));

        queue.runNow(TpDailyTaskEnum.BEAR_TRAP, true);
        assertTrue(queue.getNextQueuedTaskTypes(20).contains(TpDailyTaskEnum.BEAR_TRAP));
        assertTrue(BearRecoveryFinalization.deadline(profile).isPresent(),
                "Run Now must not clear recovery before execution owns a checkpoint");

        ReschedulingSuccessfulBearTask resumed = new ReschedulingSuccessfulBearTask(profile);
        assertTrue(queue.executeTask(resumed));
        assertEquals(1, resumed.executionCount);
        assertTrue(BearRecoveryFinalization.deadline(profile).isEmpty());
        assertFalse(queue.finalizeBearRecoveryIfDue(
                resumed, lease.eventEnd().plus(Duration.ofDays(2))));
    }

    @Test
    void durableRecoveryPinsDeviceAndRequeuesBearImmediatelyAfterRestart() {
        AccountDescriptor profile = configuredActiveProfile("Bear restart ownership ");
        RecordingQueue failedQueue = new RecordingQueue(profile);
        RecordingBearTask failedTask = new RecordingBearTask(profile);
        BearTrapSessionLease.Lease lease =
                BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        failedQueue.routeError(failedTask, failure(
                BearSessionExecutionException.FailureKind.FATAL_CONFIGURATION,
                BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION));
        BearTrapSessionLease.releaseForQueueStop(profile.getId());

        AccountDescriptor reloaded = reload(profile.getId());
        RecordingQueue restartedQueue = new RecordingQueue(reloaded);
        assertTrue(restartedQueue.hasProtectedBearOwnership(Instant.now()),
                "an unexpired durable recovery must pin the emulator open");
        assertTrue(restartedQueue.restoreDurableBearOwnershipOnStart());
        assertTrue(restartedQueue.getNextQueuedTaskTypes(20).contains(TpDailyTaskEnum.BEAR_TRAP),
                "restart must restore Bear now instead of leaving it at event end");
        assertEquals(lease.eventEnd(), BearRecoveryFinalization.deadline(reloaded).orElseThrow());
    }

    @Test
    void durableRecoveryOwnershipSurvivesMutableScheduleChanges() {
        AccountDescriptor profile = configuredActiveProfile("Bear changed schedule ownership ");
        RecordingQueue failedQueue = new RecordingQueue(profile);
        RecordingBearTask failedTask = new RecordingBearTask(profile);
        BearTrapSessionLease.Lease lease =
                BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        failedQueue.routeError(failedTask, failure(
                BearSessionExecutionException.FailureKind.FATAL_CONFIGURATION,
                BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION));
        BearTrapSessionLease.releaseForQueueStop(profile.getId());
        assertTrue(ConfigService.obtain().writeAccountSetting(
                profile, BEAR_TRAP_SCHEDULE_DATETIME_STRING, "01-01-2035 00:00"));

        AccountDescriptor reloaded = reload(profile.getId());
        RecordingQueue restartedQueue = new RecordingQueue(reloaded);
        assertEquals(lease.eventEnd(), BearRecoveryFinalization.deadline(reloaded).orElseThrow());
        assertTrue(restartedQueue.hasProtectedBearOwnership(Instant.now()));
        assertTrue(restartedQueue.restoreDurableBearOwnershipOnStart());
        assertTrue(restartedQueue.getNextQueuedTaskTypes(20).contains(TpDailyTaskEnum.BEAR_TRAP));
    }

    @Test
    void explicitlyDisabledParticipationDoesNotResumeBearOnRestart() {
        AccountDescriptor profile = configuredActiveProfile("Bear disabled ownership ");
        RecordingQueue failedQueue = new RecordingQueue(profile);
        RecordingBearTask failedTask = new RecordingBearTask(profile);
        BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        failedQueue.routeError(failedTask, failure(
                BearSessionExecutionException.FailureKind.FATAL_CONFIGURATION,
                BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION));
        BearTrapSessionLease.releaseForQueueStop(profile.getId());
        assertTrue(ConfigService.obtain().writeAccountSetting(
                profile, BEAR_TRAP_EVENT_BOOL, "false"));

        RecordingQueue restartedQueue = new RecordingQueue(reload(profile.getId()));
        assertFalse(restartedQueue.hasProtectedBearOwnership(Instant.now()));
        assertFalse(restartedQueue.restoreDurableBearOwnershipOnStart());
        assertFalse(restartedQueue.getNextQueuedTaskTypes(20).contains(TpDailyTaskEnum.BEAR_TRAP));
    }

    @Test
    void manualRunNowUsesDurableWindowWhenMutableScheduleWasChanged() {
        AccountDescriptor profile = configuredActiveProfile("Bear refused manual resume ");
        RecordingQueue queue = new RecordingQueue(profile);
        RecordingBearTask failedTask = new RecordingBearTask(profile);
        BearTrapSessionLease.Lease lease =
                BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        queue.routeError(failedTask, failure(
                BearSessionExecutionException.FailureKind.FATAL_CONFIGURATION,
                BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION));
        assertTrue(ConfigService.obtain().writeAccountSetting(
                profile, BEAR_TRAP_SCHEDULE_DATETIME_STRING, "01-01-2035 00:00"));

        queue.runNow(TpDailyTaskEnum.BEAR_TRAP, true);

        assertTrue(queue.getNextQueuedTaskTypes(20).contains(TpDailyTaskEnum.BEAR_TRAP));
        assertEquals(lease.eventEnd(), BearRecoveryFinalization.deadline(profile).orElseThrow());
    }

    @Test
    void disabledParticipationFinalizesOnceWithoutLeavingBearQueued() {
        AccountDescriptor profile = configuredActiveProfile("Bear disable reload ");
        RecordingQueue queue = new RecordingQueue(profile);
        RecordingBearTask task = new RecordingBearTask(profile);
        BearTrapSessionLease.Lease lease =
                BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        queue.routeError(task, failure(
                BearSessionExecutionException.FailureKind.FATAL_CONFIGURATION,
                BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION));

        assertTrue(ConfigService.obtain().writeAccountSetting(profile, BEAR_TRAP_EVENT_BOOL, "false"));
        AccountDescriptor reloaded = reload(profile.getId());
        RecordingQueue restartedQueue = new RecordingQueue(reloaded);
        RecordingBearTask restoredTask = new RecordingBearTask(reloaded);

        assertEquals(lease.eventEnd(),
                BearRecoveryFinalization.deadline(reloaded).orElseThrow());
        assertTrue(restartedQueue.finalizeBearRecoveryIfDue(
                restoredTask, lease.eventEnd().plusSeconds(1)));
        assertEquals(1, restartedQueue.gatherRestores);
        assertEquals(1, restartedQueue.autojoinRestores);
        assertFalse(restoredTask.isRecurring());
        assertFalse(restartedQueue.getNextQueuedTaskTypes(20).contains(TpDailyTaskEnum.BEAR_TRAP));
        assertTrue(BearRecoveryFinalization.deadline(reloaded).isEmpty());
        LocalDateTime persistedNextRun = DailyTaskRepository.getRepository()
                .findRoutine(profile.getId(), TpDailyTaskEnum.BEAR_TRAP)
                .orElseThrow()
                .getNextRunAt();
        assertTrue(Duration.between(restoredTask.getScheduled(), persistedNextRun)
                .abs().toMillis() < 1,
                "disabled cleanup must still persist the calculated next Bear window");

        assertTrue(ConfigService.obtain().writeAccountSetting(
                reloaded, BEAR_TRAP_EVENT_BOOL, "true"));
        AccountDescriptor reenabled = reload(profile.getId());
        assertTrue(reenabled.getConfig(BEAR_TRAP_EVENT_BOOL, Boolean.class));
        assertTrue(BearTrapParticipationSchedule.resolve(reenabled).isPresent(),
                "re-enabling must expose a schedulable Bear plan to queue realignment");
    }

    @Test
    void disabledProfilePerformsCleanupOnlyWithoutRestoringNormalTasksOrBear() {
        AccountDescriptor profile = configuredActiveProfile("Bear disabled profile ");
        RecordingQueue queue = new RecordingQueue(profile);
        RecordingBearTask task = new RecordingBearTask(profile);
        BearTrapSessionLease.Lease lease =
                BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        queue.routeError(task, failure(
                BearSessionExecutionException.FailureKind.FATAL_CONFIGURATION,
                BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION));
        profile.setEnabled(false);

        assertTrue(queue.finalizeBearRecoveryIfDue(task, lease.eventEnd().plusSeconds(1)));
        assertEquals(0, queue.gatherRestores);
        assertEquals(0, queue.autojoinRestores);
        assertFalse(task.isRecurring());
        assertFalse(queue.getNextQueuedTaskTypes(20).contains(TpDailyTaskEnum.BEAR_TRAP));
    }

    @Test
    void durableCheckpointRestoresLeaseWhenMutableScheduleNoLongerResolves() {
        AccountDescriptor profile = configuredActiveProfile("Bear checkpoint lease ");
        BearTrapSessionLease.Lease original =
                BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        assertTrue(BearSessionCheckpoint.open(profile, original.eventEnd()));
        BearTrapSessionLease.releaseForQueueStop(profile.getId());
        assertTrue(ConfigService.obtain().writeAccountSetting(
                profile, BEAR_TRAP_SCHEDULE_DATETIME_STRING, "01-01-2035 00:00"));

        BearTrapSessionLease.Lease restored =
                BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        assertEquals(original.eventEnd(), restored.eventEnd());
    }

    @Test
    void noRecoveryDirectiveParksBearLaterInsideTheActiveWindow() {
        for (BearSessionExecutionException.RecoveryDirective directive
                : BearSessionExecutionException.RecoveryDirective.values()) {
            AccountDescriptor profile = configuredActiveProfile("Bear in-window " + directive + " ");
            RecordingQueue queue = new RecordingQueue(profile);
            RecordingBearTask bear = new RecordingBearTask(profile);
            BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();

            for (int attempt = 1; attempt <= 6; attempt++) {
                queue.routeError(bear, failure(
                        BearSessionExecutionException.FailureKind.CAPTURE_TRANSIENT, directive));

                assertTrue(bear.isRecurring(), directive + " attempt " + attempt + " dropped Bear");
                assertFalse(bear.getScheduled().isAfter(LocalDateTime.now().plusSeconds(31)),
                        directive + " attempt " + attempt + " parked Bear at " + bear.getScheduled());
            }
            assertEquals(0, queue.gatherRestores, "normal work must stay paused during the window");
        }
    }

    @Test
    void normalReturnBeforeTheEventEndKeepsDurableOwnership() {
        AccountDescriptor profile = configuredActiveProfile("Bear early return ");
        RecordingQueue queue = new RecordingQueue(profile);
        ReschedulingSuccessfulBearTask bear = new ReschedulingSuccessfulBearTask(profile);

        assertTrue(queue.executeTask(bear));

        assertEquals(1, bear.executionCount);
        assertTrue(BearSessionCheckpoint.load(reload(profile.getId())).isPresent(),
                "an event that has not ended must keep its durable checkpoint");
        assertTrue(bear.isRecurring());
        assertFalse(bear.getScheduled().isAfter(LocalDateTime.now().plusSeconds(31)),
                "Bear must retry inside the window, not at " + bear.getScheduled());
    }

    @Test
    void protectedBearRefusesTheSessionCapInsteadOfForcingIdle() throws Exception {
        AccountDescriptor profile = configuredActiveProfile("Bear session cap ");
        configuredActiveProfile("Bear session cap sibling ");
        assertTrue(ConfigService.obtain().writeGlobalSetting(PROFILE_MAX_ACTIVE_TIME_ENABLED_BOOL, "true"));
        assertTrue(ConfigService.obtain().writeGlobalSetting(PROFILE_MAX_ACTIVE_TIME_MINUTES_INT, "1"));
        try {
            RecordingQueue queue = new RecordingQueue(profile);
            BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
            Field origin = TaskQueue.class.getDeclaredField("sessionOrigin");
            origin.setAccessible(true);
            origin.set(queue, LocalDateTime.now().minusMinutes(10));

            assertFalse(queue.enforceSessionCap(), "the cap must not force idle during Bear ownership");
            assertFalse(queue.deviceReleaseAllowed());
        } finally {
            ConfigService.obtain().writeGlobalSetting(PROFILE_MAX_ACTIVE_TIME_ENABLED_BOOL,
                    PROFILE_MAX_ACTIVE_TIME_ENABLED_BOOL.getDefaultValue());
        }
    }

    @Test
    void siblingQueueOnTheSameEmulatorCannotReleaseTheBearDevice() {
        AccountDescriptor bearProfile = configuredActiveProfile("Bear device owner ");
        AccountDescriptor sibling = new AccountDescriptor(
                null, "Bear device sibling " + UUID.randomUUID(), "0", true, 100L, 30L);
        assertTrue(ProfileService.obtain().createAccount(sibling));
        RecordingQueue bearQueue = new RecordingQueue(bearProfile);
        RecordingQueue siblingQueue = new RecordingQueue(sibling);
        BearTrapSessionLease.acquireForBearExecution(bearProfile).orElseThrow();
        bearQueue.registerDeviceProtection();
        try {
            assertFalse(siblingQueue.deviceReleaseAllowed(),
                    "a sibling profile must not close or background the emulator Bear owns");
        } finally {
            bearQueue.unregisterDeviceProtection();
        }
        assertTrue(siblingQueue.deviceReleaseAllowed());
    }

    @Test
    void crashLeavingOnlyTheCheckpointStillPinsAndRestoresBear() {
        AccountDescriptor profile = configuredActiveProfile("Bear crash checkpoint ");
        BearTrapSessionLease.Lease lease =
                BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        assertTrue(BearSessionCheckpoint.open(profile, lease.eventEnd()));
        // A crash skips requestStop: no finalizer is armed and the in-memory lease is gone.
        BearTrapSessionLease.releaseForQueueStop(profile.getId());
        AccountDescriptor reloaded = reload(profile.getId());
        assertTrue(BearRecoveryFinalization.deadline(reloaded).isEmpty());

        RecordingQueue restarted = new RecordingQueue(reloaded);

        assertTrue(restarted.hasProtectedBearOwnership(Instant.now()));
        assertTrue(restarted.restoreDurableBearOwnershipOnStart());
        assertTrue(restarted.getNextQueuedTaskTypes(20).contains(TpDailyTaskEnum.BEAR_TRAP));
    }

    @Test
    void queueStopAndErrorRoutingKeepTacticalRecoveryState() {
        AccountDescriptor profile = configuredActiveProfile("Bear tactical merge ");
        RecordingQueue queue = new RecordingQueue(profile);
        RecordingBearTask bear = new RecordingBearTask(profile);
        BearTrapSessionLease.Lease lease =
                BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        Instant sentAt = Instant.now().minusSeconds(40);
        Instant returnDeadline = sentAt.plusSeconds(420);
        assertTrue(BearSessionCheckpoint.recordTactical(profile, lease.eventEnd(), sentAt,
                returnDeadline, "PLUS_COMMITTED", 77L, "Leader", 512, "test-tactical"));

        queue.routeError(bear, failure(
                BearSessionExecutionException.FailureKind.CAPTURE_TRANSIENT,
                BearSessionExecutionException.RecoveryDirective.DEGRADED_WAIT));
        BearSessionCheckpoint.Checkpoint afterError =
                BearSessionCheckpoint.load(reload(profile.getId())).orElseThrow();
        assertEquals(returnDeadline, afterError.ownRallyReturnDeadline());
        assertEquals("PLUS_COMMITTED", afterError.joinSubstate());
        assertEquals(1, afterError.recoveryAttempts());

        queue.requestStop();
        BearSessionCheckpoint.Checkpoint afterStop =
                BearSessionCheckpoint.load(reload(profile.getId())).orElseThrow();
        assertEquals(sentAt, afterStop.ownRallySentAt());
        assertEquals(returnDeadline, afterStop.ownRallyReturnDeadline());
        assertEquals("PLUS_COMMITTED", afterStop.joinSubstate());
        assertEquals(77L, afterStop.listRowFingerprint());
        assertEquals(1, afterStop.recoveryAttempts(), "stopping must not reset the recovery budget");
    }

    @Test
    void restoreReportsFailureWhenBearCouldNotBeQueued() {
        AccountDescriptor profile = configuredActiveProfile("Bear restore refused ");
        BearTrapSessionLease.Lease lease =
                BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        assertTrue(BearSessionCheckpoint.open(profile, lease.eventEnd()));
        BearTrapSessionLease.releaseForQueueStop(profile.getId());
        TaskQueue unregistered = new TaskQueue(reload(profile.getId())) {
            @Override
            protected DelayedTask createTask(TpDailyTaskEnum kind) {
                return null;
            }
        };

        assertFalse(unregistered.restoreDurableBearOwnershipOnStart(),
                "restore must not claim ownership was resumed when Bear is not queued");
        assertTrue(unregistered.hasProtectedBearOwnership(Instant.now()),
                "the device stays pinned while ownership is unresolved");
    }

    @Test
    void operatorRevocationStopsBearReleasesTheDeviceAndQueuesCleanupOnly() {
        AccountDescriptor profile = configuredActiveProfile("Bear revoked ");
        RecordingQueue queue = new RecordingQueue(profile);
        BearTrapSessionLease.Lease lease =
                BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        assertTrue(BearSessionCheckpoint.open(profile, lease.eventEnd()));
        assertTrue(ConfigService.obtain().writeAccountSetting(profile, BEAR_TRAP_EVENT_BOOL, "false"));
        AccountDescriptor disabled = reload(profile.getId());
        queue.applyProfileUpdate(disabled);

        assertTrue(queue.revokeBearOwnership("participation disabled"));

        assertTrue(BearTrapSessionLease.active(profile.getId()).isEmpty(), "the event lease is released");
        assertFalse(queue.hasProtectedBearOwnership(Instant.now()), "the device is no longer pinned");
        assertTrue(queue.deviceReleaseAllowed());
        Instant cleanupAt = BearRecoveryFinalization.deadline(reload(profile.getId())).orElseThrow();
        assertFalse(cleanupAt.isAfter(Instant.now()), "cleanup-only finalization is due now");
        assertTrue(queue.getNextQueuedTaskTypes(20).contains(TpDailyTaskEnum.BEAR_TRAP),
                "the finalization run that restores normal work is queued");
    }

    @Test
    void revocationWithoutBearOwnershipDoesNothing() {
        AccountDescriptor profile = configuredActiveProfile("Bear revoke idle ");
        RecordingQueue queue = new RecordingQueue(profile);

        assertFalse(queue.revokeBearOwnership("participation disabled"));
        assertTrue(BearRecoveryFinalization.deadline(reload(profile.getId())).isEmpty());
    }

    private static AccountDescriptor reload(Long profileId) {
        return ProfileService.obtain().fetchAllAccounts().stream()
                .filter(candidate -> profileId.equals(candidate.getId()))
                .findFirst()
                .orElseThrow();
    }

    private static AccountDescriptor configuredActiveProfile(String prefix) {
        AccountDescriptor profile = new AccountDescriptor(
                null, prefix + UUID.randomUUID(), "0", true, 100L, 30L);
        assertTrue(ProfileService.obtain().createAccount(profile));
        LocalDateTime activationUtc = LocalDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0);
        assertTrue(ConfigService.obtain().writeAccountSetting(profile, BEAR_TRAP_EVENT_BOOL, "true"));
        assertTrue(ConfigService.obtain().writeAccountSetting(profile, BEAR_TRAP_NUMBER_INT, "1"));
        assertTrue(ConfigService.obtain().writeAccountSetting(profile, BEAR_TRAP_PREPARATION_TIME_INT, "10"));
        assertTrue(ConfigService.obtain().writeAccountSetting(
                profile, BEAR_TRAP_SCHEDULE_DATETIME_STRING, activationUtc.format(CONFIG_DATE_TIME)));
        assertTrue(ConfigService.obtain().writeAccountSetting(profile, GATHER_TASK_BOOL, "true"));
        assertTrue(ConfigService.obtain().writeAccountSetting(profile, ALLIANCE_AUTOJOIN_BOOL, "true"));
        return profile;
    }

    private static BearSessionExecutionException failure(
            BearSessionExecutionException.FailureKind kind,
            BearSessionExecutionException.RecoveryDirective directive) {
        return new BearSessionExecutionException(
                kind, directive, "0", "test", "simulated protected failure", null);
    }

    private static final class AdvancingFailureBearTask extends DelayedTask {

        private final LocalDateTime activationUtc;
        private int executionCount;

        private AdvancingFailureBearTask(AccountDescriptor profile, LocalDateTime activationUtc) {
            super(profile, TpDailyTaskEnum.BEAR_TRAP);
            this.activationUtc = activationUtc;
            reschedule(LocalDateTime.now().minusSeconds(1));
        }

        @Override
        public void run() {
            executionCount++;
            ConfigService.obtain().writeAccountSetting(
                    getProfile(),
                    BEAR_TRAP_SCHEDULE_DATETIME_STRING,
                    activationUtc.plusDays(2).format(CONFIG_DATE_TIME));
            throw new BearSessionExecutionException(
                    BearSessionExecutionException.FailureKind.CAPTURE_TRANSIENT,
                    BearSessionExecutionException.RecoveryDirective.DEGRADED_WAIT,
                    profile.getEmulatorNumber(),
                    "capture-frame",
                    "simulated Bear capture failure",
                    new IllegalStateException("simulated screenshot timeout"));
        }

        @Override
        protected void execute() {
        }
    }

    private static final class ReschedulingSuccessfulBearTask extends DelayedTask {

        private int executionCount;

        private ReschedulingSuccessfulBearTask(AccountDescriptor profile) {
            super(profile, TpDailyTaskEnum.BEAR_TRAP);
            reschedule(LocalDateTime.now().minusSeconds(1));
        }

        @Override
        public void run() {
            executionCount++;
            reschedule(LocalDateTime.now().plusDays(2));
        }

        @Override
        protected void execute() {
        }
    }

    private static final class RecordingNormalTask extends DelayedTask {

        private int executionCount;

        private RecordingNormalTask(AccountDescriptor profile) {
            super(profile, TpDailyTaskEnum.ALLIANCE_CHESTS);
            reschedule(LocalDateTime.now().minusSeconds(1));
        }

        @Override
        public void run() {
            executionCount++;
            setRecurring(false);
        }

        @Override
        protected void execute() {
        }
    }

    private static final class RecordingBearTask extends DelayedTask {

        private int executionCount;

        private RecordingBearTask(AccountDescriptor profile) {
            super(profile, TpDailyTaskEnum.BEAR_TRAP);
            reschedule(LocalDateTime.now().minusSeconds(1));
        }

        @Override
        public void run() {
            executionCount++;
        }

        @Override
        protected void execute() {
        }
    }

    private static final class RecordingQueue extends TaskQueue {

        private int deviceProbes;
        private int appRestarts;
        private int gatherRestores;
        private int autojoinRestores;

        private RecordingQueue(AccountDescriptor profile) {
            super(profile);
        }

        @Override
        protected void acquireSlot() {
            markSlotAcquired();
        }

        @Override
        protected void handleIdleTransitions() {
        }

        @Override
        protected void sleepSchedulerTick(long millis) {
        }

        @Override
        protected boolean probeBearDevice() {
            deviceProbes++;
            return true;
        }

        @Override
        protected boolean restartBearApp(DelayedTask task) {
            appRestarts++;
            return true;
        }

        @Override
        public synchronized void runNow(TpDailyTaskEnum kind, boolean recurring) {
            if (kind == TpDailyTaskEnum.GATHER_RESOURCES) {
                gatherRestores++;
            } else if (kind == TpDailyTaskEnum.ALLIANCE_AUTOJOIN) {
                autojoinRestores++;
            } else {
                super.runNow(kind, recurring);
            }
        }

        @Override
        protected DelayedTask createTask(TpDailyTaskEnum kind) {
            if (kind == TpDailyTaskEnum.BEAR_TRAP) {
                return new RecordingBearTask(getProfile());
            }
            return super.createTask(kind);
        }
    }
}
