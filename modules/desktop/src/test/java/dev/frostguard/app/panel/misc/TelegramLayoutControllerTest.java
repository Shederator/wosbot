package dev.frostguard.app.panel.misc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.api.runtime.RuntimeChannel;
import dev.frostguard.api.runtime.WorkspacePaths;

class TelegramLayoutControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void startupWrapperPreservesWorkspaceIdentity() {
        WorkspacePaths workspace = new WorkspacePaths(tempDir.resolve("Bot 1"), RuntimeChannel.STABLE);
        Path watcherLauncher = tempDir.resolve("install/Start-Frostguard-Watcher.bat");

        String script = TelegramLayoutController.workspaceWatcherLauncherContent(
                watcherLauncher, workspace);

        assertTrue(script.contains("set \"FROSTGUARD_WORKSPACE=" + workspace.root() + "\""));
        assertTrue(script.contains("set \"FROSTGUARD_CHANNEL=stable\""));
        assertTrue(script.contains("call \"" + watcherLauncher.toAbsolutePath().normalize() + "\""));
    }

    @Test
    void startupWrapperStartsThePackagedNativeWatcher() {
        WorkspacePaths workspace = new WorkspacePaths(tempDir.resolve("Bot 1"), RuntimeChannel.STABLE);
        Path watcherLauncher = tempDir.resolve("install/FrostguardWatcher.exe");

        String script = TelegramLayoutController.workspaceWatcherLauncherContent(
                watcherLauncher, workspace);

        assertTrue(script.contains("start \"\" \""
                + watcherLauncher.toAbsolutePath().normalize() + "\""));
    }
}
