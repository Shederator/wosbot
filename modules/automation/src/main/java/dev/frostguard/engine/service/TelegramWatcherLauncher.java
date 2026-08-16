package dev.frostguard.engine.service;

import dev.frostguard.api.platform.PlatformPaths;
import dev.frostguard.api.runtime.WorkspacePaths;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelegramWatcherLauncher {

    private static final Logger logger = LoggerFactory.getLogger(TelegramWatcherLauncher.class);
    private static final String WATCHER_LAUNCHER_PROPERTY = "frostguard.watcher.launcher";

    public static void startWatcherIfNotRunning() {
        if (isWatcherRunning()) {
            logger.info("Telegram Watcher is already running.");
            return;
        }

        logger.info("Telegram Watcher is not running. Attempting to start it...");
        File launcher = resolveLauncher();

        if (launcher != null && launcher.exists()) {
            try {
                ProcessBuilder pb;
                if (isNativeLauncher(launcher)) {
                    pb = new ProcessBuilder(launcher.getAbsolutePath());
                    pb.directory(launcher.getParentFile());
                } else if (PlatformPaths.isWindows()) {
                    pb = new ProcessBuilder("cmd", "/c", "start", "\"FG-TG-Watcher\"", "/b", launcher.getName());
                    pb.directory(launcher.getParentFile());
                } else {
                    pb = new ProcessBuilder(launcher.getAbsolutePath());
                    pb.directory(launcher.getParentFile());
                }
                pb.environment().put("FROSTGUARD_WORKSPACE", WorkspacePaths.current().root().toString());
                pb.environment().put("FROSTGUARD_CHANNEL",
                        WorkspacePaths.current().channel().directoryName());
                pb.start();
                logger.info("Executed {}", launcher.getAbsolutePath());
            } catch (IOException e) {
                logger.error("Failed to start Telegram Watcher launcher", e);
            }
        } else {
            logger.warn("Could not locate the Telegram Watcher launcher.");
        }
    }

    private static boolean isWatcherRunning() {
        Path lockPath = WorkspacePaths.current().watcherLock();
        try {
            Files.createDirectories(lockPath.getParent());
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                try (FileLock lock = channel.tryLock()) {
                    return lock == null;
                } catch (OverlappingFileLockException lockedInThisProcess) {
                    return true;
                }
            }
        } catch (Exception e) {
            logger.warn("Could not inspect watcher lock {}: {}", lockPath, e.getMessage());
            return false;
        }
    }

    private static File resolveLauncher() {
        String packagedLauncher = System.getProperty(WATCHER_LAUNCHER_PROPERTY, "").trim();
        if (!packagedLauncher.isBlank()) {
            File launcher = new File(packagedLauncher);
            if (launcher.isFile()) {
                return launcher;
            }
        }

        String[] relativeNames = PlatformPaths.isWindows()
                ? new String[]{
                        "fg-watcher.bat",
                        "packaging/desktop/src/main/windows/Start-Frostguard-Watcher.bat"
                }
                : new String[]{
                        "Frostguard Watcher",
                        "Frostguard Nightly Watcher",
                        "Start Frostguard Watcher.app/Contents/MacOS/Start Frostguard Watcher",
                        "fg-watcher.command"
                };

        try {
            Path cfg = WorkspacePaths.current().watcherConfig();
            if (Files.exists(cfg)) {
                Properties props = new Properties();
                try (java.io.FileInputStream fis = new java.io.FileInputStream(cfg.toFile())) {
                    props.load(fis);
                }
                String jarPath = props.getProperty("botJarPath", "");
                if (!jarPath.isBlank()) {
                    File found = findLauncherNear(new File(jarPath).getParentFile(), relativeNames);
                    if (found != null) {
                        return found;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return findLauncherNear(new File(System.getProperty("user.dir")), relativeNames);
    }

    private static File findLauncherNear(File startDir, String[] relativeNames) {
        File dir = startDir;
        for (int i = 0; i < 5 && dir != null; i++) {
            for (String name : relativeNames) {
                File candidate = new File(dir, name);
                if (candidate.exists()) {
                    return candidate;
                }
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    private static boolean isNativeLauncher(File launcher) {
        String name = launcher.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".exe")
                || (!name.endsWith(".bat") && !name.endsWith(".cmd") && !name.endsWith(".command")
                        && launcher.canExecute());
    }
}
