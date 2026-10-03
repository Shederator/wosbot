package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TelegramWatcherLauncherTest {

    @TempDir
    Path tempDir;

    @Test
    void reportsWatcherRunningOnlyWhileWorkspaceLockIsHeld() throws Exception {
        Path lockPath = tempDir.resolve("watcher/watcher.lock");
        assertFalse(TelegramWatcherLauncher.isWatcherRunning(lockPath));

        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            assertTrue(TelegramWatcherLauncher.isWatcherRunning(lockPath));
        }

        assertFalse(TelegramWatcherLauncher.isWatcherRunning(lockPath));
    }
}
