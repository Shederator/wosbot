package dev.frostguard.app.panel.misc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

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

    @Test
    void detectsNewestDesktopJarFromMavenModuleWorkingDirectory() throws Exception {
        Path desktop = tempDir.resolve("modules/desktop");
        Path target = desktop.resolve("target");
        Path classes = target.resolve("classes");
        Files.createDirectories(classes);
        Path older = Files.createFile(target.resolve("frostguard-desktop-3.0.9.jar"));
        Path newer = Files.createFile(target.resolve("frostguard-desktop-3.0.10.jar"));
        Files.createFile(target.resolve("frostguard-desktop-3.0.11-sources.jar"));
        Files.setLastModifiedTime(older, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(newer, FileTime.from(Instant.parse("2026-01-02T00:00:00Z")));

        assertEquals(newer.toString(),
                TelegramLayoutController.autoDetectBotJar(classes.toFile(), desktop.toFile()));
    }

    @Test
    void detectsDesktopJarFromRepositoryWorkingDirectory() throws Exception {
        Path jar = tempDir.resolve("modules/desktop/target/frostguard-desktop-3.0.2.jar");
        Files.createDirectories(jar.getParent());
        Files.createFile(jar);

        assertEquals(jar.toString(),
                TelegramLayoutController.autoDetectBotJar(null, tempDir.toFile()));
    }

    @Test
    void keepsTheRunningJarAheadOfIncrementalBuildArtifacts() throws Exception {
        Path runningJar = Files.createFile(tempDir.resolve("frostguard-desktop-3.0.2.jar"));
        Path target = tempDir.resolve("modules/desktop/target");
        Files.createDirectories(target);
        Files.createFile(target.resolve("frostguard-desktop-3.0.3.jar"));

        assertEquals(runningJar.toString(),
                TelegramLayoutController.autoDetectBotJar(runningJar.toFile(), tempDir.toFile()));
    }
}
