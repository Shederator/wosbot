package dev.frostguard.app.bootstrap;

import dev.frostguard.api.runtime.WorkspacePaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupOptionsTest {
    @TempDir
    Path tempDir;

    private String previousChannel;
    private String previousApplicationId;
    private String previousWorkspace;
    private String previousUserHome;

    @BeforeEach
    void configureInstalledStableBuild() {
        previousChannel = System.getProperty(WorkspacePaths.CHANNEL_PROPERTY);
        previousApplicationId = System.getProperty(WorkspacePaths.APPLICATION_ID_PROPERTY);
        previousWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        previousUserHome = System.getProperty("user.home");
        System.setProperty(WorkspacePaths.CHANNEL_PROPERTY, "stable");
        System.setProperty(WorkspacePaths.APPLICATION_ID_PROPERTY, "dev.frostguard.desktop");
        System.clearProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void restoreRuntimeProperties() {
        restore(WorkspacePaths.CHANNEL_PROPERTY, previousChannel);
        restore(WorkspacePaths.APPLICATION_ID_PROPERTY, previousApplicationId);
        restore(WorkspacePaths.WORKSPACE_PROPERTY, previousWorkspace);
        restore("user.home", previousUserHome);
    }

    @Test
    void keepsTheDefaultInstalledWorkspaceWithoutAnOption() {
        StartupOptions options = StartupOptions.parse(new String[] {"--autostart"});

        assertFalse(options.headless());
        assertFalse(options.nativeSmokeTest());
        assertEquals(tempDir.resolve(".frostguard/workspaces/stable/default").toAbsolutePath(),
                options.resolveWorkspace().root());
    }

    @Test
    void resolvesASeparateNamedInstalledWorkspace() {
        StartupOptions options = StartupOptions.parse(
                new String[] {"--headless", "--workspace", "bot-2"});

        assertTrue(options.headless());
        assertEquals(tempDir.resolve(".frostguard/workspaces/stable/bot-2").toAbsolutePath(),
                options.resolveWorkspace().root());
    }

    @Test
    void acceptsTheEqualsFormAndNativeSmokeFlag() {
        StartupOptions options = StartupOptions.parse(
                new String[] {"--workspace=qa", "--frostguard-native-smoke-test"});

        assertTrue(options.nativeSmokeTest());
        assertEquals("qa", options.workspaceName());
    }

    @Test
    void rejectsMissingDuplicateAndUnsafeWorkspaceNames() {
        assertThrows(IllegalArgumentException.class,
                () -> StartupOptions.parse(new String[] {"--workspace"}));
        assertThrows(IllegalArgumentException.class,
                () -> StartupOptions.parse(new String[] {"--workspace=one", "--workspace", "two"}));
        assertThrows(IllegalArgumentException.class,
                () -> StartupOptions.parse(new String[] {"--workspace", "../shared"})
                        .resolveWorkspace());
    }

    private static void restore(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }
}
