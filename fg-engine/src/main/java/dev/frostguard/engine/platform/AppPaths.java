package dev.frostguard.engine.platform;

public final class AppPaths {

    private AppPaths() {
    }

    public static java.nio.file.Path userHome() {
        return dev.frostguard.api.platform.AppPaths.userHome();
    }

    public static java.nio.file.Path workingDirectory() {
        return dev.frostguard.api.platform.AppPaths.workingDirectory();
    }

    public static java.nio.file.Path tempDirectory() {
        return dev.frostguard.api.platform.AppPaths.tempDirectory();
    }

    public static java.nio.file.Path appDataDirectory() {
        return dev.frostguard.api.platform.AppPaths.appDataDirectory();
    }

    public static java.nio.file.Path logsDirectory() {
        return dev.frostguard.api.platform.AppPaths.logsDirectory();
    }

    public static java.nio.file.Path customTasksDirectory() {
        return dev.frostguard.api.platform.AppPaths.customTasksDirectory();
    }

    public static java.nio.file.Path ocrDebugDirectory() {
        return dev.frostguard.api.platform.AppPaths.ocrDebugDirectory();
    }

    public static java.nio.file.Path watcherConfigFile() {
        return dev.frostguard.api.platform.AppPaths.watcherConfigFile();
    }
}
