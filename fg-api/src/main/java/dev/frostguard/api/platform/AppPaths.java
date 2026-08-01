package dev.frostguard.api.platform;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

public final class AppPaths {

    private AppPaths() {
    }

    public static Path userHome() {
        return Paths.get(System.getProperty("user.home", "."));
    }

    public static Path workingDirectory() {
        return Paths.get(System.getProperty("user.dir", "."));
    }

    public static Path tempDirectory() {
        return Paths.get(System.getProperty("java.io.tmpdir", "."));
    }

    public static Path appDataDirectory() {
        String osName = System.getProperty("os.name", "");
        String lower = osName.toLowerCase(Locale.ROOT);
        if (lower.contains("mac")) {
            return userHome().resolve("Library").resolve("Application Support").resolve("Frostguard");
        }
        if (lower.contains("linux")) {
            return userHome().resolve(".frostguard");
        }
        return userHome().resolve(".frostguard");
    }

    public static Path logsDirectory() {
        return workingDirectory().resolve("logs");
    }

    public static Path customTasksDirectory() {
        return workingDirectory().resolve("custom_tasks");
    }

    public static Path ocrDebugDirectory() {
        return appDataDirectory().resolve("ocr-debug");
    }

    public static Path watcherConfigFile() {
        return appDataDirectory().resolve("telegram-watcher.properties");
    }
}
