package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.engine.service.LoggingService;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.service.ScheduleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskQueueIdleHandoffTest {

    private final List<Long> createdAccountIds = new ArrayList<>();

    @BeforeAll
    static void initializeTestWorkspace() {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
    }

    @AfterEach
    void cleanup() {
        LoggingService.obtain().attachObserver(null);
        TaskDispatcher coordinator = ScheduleService.obtain().getCoordinator();
        for (Long accountId : createdAccountIds) {
            coordinator.deregisterAccount(accountId);
        }
    }

    @Test
    void idleProfileWithKeepOpenYieldsToOverduePeerSibling() {
        String emulatorNumber = "test-emu-" + UUID.randomUUID();
        AccountDescriptor profileA = persistedProfile("Profile A " + UUID.randomUUID(), emulatorNumber, true, 100L);
        profileA.setConfig(ConfigurationKeyEnum.KEEP_EMULATOR_OPEN_BOOL, true);
        AccountDescriptor profileB = persistedProfile("Profile B " + UUID.randomUUID(), emulatorNumber, true, 200L);

        TestableTaskQueue queueA = new TestableTaskQueue(profileA);
        TestableTaskQueue queueB = new TestableTaskQueue(profileB);
        registerQueues(queueA, queueB);

        // Profile A has a recurring task in the future (backlog not empty)
        queueA.enqueue(new TestDelayedTask(profileA, TpDailyTaskEnum.DAILY_MISSIONS, LocalDateTime.now().plusHours(2)));
        queueA.statusModel.setDelayUntil(LocalDateTime.now().plusHours(2));
        queueA.statusModel.setIdleCeilingMinutes(15);
        queueA.statusModel.setOperational(true);
        queueA.retainLease();

        // Profile B has an overdue runnable task
        queueB.enqueue(new TestDelayedTask(profileB, TpDailyTaskEnum.GATHER_RESOURCES, LocalDateTime.now().minusMinutes(5)));
        queueB.statusModel.setOperational(true);

        queueA.handleIdleTransitions();

        assertTrue(queueA.statusModel.isIdleTimeExceeded());
        assertTrue(queueA.events.contains("release"), "Active slot lease must be released for sibling handover");
        assertFalse(queueA.suspendCalled, "Device must not be suspended when handing over to peer");

        assertFalse(queueB.isPaused());
        queueB.runSchedulerTick();
        assertEquals(List.of(TpDailyTaskEnum.INITIALIZE), queueB.executed,
                "The peer must run Initialize on the normal scheduler path before gameplay.");
    }

    @Test
    void idleProfileWithKeepOpenRetainsDeviceWhenNoPeerIsOverdue() {
        String emulatorNumber = "test-emu-" + UUID.randomUUID();
        AccountDescriptor profileA = persistedProfile("Profile A " + UUID.randomUUID(), emulatorNumber, true, 100L);
        profileA.setConfig(ConfigurationKeyEnum.KEEP_EMULATOR_OPEN_BOOL, true);
        AccountDescriptor profileB = persistedProfile("Profile B " + UUID.randomUUID(), emulatorNumber, true, 200L);

        TestableTaskQueue queueA = new TestableTaskQueue(profileA);
        TestableTaskQueue queueB = new TestableTaskQueue(profileB);
        registerQueues(queueA, queueB);

        queueA.enqueue(new TestDelayedTask(profileA, TpDailyTaskEnum.DAILY_MISSIONS, LocalDateTime.now().plusHours(2)));
        queueA.statusModel.setDelayUntil(LocalDateTime.now().plusHours(2));
        queueA.statusModel.setIdleCeilingMinutes(15);
        queueA.statusModel.setOperational(true);
        queueA.retainLease();

        // Profile B has a task scheduled in the future (not overdue)
        queueB.enqueue(new TestDelayedTask(profileB, TpDailyTaskEnum.GATHER_RESOURCES, LocalDateTime.now().plusMinutes(30)));
        queueB.statusModel.setOperational(true);

        queueA.handleIdleTransitions();

        assertTrue(queueA.statusModel.isIdleTimeExceeded());
        assertEquals(0, queueA.slotReleaseCount, "Slot must not be released when keep-open is enabled and peer is not overdue");
        assertFalse(queueA.suspendCalled, "Device must not be suspended when keep-open is enabled");
        assertFalse(queueB.isExecutingTask(TpDailyTaskEnum.INITIALIZE));
    }

    @Test
    void singleProfileWithKeepOpenRetainsDeviceWithoutHandoverOrSuspend() {
        String emulatorNumber = "test-emu-" + UUID.randomUUID();
        AccountDescriptor profileA = persistedProfile("Profile A " + UUID.randomUUID(), emulatorNumber, true, 100L);
        profileA.setConfig(ConfigurationKeyEnum.KEEP_EMULATOR_OPEN_BOOL, true);

        TestableTaskQueue queueA = new TestableTaskQueue(profileA);
        registerQueues(queueA);

        queueA.enqueue(new TestDelayedTask(profileA, TpDailyTaskEnum.DAILY_MISSIONS, LocalDateTime.now().plusHours(2)));
        queueA.statusModel.setDelayUntil(LocalDateTime.now().plusHours(2));
        queueA.statusModel.setIdleCeilingMinutes(15);
        queueA.statusModel.setOperational(true);
        queueA.retainLease();

        queueA.handleIdleTransitions();

        assertTrue(queueA.statusModel.isIdleTimeExceeded());
        assertEquals(0, queueA.slotReleaseCount, "Slot must not be released for single-profile keep-open");
        assertFalse(queueA.suspendCalled, "Device must not be suspended for single-profile keep-open");
    }

    @Test
    void singleProfileWithoutKeepOpenSuspendsDevice() {
        String emulatorNumber = "test-emu-" + UUID.randomUUID();
        AccountDescriptor profileA = persistedProfile("Profile A " + UUID.randomUUID(), emulatorNumber, true, 100L);
        profileA.setConfig(ConfigurationKeyEnum.KEEP_EMULATOR_OPEN_BOOL, false);

        TestableTaskQueue queueA = new TestableTaskQueue(profileA);
        registerQueues(queueA);

        LocalDateTime delayUntil = LocalDateTime.now().plusHours(2);
        queueA.enqueue(new TestDelayedTask(profileA, TpDailyTaskEnum.DAILY_MISSIONS, delayUntil));
        queueA.statusModel.setDelayUntil(delayUntil);
        queueA.statusModel.setIdleCeilingMinutes(15);
        queueA.statusModel.setOperational(true);
        queueA.retainLease();

        queueA.handleIdleTransitions();

        assertTrue(queueA.statusModel.isIdleTimeExceeded());
        assertTrue(queueA.suspendCalled, "Device suspend must be invoked when keep-open is false");
    }

    @Test
    void idleProfileHoldingOpenSlotHandsOverWhenSiblingBecomesOverdueLater() {
        String emulatorNumber = "test-emu-" + UUID.randomUUID();
        AccountDescriptor profileA = persistedProfile("Profile A " + UUID.randomUUID(), emulatorNumber, true, 100L);
        profileA.setConfig(ConfigurationKeyEnum.KEEP_EMULATOR_OPEN_BOOL, true);
        AccountDescriptor profileB = persistedProfile("Profile B " + UUID.randomUUID(), emulatorNumber, true, 200L);

        TestableTaskQueue queueA = new TestableTaskQueue(profileA);
        TestableTaskQueue queueB = new TestableTaskQueue(profileB);
        registerQueues(queueA, queueB);

        // Profile A is already in the idle-time-exceeded state, still holding lease
        queueA.enqueue(new TestDelayedTask(profileA, TpDailyTaskEnum.DAILY_MISSIONS, LocalDateTime.now().plusHours(2)));
        queueA.statusModel.setDelayUntil(LocalDateTime.now().plusHours(2));
        queueA.statusModel.setIdleCeilingMinutes(15);
        queueA.statusModel.setOperational(true);
        queueA.statusModel.setIdleTimeExceeded(true);
        queueA.retainLease();

        // Profile B now has an overdue task
        queueB.enqueue(new TestDelayedTask(profileB, TpDailyTaskEnum.INTEL, LocalDateTime.now().minusMinutes(2)));
        queueB.statusModel.setOperational(true);

        queueA.handleIdleTransitions();

        assertTrue(queueA.events.contains("release"), "Idle profile keeping device open must yield slot when sibling becomes overdue");
        assertFalse(queueB.isPaused());
        queueB.runSchedulerTick();
        assertEquals(List.of(TpDailyTaskEnum.INITIALIZE), queueB.executed,
                "The peer must run Initialize on the normal scheduler path before gameplay.");
    }

    @Test
    void peerPrioritizationSelectsHighestPriorityTaskAndAccount() {
        String emulatorNumber = "test-emu-" + UUID.randomUUID();
        AccountDescriptor profileA = persistedProfile("Profile A " + UUID.randomUUID(), emulatorNumber, true, 50L);
        AccountDescriptor profileB = persistedProfile("Profile B " + UUID.randomUUID(), emulatorNumber, true, 100L);
        AccountDescriptor profileC = persistedProfile("Profile C " + UUID.randomUUID(), emulatorNumber, true, 200L);

        TestableTaskQueue queueA = new TestableTaskQueue(profileA);
        TestableTaskQueue queueB = new TestableTaskQueue(profileB);
        TestableTaskQueue queueC = new TestableTaskQueue(profileC);
        registerQueues(queueA, queueB, queueC);

        queueA.statusModel.setOperational(true);
        queueB.statusModel.setOperational(true);
        queueC.statusModel.setOperational(true);

        // Both B and C have overdue tasks with default priority
        queueB.enqueue(new TestDelayedTask(profileB, TpDailyTaskEnum.GATHER_RESOURCES, LocalDateTime.now().minusMinutes(5)));
        queueC.enqueue(new TestDelayedTask(profileC, TpDailyTaskEnum.GATHER_RESOURCES, LocalDateTime.now().minusMinutes(5)));

        Optional<TaskQueue.PeerSwitchCandidate> candidate = queueA.findBestOverduePeerOnSameEmulator();

        assertTrue(candidate.isPresent());
        assertEquals(profileC.getId(), candidate.get().account().getId(),
                "Candidate with higher account priority (sortWeight 200 vs 100) must be selected");
    }

    @Test
    void disabledSiblingIsNeverTargetedForHandover() {
        String emulatorNumber = "test-emu-" + UUID.randomUUID();
        AccountDescriptor profileA = persistedProfile("Profile A " + UUID.randomUUID(), emulatorNumber, true, 100L);
        profileA.setConfig(ConfigurationKeyEnum.KEEP_EMULATOR_OPEN_BOOL, true);
        AccountDescriptor profileB = persistedProfile("Profile B " + UUID.randomUUID(), emulatorNumber, false, 200L);

        TestableTaskQueue queueA = new TestableTaskQueue(profileA);
        TestableTaskQueue queueB = new TestableTaskQueue(profileB);
        registerQueues(queueA, queueB);

        queueA.enqueue(new TestDelayedTask(profileA, TpDailyTaskEnum.DAILY_MISSIONS, LocalDateTime.now().plusHours(2)));
        queueA.statusModel.setDelayUntil(LocalDateTime.now().plusHours(2));
        queueA.statusModel.setIdleCeilingMinutes(15);
        queueA.statusModel.setOperational(true);
        queueA.retainLease();

        queueB.enqueue(new TestDelayedTask(profileB, TpDailyTaskEnum.GATHER_RESOURCES, LocalDateTime.now().minusMinutes(10)));
        queueB.statusModel.setOperational(true);

        queueA.handleIdleTransitions();

        assertTrue(queueA.statusModel.isIdleTimeExceeded());
        assertEquals(0, queueA.slotReleaseCount, "Disabled sibling must not trigger slot handover");
        assertFalse(queueA.suspendCalled);
    }

    @Test
    void siblingOnDifferentEmulatorIsNeverTargeted() {
        AccountDescriptor profileA = persistedProfile("Profile A " + UUID.randomUUID(), "emu-1-" + UUID.randomUUID(), true, 100L);
        profileA.setConfig(ConfigurationKeyEnum.KEEP_EMULATOR_OPEN_BOOL, true);
        AccountDescriptor profileB = persistedProfile("Profile B " + UUID.randomUUID(), "emu-2-" + UUID.randomUUID(), true, 200L);

        TestableTaskQueue queueA = new TestableTaskQueue(profileA);
        TestableTaskQueue queueB = new TestableTaskQueue(profileB);
        registerQueues(queueA, queueB);

        queueA.enqueue(new TestDelayedTask(profileA, TpDailyTaskEnum.DAILY_MISSIONS, LocalDateTime.now().plusHours(2)));
        queueA.statusModel.setDelayUntil(LocalDateTime.now().plusHours(2));
        queueA.statusModel.setIdleCeilingMinutes(15);
        queueA.statusModel.setOperational(true);
        queueA.retainLease();

        queueB.enqueue(new TestDelayedTask(profileB, TpDailyTaskEnum.GATHER_RESOURCES, LocalDateTime.now().minusMinutes(10)));
        queueB.statusModel.setOperational(true);

        queueA.handleIdleTransitions();

        assertTrue(queueA.statusModel.isIdleTimeExceeded());
        assertEquals(0, queueA.slotReleaseCount, "Sibling on different emulator must not trigger slot handover");
        assertFalse(queueA.suspendCalled);
    }


    @Test
    void handoverArmsInitializeWhilePeerWorkerWaitsForSlot() {
        HandoffFixture fixture = overdueHandoff();
        fixture.peer().beforeAcquire = fixture.owner()::handleIdleTransitions;

        fixture.peer().runSchedulerTick();

        assertEquals(1, fixture.owner().slotReleaseCount);
        assertEquals(List.of(TpDailyTaskEnum.INITIALIZE), fixture.peer().executed);
        fixture.peer().runSchedulerTick();
        assertEquals(List.of(TpDailyTaskEnum.INITIALIZE, TpDailyTaskEnum.GATHER_RESOURCES),
                fixture.peer().executed);
    }

    @Test
    void manuallyPausedPeerKeepsPauseAndDoesNotReceiveLease() {
        HandoffFixture fixture = overdueHandoff();
        fixture.peer().pause();

        fixture.owner().handleIdleTransitions();

        assertEquals(0, fixture.owner().slotReleaseCount);
        assertTrue(fixture.peer().statusModel.isUserPaused());
        assertTrue(fixture.peer().isPaused());
        assertFalse(fixture.peer().getNextQueuedTaskTypes(2).contains(TpDailyTaskEnum.INITIALIZE));
    }

    @Test
    void cooldownPeerKeepsItsRetryAcrossIdleChecks() {
        HandoffFixture fixture = overdueHandoff();
        LocalDateTime retryAt = LocalDateTime.now().plusMinutes(15);
        var initialize = new TestDelayedTask(fixture.peer().getProfile(), TpDailyTaskEnum.INITIALIZE, retryAt);
        fixture.peer().enqueue(initialize);
        TaskQueue.applyProfileCooldown(initialize, fixture.peer().statusModel, retryAt);

        fixture.owner().handleIdleTransitions();
        fixture.owner().handleIdleTransitions();

        assertEquals(0, fixture.owner().slotReleaseCount);
        assertTrue(fixture.peer().isPaused());
        assertEquals(retryAt, fixture.peer().statusModel.getDelayUntil());
        assertEquals(retryAt, initialize.getScheduled());
    }

    @Test
    void failedReleaseKeepsSourceLeaseAndAllowsHandoverToRetry() {
        HandoffFixture fixture = overdueHandoff();
        fixture.owner().failSlotRelease = true;

        fixture.owner().handleIdleTransitions();

        assertEquals(0, fixture.owner().slotReleaseCount);
        assertFalse(fixture.owner().statusModel.isIdleTimeExceeded());
        assertTrue(fixture.peer().executed.isEmpty());

        fixture.owner().failSlotRelease = false;
        fixture.owner().handleIdleTransitions();

        assertEquals(1, fixture.owner().slotReleaseCount);
        fixture.peer().runSchedulerTick();
        assertEquals(List.of(TpDailyTaskEnum.INITIALIZE), fixture.peer().executed);
    }

    private HandoffFixture overdueHandoff() {
        String emulator = "test-emu-" + UUID.randomUUID();
        var ownerProfile = persistedProfile("Owner " + UUID.randomUUID(), emulator, true, 100L);
        ownerProfile.setConfig(ConfigurationKeyEnum.KEEP_EMULATOR_OPEN_BOOL, true);
        var peerProfile = persistedProfile("Peer " + UUID.randomUUID(), emulator, true, 200L);
        var owner = new TestableTaskQueue(ownerProfile);
        var peer = new TestableTaskQueue(peerProfile);
        registerQueues(owner, peer);
        LocalDateTime nextOwnTask = LocalDateTime.now().plusHours(2);
        owner.enqueue(owner.task(TpDailyTaskEnum.DAILY_MISSIONS, nextOwnTask));
        owner.statusModel.setDelayUntil(nextOwnTask);
        owner.statusModel.setOperational(true);
        owner.retainLease();
        peer.statusModel.setOperational(true);
        peer.enqueue(peer.task(TpDailyTaskEnum.GATHER_RESOURCES, LocalDateTime.now().minusMinutes(5)));
        return new HandoffFixture(owner, peer);
    }

    private record HandoffFixture(TestableTaskQueue owner, TestableTaskQueue peer) {
    }

    private AccountDescriptor persistedProfile(String name, String emulator, boolean enabled, long priority) {
        AccountDescriptor profile = new AccountDescriptor(
                null, name, emulator, enabled, priority, 30L);
        assertTrue(ProfileService.obtain().createAccount(profile));
        createdAccountIds.add(profile.getId());
        return profile;
    }

    private void registerQueues(TestableTaskQueue... queues) {
        TaskDispatcher coordinator = ScheduleService.obtain().getCoordinator();
        for (TestableTaskQueue q : queues) {
            coordinator.registerQueue(q);
        }
    }

    private static final class TestableTaskQueue extends TaskQueue {

        private final List<String> events = new ArrayList<>();
        private final List<TpDailyTaskEnum> executed = new ArrayList<>();
        private Runnable beforeAcquire = () -> {};
        private boolean failSlotRelease;
        private boolean suspendCalled;
        private int slotReleaseCount;

        private TestableTaskQueue(AccountDescriptor profile) {
            super(profile);
        }

        private void retainLease() {
            markSlotAcquired();
        }

        @Override
        protected void acquireSlot() {
            beforeAcquire.run();
            events.add("acquire");
            markSlotAcquired();
        }

        @Override
        protected void releaseEmulatorSlotLease() {
            if (failSlotRelease) {
                throw new IllegalStateException("Synthetic lease release failure");
            }
            slotReleaseCount++;
            events.add("release");
        }

        @Override
        protected void suspendDevice(LocalDateTime until, boolean freeSlot) {
            suspendCalled = true;
            events.add("suspend");
            if (freeSlot) {
                releaseActiveSlotLease();
            }
        }

        @Override
        DelayedTask createIdleWakeInitializeTask() {
            return task(TpDailyTaskEnum.INITIALIZE, LocalDateTime.now());
        }

        TestDelayedTask task(TpDailyTaskEnum type, LocalDateTime scheduledAt) {
            return new TestDelayedTask(getProfile(), type, scheduledAt) {
                @Override
                public void run() {
                    executed.add(type);
                    setRecurring(false);
                }
            };
        }

        @Override
        protected void sleepSchedulerTick(long millis) {
        }

        @Override
        protected void sleepPausedTick() {
        }
    }

    private static class TestDelayedTask extends DelayedTask {

        private TestDelayedTask(AccountDescriptor profile, TpDailyTaskEnum type, LocalDateTime scheduledAt) {
            super(profile, type);
            reschedule(scheduledAt);
        }

        @Override
        protected void execute() {
        }
    }
}
