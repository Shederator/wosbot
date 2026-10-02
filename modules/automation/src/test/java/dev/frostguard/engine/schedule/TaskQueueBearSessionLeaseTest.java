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
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.engine.service.ScheduleService;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.data.repository.DailyTaskRepository;
import dev.frostguard.engine.error.BearSessionExecutionException;
import dev.frostguard.engine.error.ProfileCooldownException;
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
                null, "Bear lease " + UUID.randomUUID(), "0", true, 100L, 30L);
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

    @Test
    void schedulerTickFailureDoesNotKillTheQueueWorker() {
        AccountDescriptor profile = configuredActiveProfile("Bear worker survives ");
        int[] ticks = {0};
        TaskQueue queue = new TaskQueue(profile) {
            @Override
            void runSchedulerTick() {
                ticks[0]++;
                throw new IllegalStateException("could not clear durable Bear state");
            }

            @Override
            protected void sleepSchedulerTick(long millis) {
            }
        };

        queue.runSchedulerTickSafely();
        queue.runSchedulerTickSafely();

        assertEquals(2, ticks[0], "the worker must keep ticking after a routing failure");
    }

    @Test
    void siblingYieldsItsSlotToTheBearOwnerInsteadOfHoldingIt() throws Exception {
        AccountDescriptor bearProfile = configuredActiveProfile("Bear slot owner ");
        AccountDescriptor sibling = new AccountDescriptor(
                null, "Bear slot sibling " + UUID.randomUUID(), "0", true, 100L, 30L);
        assertTrue(ProfileService.obtain().createAccount(sibling));
        RecordingQueue bearQueue = new RecordingQueue(bearProfile);
        RecordingQueue siblingQueue = new RecordingQueue(sibling);
        BearTrapSessionLease.acquireForBearExecution(bearProfile).orElseThrow();
        bearQueue.registerDeviceProtection();
        try {
            bearQueue.markSlotAcquired();
            siblingQueue.markSlotAcquired();

            assertFalse(bearQueue.yieldSlotToProtectedOwner(), "the owner keeps its own slot");
            assertTrue(siblingQueue.yieldSlotToProtectedOwner(),
                    "a sibling must not hold the slot the Bear owner is waiting for");
            assertTrue(TaskQueue.requiresSlotAcquisition(sessionOrigin(siblingQueue)));
            assertFalse(TaskQueue.requiresSlotAcquisition(sessionOrigin(bearQueue)));
        } finally {
            bearQueue.unregisterDeviceProtection();
        }
    }

    private static LocalDateTime sessionOrigin(TaskQueue queue) throws Exception {
        Field origin = TaskQueue.class.getDeclaredField("sessionOrigin");
        origin.setAccessible(true);
        return (LocalDateTime) origin.get(queue);
    }

    @Test
    void expiredSessionWithoutALeaseFinalizesInsteadOfRetryingForever() {
        AccountDescriptor profile = new AccountDescriptor(
                null, "Bear expired cleanup " + UUID.randomUUID(), "0", true, 100L, 30L);
        assertTrue(ProfileService.obtain().createAccount(profile));
        assertTrue(ConfigService.obtain().writeAccountSetting(profile, BEAR_TRAP_EVENT_BOOL, "true"));
        Instant endedAt = Instant.now().minusSeconds(120);
        assertTrue(BearSessionCheckpoint.open(profile, endedAt));
        RecordingQueue queue = new RecordingQueue(reload(profile.getId()));
        RecordingBearTask bear = new RecordingBearTask(reload(profile.getId()));

        assertTrue(queue.executeTask(bear), "an ended session must finalize, not be refused");

        assertEquals(0, bear.executionCount, "finalization performs no Bear strategy input");
        assertTrue(BearSessionCheckpoint.load(reload(profile.getId())).isEmpty());
        assertTrue(BearRecoveryFinalization.deadline(reload(profile.getId())).isEmpty());
    }

    @Test
    void exhaustedRecoveryKeepsOwnershipButSwitchesTheRestOfTheEventToObserveOnly() {
        AccountDescriptor profile = configuredActiveProfile("Bear observe fallback ");
        RecordingQueue queue = new RecordingQueue(profile);
        RecordingBearTask bear = new RecordingBearTask(profile);
        BearTrapSessionLease.Lease lease =
                BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        BearSessionExecutionException captureFailure = failure(
                BearSessionExecutionException.FailureKind.CAPTURE_TRANSIENT,
                BearSessionExecutionException.RecoveryDirective.DEGRADED_WAIT);

        for (int attempt = 1; attempt <= 4; attempt++) {
            queue.routeError(bear, captureFailure);
            assertFalse(BearObserveOnlyFallback.active(reload(profile.getId()), Instant.now()),
                    "a recovery inside the budget keeps full input (attempt " + attempt + ")");
        }
        queue.routeError(bear, captureFailure);

        assertTrue(BearObserveOnlyFallback.active(reload(profile.getId()), Instant.now()),
                "after the budget the rest of the event is observed, not played blind");
        assertFalse(BearObserveOnlyFallback.active(reload(profile.getId()), lease.eventEnd()),
                "the fallback ends with the event");
        assertTrue(queue.finalizeBearRecoveryIfDue(bear, lease.eventEnd().plusSeconds(1)));
        assertTrue(BearObserveOnlyFallback.until(reload(profile.getId())).isEmpty(),
                "finalization clears the fallback");
    }

    @Test
    void operatorActionInsideTheWindowSwitchesToObserveOnly() {
        AccountDescriptor profile = configuredActiveProfile("Bear observe operator ");
        RecordingQueue queue = new RecordingQueue(profile);
        BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();

        queue.routeError(new RecordingBearTask(profile), failure(
                BearSessionExecutionException.FailureKind.FATAL_CONFIGURATION,
                BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION));

        assertTrue(BearObserveOnlyFallback.active(reload(profile.getId()), Instant.now()));
    }

    @Test
    void cooldownDuringBearOwnershipNeitherStopsTheGameNorFreesTheSlot() throws Exception {
        AccountDescriptor profile = configuredActiveProfile("Bear cooldown ");
        RecordingQueue queue = new RecordingQueue(profile);
        BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        queue.markSlotAcquired();

        queue.routeError(new RecordingNormalTask(profile), new ProfileCooldownException(
                "simulated startup cooldown", LocalDateTime.now().plusMinutes(5)));

        assertEquals(0, queue.gameStops, "the game Bear plays must not be force-stopped");
        assertEquals(0, queue.slotReleases, "the Bear profile keeps its emulator slot");
        assertFalse(TaskQueue.requiresSlotAcquisition(sessionOrigin(queue)));
    }

    @Test
    void cooldownWithoutBearOwnershipStillReleasesItsResources() {
        AccountDescriptor profile = configuredActiveProfile("Bear cooldown idle ");
        assertTrue(ConfigService.obtain().writeAccountSetting(profile, BEAR_TRAP_EVENT_BOOL, "false"));
        RecordingQueue queue = new RecordingQueue(reload(profile.getId()));

        queue.routeError(new RecordingNormalTask(profile), new ProfileCooldownException(
                "simulated startup cooldown", LocalDateTime.now().plusMinutes(5)));

        assertEquals(1, queue.gameStops);
        assertEquals(1, queue.slotReleases);
    }

    @Test
    void failedIdleWakeKeepsTheSlotOnlyWhileBearOwnsTheEvent() throws Exception {
        AccountDescriptor owner = configuredActiveProfile("Bear idle wake owner ");
        RecordingQueue protectedQueue = new RecordingQueue(owner);
        BearTrapSessionLease.acquireForBearExecution(owner).orElseThrow();
        protectedQueue.markSlotAcquired();
        protectedQueue.releaseSlotAfterFailedIdleWake();
        assertFalse(TaskQueue.requiresSlotAcquisition(sessionOrigin(protectedQueue)));

        AccountDescriptor plain = configuredActiveProfile("Bear idle wake plain ");
        assertTrue(ConfigService.obtain().writeAccountSetting(plain, BEAR_TRAP_EVENT_BOOL, "false"));
        RecordingQueue plainQueue = new RecordingQueue(reload(plain.getId()));
        plainQueue.markSlotAcquired();
        plainQueue.releaseSlotAfterFailedIdleWake();
        assertTrue(TaskQueue.requiresSlotAcquisition(sessionOrigin(plainQueue)));
    }

    @Test
    void protectedQueueUnderAnIdleBreachKeepsItsSlotAndDevice() throws Exception {
        AccountDescriptor profile = configuredActiveProfile("Bear idle breach ");
        BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        TaskQueue queue = new TaskQueue(profile) {
            @Override
            protected void acquireSlot() {
                markSlotAcquired();
            }
        };
        queue.markSlotAcquired();
        queue.enqueue(new RecordingNormalTask(profile));
        queue.statusModel.setDelayUntil(LocalDateTime.now().plusHours(2));

        queue.handleIdleTransitions();

        assertFalse(TaskQueue.requiresSlotAcquisition(sessionOrigin(queue)),
                "idle handling must not release the Bear profile's slot");
        assertFalse(queue.statusModel.isIdleTimeExceeded(), "Bear ownership skips idle handling");
    }

    @Test
    void revocationCancelsTheRunningBearSession() {
        AccountDescriptor profile = configuredActiveProfile("Bear revoke running ");
        RecordingQueue queue = new RecordingQueue(profile);
        // Production creates and runs the same BearTrapRoutine class; mirror that equality.
        queue.bearFactory = owner -> new RevokingBearTask(owner, queue);
        RevokingBearTask bear = new RevokingBearTask(profile, queue);

        queue.executeTask(bear);

        assertTrue(bear.cancelledAfterRevocation, "the running Bear session must be cancelled");
        assertEquals(1, queue.getNextQueuedTaskTypes(20).stream()
                        .filter(type -> type == TpDailyTaskEnum.BEAR_TRAP).count(),
                "exactly one Bear task carries the cleanup-only finalization");
        assertFalse(BearRecoveryFinalization.deadline(reload(profile.getId())).orElseThrow()
                .isAfter(Instant.now()), "the cleanup finalization is due now");
        assertTrue(queue.bearScheduledAt().orElseThrow().isBefore(LocalDateTime.now().plusSeconds(2)),
                "the queued cleanup runs now, not after an in-window retry delay");
    }

    @Test
    void theBacklogNeverHoldsTwoBearTasksAndTheEarliestWins() {
        AccountDescriptor profile = configuredActiveProfile("Bear single task ");
        RecordingQueue queue = new RecordingQueue(profile);
        RecordingBearTask later = new RecordingBearTask(profile);
        later.reschedule(LocalDateTime.now().plusMinutes(10));
        RecordingBearTask sooner = new RecordingBearTask(profile);
        sooner.reschedule(LocalDateTime.now().plusSeconds(5));

        queue.enqueue(later);
        queue.enqueue(sooner);
        queue.enqueue(later);

        assertEquals(1, queue.getNextQueuedTaskTypes(20).stream()
                .filter(type -> type == TpDailyTaskEnum.BEAR_TRAP).count());
        assertTrue(queue.bearScheduledAt().orElseThrow().isBefore(LocalDateTime.now().plusSeconds(10)));
    }

    private static final class RevokingBearTask extends DelayedTask {

        private final TaskQueue queue;
        private boolean cancelledAfterRevocation;

        private RevokingBearTask(AccountDescriptor profile, TaskQueue queue) {
            super(profile, TpDailyTaskEnum.BEAR_TRAP);
            this.queue = queue;
            reschedule(LocalDateTime.now().minusSeconds(1));
        }

        @Override
        public void run() {
            queue.revokeBearOwnership("profile disabled");
            try {
                checkPreemption();
            } catch (RuntimeException cancelled) {
                cancelledAfterRevocation = true;
            }
        }

        @Override
        protected void execute() {
        }
    }

    @Test
    void aScheduleRealignDuringTheEventCannotMoveBearLater() {
        AccountDescriptor profile = configuredActiveProfile("Bear realign ");
        RecordingQueue queue = new RecordingQueue(profile);
        BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        RecordingBearTask retry = new RecordingBearTask(profile);
        retry.reschedule(LocalDateTime.now().plusSeconds(20));
        queue.enqueue(retry);

        assertFalse(queue.scheduleOrRescheduleQueuedTask(
                TpDailyTaskEnum.BEAR_TRAP, profile, LocalDateTime.now().plusDays(2)));

        assertTrue(queue.bearScheduledAt().orElseThrow().isBefore(LocalDateTime.now().plusSeconds(31)),
                "an edited schedule must not move the in-window retry out of the event");
    }

    @Test
    void aRevocationThatLandsBeforeTheLeaseIsTakenStillWins() {
        AccountDescriptor profile = configuredActiveProfile("Bear revoke before lease ");
        RecordingQueue queue = new RecordingQueue(profile);
        BearTrapSessionLease.Lease lease =
                BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        assertTrue(BearSessionCheckpoint.open(profile, lease.eventEnd()));
        assertTrue(queue.revokeBearOwnership("profile disabled"));
        RecordingBearTask bear = new RecordingBearTask(reload(profile.getId()));

        // The worker can still be running with the profile it loaded before the revocation.
        assertTrue(queue.executeTask(bear));

        assertEquals(0, bear.executionCount, "a due cleanup finalization must not become a full session");
        assertTrue(BearRecoveryFinalization.deadline(reload(profile.getId())).isEmpty(),
                "the finalization ran instead of being erased");
    }

    @Test
    void revokedParticipationCannotRegainTheEventLease() {
        AccountDescriptor profile = configuredActiveProfile("Bear checkpoint disabled ");
        BearTrapSessionLease.Lease lease =
                BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        assertTrue(BearSessionCheckpoint.open(profile, lease.eventEnd()));
        BearTrapSessionLease.releaseForQueueStop(profile.getId());
        // Only the durable checkpoint still proves the event once the schedule was edited away.
        assertTrue(ConfigService.obtain().writeAccountSetting(
                profile, BEAR_TRAP_SCHEDULE_DATETIME_STRING, "01-01-2035 00:00"));
        assertTrue(BearTrapSessionLease.acquireForBearExecution(reload(profile.getId())).isPresent(),
                "with participation on, the checkpoint restores the lease");
        BearTrapSessionLease.releaseForQueueStop(profile.getId());
        assertTrue(ConfigService.obtain().writeAccountSetting(profile, BEAR_TRAP_EVENT_BOOL, "false"));

        assertTrue(BearTrapSessionLease.acquireForBearExecution(reload(profile.getId())).isEmpty(),
                "disabled participation must not regain the event lease from its checkpoint");

        assertTrue(ConfigService.obtain().writeAccountSetting(profile, BEAR_TRAP_EVENT_BOOL, "true"));
        AccountDescriptor disabledProfile = reload(profile.getId());
        disabledProfile.setEnabled(false);
        assertTrue(BearTrapSessionLease.acquireForBearExecution(disabledProfile).isEmpty(),
                "a disabled profile must not regain the event lease after a restart");
    }

    @Test
    void aDisabledProfileIsNotProtectedByItsDurableRecovery() {
        AccountDescriptor profile = configuredActiveProfile("Bear protection disabled ");
        BearTrapSessionLease.Lease lease =
                BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        assertTrue(BearRecoveryFinalization.arm(profile, lease.eventEnd()));
        BearTrapSessionLease.releaseForQueueStop(profile.getId());
        AccountDescriptor disabled = reload(profile.getId());
        disabled.setEnabled(false);

        assertFalse(new RecordingQueue(disabled).hasProtectedBearOwnership(Instant.now()));
        assertTrue(new RecordingQueue(reload(profile.getId())).hasProtectedBearOwnership(Instant.now()));
    }

    @Test
    void aRevocationDuringTheLeaseClaimWaitsAndThenCancelsTheSession() throws Exception {
        AccountDescriptor profile = configuredActiveProfile("Bear revoke race ");
        RecordingQueue queue = new RecordingQueue(profile);
        CancellationObservingBearTask bear = new CancellationObservingBearTask(profile);
        Thread[] revoker = new Thread[1];
        queue.afterLease = () -> {
            revoker[0] = new Thread(() -> queue.revokeBearOwnership("profile disabled"));
            revoker[0].start();
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };

        queue.executeTask(bear);
        revoker[0].join(5_000);

        assertTrue(bear.cancelled,
                "a revocation racing the lease claim must cancel the session, not be lost");
    }

    private static final class CancellationObservingBearTask extends DelayedTask {

        private boolean cancelled;

        private CancellationObservingBearTask(AccountDescriptor profile) {
            super(profile, TpDailyTaskEnum.BEAR_TRAP);
            reschedule(LocalDateTime.now().minusSeconds(1));
        }

        @Override
        public void run() {
            try {
                Thread.sleep(500);
                checkPreemption();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException cancellation) {
                cancelled = true;
            }
        }

        @Override
        protected void execute() {
        }
    }

    @Test
    void aRevocationThatTakesTheLockFirstStopsAWorkerBetweenItsChecks() throws Exception {
        AccountDescriptor profile = configuredActiveProfile("Bear revoke first ");
        RecordingQueue queue = new RecordingQueue(profile);
        BearTrapSessionLease.Lease lease =
                BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        assertTrue(BearSessionCheckpoint.open(profile, lease.eventEnd()));
        RecordingBearTask bear = new RecordingBearTask(profile);
        Thread[] worker = new Thread[1];
        queue.afterOwnershipReleased = () -> {
            // The worker passes the pre-lock finalizer check before the revocation arms it.
            worker[0] = new Thread(() -> queue.executeTask(bear));
            worker[0].start();
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };

        assertTrue(queue.revokeBearOwnership("profile disabled"));
        worker[0].join(10_000);

        assertEquals(0, bear.executionCount,
                "a worker racing a revocation must run the cleanup, not a full session");
        RecordingBearTask later = new RecordingBearTask(profile);
        assertTrue(queue.executeTask(later));
        assertEquals(0, later.executionCount, "every later run for the revoked event is cleanup-only");
        assertTrue(BearRecoveryFinalization.deadline(reload(profile.getId())).isEmpty(),
                "the cleanup finalization ran");
    }

    @Test
    void revocationDoesNotDeadlockWithAConcurrentProfileSettingWrite() throws Exception {
        AccountDescriptor profile = configuredActiveProfile("Bear deadlock revoke ");
        RecordingQueue queue = new RecordingQueue(profile);
        BearTrapSessionLease.Lease lease =
                BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        assertTrue(BearSessionCheckpoint.open(profile, lease.eventEnd()));
        CountDownLatch writerHoldsTheSettingLock = new CountDownLatch(1);
        ProfileService.obtain().registerDataObserver(changed -> {
            if (Thread.currentThread().getName().equals("bear-deadlock-writer")) {
                writerHoldsTheSettingLock.countDown();
            }
        });
        queue.afterOwnershipReleased = () -> {
            Thread writer = new Thread(() -> ConfigService.obtain().writeAccountSetting(
                    profile.getId(), ConfigurationKeyEnum.BEAR_TRAP_OBSERVE_ONLY_BOOL, "false"),
                    "bear-deadlock-writer");
            writer.setDaemon(true);
            writer.start();
            try {
                writerHoldsTheSettingLock.await(5, TimeUnit.SECONDS);
                Thread.sleep(300);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        withQueueRegistered(queue, profile, () -> {
            Thread revoker = new Thread(() -> queue.revokeBearOwnership("profile disabled"),
                    "bear-deadlock-revoker");
            revoker.setDaemon(true);
            revoker.start();
            revoker.join(5_000);

            assertEquals("none", deadlockedThreads(),
                    "revocation and a concurrent profile-setting write must not deadlock");
        });
    }

    @Test
    void theLeaseClaimDoesNotDeadlockWithAnOperatorSettingWrite() throws Exception {
        AccountDescriptor profile = configuredActiveProfile("Bear deadlock claim ");
        RecordingQueue queue = new RecordingQueue(profile);
        CountDownLatch operatorHoldsTheSettingLock = new CountDownLatch(1);
        ProfileService.obtain().registerDataObserver(changed -> {
            if (Thread.currentThread().getName().equals("bear-deadlock-operator")) {
                operatorHoldsTheSettingLock.countDown();
                try {
                    Thread.sleep(700);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        withQueueRegistered(queue, profile, () -> {
            Thread operator = new Thread(() -> ConfigService.obtain().writeAccountSetting(
                    profile.getId(), ConfigurationKeyEnum.BEAR_TRAP_OBSERVE_ONLY_BOOL, "false"),
                    "bear-deadlock-operator");
            operator.setDaemon(true);
            operator.start();
            assertTrue(operatorHoldsTheSettingLock.await(5, TimeUnit.SECONDS));
            Thread worker = new Thread(() -> queue.executeTask(new RecordingBearTask(profile)),
                    "bear-deadlock-worker");
            worker.setDaemon(true);
            worker.start();
            worker.join(5_000);
            operator.join(1_000);

            assertEquals("none", deadlockedThreads(),
                    "the lease claim and an operator setting write must not deadlock");
        });
    }

    @Test
    void aProfileDisabledMidTickDoesNotRegainItsBearSession() {
        AccountDescriptor profile = configuredActiveProfile("Bear disabled mid tick ");
        RecordingQueue queue = new RecordingQueue(profile);
        List<RecordingBearTask> created = new ArrayList<>();
        queue.bearFactory = owner -> {
            RecordingBearTask task = new RecordingBearTask(owner);
            created.add(task);
            return task;
        };
        BearTrapSessionLease.Lease lease =
                BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        assertTrue(BearSessionCheckpoint.open(profile, lease.eventEnd()));
        AccountDescriptor disabled = reload(profile.getId());
        disabled.setEnabled(false);
        assertTrue(ProfileService.obtain().persistAccount(disabled));
        // The worker still holds the profile it loaded at the start of its tick.
        assertTrue(queue.revokeBearOwnership("profile disabled"));
        RecordingBearTask bear = new RecordingBearTask(profile);

        queue.executeTask(bear);
        for (int tick = 0; tick < 3; tick++) {
            queue.runSchedulerTick();
        }

        int runs = bear.executionCount + created.stream().mapToInt(task -> task.executionCount).sum();
        assertEquals(0, runs, "a disabled profile must not regain a Bear session");
    }

    @SuppressWarnings("unchecked")
    private static void withQueueRegistered(TaskQueue queue, AccountDescriptor profile, ThrowingAction action)
            throws Exception {
        Field dispatcherField = ScheduleService.class.getDeclaredField("dispatcher");
        dispatcherField.setAccessible(true);
        TaskDispatcher dispatcher = (TaskDispatcher) dispatcherField.get(ScheduleService.obtain());
        Field queuesField = TaskDispatcher.class.getDeclaredField("managedQueues");
        queuesField.setAccessible(true);
        Map<Long, TaskQueue> queues = (Map<Long, TaskQueue>) queuesField.get(dispatcher);
        queues.put(profile.getId(), queue);
        try {
            action.run();
        } finally {
            queues.remove(profile.getId(), queue);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static String deadlockedThreads() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long[] ids = threads.findDeadlockedThreads();
        if (ids == null) {
            return "none";
        }
        StringBuilder cycle = new StringBuilder();
        for (ThreadInfo info : threads.getThreadInfo(ids, true, true)) {
            cycle.append(info.getThreadName()).append(" waits ").append(info.getLockName())
                    .append(" held by ").append(info.getLockOwnerName()).append("; ");
        }
        return cycle.toString();
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
        private Function<AccountDescriptor, DelayedTask> bearFactory =
                RecordingBearTask::new;
        private Runnable afterLease = () -> { };
        private Runnable afterOwnershipReleased = () -> { };
        private int gameStops;
        private int slotReleases;

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
        protected void afterBearLeaseAcquired() {
            afterLease.run();
        }

        @Override
        protected void afterBearOwnershipReleased() {
            afterOwnershipReleased.run();
        }

        @Override
        protected boolean stopBlockedGameProcess(DelayedTask task) {
            gameStops++;
            return true;
        }

        @Override
        protected boolean releaseBlockedProfileSlot(DelayedTask task) {
            slotReleases++;
            return true;
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
                return bearFactory.apply(getProfile());
            }
            return super.createTask(kind);
        }
    }
}
