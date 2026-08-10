package dev.frostguard.engine.emulator;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import java.nio.file.Path;
import java.nio.file.Paths;

// Supported emulator backends with their CLI binary metadata.
public enum EmulatorType {

    MUMU("MuMuPlayer", ConfigurationKeyEnum.MUMU_PATH_STRING, "MuMuManager.exe",
            "C:\\Program Files\\Netease\\MuMuPlayer\\nx_main"),

    MEMU("MEmu Player", ConfigurationKeyEnum.MEMU_PATH_STRING, "memuc.exe",
            "C:\\Program Files\\Microvirt\\MEmu"),

    LDPLAYER("LDPlayer", ConfigurationKeyEnum.LDPLAYER_PATH_STRING, "ldconsole.exe",
            "C:\\LDPlayer\\LDPlayer9");

    private final String label;
    private final ConfigurationKeyEnum cfgKey;
    private final String exe;
    private final String fallbackDir;

    EmulatorType(String label, ConfigurationKeyEnum cfgKey, String exe, String fallbackDir) {
        this.label = label;
        this.cfgKey = cfgKey;
        this.exe = exe;
        this.fallbackDir = fallbackDir;
    }

    public String getDisplayName()   { return label; }
    public String getConfigKey()     { return cfgKey.name(); }
    public ConfigurationKeyEnum getConfigEnum() { return cfgKey; }
    public String getExecutableName(){ return exe; }

    public String getDefaultPath()   { return Paths.get(fallbackDir, exe).toString(); }

    public String resolvePath(String override) {
        return (override == null || override.isBlank())
                ? getDefaultPath()
                : Paths.get(override, exe).toString();
    }
}
