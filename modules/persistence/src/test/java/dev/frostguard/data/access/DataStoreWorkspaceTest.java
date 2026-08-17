package dev.frostguard.data.access;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.api.runtime.WorkspacePaths;

class DataStoreWorkspaceTest {
    @TempDir
    Path tempDir;

    @Test
    void opensTheDefaultDatabaseInsideTheSelectedWorkspace() {
        String previous = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, tempDir.toString());
        try (DataStore ignored = DataStore.openIsolated(Map.of("hibernate.hbm2ddl.auto", "create"))) {
            assertTrue(Files.isRegularFile(tempDir.resolve("frostguard.db")));
        } finally {
            if (previous == null) {
                System.clearProperty(WorkspacePaths.WORKSPACE_PROPERTY);
            } else {
                System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, previous);
            }
        }
    }
}
