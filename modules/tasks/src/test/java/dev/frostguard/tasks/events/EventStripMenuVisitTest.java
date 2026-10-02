package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.data.access.DataStore;
import dev.frostguard.engine.helper.NavigationHelper.EventMenuOpenResult;
import dev.frostguard.tasks.alliance.AllianceChampionshipRoutine;
import dev.frostguard.tasks.alliance.AllianceMobilizationRoutine;
import dev.frostguard.tasks.exploration.TundraTruckEventRoutine;
import dev.frostguard.vision.convert.GameTimeUtils;

class EventStripMenuVisitTest {
    private static DataStore stubStore;

    @BeforeAll
    static void createTestWorkspace() throws IOException {
        Files.createDirectories(WorkspacePaths.current().root());
        stubStore = DataStore.openIsolated(Map.of(
                "jakarta.persistence.jdbc.url", "jdbc:sqlite::memory:",
                "hibernate.hbm2ddl.auto", "create-drop"));
    }

    @AfterAll
    static void closeUnusedStore() {
        stubStore.close();
    }

    @Test
    void heroMissionRetriesMissingTabOnThreeVisitsThenWaitsForTheDailyReset() {
        HeroProbe probe = new HeroProbe();
        for (int attempt = 1; attempt <= 3; attempt++) {
            probe.respond(EventMenuOpenResult.TAB_ABSENT);
            if (attempt < 3) {
                assertTrue(probe.scheduledAt().isAfter(LocalDateTime.now().plusMinutes(4)));
                assertTrue(probe.scheduledAt().isBefore(LocalDateTime.now().plusMinutes(6)));
            } else {
                assertEquals(probe.rest(), probe.scheduledAt());
            }
            assertTrue(probe.warning().contains("navigation attempt " + attempt + "/3"));
            assertEquals(attempt == 3, probe.warning().contains("snapshot=test"));
        }
        assertEquals(probe.rest(), probe.scheduledAt());
        assertTrue(probe.warning().contains("navigation attempt 3/3"));
    }

    @Test
    void heroMissionProgressFailuresUseTheirOwnThreeVisitBudget() {
        HeroProbe probe = new HeroProbe();
        for (int attempt = 1; attempt <= 3; attempt++) {
            probe.recordProgressFailure();
            if (attempt < 3) {
                assertTrue(probe.scheduledAt().isAfter(LocalDateTime.now().plusMinutes(4)));
                assertTrue(probe.scheduledAt().isBefore(LocalDateTime.now().plusMinutes(6)));
            } else {
                assertEquals(probe.rest(), probe.scheduledAt());
            }
            assertTrue(probe.warning().contains("progress attempt " + attempt + "/3"));
            assertEquals(attempt == 3, probe.warning().contains("snapshot=test"));
        }
        assertEquals(probe.rest(), probe.scheduledAt());
    }

    @Test
    void manualLaunchAfterThirdNavigationFailureDefersBeforeOpeningEventMenu() {
        HeroProbe probe = new HeroProbe();
        probe.exhaustNavigationBudget();

        probe.launchOnce();

        assertEquals(0, probe.menuOpenCount);
        assertEquals(probe.rest(), probe.scheduledAt());
        assertTrue(probe.warning().contains("visit budget exhausted"));
    }

    @Test
    void completedHeroMissionClaimsFinalRewardBeforeSchedulingResetWithoutRallying() {
        HeroProbe probe = new HeroProbe();
        probe.progress = HeroMissionProgressBar.State.COMPLETE;

        probe.handleOnce();

        assertEquals(List.of("claim", "schedule"), probe.actionOrder);
        assertEquals(probe.rest(), probe.scheduledAt());
        assertFalse(probe.actionOrder.contains("rally"));
    }

    @Test
    void inProgressHeroMissionClaimsRewardsThenRallies() {
        HeroProbe probe = new HeroProbe();
        probe.progress = HeroMissionProgressBar.State.IN_PROGRESS;

        probe.handleOnce();

        assertEquals(List.of("claim", "rally"), probe.actionOrder);
    }

    @Test
    void tundraTruckRetriesAMissingTabOnceThenUsesItsRestTime() {
        assertOneExtraVisitThenReset(new TundraProbe());
    }

    @Test
    void allianceChampionshipRetriesAMissingTabOnceThenWaitsForTheDailyReset() {
        assertOneExtraVisitThenReset(new ChampionshipProbe());
    }

    @Test
    void allianceMobilizationRetriesAMissingTabOnceThenWaitsForTheDailyReset() {
        assertOneExtraVisitThenReset(new MobilizationProbe());
    }

    private static void assertOneExtraVisitThenReset(Probe probe) {
        probe.respond(EventMenuOpenResult.TAB_ABSENT);
        assertTrue(probe.scheduledAt().isAfter(LocalDateTime.now().plusMinutes(4)));
        assertTrue(probe.scheduledAt().isBefore(LocalDateTime.now().plusMinutes(6)));
        assertTrue(probe.warning().contains("One more menu visit"));
        assertTrue(probe.warning().contains("snapshot=test"));

        probe.respond(EventMenuOpenResult.TAB_ABSENT);
        assertEquals(probe.rest(), probe.scheduledAt());
        assertTrue(probe.warning().contains("not completed"));
    }

    private interface Probe {
        void respond(EventMenuOpenResult opened);

        LocalDateTime scheduledAt();

        LocalDateTime rest();

        String warning();
    }

    private static final class HeroProbe extends HeroMissionEventRoutine implements Probe {
        private LocalDateTime scheduledAt;
        private String warning;
        private int menuOpenCount;
        private HeroMissionProgressBar.State progress = HeroMissionProgressBar.State.IN_PROGRESS;
        private final List<String> actionOrder = new ArrayList<>();
        private final HeroMissionVisitBudget budget = new HeroMissionVisitBudget(
                new MemoryStreakRepository(), 1L, java.time.Clock.systemDefaultZone());

        private HeroProbe() {
            super(account(), TpDailyTaskEnum.EVENT_HERO_MISSION);
        }

        @Override
        public void respond(EventMenuOpenResult opened) {
            respondToMenu(opened);
        }

        private void recordProgressFailure() {
            respondToProgressFailure();
        }

        private void exhaustNavigationBudget() {
            for (int attempt = 0; attempt < HeroMissionVisitBudget.MAX_VISITS; attempt++) {
                budget.recordFailure(HeroMissionVisitBudget.FailureKind.NAVIGATION);
            }
        }

        private void launchOnce() {
            execute();
        }

        private void handleOnce() {
            handleHeroMissionEvent();
        }

        @Override
        HeroMissionProgressBar.State readProgressBar() {
            return progress;
        }

        @Override
        void claimAllRewards() {
            actionOrder.add("claim");
        }

        @Override
        boolean rallyReaper() {
            actionOrder.add("rally");
            return true;
        }

        @Override
        EventMenuOpenResult openHeroMenu() {
            menuOpenCount++;
            return EventMenuOpenResult.REACHED;
        }

        @Override
        HeroMissionVisitBudget visitBudget() {
            return budget;
        }

        @Override
        public LocalDateTime scheduledAt() {
            return scheduledAt;
        }

        @Override
        public LocalDateTime rest() {
            return GameTimeUtils.dailyResetTime();
        }

        @Override
        public String warning() {
            return warning;
        }

        @Override
        String diagnosticSnapshot(String control) {
            return "snapshot=test";
        }

        @Override
        public void reschedule(LocalDateTime rescheduledTime) {
            actionOrder.add("schedule");
            scheduledAt = rescheduledTime;
        }

        @Override
        public void logWarning(String message) {
            warning = message;
        }
    }

    private static final class TundraProbe extends TundraTruckEventRoutine implements Probe {
        private LocalDateTime scheduledAt;
        private String warning;

        private TundraProbe() {
            super(account(), TpDailyTaskEnum.EVENT_TUNDRA_TRUCK);
        }

        @Override
        public void respond(EventMenuOpenResult opened) {
            respondToMenu(opened);
        }

        @Override
        public LocalDateTime scheduledAt() {
            return scheduledAt;
        }

        @Override
        public LocalDateTime rest() {
            return activationRest();
        }

        @Override
        public String warning() {
            return warning;
        }

        @Override
        protected String diagnosticSnapshot(String type) {
            return "snapshot=test";
        }

        @Override
        public void reschedule(LocalDateTime rescheduledTime) {
            scheduledAt = rescheduledTime;
        }

        @Override
        public void logWarning(String message) {
            warning = message;
        }
    }

    private static final class ChampionshipProbe extends AllianceChampionshipRoutine implements Probe {
        private LocalDateTime scheduledAt;
        private String warning;

        private ChampionshipProbe() {
            super(account(), TpDailyTaskEnum.ALLIANCE_CHAMPIONSHIP);
        }

        @Override
        public void respond(EventMenuOpenResult opened) {
            respondToMenu(opened);
        }

        @Override
        public LocalDateTime scheduledAt() {
            return scheduledAt;
        }

        @Override
        public LocalDateTime rest() {
            return GameTimeUtils.dailyResetTime();
        }

        @Override
        public String warning() {
            return warning;
        }

        @Override
        protected String diagnosticSnapshot(String type) {
            return "snapshot=test";
        }

        @Override
        public void reschedule(LocalDateTime rescheduledTime) {
            scheduledAt = rescheduledTime;
        }

        @Override
        public void logWarning(String message) {
            warning = message;
        }
    }

    private static final class MobilizationProbe extends AllianceMobilizationRoutine implements Probe {
        private LocalDateTime scheduledAt;
        private String warning;

        private MobilizationProbe() {
            super(account(), TpDailyTaskEnum.ALLIANCE_MOBILIZATION);
        }

        @Override
        public void respond(EventMenuOpenResult opened) {
            respondToMenu(opened);
        }

        @Override
        public LocalDateTime scheduledAt() {
            return scheduledAt;
        }

        @Override
        public LocalDateTime rest() {
            return GameTimeUtils.dailyResetTime();
        }

        @Override
        public String warning() {
            return warning;
        }

        @Override
        protected String diagnosticSnapshot(String type) {
            return "snapshot=test";
        }

        @Override
        public void reschedule(LocalDateTime rescheduledTime) {
            scheduledAt = rescheduledTime;
        }

        @Override
        public void logWarning(String message) {
            warning = message;
        }
    }

    private static AccountDescriptor account() {
        return new AccountDescriptor(1L, "Test", "1", true, 1L, 30L);
    }

    private static final class MemoryStreakRepository extends dev.frostguard.data.repository.TaskFailureStreakRepository {
        private final java.util.Map<String, dev.frostguard.api.domain.TaskFailureStreakData> streaks =
                new java.util.HashMap<>();

        private MemoryStreakRepository() {
            super(stubStore);
        }

        @Override
        public dev.frostguard.api.domain.TaskFailureStreakData recordFailureSince(
                long profileId, String taskKey, String signature, LocalDateTime failedAt,
                LocalDateTime resetBoundary) {
            var previous = streaks.get(taskKey);
            int count = previous != null && !previous.lastFailureAt().isBefore(resetBoundary)
                    ? previous.consecutiveFailures() + 1 : 1;
            var next = new dev.frostguard.api.domain.TaskFailureStreakData(
                    profileId, taskKey, signature, count,
                    previous == null || count == 1 ? failedAt : previous.firstFailureAt(), failedAt);
            streaks.put(taskKey, next);
            return next;
        }

        @Override
        public java.util.Optional<dev.frostguard.api.domain.TaskFailureStreakData> find(long profileId, String taskKey) {
            return java.util.Optional.ofNullable(streaks.get(taskKey));
        }

        @Override
        public boolean clear(long profileId, String taskKey) {
            return streaks.remove(taskKey) != null;
        }
    }
}
