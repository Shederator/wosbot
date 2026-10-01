package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

class BearSessionCoordinatorTest {

    @Test
    void thirtyMinuteSessionConfirmsFiveOwnRalliesAndRelaunchesWithinTenSeconds() {
        ScriptedDriver driver = new ScriptedDriver();
        BearSessionCoordinator coordinator = coordinator(driver, Duration.ofMinutes(30), true, false);

        assertEquals(BearSessionCoordinator.ExitReason.EVENT_ENDED, coordinator.run());
        assertEquals(5, driver.ownRallyStarts.size());
        assertEquals(List.of(0L, 324L, 648L, 972L, 1296L), driver.ownRallyStarts.stream()
                .map(start -> Duration.between(Instant.EPOCH, start).toSeconds())
                .toList());
        assertTrue(driver.maxRelaunchDelaySeconds <= 10,
                "an idle own-rally slot must be reused within ten seconds");
    }

    @Test
    void ownRallyAndJoinFlagsRunTogetherAndOwnReturnPreemptsTheNextJoinPass() {
        ScriptedDriver driver = new ScriptedDriver();
        driver.freeSlots = 2;
        driver.joinResults.addAll(List.of(
                BearSessionCoordinator.JoinOutcome.JOINED,
                BearSessionCoordinator.JoinOutcome.JOINED));
        BearSessionCoordinator coordinator = new BearSessionCoordinator(
                driver, Instant.EPOCH.plus(Duration.ofMinutes(12)), true, 1, true, List.of(2, 3));

        assertEquals(BearSessionCoordinator.ExitReason.EVENT_ENDED, coordinator.run());
        assertEquals(List.of("own:1", "join:2", "join:3", "own:1"), driver.actions,
                "flag 1 must maintain the own rally while flags 2 and 3 fill ordinary slots");
        assertEquals(List.of(0L, 324L), driver.ownRallyStarts.stream()
                .map(start -> Duration.between(Instant.EPOCH, start).toSeconds())
                .toList());
        assertTrue(driver.states.contains(BearSessionCoordinator.State.OWN_RALLY_ACTIVE));
        assertTrue(driver.states.contains(BearSessionCoordinator.State.FILL_JOIN_SLOTS));
    }

    @Test
    void prospectiveOwnRallyMayEnterPreflightButMustRefuseAFullCyclePastEventEnd() {
        ScriptedDriver driver = new ScriptedDriver();
        driver.startResults.add(BearSessionCoordinator.OwnRallyStartResult.recoverable(
                BearSessionCoordinator.OwnRallyStartOutcome.TOO_LATE));
        BearSessionCoordinator coordinator = coordinator(
                driver, Duration.ofMinutes(5).plusSeconds(30), true, false);

        assertEquals(BearSessionCoordinator.ExitReason.EVENT_ENDED, coordinator.run());
        assertEquals(1, driver.startCalls,
                "the driver must read current travel before applying the dynamic cutoff");
        assertEquals(0, driver.ownRallyStarts.size());
    }

    @Test
    void finalFiveMinutesRemainJoinOnlyWhenAJoinableRallyExists() {
        ScriptedDriver driver = new ScriptedDriver();
        driver.startResults.add(BearSessionCoordinator.OwnRallyStartResult.recoverable(
                BearSessionCoordinator.OwnRallyStartOutcome.TOO_LATE));
        driver.freeSlots = 1;
        driver.joinResults.add(BearSessionCoordinator.JoinOutcome.JOINED);
        driver.joinResults.add(BearSessionCoordinator.JoinOutcome.NO_JOINABLE_RALLY);
        BearSessionCoordinator coordinator = new BearSessionCoordinator(
                driver,
                Instant.EPOCH.plus(Duration.ofMinutes(5)),
                true,
                1,
                true,
                List.of(2, 3, 4, 5, 6));

        assertEquals(BearSessionCoordinator.ExitReason.EVENT_ENDED, coordinator.run());
        assertEquals(1, driver.startCalls);
        assertEquals(1, driver.joined);
        assertEquals(List.of(2), driver.joinFlags);
    }

    @Test
    void finalFiveMinuteListMustBeEmptyThreeTimesBeforeJoiningStops() {
        ScriptedDriver driver = new ScriptedDriver();
        driver.freeSlots = 1;
        driver.joinResults.addAll(List.of(
                BearSessionCoordinator.JoinOutcome.NO_JOINABLE_RALLY,
                BearSessionCoordinator.JoinOutcome.NO_JOINABLE_RALLY,
                BearSessionCoordinator.JoinOutcome.NO_JOINABLE_RALLY));
        BearSessionCoordinator coordinator = new BearSessionCoordinator(
                driver,
                Instant.EPOCH.plus(Duration.ofMinutes(5)),
                true,
                1,
                true,
                List.of(2, 3, 4, 5, 6));

        assertEquals(BearSessionCoordinator.ExitReason.EVENT_ENDED, coordinator.run());
        assertEquals(List.of(2, 2, 2), driver.joinFlags,
                "one transient empty frame must not end the final join window");
        assertTrue(driver.pauseDurations.getLast().compareTo(Duration.ofMinutes(4)) > 0,
                "only three complete empty scans may drain the final join window");
    }

    @Test
    void pageNotReadyNeverCountsAsAnEmptyRallyList() {
        ScriptedDriver driver = new ScriptedDriver();
        driver.freeSlots = 1;
        driver.joinResults.addAll(List.of(
                BearSessionCoordinator.JoinOutcome.PAGE_NOT_READY,
                BearSessionCoordinator.JoinOutcome.NO_JOINABLE_RALLY,
                BearSessionCoordinator.JoinOutcome.NO_JOINABLE_RALLY,
                BearSessionCoordinator.JoinOutcome.NO_JOINABLE_RALLY));
        BearSessionCoordinator coordinator = new BearSessionCoordinator(
                driver,
                Instant.EPOCH.plus(Duration.ofMinutes(5)),
                false,
                1,
                true,
                List.of(2));

        assertEquals(BearSessionCoordinator.ExitReason.EVENT_ENDED, coordinator.run());
        assertEquals(List.of(2, 2, 2, 2), driver.joinFlags);
        assertTrue(driver.recoveries >= 1);
    }

    @Test
    void listObservationAndScrollDoNotConsumeRallyAttemptBudget() {
        ScriptedDriver driver = new ScriptedDriver();
        driver.freeSlots = 1;
        for (int i = 0; i < 20; i++) {
            driver.joinResults.add(i % 2 == 0
                    ? BearSessionCoordinator.JoinOutcome.LIST_OBSERVING
                    : BearSessionCoordinator.JoinOutcome.LIST_ADVANCED);
        }
        driver.joinResults.add(BearSessionCoordinator.JoinOutcome.JOINED);
        driver.joinResults.add(BearSessionCoordinator.JoinOutcome.NO_JOINABLE_RALLY);

        BearSessionCoordinator coordinator = new BearSessionCoordinator(
                driver, Instant.EPOCH.plusSeconds(30), false, 1, true, List.of(2));

        assertEquals(BearSessionCoordinator.ExitReason.EVENT_ENDED, coordinator.run());
        assertEquals(1, driver.joined);
    }

    @Test
    void allConfiguredFormationsOccupiedReturnsToDeadlineWaitInsteadOfHammeringRow() {
        ScriptedDriver driver = new ScriptedDriver();
        driver.freeSlots = 1;
        for (int i = 0; i < 10; i++) {
            driver.joinResults.add(BearSessionCoordinator.JoinOutcome.NO_FORMATION_AVAILABLE);
        }

        BearSessionCoordinator coordinator = new BearSessionCoordinator(
                driver, Instant.EPOCH.plusSeconds(6), false, 1, true, List.of(2, 3));

        assertEquals(BearSessionCoordinator.ExitReason.EVENT_ENDED, coordinator.run());
        assertTrue(driver.pauses > 0);
        assertTrue(driver.pauseDurations.stream().anyMatch(
                duration -> duration.compareTo(Duration.ofSeconds(1)) >= 0));
    }

    @Test
    void doesNotDuplicateAnUnclassifiedExistingRally() {
        ScriptedDriver driver = new ScriptedDriver();
        driver.existingOwnRallyUntil = Instant.EPOCH.plusSeconds(100);

        BearSessionCoordinator coordinator = coordinator(driver, Duration.ofSeconds(90), true, false);

        assertEquals(BearSessionCoordinator.ExitReason.EVENT_ENDED, coordinator.run());
        assertEquals(0, driver.startCalls);
        assertTrue(driver.states.contains(BearSessionCoordinator.State.WAIT_FOR_NEXT_USEFUL_DEADLINE));
    }

    @Test
    void adoptsPersistedSpecialRallyDeadlineAndRecreatesAtItsExactReturn() {
        ScriptedDriver driver = new ScriptedDriver();
        driver.existingOwnRallyUntil = Instant.EPOCH.plusSeconds(40);
        driver.classifiedExisting = true;

        BearSessionCoordinator coordinator = coordinator(driver, Duration.ofMinutes(7), true, false);

        assertEquals(BearSessionCoordinator.ExitReason.EVENT_ENDED, coordinator.run());
        assertTrue(driver.ownRallyStarts.stream()
                .anyMatch(start -> !start.isBefore(Instant.EPOCH.plusSeconds(40))));
    }

    @Test
    void recoverableFailuresDoNotEndTheActiveEvent() {
        ScriptedDriver driver = new ScriptedDriver();
        driver.startResults.add(BearSessionCoordinator.OwnRallyStartResult.recoverable(
                BearSessionCoordinator.OwnRallyStartOutcome.NAVIGATION_FAILURE));
        driver.startResults.add(BearSessionCoordinator.OwnRallyStartResult.recoverable(
                BearSessionCoordinator.OwnRallyStartOutcome.STALE_SCREEN));
        driver.startResults.add(BearSessionCoordinator.OwnRallyStartResult.confirmed(
                1, driver.now(), Duration.ofMinutes(5), Duration.ofSeconds(12)));

        BearSessionCoordinator coordinator = coordinator(driver, Duration.ofMinutes(6), true, false);

        assertEquals(BearSessionCoordinator.ExitReason.EVENT_ENDED, coordinator.run());
        assertEquals(3, driver.startCalls);
        assertEquals(2, driver.recoveries);
        assertEquals(1, driver.ownRallyStarts.size());
    }

    @Test
    void fillsEveryFreeJoinSlotInOnePassAndAdvancesPastBadCandidates() {
        ScriptedDriver driver = new ScriptedDriver();
        driver.freeSlots = 3;
        driver.joinResults.addAll(List.of(
                BearSessionCoordinator.JoinOutcome.FORMATION_UNAVAILABLE,
                BearSessionCoordinator.JoinOutcome.RALLY_FULL,
                BearSessionCoordinator.JoinOutcome.JOINED,
                BearSessionCoordinator.JoinOutcome.RALLY_DEPARTED,
                BearSessionCoordinator.JoinOutcome.JOINED,
                BearSessionCoordinator.JoinOutcome.JOINED,
                BearSessionCoordinator.JoinOutcome.NO_JOINABLE_RALLY));

        BearSessionCoordinator coordinator = new BearSessionCoordinator(
                driver, Instant.EPOCH.plusSeconds(1), false, 7, true, List.of(1, 2, 3));

        coordinator.run();

        assertEquals(3, driver.joined);
        assertTrue(driver.joinFormationLists.stream().allMatch(List.of(1, 2, 3)::equals),
                "every row must inspect configured formations from the beginning");
    }

    @Test
    void staleJoinScreenRecoversAndContinuesWithinTheSameEvent() {
        ScriptedDriver driver = new ScriptedDriver();
        driver.freeSlots = 1;
        driver.joinResults.addAll(List.of(
                BearSessionCoordinator.JoinOutcome.OCR_MISS,
                BearSessionCoordinator.JoinOutcome.STALE_SCREEN,
                BearSessionCoordinator.JoinOutcome.NAVIGATION_FAILURE,
                BearSessionCoordinator.JoinOutcome.MARCH_QUEUE_FULL,
                BearSessionCoordinator.JoinOutcome.RALLY_GONE,
                BearSessionCoordinator.JoinOutcome.ALREADY_JOINED_OR_MARCHING,
                BearSessionCoordinator.JoinOutcome.JOINED,
                BearSessionCoordinator.JoinOutcome.NO_JOINABLE_RALLY));

        BearSessionCoordinator coordinator = new BearSessionCoordinator(
                driver, Instant.EPOCH.plusSeconds(5), false, 7, true, List.of(1));

        assertEquals(BearSessionCoordinator.ExitReason.EVENT_ENDED, coordinator.run());
        assertEquals(1, driver.joined);
        assertEquals(3, driver.recoveries);
    }

    @Test
    void failedScreenRecoveryBacksOffInsteadOfHammeringTheUi() {
        ScriptedDriver driver = new ScriptedDriver();
        driver.freeSlots = 1;
        driver.recoverySucceeds = false;
        driver.joinResults.add(BearSessionCoordinator.JoinOutcome.PAGE_NOT_READY);
        driver.cancelAfterPauses = 1;
        BearSessionCoordinator coordinator = new BearSessionCoordinator(
                driver, Instant.EPOCH.plus(Duration.ofMinutes(6)), false, 1, true, List.of(2));

        coordinator.run();

        assertEquals(1, driver.recoveries);
        assertTrue(driver.pauseDurations.contains(Duration.ofSeconds(2)),
                "a failed recovery must pause before taking another UI action");
    }

    @Test
    void cancellationStopsWithoutRunningNormalTaskCleanup() {
        ScriptedDriver driver = new ScriptedDriver();
        driver.cancelAfterPauses = 1;
        BearSessionCoordinator coordinator = coordinator(driver, Duration.ofMinutes(30), false, false);

        assertEquals(BearSessionCoordinator.ExitReason.CANCELLED, coordinator.run());
        assertEquals(0, driver.normalTaskCleanupCalls);
        assertTrue(!BearSessionCoordinator.shouldResumeNormalTasks(BearSessionCoordinator.ExitReason.CANCELLED));
        assertTrue(BearSessionCoordinator.shouldResumeNormalTasks(BearSessionCoordinator.ExitReason.EVENT_ENDED));
        assertTrue(!BearSessionCoordinator.shouldResumeNormalTasks(null));
        assertTrue(!BearSessionCoordinator.shouldResumeNormalTasks(
                BearSessionCoordinator.ExitReason.UNRECOVERABLE_FAILURE));
        assertTrue(BearSessionCoordinator.shouldRetryDuringActiveWindow(null));
        assertTrue(BearSessionCoordinator.shouldRetryDuringActiveWindow(
                BearSessionCoordinator.ExitReason.UNRECOVERABLE_FAILURE));
        assertTrue(!BearSessionCoordinator.shouldRetryDuringActiveWindow(
                BearSessionCoordinator.ExitReason.CANCELLED));
    }

    @Test
    void retriesAnUnverifiedMarchScreenBeforeUsingTheVerifiedJoinSlot() {
        ScriptedDriver driver = new ScriptedDriver();
        driver.unreliableReads = 1;
        driver.freeSlots = 1;
        driver.cancelAfterPauses = 2;
        driver.joinResults.add(BearSessionCoordinator.JoinOutcome.JOINED);
        BearSessionCoordinator coordinator = new BearSessionCoordinator(
                driver, Instant.EPOCH.plus(Duration.ofMinutes(6)), true, 7, true, List.of(1));

        coordinator.run();

        assertEquals(1, driver.recoveries);
        assertEquals(1, driver.startCalls);
        assertEquals(1, driver.joined,
                "the Bear Special rally must not consume an ordinary verified join slot");
    }

    @Test
    void ownSpecialRallyLeavesEveryOrdinaryJoinSlotAvailable() {
        ScriptedDriver driver = new ScriptedDriver();
        driver.freeSlots = 2;
        driver.joinResults.addAll(List.of(
                BearSessionCoordinator.JoinOutcome.JOINED,
                BearSessionCoordinator.JoinOutcome.JOINED));
        driver.cancelAfterPauses = 1;
        BearSessionCoordinator coordinator = new BearSessionCoordinator(
                driver, Instant.EPOCH.plus(Duration.ofMinutes(6)), true, 7, true, List.of(1));

        coordinator.run();

        assertEquals(1, driver.ownRallyStarts.size());
        assertEquals(2, driver.joined);
        assertEquals(List.of(7), driver.ownRallyFlags);
    }

    @Test
    void recoverableOwnRallyFailureFallsBackToJoiningBeforeRetrying() {
        ScriptedDriver driver = new ScriptedDriver();
        driver.freeSlots = 2;
        driver.startResults.add(BearSessionCoordinator.OwnRallyStartResult.recoverable(
                BearSessionCoordinator.OwnRallyStartOutcome.NAVIGATION_FAILURE));
        driver.startResults.add(BearSessionCoordinator.OwnRallyStartResult.confirmed(
                1, driver.now(), Duration.ofMinutes(5), Duration.ofSeconds(12)));
        driver.joinResults.add(BearSessionCoordinator.JoinOutcome.JOINED);
        driver.cancelAfterPauses = 2;

        BearSessionCoordinator coordinator = new BearSessionCoordinator(
                driver, Instant.EPOCH.plus(Duration.ofMinutes(6)), true, 4, true, List.of(2, 3));

        coordinator.run();

        assertEquals(1, driver.joined);
        assertEquals(List.of(4), driver.ownRallyFlags);
        assertEquals(List.of("own:4", "join:2", "join:3"), driver.actions,
                "one bounded own-rally recovery must fall back to joining for this pass");
    }

    @Test
    void repeatedOwnRallyFailuresCannotStarveJoinRewards() {
        ScriptedDriver driver = new ScriptedDriver();
        driver.freeSlots = 2;
        driver.startResults.addAll(List.of(
                BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.RALLY_TIMER_NOT_CONFIRMED),
                BearSessionCoordinator.OwnRallyStartResult.recoverable(
                        BearSessionCoordinator.OwnRallyStartOutcome.DEPLOY_NOT_CONFIRMED)));
        driver.joinResults.add(BearSessionCoordinator.JoinOutcome.JOINED);
        driver.cancelAfterPauses = 4;

        BearSessionCoordinator coordinator = new BearSessionCoordinator(
                driver, Instant.EPOCH.plus(Duration.ofMinutes(6)), true, 4, true, List.of(2));

        coordinator.run();

        assertEquals(List.of("own:4", "join:2", "join:2", "own:4", "join:2"), driver.actions,
                "each failed own-rally pass must yield to configured joining before retrying");
        assertEquals(2, driver.marchReads,
                "each own-rally retry must begin from a fresh march snapshot");
    }

    @Test
    void recoverableOwnNavigationFailureDoesNotReopenTheMarchSidebar() {
        ScriptedDriver driver = new ScriptedDriver();
        driver.freeSlots = 2;
        driver.startResults.add(BearSessionCoordinator.OwnRallyStartResult.recoverable(
                BearSessionCoordinator.OwnRallyStartOutcome.NAVIGATION_FAILURE));
        driver.startResults.add(BearSessionCoordinator.OwnRallyStartResult.confirmed(
                1, driver.now(), Duration.ofMinutes(5), Duration.ofSeconds(12)));
        driver.joinResults.add(BearSessionCoordinator.JoinOutcome.JOINED);
        driver.cancelAfterPauses = 1;

        BearSessionCoordinator coordinator = new BearSessionCoordinator(
                driver, Instant.EPOCH.plus(Duration.ofMinutes(6)), true, 4, true, List.of(2));

        coordinator.run();

        assertEquals(1, driver.marchReads,
                "navigation recovery must reuse the reliable session snapshot instead of reopening marches");
    }

    @Test
    void freeJoinSlotKeepsReturningOwnRallyOnFastPoll() {
        ScriptedDriver driver = new ScriptedDriver();
        driver.freeSlots = 2;
        driver.joinResults.add(BearSessionCoordinator.JoinOutcome.JOINED);
        driver.cancelAfterPauses = 3;

        BearSessionCoordinator coordinator = new BearSessionCoordinator(
                driver, Instant.EPOCH.plus(Duration.ofMinutes(6)), true, 4, true, List.of(2));

        coordinator.run();

        assertTrue(driver.pauseDurations.stream().allMatch(duration -> duration.compareTo(Duration.ofSeconds(1)) <= 0));
    }

    @Test
    void configuredJoinFormationOrderIsPreservedAndDeduplicated() {
        assertEquals(List.of(4, 2, 11), BearTrapRoutine.decodeJoinFlags("4,2,4,11,bad,13"));
    }

    @Test
    void exactOwnReturnDeadlineStopsNewJoinsButLetsCommittedJoinFinish() {
        ScriptedDriver driver = new ScriptedDriver();
        driver.freeSlots = 2;
        driver.defaultTravel = Duration.ofSeconds(11);
        driver.joinDuration = Duration.ofSeconds(323);
        driver.joinResults.add(BearSessionCoordinator.JoinOutcome.JOINED);
        driver.cancelAfterPauses = 1;

        BearSessionCoordinator coordinator = new BearSessionCoordinator(
                driver, Instant.EPOCH.plus(Duration.ofMinutes(12)), true, 1, true, List.of(2, 3));

        coordinator.run();

        assertEquals(List.of("own:1", "join:2", "own:1"), driver.actions.subList(0, 3));
        assertEquals(Instant.EPOCH.plusSeconds(322), driver.ownDeadlinesSeen.getFirst());
        assertEquals(Instant.EPOCH.plusSeconds(323), driver.ownRallyStarts.get(1),
                "the committed join finishes, then own rally recreation preempts another plus tap");
    }

    @Test
    void selectsTopmostVerifiedBearRallyOnly() {
        assertEquals(100, BearSessionCoordinator.selectJoinCandidateRow(
                List.of(300, 100, 200), List.of(105, 305), 20).orElseThrow());
        assertEquals(300, BearSessionCoordinator.selectJoinCandidateRow(
                List.of(100, 300, 200), List.of(302), 20).orElseThrow());
        assertTrue(BearSessionCoordinator.selectJoinCandidateRow(
                List.of(100, 300), List.of(200), 20).isEmpty());
    }

    private static BearSessionCoordinator coordinator(
            ScriptedDriver driver, Duration duration, boolean ownRallies, boolean joins) {
        return new BearSessionCoordinator(
                driver, Instant.EPOCH.plus(duration), ownRallies, 7, joins, List.of(1, 2, 3));
    }

    private static final class ScriptedDriver implements BearSessionCoordinator.Driver {
        private Instant now = Instant.EPOCH;
        private Instant busyUntil;
        private Instant existingOwnRallyUntil;
        private int trackedSlot = 1;
        private int freeSlots;
        private int startCalls;
        private int recoveries;
        private int joined;
        private int pauses;
        private int cancelAfterPauses = Integer.MAX_VALUE;
        private int normalTaskCleanupCalls;
        private int unreliableReads;
        private int marchReads;
        private Duration defaultTravel = Duration.ofSeconds(12);
        private Duration joinDuration = Duration.ZERO;
        private boolean recoverySucceeds = true;
        private boolean classifiedExisting;
        private long maxRelaunchDelaySeconds;
        private Instant lastIdleAt;
        private final Deque<BearSessionCoordinator.OwnRallyStartResult> startResults = new ArrayDeque<>();
        private final Deque<BearSessionCoordinator.JoinOutcome> joinResults = new ArrayDeque<>();
        private final List<Instant> ownRallyStarts = new ArrayList<>();
        private final List<Integer> ownRallyFlags = new ArrayList<>();
        private final List<Integer> joinFlags = new ArrayList<>();
        private final List<List<Integer>> joinFormationLists = new ArrayList<>();
        private final List<Instant> ownDeadlinesSeen = new ArrayList<>();
        private final List<String> actions = new ArrayList<>();
        private final List<Duration> pauseDurations = new ArrayList<>();
        private final List<BearSessionCoordinator.State> states = new ArrayList<>();

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public boolean cancellationRequested() {
            return pauses >= cancelAfterPauses;
        }

        @Override
        public BearSessionCoordinator.EventStatus eventStatus() {
            return BearSessionCoordinator.EventStatus.ACTIVE;
        }

        @Override
        public BearSessionCoordinator.MarchSnapshot readMarches(OptionalInt trackedOwnSlot, boolean mayAdoptExisting) {
            marchReads++;
            if (unreliableReads-- > 0) {
                return new BearSessionCoordinator.MarchSnapshot(
                        false, 0, BearSessionCoordinator.OwnRallyObservation.absent());
            }
            Instant activeUntil = busyUntil != null ? busyUntil : existingOwnRallyUntil;
            if (activeUntil != null && now.isBefore(activeUntil)) {
                if (busyUntil == null && mayAdoptExisting && !classifiedExisting) {
                    return new BearSessionCoordinator.MarchSnapshot(true, freeSlots,
                            BearSessionCoordinator.OwnRallyObservation.unclassifiedActive(trackedSlot));
                }
                return new BearSessionCoordinator.MarchSnapshot(true, freeSlots,
                        BearSessionCoordinator.OwnRallyObservation.active(
                                trackedSlot, BearSessionCoordinator.OwnRallyPhase.RETURNING,
                                Duration.between(now, activeUntil)));
            }
            if (activeUntil != null) {
                lastIdleAt = activeUntil;
                busyUntil = null;
                existingOwnRallyUntil = null;
                return new BearSessionCoordinator.MarchSnapshot(true, freeSlots,
                        BearSessionCoordinator.OwnRallyObservation.idle(trackedSlot));
            }
            return new BearSessionCoordinator.MarchSnapshot(true, freeSlots,
                    BearSessionCoordinator.OwnRallyObservation.absent());
        }

        @Override
        public BearSessionCoordinator.OwnRallyStartResult startOwnRally(int formation) {
            startCalls++;
            ownRallyFlags.add(formation);
            actions.add("own:" + formation);
            BearSessionCoordinator.OwnRallyStartResult result = startResults.isEmpty()
                    ? BearSessionCoordinator.OwnRallyStartResult.confirmed(
                            trackedSlot, now, Duration.ofMinutes(5), defaultTravel)
                    : startResults.removeFirst();
            if (result.outcome() == BearSessionCoordinator.OwnRallyStartOutcome.CONFIRMED) {
                if (lastIdleAt != null) {
                    maxRelaunchDelaySeconds = Math.max(maxRelaunchDelaySeconds,
                            Duration.between(lastIdleAt, now).toSeconds());
                }
                ownRallyStarts.add(now);
                busyUntil = now.plus(result.rallyCountdown()).plus(result.oneWayTravel().multipliedBy(2));
            }
            return result;
        }

        @Override
        public BearSessionCoordinator.JoinOutcome joinNext(
                List<Integer> formations,
                Instant ownReturnDeadline) {
            joinFormationLists.add(List.copyOf(formations));
            ownDeadlinesSeen.add(ownReturnDeadline);
            int formation = formations.get(Math.min(joined, formations.size() - 1));
            joinFlags.add(formation);
            actions.add("join:" + formation);
            BearSessionCoordinator.JoinOutcome outcome = joinResults.isEmpty()
                    ? BearSessionCoordinator.JoinOutcome.NO_JOINABLE_RALLY
                    : joinResults.removeFirst();
            if (outcome == BearSessionCoordinator.JoinOutcome.JOINED) {
                joined++;
                freeSlots--;
            }
            now = now.plus(joinDuration);
            return outcome;
        }

        @Override
        public boolean recover(BearSessionCoordinator.State resumeState) {
            recoveries++;
            return recoverySucceeds;
        }

        @Override
        public void pause(Duration duration) {
            pauses++;
            pauseDurations.add(duration);
            now = now.plus(duration.isZero() || duration.isNegative() ? Duration.ofSeconds(1) : duration);
        }

        @Override
        public void stateChanged(BearSessionCoordinator.State state) {
            states.add(state);
        }
    }
}
