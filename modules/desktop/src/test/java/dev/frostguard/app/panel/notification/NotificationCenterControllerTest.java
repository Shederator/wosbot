package dev.frostguard.app.panel.notification;

import dev.frostguard.api.domain.ActionRequiredIncidentData;
import dev.frostguard.api.runtime.RuntimeChannel;
import dev.frostguard.api.runtime.WorkspacePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotificationCenterControllerTest {

    @TempDir
    Path tempDirectory;

    @Test
    void filtersActiveAndRecoveredHistoryIndependently() {
        ActionRequiredIncidentData active = incident("active", null);
        ActionRequiredIncidentData acknowledged = incident(
                "acknowledged", LocalDateTime.now(), null);
        ActionRequiredIncidentData recovered = incident("recovered", LocalDateTime.now());
        List<ActionRequiredIncidentData> incidents = List.of(active, acknowledged, recovered);

        assertEquals(List.of(active), NotificationCenterController.visibleIncidents(
                incidents, NotificationCenterController.NotificationFilter.ACTION_REQUIRED));
        assertEquals(List.of(acknowledged), NotificationCenterController.visibleIncidents(
                incidents, NotificationCenterController.NotificationFilter.ACKNOWLEDGED));
        assertEquals(incidents, NotificationCenterController.visibleIncidents(
                incidents, NotificationCenterController.NotificationFilter.ALL));
        assertEquals(List.of(recovered), NotificationCenterController.visibleIncidents(
                incidents, NotificationCenterController.NotificationFilter.RECOVERED));
    }

    @Test
    void resolvesRenamedAccountLogByStableProfileSuffix() throws Exception {
        WorkspacePaths workspace = new WorkspacePaths(tempDirectory, RuntimeChannel.DEVELOPMENT);
        Files.createDirectories(workspace.logs());
        Path renamedLog = workspace.logs().resolve("account_Renamed_7.log");
        Files.writeString(renamedLog, "evidence");

        assertEquals(renamedLog, NotificationCenterController.resolveLogTarget(
                workspace, incident("active", null)));
    }

    private static ActionRequiredIncidentData incident(String id, LocalDateTime recoveredAt) {
        return incident(id, null, recoveredAt);
    }

    private static ActionRequiredIncidentData incident(
            String id, LocalDateTime acknowledgedAt, LocalDateTime recoveredAt) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 20, 23, 38);
        return new ActionRequiredIncidentData(
                id, 7L, "Dave", "INITIALIZE", "Initialize",
                "startup.play-store-redirect", "Complete the game update in Google Play",
                "Google Play is waiting for the game update to be completed.",
                "Game update completed outside Frostguard or home/world screen",
                "Google Play foreground package after the in-game update action",
                "Tapped verified Update and captured a fresh frame", "Pause and retry",
                "gameStopped=true; slotReleased=true",
                "bounded log", now.plusHours(1), now.minusMinutes(8), now, 2,
                acknowledgedAt, recoveredAt);
    }
}
