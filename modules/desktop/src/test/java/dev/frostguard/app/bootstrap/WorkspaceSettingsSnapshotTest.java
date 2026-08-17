package dev.frostguard.app.bootstrap;

import dev.frostguard.api.runtime.RuntimeChannel;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceSettingsSnapshotTest {
    @TempDir
    Path tempDir;

    private WorkspacePaths stable;
    private WorkspacePaths nightly;

    @AfterEach
    void removePreferenceNodes() throws Exception {
        if (stable != null) {
            WorkspacePreferences.removeAll(stable);
        }
        if (nightly != null) {
            WorkspacePreferences.removeAll(nightly);
        }
    }

    @Test
    void copiesAClosedStableSnapshotWithoutSharingRuntimeFiles() throws Exception {
        initializeWorkspaces();
        Files.writeString(stable.database(), "database");
        Files.writeString(Path.of(stable.database() + "-wal"), "wal");
        Files.createDirectories(stable.config().resolve("nested"));
        Files.writeString(stable.config().resolve("nested/settings.json"), "settings");
        Files.writeString(stable.customTasks().resolve("task.java"), "task");
        Files.writeString(stable.watcherConfig(), "token=secret");
        Files.writeString(stable.logs().resolve("private.log"), "log");
        Files.writeString(stable.cache().resolve("download.part"), "partial");
        WorkspacePreferences.node(stable, "window").put("windowX", "42");
        WorkspacePreferences.node(nightly, "window").put("stale", "remove-me");

        WorkspaceSettingsSnapshot snapshot = new WorkspaceSettingsSnapshot(stable, nightly);
        assertTrue(snapshot.isTargetFresh());
        assertTrue(snapshot.sourceIsAvailable());
        snapshot.copyFromStable();

        assertEquals("database", Files.readString(nightly.database()));
        assertEquals("wal", Files.readString(Path.of(nightly.database() + "-wal")));
        assertEquals("settings", Files.readString(nightly.config().resolve("nested/settings.json")));
        assertEquals("task", Files.readString(nightly.customTasks().resolve("task.java")));
        assertEquals("token=secret", Files.readString(nightly.watcherConfig()));
        assertEquals("42", WorkspacePreferences.node(nightly, "window").get("windowX", ""));
        assertEquals("", WorkspacePreferences.node(nightly, "window").get("stale", ""));
        assertFalse(Files.exists(nightly.logs().resolve("private.log")));
        assertFalse(Files.exists(nightly.cache().resolve("download.part")));
        assertTrue(Files.readString(nightly.marker()).contains("\"channel\": \"nightly\""));
        assertTrue(snapshot.isCompleted());
    }

    @Test
    void refusesToCopyWhileStableOwnsItsWorkspace() throws Exception {
        initializeWorkspaces();
        Files.writeString(stable.database(), "database");
        WorkspaceSettingsSnapshot snapshot = new WorkspaceSettingsSnapshot(stable, nightly);

        try (WorkspaceSession ignored = WorkspaceSession.open(stable)) {
            assertFalse(snapshot.sourceIsAvailable());
            assertThrows(IOException.class, snapshot::copyFromStable);
        }
        assertTrue(snapshot.isTargetFresh());
    }

    @Test
    void neverOverwritesExistingNightlySettings() throws Exception {
        initializeWorkspaces();
        Files.writeString(stable.database(), "stable");
        Files.writeString(nightly.database(), "nightly");
        WorkspaceSettingsSnapshot snapshot = new WorkspaceSettingsSnapshot(stable, nightly);

        assertFalse(snapshot.isTargetFresh());
        assertThrows(IOException.class, snapshot::copyFromStable);
        assertEquals("nightly", Files.readString(nightly.database()));
    }

    @Test
    void recordsAnExplicitFreshStartAndDoesNotOfferCopyAgain() throws Exception {
        initializeWorkspaces();
        Files.writeString(stable.database(), "stable");
        WorkspacePreferences.node(nightly, "window").put("stale", "remove-me");
        WorkspaceSettingsSnapshot snapshot = new WorkspaceSettingsSnapshot(stable, nightly);

        snapshot.startFresh();

        assertTrue(snapshot.isCompleted());
        assertEquals("", WorkspacePreferences.node(nightly, "window").get("stale", ""));
        assertThrows(IOException.class, snapshot::copyFromStable);
    }

    @Test
    void rollsBackAnInterruptedFirstRunCopyBeforeOfferingItAgain() throws Exception {
        initializeWorkspaces();
        Files.writeString(nightly.database(), "partial");
        Files.writeString(nightly.config().resolve("partial.properties"), "partial");
        Files.writeString(nightly.root().resolve("channel-onboarding.in-progress"), "source=stable\n");
        WorkspacePreferences.node(nightly, "window").put("windowX", "13");
        WorkspaceSettingsSnapshot snapshot = new WorkspaceSettingsSnapshot(stable, nightly);

        assertTrue(snapshot.isTargetFresh());
        assertFalse(Files.exists(nightly.database()));
        assertFalse(Files.exists(nightly.config().resolve("partial.properties")));
        assertEquals("", WorkspacePreferences.node(nightly, "window").get("windowX", ""));
    }

    private void initializeWorkspaces() {
        stable = new WorkspacePaths(tempDir.resolve("stable"), RuntimeChannel.STABLE);
        nightly = new WorkspacePaths(tempDir.resolve("nightly"), RuntimeChannel.NIGHTLY);
        WorkspaceSession.initializeLayout(stable);
        WorkspaceSession.initializeLayout(nightly);
    }
}
