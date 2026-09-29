package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.helper.NavigationHelper.EventMenuOpenResult;
import dev.frostguard.tasks.alliance.AllianceChampionshipRoutine;
import dev.frostguard.tasks.alliance.AllianceMobilizationRoutine;
import dev.frostguard.tasks.exploration.TundraTruckEventRoutine;
import dev.frostguard.vision.convert.GameTimeUtils;

class EventStripMenuVisitTest {

    @BeforeAll
    static void createTestWorkspace() throws IOException {
        Files.createDirectories(WorkspacePaths.current().root());
    }

    @Test
    void heroMissionRetriesAMissingTabOnceThenWaitsForTheDailyReset() {
        assertOneExtraVisitThenReset(new HeroProbe());
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

        private HeroProbe() {
            super(account(), TpDailyTaskEnum.EVENT_HERO_MISSION);
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
        String diagnosticSnapshot(String control) {
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
}
