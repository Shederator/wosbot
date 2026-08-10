package dev.frostguard.engine.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelegramWatcherLauncher {

    private static final Logger logger = LoggerFactory.getLogger(TelegramWatcherLauncher.class);

    public static void startWatcherIfNotRunning() {
        if (isWatcherRunning()) {
            logger.info("Telegram Watcher is already running.");
            return;
        }

        logger.info("Telegram Watcher is not running. Attempting to start it...");
        File batFile = resolveBatFile();

        if (batFile != null && batFile.exists()) {
            try {
                ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "start", "\"FG-TG-Watcher\"", "/b", batFile.getName());
                pb.directory(batFile.getParentFile());
                pb.start();
                logger.info("Executed {}", batFile.getAbsolutePath());
            } catch (IOException e) {
                logger.error("Failed to start Telegram Watcher bat file", e);
            }
        } else {
            logger.warn("Could not locate the Telegram Watcher launcher.");
        }
    }

    private static boolean isWatcherRunning() {
        try {
            return ProcessHandle.allProcesses()
                    .filter(ph -> ph.info().command().map(c -> c.toLowerCase().contains("java")).orElse(false))
                    .filter(ph -> ph.info().arguments().map(args -> {
                        for (String a : args) {
                            if (a.toLowerCase().contains("frostguard-watcher")) {
                                return true;
                            }
                        }
                        return false;
                    }).orElse(false))
                    .findFirst()
                    .isPresent();
        } catch (Exception e) {
            logger.error("Error checking if watcher is running", e);
            return false;
        }
    }

    private static File resolveBatFile() {
        // Try to load the jar path from ~/.frostguard/telegram-watcher.properties
        try {
            Path cfg = Paths.get(System.getProperty("user.home"), ".frostguard", "telegram-watcher.properties");
            if (Files.exists(cfg)) {
                Properties props = new Properties();
                try (java.io.FileInputStream fis = new java.io.FileInputStream(cfg.toFile())) {
                    props.load(fis);
                }
                String jarPath = props.getProperty("botJarPath", "");
                if (!jarPath.isBlank()) {
                    File dir = new File(jarPath).getParentFile();
                    for (int i = 0; i < 5 && dir != null; i++) {
                        File bat = new File(dir, "fg-watcher.bat");
                        if (bat.exists()) return bat;
                        File sourceLauncher = new File(dir,
                                "packaging/desktop/src/main/windows/Start-Frostguard-Watcher.bat");
                        if (sourceLauncher.exists()) return sourceLauncher;
                        dir = dir.getParentFile();
                    }
                }
            }
        } catch (Exception ignored) {}

        // Fallback: walk up from current working dir
        File dir = new File(System.getProperty("user.dir"));
        for (int i = 0; i < 5 && dir != null; i++) {
            File bat = new File(dir, "fg-watcher.bat");
            if (bat.exists()) return bat;
            File sourceLauncher = new File(dir,
                    "packaging/desktop/src/main/windows/Start-Frostguard-Watcher.bat");
            if (sourceLauncher.exists()) return sourceLauncher;
            dir = dir.getParentFile();
        }

        return null;
    }
}
