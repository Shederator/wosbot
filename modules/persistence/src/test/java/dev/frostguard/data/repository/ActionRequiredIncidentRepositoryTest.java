package dev.frostguard.data.repository;

import dev.frostguard.api.domain.ActionRequiredIncidentData;
import dev.frostguard.api.domain.ActionRequiredIncidentReport;
import dev.frostguard.api.domain.ActionRequiredIncidentState;
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

class ActionRequiredIncidentRepositoryTest {

    private Path database;
    private DataStore store;
    private ActionRequiredIncidentRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        database = Files.createTempFile("frostguard-incident-test-", ".db");
        store = DataStore.openIsolated(Map.of(
                "jakarta.persistence.jdbc.url", "jdbc:sqlite:" + database,
                "hibernate.hbm2ddl.auto", "create-drop"));
        repository = new ActionRequiredIncidentRepository(store);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            store.close();
        }
        Files.deleteIfExists(database);
    }

    @Test
    void deduplicatesAcknowledgesRecoversAndReopensStableIncident() {
        LocalDateTime firstSeen = LocalDateTime.of(2026, 8, 20, 23, 30);
        LocalDateTime secondSeen = firstSeen.plusMinutes(8);

        ActionRequiredIncidentData first = repository.recordOccurrence(
                report("Store sign-in is required", firstSeen.plusHours(1)), "first log", firstSeen);
        ActionRequiredIncidentData second = repository.recordOccurrence(
                report("Store sign-in remains required", secondSeen.plusHours(1)), "second log", secondSeen);

        assertEquals(first.id(), second.id());
        assertEquals(firstSeen, second.firstSeenAt());
        assertEquals(secondSeen, second.lastSeenAt());
        assertEquals(2, second.occurrenceCount());
        assertEquals("Store sign-in remains required", second.cause());
        assertEquals("second log", second.logExcerpt());
        assertTrue(second.isUnread());

        assertTrue(repository.acknowledge(second.id(), secondSeen.plusMinutes(1)));
        ActionRequiredIncidentData acknowledged = repository.findAll().getFirst();
        assertFalse(acknowledged.isUnread());
        assertEquals(ActionRequiredIncidentState.ACTIVE, acknowledged.state());

        ActionRequiredIncidentData reactivated = repository.recordOccurrence(
                report("Store sign-in is required again", secondSeen.plusHours(1)),
                "third log", secondSeen.plusMinutes(2));
        assertTrue(reactivated.isUnread());
        assertEquals(3, reactivated.occurrenceCount());

        assertEquals(1, repository.recoverTask(7L, "INITIALIZE", secondSeen.plusMinutes(2)));
        ActionRequiredIncidentData recovered = repository.findAll().getFirst();
        assertEquals(ActionRequiredIncidentState.RECOVERED, recovered.state());
        assertFalse(recovered.isUnread());

        ActionRequiredIncidentData reopened = repository.recordOccurrence(
                report("Store sign-in is required again", secondSeen.plusHours(2)),
                "fourth log", secondSeen.plusHours(1));
        assertEquals(first.id(), reopened.id());
        assertEquals(4, reopened.occurrenceCount());
        assertEquals(ActionRequiredIncidentState.ACTIVE, reopened.state());
        assertTrue(reopened.isUnread());
    }

    private static ActionRequiredIncidentReport report(String cause, LocalDateTime retryAt) {
        return new ActionRequiredIncidentReport(
                7L,
                "Dave",
                "INITIALIZE",
                "Initialize",
                "startup.mandatory-update",
                "Game update requires operator action",
                cause,
                "home/world",
                "mandatory-update-dialog",
                "No store button tap sent",
                "Pause for one hour and retry",
                "gameStopped=true; slotReleased=true",
                retryAt);
    }
}
