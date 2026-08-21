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

        TaskFailureStreakData changed = repository.recordFailure(
                7L, "TASK", "different", first.plusMinutes(15));
        assertEquals(1, changed.consecutiveFailures());
        assertEquals("different", changed.signature());

        assertTrue(repository.clear(7L, "TASK"));
        assertEquals(1, repository.recordFailure(7L, "TASK", "same", first.plusMinutes(20))
                .consecutiveFailures());
    }
}
