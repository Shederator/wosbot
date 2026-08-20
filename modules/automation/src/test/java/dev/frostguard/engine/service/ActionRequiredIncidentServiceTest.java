package dev.frostguard.engine.service;

import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.api.domain.ActionRequiredIncidentData;
import dev.frostguard.api.domain.ActionRequiredIncidentReport;
import dev.frostguard.api.domain.ActionRequiredIncidentState;
import dev.frostguard.data.access.DataStore;
import dev.frostguard.data.repository.ActionRequiredIncidentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionRequiredIncidentServiceTest {

    private Path database;
    private DataStore store;
    private ActionRequiredIncidentService service;

    @BeforeEach
    void setUp() throws Exception {
        database = Files.createTempFile("frostguard-incident-service-test-", ".db");
        store = DataStore.openIsolated(Map.of(
                "jakarta.persistence.jdbc.url", "jdbc:sqlite:" + database,
                "hibernate.hbm2ddl.auto", "create-drop"));
        service = new ActionRequiredIncidentService(
                new ActionRequiredIncidentRepository(store), LoggingService.obtain());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            store.close();
        }
        Files.deleteIfExists(database);
    }

    @Test
    void persistsHeadlessReportsPublishesChangesAndBuildsShareableDiagnostics() {
        List<List<ActionRequiredIncidentData>> updates = new ArrayList<>();
        service.registerListener(updates::add);
        LoggingService.obtain().emit(TpMessageSeverityEnum.ERROR, "Initialize", "Dave",
                "Store response token=private-token accountId=998877 still requires sign-in");

        ActionRequiredIncidentData first = service.report(report());
        ActionRequiredIncidentData repeated = service.report(report());

        assertEquals(first.id(), repeated.id());
        assertEquals(2, repeated.occurrenceCount());
        assertEquals(2, updates.size());
        assertEquals(1, service.findAll().size());

        String diagnostics = ActionRequiredIncidentService.formatDiagnostics(repeated);
        assertTrue(diagnostics.contains("Frostguard ACTION REQUIRED"));
        assertTrue(diagnostics.contains("Profile: Dave"));
        assertTrue(diagnostics.contains("Expected: Game update completed outside Frostguard or home/world screen"));
        assertTrue(diagnostics.contains("token=<redacted>"));
        assertTrue(diagnostics.contains("accountId=<redacted>"));
        assertFalse(diagnostics.contains("private-token"));
        assertFalse(diagnostics.contains("998877"));
        assertFalse(diagnostics.contains("Profile ID"));
        assertFalse(diagnostics.contains(first.id()));

        assertTrue(service.acknowledge(first.id()));
        assertFalse(service.findAll().getFirst().isUnread());
        assertEquals(1, service.recoverTask(7L, "INITIALIZE"));
        assertEquals(ActionRequiredIncidentState.RECOVERED, service.findAll().getFirst().state());
    }

    private static ActionRequiredIncidentReport report() {
        return new ActionRequiredIncidentReport(
                7L,
                "Dave",
                "INITIALIZE",
                "Initialize",
                "startup.play-store-redirect",
                "Complete the game update in Google Play",
                "Google Play is waiting for the game update to be completed.",
                "Game update completed outside Frostguard or home/world screen",
                "Google Play foreground package after the in-game update action",
                "Tapped the detected Update button, captured a fresh frame, and verified Google Play foreground",
                "Pause for one hour and retry",
                "gameStopped=true; slotReleased=true",
                LocalDateTime.now().plusHours(1));
    }
}
