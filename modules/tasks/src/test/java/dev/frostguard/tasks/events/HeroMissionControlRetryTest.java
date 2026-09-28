package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.runtime.WorkspacePaths;

class HeroMissionControlRetryTest {

    @BeforeAll
    static void createTestWorkspace() throws IOException {
        Files.createDirectories(WorkspacePaths.current().root());
    }

    @Test
    void reschedulesAMissingRallyControlInsteadOfLeavingTheScheduleUnchanged() {
        TestRoutine routine = new TestRoutine();
        routine.rallyFound = false;

        LocalDateTime before = LocalDateTime.now();

        assertFalse(routine.rallyReaper());

        assertTrue(routine.scheduledAt.isAfter(before.plusMinutes(4)));
        assertTrue(routine.scheduledAt.isBefore(before.plusMinutes(6)));
        assertTrue(routine.warnings.stream().anyMatch(message -> message.contains("rally-button")));
        assertTrue(routine.warnings.stream().anyMatch(message -> message.contains("snapshot=test")));
    }

    private static final class TestRoutine extends HeroMissionEventRoutine {
        private final java.util.List<String> warnings = new ArrayList<>();
        private boolean rallyFound;
        private LocalDateTime scheduledAt;

        private TestRoutine() {
            super(new AccountDescriptor(1L, "Test", "1", true, 1L, 30L),
                    TpDailyTaskEnum.EVENT_HERO_MISSION);
        }

        @Override
        ImageSearchResultData findTraceButton() {
            return found();
        }

        @Override
        ImageSearchResultData findRallyButton() {
            return rallyFound ? found() : missed();
        }

        @Override
        String diagnosticSnapshot(String control) {
            return "snapshot=test";
        }

        @Override
        public boolean tapInside(ImageSearchResultData result) {
            return true;
        }

        @Override
        public void tapNear(PointData point) {
        }

        @Override
        protected void sleepTask(long millis) {
        }

        @Override
        public void reschedule(LocalDateTime rescheduledTime) {
            scheduledAt = rescheduledTime;
        }

        @Override
        public void logWarning(String message) {
            warnings.add(message);
        }

    }

    private static ImageSearchResultData found() {
        return new ImageSearchResultData(true, new PointData(10, 10), 100);
    }

    private static ImageSearchResultData missed() {
        return new ImageSearchResultData(false, null, 0);
    }
}
