package dev.frostguard.engine.emulator;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.platform.PlatformPaths;
import java.io.File;
import java.nio.file.Paths;
import java.util.Arrays;

// Supported emulator backends with their CLI binary metadata.
public enum EmulatorType {

    MUMU("MuMuPlayer", ConfigurationKeyEnum.MUMU_PATH_STRING, "MuMuManager.exe",
            "C:\\Program Files\\Netease\\MuMuPlayer\\nx_main",
            PlatformPaths.OperatingSystem.WINDOWS, true),

    MEMU("MEmu Player", ConfigurationKeyEnum.MEMU_PATH_STRING, "memuc.exe",
            "C:\\Program Files\\Microvirt\\MEmu",
            PlatformPaths.OperatingSystem.WINDOWS, true),

    LDPLAYER("LDPlayer", ConfigurationKeyEnum.LDPLAYER_PATH_STRING, "ldconsole.exe",
            "C:\\LDPlayer\\LDPlayer9",
            PlatformPaths.OperatingSystem.WINDOWS, true),

    BLUESTACKS_AIR("BlueStacks Air", ConfigurationKeyEnum.BLUESTACKS_AIR_PATH_STRING, "",
            "/Applications/BlueStacks.app",
            PlatformPaths.OperatingSystem.MACOS, false);

    private final String label;
    private final ConfigurationKeyEnum cfgKey;
    private final String exe;
    private final String fallbackDir;
    private final PlatformPaths.OperatingSystem platform;
    private final boolean requiresExecutableOnDisk;

    EmulatorType(String label, ConfigurationKeyEnum cfgKey, String exe, String fallbackDir,
            PlatformPaths.OperatingSystem platform, boolean requiresExecutableOnDisk) {
        this.label = label;
        this.cfgKey = cfgKey;
        this.exe = exe;
        this.fallbackDir = fallbackDir;
        this.platform = platform;
        this.requiresExecutableOnDisk = requiresExecutableOnDisk;
    }

    public static EmulatorType[] valuesForCurrentPlatform() {
        PlatformPaths.OperatingSystem current = PlatformPaths.currentOs();
        return Arrays.stream(values())
                .filter(type -> type.platform == current)
                .toArray(EmulatorType[]::new);
    }

    public static EmulatorType defaultForCurrentPlatform() {
        if (PlatformPaths.isMacOs()) {
            return BLUESTACKS_AIR;
        }
        return MUMU;
    }

    public boolean isSupportedOnCurrentPlatform() {
        return platform == PlatformPaths.currentOs();
    }

    public boolean requiresExecutableOnDisk() {
        return requiresExecutableOnDisk;
    }

    public boolean isAdbOnlyProvider() {
        return !requiresExecutableOnDisk;
    }

    public boolean isConfiguredPathValid(String configuredPath) {
        if (this == BLUESTACKS_AIR) {
            String bundlePath = configuredPath;
            if (bundlePath == null || bundlePath.isBlank()) {
                bundlePath = resolveConfiguredDirectory();
            }
            return bundlePath != null && !bundlePath.isBlank() && new File(bundlePath).isDirectory();
        }
        if (configuredPath == null || configuredPath.isBlank()) {
            return false;
        }
        if (!requiresExecutableOnDisk) {
            return true;
        }
        if (exe == null || exe.isBlank()) {
            return new File(configuredPath).exists();
        }
        return new File(configuredPath, exe).isFile();
    }

    public String resolveConfiguredDirectory() {
        if (this == BLUESTACKS_AIR) {
            String detected = PlatformPaths.detectBlueStacksMacBundle();
            if (detected != null) {
                return detected;
            }
        }
        return fallbackDir;
    }

    public String getDisplayName()   { return label; }
    public String getConfigKey()     { return cfgKey.name(); }
    public ConfigurationKeyEnum getConfigEnum() { return cfgKey; }
    public String getExecutableName(){ return exe; }

    public String getDefaultDirectory() {
        return fallbackDir;
    }

    public String getDefaultPath() {
        if (exe == null || exe.isBlank()) {
            return fallbackDir;
        }
        return Paths.get(fallbackDir, exe).toString();
    }

    public String resolvePath(String override) {
        if (override == null || override.isBlank()) {
            return getDefaultPath();
        }
        if (exe == null || exe.isBlank()) {
            return override;
        }
        return Paths.get(override, exe).toString();
    }
}
