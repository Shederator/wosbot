package dev.frostguard.engine.schedule;

import static dev.frostguard.api.configs.ConfigurationKeyEnum.ALLIANCE_AUTOJOIN_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.GATHER_TASK_BOOL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.service.ScheduleService;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Revocation interleavings that need a registered queue and a task factory. */
class TaskQueueBearRevocationRaceTest {

    private static final DateTimeFormatter CONFIG_DATE_TIME = DateTimeFormatter.ofPattern("dd-MM-uuuu HH:mm");

    @BeforeAll
    static void initialize() {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
        DelayedTaskRegistry.registerFactory((type, owner) -> type == TpDailyTaskEnum.BEAR_TRAP
                ? new CountingBearTask(owner) : new NoOpTask(owner, type));
    }

    @AfterAll
    static void resetFactory() {
        DelayedTaskRegistry.registerFactory(null);
    }

    @AfterEach
    void clearLeases() {
        BearTrapSessionLease.clearForTests();
    }

    @Test
    void aRevocationBetweenTheWorkersChecksAndItsClaimLeavesNoLiveLease() throws Exception {
        AccountDescriptor profile = configuredActiveProfile("Bear revoke between checks ");
        RaceQueue queue = new RaceQueue(profile);
        BearTrapSessionLease.Lease lease = BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        assertTrue(BearSessionCheckpoint.open(profile, lease.eventEnd()));
        CountDownLatch workerPassedChecks = new CountDownLatch(1);
        CountDownLatch revokerReleased = new CountDownLatch(1);
        CountDownLatch workerClaimed = new CountDownLatch(1);
        AtomicInteger workerFinalizeChecks = new AtomicInteger();
        queue.onFinalize = () -> {
            if (Thread.currentThread().getName().equals("bear-race-worker")
                    && workerFinalizeChecks.incrementAndGet() == 2) {
                workerPassedChecks.countDown();
                await(revokerReleased);
            }
        };
        queue.onReleased = () -> {
            revokerReleased.countDown();
            await(workerClaimed);
        };
        queue.onLease = workerClaimed::countDown;
        // The operator persisted the disabled profile before revoking; the worker's copy is older.
        AccountDescriptor disabled = reload(profile.getId());
        disabled.setEnabled(false);
        assertTrue(ProfileService.obtain().persistAccount(disabled));
        CountingBearTask bear = new CountingBearTask(profile);

        withQueueRegistered(queue, profile, () -> {
            Thread worker = new Thread(() -> queue.executeTask(bear), "bear-race-worker");
            worker.start();
            await(workerPassedChecks);
            Thread revoker = new Thread(() -> queue.revokeBearOwnership("profile disabled"), "bear-race-revoker");
            revoker.start();
            worker.join(10_000);
            revoker.join(10_000);
            CountingBearTask cleanup = new CountingBearTask(reload(profile.getId()));
            queue.executeTask(cleanup);

            assertEquals(0, bear.inputs + cleanup.inputs, "a revoked profile must send no Bear input");
            assertFalse(BearTrapSessionLease.active(profile.getId()).isPresent(),
                    "the lease taken in the race must be released");
            assertTrue(queue.deviceReleaseAllowed(), "the device must no longer be pinned");
            assertFalse(BearTrapProtectionPolicy.evaluateTask(
                    reload(profile.getId()), TpDailyTaskEnum.GATHER_RESOURCES).blocked(),
                    "normal work must not stay blocked after the revocation");
        });
    }

    @Test
    void reEnablingDuringARevokedEventResumesBearInsteadOfLoopingCleanup() throws Exception {
        AccountDescriptor profile = configuredActiveProfile("Bear re-enabled ");
        RaceQueue queue = new RaceQueue(profile);
        BearTrapSessionLease.Lease lease = BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        assertTrue(BearSessionCheckpoint.open(profile, lease.eventEnd()));
        CountingBearTask.inputsAcrossTasks.set(0);

        withQueueRegistered(queue, profile, () -> {
            assertTrue(ConfigService.obtain().writeAccountSetting(profile.getId(), BEAR_TRAP_EVENT_BOOL, "false"));
            queue.runSchedulerTick();
            assertTrue(ConfigService.obtain().writeAccountSetting(profile.getId(), BEAR_TRAP_EVENT_BOOL, "true"));
            int finalizationsBefore = queue.finalizations.get();
            queue.runNow(TpDailyTaskEnum.BEAR_TRAP, true);

            for (int tick = 0; tick < 20; tick++) {
                queue.runSchedulerTick();
            }

            assertTrue(queue.finalizations.get() - finalizationsBefore <= 1,
                    "re-enabling must not loop cleanup finalizations: "
                            + (queue.finalizations.get() - finalizationsBefore));
            assertTrue(CountingBearTask.inputsAcrossTasks.get() >= 1,
                    "the operator re-enabled Bear inside the event, so the session resumes");
            assertTrue(queue.sleeps.get() > 0, "the scheduler must not spin without sleeping");
        });
    }

    @Test
    void finalizationTrustsThePersistedDisableOverTheQueuesCopy() {
        AccountDescriptor profile = configuredActiveProfile("Bear persisted disable ");
        RaceQueue queue = new RaceQueue(profile);
        CountingBearTask task = new CountingBearTask(profile);
        BearTrapSessionLease.Lease lease = BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        assertTrue(BearRecoveryFinalization.arm(profile, Instant.now()));
        AccountDescriptor disabled = reload(profile.getId());
        disabled.setEnabled(false);
        assertTrue(ProfileService.obtain().persistAccount(disabled));

        assertTrue(queue.finalizeBearRecoveryIfDue(task, lease.eventEnd().plusSeconds(1)));

        assertEquals(0, queue.gatherRestores, "a profile disabled in storage must not restore gathering");
        assertFalse(queue.getNextQueuedTaskTypes(20).contains(TpDailyTaskEnum.BEAR_TRAP),
                "a profile disabled in storage must not requeue Bear");
    }

    @Test
    void aRevokedRunWhoseFinalizerWasLostStillOnlyCleansUp() {
        AccountDescriptor profile = configuredActiveProfile("Bear lost finalizer ");
        RaceQueue queue = new RaceQueue(profile);
        BearTrapSessionLease.Lease lease = BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        assertTrue(BearSessionCheckpoint.open(profile, lease.eventEnd()));
        persistDisabled(profile);
        assertTrue(queue.revokeBearOwnership("profile disabled"));
        // The revocation's finalizer write failed or was erased.
        assertTrue(BearRecoveryFinalization.clear(profile));
        CountingBearTask bear = new CountingBearTask(reload(profile.getId()));

        assertTrue(queue.executeTask(bear));

        assertEquals(0, bear.inputs, "a revoked event must not run a session");
        assertTrue(queue.finalizations.get() >= 1, "the cleanup finalization ran");
        assertTrue(BearSessionCheckpoint.load(reload(profile.getId())).isEmpty(),
                "the cleanup finalization cleared the session checkpoint");
    }

    @Test
    void aDisableDuringTheEventsFirstClaimSendsNoInputAndLeavesNoLease() throws Exception {
        AccountDescriptor profile = configuredActiveProfile("Bear first claim disable ");
        RaceQueue queue = new RaceQueue(profile);
        CountDownLatch workerPassedChecks = new CountDownLatch(1);
        CountDownLatch revoked = new CountDownLatch(1);
        AtomicInteger workerFinalizeChecks = new AtomicInteger();
        queue.onFinalize = () -> {
            if (Thread.currentThread().getName().equals("bear-first-claim")
                    && workerFinalizeChecks.incrementAndGet() == 2) {
                workerPassedChecks.countDown();
                await(revoked);
            }
        };
        CountingBearTask bear = new CountingBearTask(profile);

        withQueueRegistered(queue, profile, () -> {
            Thread worker = new Thread(() -> queue.executeTask(bear), "bear-first-claim");
            worker.start();
            await(workerPassedChecks);
            // Nothing is owned yet, so the revocation itself finds nothing to stop.
            persistDisabled(profile);
            queue.revokeBearOwnership("profile disabled");
            revoked.countDown();
            worker.join(10_000);
            CountingBearTask cleanup = new CountingBearTask(reload(profile.getId()));
            queue.executeTask(cleanup);

            assertEquals(0, bear.inputs + cleanup.inputs, "a profile disabled before its claim must send no Bear input");
            assertFalse(BearTrapSessionLease.active(profile.getId()).isPresent(),
                    "the first claim must not keep a lease for a disabled profile");
            assertTrue(queue.deviceReleaseAllowed(), "the device must not stay pinned");
            assertTrue(BearSessionCheckpoint.load(reload(profile.getId())).isEmpty(),
                    "the cleanup finalization cleared the checkpoint the claim opened");
        });
    }

    @Test
    void aRevokedRunKeepsCleaningUpWhileParticipationIsStillOffInStorage() throws Exception {
        AccountDescriptor profile = configuredActiveProfile("Bear participation still off ");
        // The queue's copy still says the profile participates; storage says it does not.
        RaceQueue queue = new RaceQueue(profile);
        BearTrapSessionLease.Lease lease = BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        assertTrue(BearSessionCheckpoint.open(profile, lease.eventEnd()));
        assertTrue(ConfigService.obtain().writeAccountSetting(profile.getId(), BEAR_TRAP_EVENT_BOOL, "false"));
        assertTrue(queue.revokeBearOwnership("Bear participation disabled"));
        assertTrue(BearRecoveryFinalization.clear(profile));
        CountingBearTask bear = new CountingBearTask(profile);

        withQueueRegistered(queue, profile, () -> queue.executeTask(bear));

        assertEquals(0, bear.inputs, "participation is still off in storage, so the revoked event stays revoked");
        assertFalse(BearTrapSessionLease.active(profile.getId()).isPresent());
    }

    @Test
    void reEnablingTheProfileResumesBearWithoutAnotherSchedulingAction() throws Exception {
        AccountDescriptor profile = configuredActiveProfile("Bear profile re-enabled ");
        RaceQueue queue = new RaceQueue(profile);
        BearTrapSessionLease.Lease lease = BearTrapSessionLease.acquireForBearExecution(profile).orElseThrow();
        assertTrue(BearSessionCheckpoint.open(profile, lease.eventEnd()));
        CountingBearTask.inputsAcrossTasks.set(0);

        withQueueRegistered(queue, profile, () -> {
            persistDisabled(profile);
            assertTrue(queue.revokeBearOwnership("profile disabled"));
            for (int tick = 0; tick < 3; tick++) {
                queue.runSchedulerTick();
            }
            assertEquals(0, CountingBearTask.inputsAcrossTasks.get(), "the revoked event only cleaned up");
            // Production re-enable: persist the profile, then hand the queue the operator's resume.
            AccountDescriptor enabled = reload(profile.getId());
            enabled.setEnabled(true);
            assertTrue(ProfileService.obtain().persistAccount(enabled));
            ScheduleService.obtain().resumeBearOwnership(profile.getId());
            int finalizationsBefore = queue.finalizations.get();

            for (int tick = 0; tick < 10; tick++) {
                queue.runSchedulerTick();
            }

            assertTrue(CountingBearTask.inputsAcrossTasks.get() >= 1,
                    "re-enabling the profile inside the event resumes the Bear session");
            assertTrue(queue.finalizations.get() - finalizationsBefore <= 1, "no cleanup loop after re-enable");
        });
    }

    /** Production persists the operator's disable before it revokes Bear ownership. */
    private static void persistDisabled(AccountDescriptor profile) {
        AccountDescriptor disabled = reload(profile.getId());
        disabled.setEnabled(false);
        assertTrue(ProfileService.obtain().persistAccount(disabled));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "interleaving latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
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

    private static AccountDescriptor reload(Long profileId) {
        return ProfileService.obtain().fetchAllAccounts().stream()
                .filter(candidate -> profileId.equals(candidate.getId()))
                .findFirst()
                .orElseThrow();
    }

    private static AccountDescriptor configuredActiveProfile(String prefix) {
        AccountDescriptor profile = new AccountDescriptor(null, prefix + UUID.randomUUID(), "0", true, 100L, 30L);
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

    private static final class NoOpTask extends DelayedTask {

        private NoOpTask(AccountDescriptor profile, TpDailyTaskEnum type) {
            super(profile, type);
        }

        @Override
        public void run() {
            setRecurring(false);
        }

        @Override
        protected void execute() {
        }
    }

    /** Counts Bear input only once the run gets past its first cancellation check. */
    private static final class CountingBearTask extends DelayedTask {

        private static final AtomicInteger inputsAcrossTasks = new AtomicInteger();
        private int inputs;

        private CountingBearTask(AccountDescriptor profile) {
            super(profile, TpDailyTaskEnum.BEAR_TRAP);
            reschedule(LocalDateTime.now().minusSeconds(1));
        }

        @Override
        public void run() {
            checkPreemption();
            inputs++;
            inputsAcrossTasks.incrementAndGet();
        }

        @Override
        protected void execute() {
        }
    }

    private static final class RaceQueue extends TaskQueue {

        private Runnable onFinalize = () -> { };
        private Runnable onReleased = () -> { };
        private Runnable onLease = () -> { };
        private final AtomicInteger finalizations = new AtomicInteger();
        private final AtomicInteger sleeps = new AtomicInteger();
        private int gatherRestores;

        private RaceQueue(AccountDescriptor profile) {
            super(profile);
        }

        @Override
        boolean finalizeBearRecoveryIfDue(DelayedTask task, Instant now) {
            onFinalize.run();
            boolean done = super.finalizeBearRecoveryIfDue(task, now);
            if (done) {
                finalizations.incrementAndGet();
            }
            return done;
        }

        @Override
        protected void afterBearOwnershipReleased() {
            onReleased.run();
        }

        @Override
        protected void afterBearLeaseAcquired() {
            onLease.run();
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
            sleeps.incrementAndGet();
        }

        @Override
        public synchronized void runNow(TpDailyTaskEnum kind, boolean recurring) {
            if (kind == TpDailyTaskEnum.GATHER_RESOURCES) {
                gatherRestores++;
            } else if (kind == TpDailyTaskEnum.BEAR_TRAP) {
                super.runNow(kind, recurring);
            }
        }

        @Override
        protected DelayedTask createTask(TpDailyTaskEnum kind) {
            if (kind == TpDailyTaskEnum.BEAR_TRAP) {
                return new CountingBearTask(getProfile());
            }
            return super.createTask(kind);
        }
    }
}
