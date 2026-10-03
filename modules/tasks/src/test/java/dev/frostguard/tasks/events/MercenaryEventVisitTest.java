package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.helper.NavigationHelper.EventMenuOpenResult;
import dev.frostguard.vision.convert.GameTimeUtils;

class MercenaryEventVisitTest {

    @BeforeAll
    static void createTestWorkspace() throws IOException {
        Files.createDirectories(WorkspacePaths.current().root());
    }

    @Test
    void firstTabMissRetriesOnceAndTheSecondWaitsUntilTheDailyReset() {
        TestRoutine routine = new TestRoutine();

        routine.respondToMenu(EventMenuOpenResult.TAB_ABSENT);
        LocalDateTime first = routine.scheduledAt;
        assertTrue(first.isAfter(LocalDateTime.now().plusMinutes(4)));
        assertTrue(first.isBefore(LocalDateTime.now().plusMinutes(6)));
        assertTrue(routine.warnings.get(0).contains("One more menu visit"));
        assertTrue(routine.warnings.get(0).contains("snapshot=test"));
        assertFalse(routine.warnings.get(0).contains("completed"));

        routine.respondToMenu(EventMenuOpenResult.TAB_ABSENT);
        assertEquals(GameTimeUtils.dailyResetTime(), routine.scheduledAt);
        assertTrue(routine.warnings.get(1).contains("hunt was not completed"));
        assertTrue(routine.warnings.get(1).contains("snapshot=test"));

        routine.respondToMenu(EventMenuOpenResult.REACHED);
        routine.respondToMenu(EventMenuOpenResult.TAB_ABSENT);
        assertTrue(routine.scheduledAt.isAfter(LocalDateTime.now().plusMinutes(4)));
        assertTrue(routine.scheduledAt.isBefore(LocalDateTime.now().plusMinutes(6)));
    }

    @Test
    void closedEventsPanelRetriesWithoutSpendingTheMenuVisit() {
        TestRoutine routine = new TestRoutine();

        routine.respondToMenu(EventMenuOpenResult.PANEL_CLOSED);
        routine.respondToMenu(EventMenuOpenResult.PANEL_CLOSED);
        routine.respondToMenu(EventMenuOpenResult.TAB_ABSENT);

        assertTrue(routine.scheduledAt.isAfter(LocalDateTime.now().plusMinutes(4)));
        assertTrue(routine.scheduledAt.isBefore(LocalDateTime.now().plusMinutes(6)));
        assertTrue(routine.warnings.get(2).contains("One more menu visit"));
    }

    @Test
    void absentHuntControlsStayUnconfirmedAndDoNotWaitForTheReset() {
        TestRoutine routine = new TestRoutine();

        routine.respondToOpenedScreen(false);

        assertTrue(routine.scheduledAt.isAfter(LocalDateTime.now().plusMinutes(4)));
        assertTrue(routine.scheduledAt.isBefore(LocalDateTime.now().plusMinutes(6)));
        assertTrue(routine.warnings.get(0).contains("completion is not confirmed"));
        assertFalse(routine.warnings.get(0).contains("snapshot="));
        assertFalse(routine.warnings.get(0).contains("Mercenary hunt completed"));
        assertEquals(0, routine.snapshots);
    }

    private static final class TestRoutine extends MercenaryEventRoutine {
        private final List<String> warnings = new ArrayList<>();
        private LocalDateTime scheduledAt;
        private int snapshots;

        private TestRoutine() {
            super(new AccountDescriptor(1L, "Test", "1", true, 1L, 30L), TpDailyTaskEnum.MERCENARY_EVENT);
        }

        @Override
        String diagnosticSnapshot(String type) {
            snapshots++;
            return "snapshot=test";
        }

        @Override
        public void reschedule(LocalDateTime rescheduledTime) {
            scheduledAt = rescheduledTime;
        }

        @Override
        public void logWarning(String message) {
            warnings.add(message);
        }

        @Override
        public void logInfo(String message) {
        }

        @Override
        public void logError(String message, Throwable exception) {
            warnings.add(message);
        }
    }
}
