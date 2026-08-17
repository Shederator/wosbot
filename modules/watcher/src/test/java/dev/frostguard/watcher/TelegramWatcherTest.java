package dev.frostguard.watcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.api.runtime.RuntimeChannel;
import dev.frostguard.api.runtime.WorkspacePaths;

class TelegramWatcherTest {

    @TempDir
    Path tempDir;

    @Test
    void packagedWatcherLaunchesTheNativeApplication() {
        Path launcher = tempDir.resolve("Frostguard.exe");
        WorkspacePaths workspace = new WorkspacePaths(tempDir.resolve("Bot 1"), RuntimeChannel.STABLE);

        assertEquals(List.of(launcher.toAbsolutePath().toString(), "--headless"),
                TelegramWatcher.botLaunchCommand(launcher, Path.of("java"),
                        tempDir.resolve("frostguard.jar"), workspace, true));
    }

    @Test
    void sourceWatcherPreservesWorkspaceThroughJvmOptions() {
        WorkspacePaths workspace = new WorkspacePaths(tempDir.resolve("Bot 1"), RuntimeChannel.DEVELOPMENT);
        Path jar = tempDir.resolve("frostguard.jar");

        assertEquals(List.of(
                "java",
                "-Dfrostguard.workspace=" + workspace.root(),
                "-Dfrostguard.channel=development",
                "-jar",
                jar.toAbsolutePath().toString()),
                TelegramWatcher.botLaunchCommand(null, Path.of("java"), jar, workspace, false));
    }
}
