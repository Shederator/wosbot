package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.api.runtime.RuntimeChannel;
import dev.frostguard.api.runtime.WorkspacePaths;

class TaskQueueDesktopJarResolutionTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesPackagedDesktopJarFromWorkingDirectory() throws Exception {
        Path desktopJar = Files.createFile(tempDir.resolve("frostguard-desktop-2.1.0.jar"));
        Files.createFile(tempDir.resolve("frostguard-watcher-2.1.0.jar"));

        assertEquals(desktopJar.toAbsolutePath().toString(),
                TaskQueue.resolveDesktopJarForAutostart(tempDir));
    }

    @Test
    void resolvesNewestDesktopJarFromSourceModuleTarget() throws Exception {
        Path target = Files.createDirectories(tempDir.resolve("modules/desktop/target"));
        Files.createFile(target.resolve("frostguard-desktop-2.1.0.jar"));
        Path newest = Files.createFile(target.resolve("frostguard-desktop-3.0.0.jar"));

        assertEquals(newest.toAbsolutePath().toString(),
                TaskQueue.resolveDesktopJarForAutostart(tempDir));
    }

    @Test
    void rejectsWorkingDirectoryWithoutDesktopArtifact() {
        assertThrows(IOException.class,
                () -> TaskQueue.resolveDesktopJarForAutostart(tempDir));
    }

    @Test
    void nativeAutostartWrapperPreservesTheWorkspace() {
        Path launcher = tempDir.resolve("Frostguard.exe").toAbsolutePath();
        WorkspacePaths workspace = new WorkspacePaths(tempDir.resolve("Bot 1"), RuntimeChannel.STABLE);

        String script = TaskQueue.nativeAutostartLauncherContent(launcher, workspace);

        assertTrue(script.contains("set \"FROSTGUARD_WORKSPACE=" + workspace.root() + "\""));
        assertTrue(script.contains("set \"FROSTGUARD_CHANNEL=stable\""));
        assertTrue(script.contains("start \"\" \"" + launcher + "\" --autostart"));
    }

    @Test
    void usesTheJpackageLauncherPathWhenNoExplicitOverrideExists() {
        String oldConfigured = System.getProperty("frostguard.launcher");
        String oldJpackage = System.getProperty("jpackage.app-path");
        try {
            System.clearProperty("frostguard.launcher");
            System.setProperty("jpackage.app-path", tempDir.resolve("Frostguard Nightly.exe").toString());

            assertEquals(tempDir.resolve("Frostguard Nightly.exe").toString(),
                    TaskQueue.packagedApplicationLauncher());
        } finally {
            restore("frostguard.launcher", oldConfigured);
            restore("jpackage.app-path", oldJpackage);
        }
    }

    private static void restore(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }
}
