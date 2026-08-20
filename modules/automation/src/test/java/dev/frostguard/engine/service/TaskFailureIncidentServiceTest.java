package dev.frostguard.engine.service;

import dev.frostguard.api.domain.ActionRequiredIncidentData;
import dev.frostguard.api.domain.ActionRequiredIncidentState;
import dev.frostguard.api.domain.TaskFailureReport;
import dev.frostguard.data.access.DataStore;
import dev.frostguard.data.repository.ActionRequiredIncidentRepository;
import dev.frostguard.data.repository.TaskFailureStreakRepository;
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

class TaskFailureIncidentServiceTest {

    private Path database;
    private DataStore store;
    private ActionRequiredIncidentService incidentService;
    private TaskFailureIncidentService failureService;

    @BeforeEach
    void setUp() throws Exception {
        database = Files.createTempFile("frostguard-task-failure-service-", ".db");
        store = DataStore.openIsolated(Map.of(
                "jakarta.persistence.jdbc.url", "jdbc:sqlite:" + database,
                "hibernate.hbm2ddl.auto", "create-drop"));
        incidentService = new ActionRequiredIncidentService(
                new ActionRequiredIncidentRepository(store), LoggingService.obtain());
        failureService = new TaskFailureIncidentService(
                new TaskFailureStreakRepository(store), incidentService);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            store.close();
        }
        Files.deleteIfExists(database);
    }

    @Test
    void escalatesThirdStableFailureReactivatesAfterAcknowledgeAndRecoversOnSuccess() {
        TaskFailureIncidentService.FailureDecision first = failureService.recordFailure(report("stable"));
        TaskFailureIncidentService.FailureDecision second = failureService.recordFailure(report("stable"));
        TaskFailureIncidentService.FailureDecision third = failureService.recordFailure(report("stable"));

        assertFalse(first.escalated());
        assertFalse(second.escalated());
        assertTrue(third.escalated());
        assertEquals(3, third.consecutiveFailures());
        assertEquals(1, incidentService.findAll().size());

        ActionRequiredIncidentData incident = incidentService.findAll().getFirst();
        assertTrue(incident.isUnread());
        assertTrue(incidentService.acknowledge(incident.id()));
        assertFalse(incidentService.findAll().getFirst().isUnread());

        TaskFailureIncidentService.FailureDecision fourth = failureService.recordFailure(report("stable"));
        assertTrue(fourth.escalated());
        assertTrue(fourth.incident().orElseThrow().isUnread());
        assertEquals(2, fourth.incident().orElseThrow().occurrenceCount());

        assertEquals(1, failureService.recordSuccess(7L, "TASK"));
        assertEquals(ActionRequiredIncidentState.RECOVERED,
                incidentService.findAll().getFirst().state());

        assertFalse(failureService.recordFailure(report("stable")).escalated());
        assertEquals(ActionRequiredIncidentState.RECOVERED,
                incidentService.findAll().getFirst().state());
    }

    @Test
    void aDifferentStableSignatureStartsANewSequence() {
        failureService.recordFailure(report("first"));
        failureService.recordFailure(report("first"));

        TaskFailureIncidentService.FailureDecision changed =
                failureService.recordFailure(report("different"));

        assertEquals(1, changed.consecutiveFailures());
        assertFalse(changed.escalated());
        assertTrue(incidentService.findAll().isEmpty());
    }

    private static TaskFailureReport report(String signature) {
        return new TaskFailureReport(
                7L, "Dave", "TASK", "Test task", signature,
                "Task repeatedly failed", "The same operation failed",
                "Successful completion", "Transient failure", "Attempted operation",
                "Retry later", "No cleanup", LocalDateTime.now().plusMinutes(5), 3);
    }
}
