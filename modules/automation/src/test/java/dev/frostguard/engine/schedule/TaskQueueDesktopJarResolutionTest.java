package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
