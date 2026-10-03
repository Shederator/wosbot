package dev.frostguard.data.repository;

import dev.frostguard.api.domain.TaskFailureStreakData;
import dev.frostguard.data.access.DataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskFailureStreakRepositoryTest {

    private Path database;
    private DataStore store;
    private TaskFailureStreakRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        database = Files.createTempFile("frostguard-task-failure-streak-", ".db");
        store = DataStore.openIsolated(Map.of(
                "jakarta.persistence.jdbc.url", "jdbc:sqlite:" + database,
                "hibernate.hbm2ddl.auto", "create-drop"));
        repository = new TaskFailureStreakRepository(store);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            store.close();
        }
        Files.deleteIfExists(database);
    }

    @Test
    void persistsConsecutiveSignatureAndResetsOnDifferentFailureOrSuccess() {
        LocalDateTime first = LocalDateTime.of(2026, 8, 21, 1, 0);

        assertEquals(1, repository.recordFailure(7L, "TASK", "same", first)
                .consecutiveFailures());
        assertEquals(2, repository.recordFailure(7L, "TASK", "same", first.plusMinutes(5))
                .consecutiveFailures());

        TaskFailureStreakData third = new TaskFailureStreakRepository(store)
                .recordFailure(7L, "TASK", "same", first.plusMinutes(10));
        assertEquals(3, third.consecutiveFailures());
        assertEquals(first, third.firstFailureAt());
        assertEquals(third, new TaskFailureStreakRepository(store).find(7L, "TASK").orElseThrow());

        TaskFailureStreakData changed = repository.recordFailure(
                7L, "TASK", "different", first.plusMinutes(15));
        assertEquals(1, changed.consecutiveFailures());
        assertEquals("different", changed.signature());

        assertTrue(repository.clear(7L, "TASK"));
        assertEquals(1, repository.recordFailure(7L, "TASK", "same", first.plusMinutes(20))
                .consecutiveFailures());
    }

    @Test
    void resetsWhenLastFailureIsBeforeResetBoundary() {
        LocalDateTime first = LocalDateTime.of(2026, 8, 21, 1, 0);
        repository.recordFailure(7L, "TASK", "same", first);

        TaskFailureStreakData reset = repository.recordFailureSince(
                7L, "TASK", "same", first.plusMinutes(15), first.plusMinutes(10));

        assertEquals(1, reset.consecutiveFailures());
        assertEquals(first.plusMinutes(15), reset.firstFailureAt());
        assertEquals(first.plusMinutes(15), reset.lastFailureAt());
    }

    @Test
    void continuesCountingFailuresWithinSameResetCycle() {
        LocalDateTime first = LocalDateTime.of(2026, 8, 21, 1, 0);
        repository.recordFailureSince(7L, "TASK", "same", first, first);

        TaskFailureStreakData second = repository.recordFailureSince(
                7L, "TASK", "same", first.plusMinutes(5), first);
        TaskFailureStreakData third = repository.recordFailureSince(
                7L, "TASK", "same", first.plusMinutes(10), first);

        assertEquals(2, second.consecutiveFailures());
        assertEquals(3, third.consecutiveFailures());
        assertEquals(first, third.firstFailureAt());
    }

    @Test
    void isolatesStreaksByProfileAndTask() {
        LocalDateTime first = LocalDateTime.of(2026, 8, 21, 1, 0);
        repository.recordFailure(7L, "TASK", "same", first);
        repository.recordFailure(8L, "TASK", "same", first);
        repository.recordFailure(7L, "OTHER_TASK", "same", first);

        assertEquals(1, repository.find(7L, "TASK").orElseThrow().consecutiveFailures());
        assertEquals(1, repository.find(8L, "TASK").orElseThrow().consecutiveFailures());
        assertEquals(1, repository.find(7L, "OTHER_TASK").orElseThrow().consecutiveFailures());
        assertFalse(repository.find(9L, "TASK").isPresent());

        TaskFailureStreakData updated = repository.recordFailure(7L, "TASK", "same", first.plusMinutes(1));
        assertEquals(2, updated.consecutiveFailures());
        assertEquals(1, repository.find(8L, "TASK").orElseThrow().consecutiveFailures());
        assertEquals(1, repository.find(7L, "OTHER_TASK").orElseThrow().consecutiveFailures());
    }
}
