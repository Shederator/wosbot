package dev.frostguard.engine.platform;

import java.nio.file.Path;
import java.util.Locale;

public final class PlatformRuntime {

    private PlatformRuntime() {
    }

    public static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
    }

    public static boolean isMacOs(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("mac");
    }

    public static boolean isLinux(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("linux");
    }

    public static String executableName(String windowsName, String unixName, String osName) {
        if (isWindows(osName)) {
            return windowsName;
        }
        return unixName;
    }

    public static Path join(Path basePath, String... parts) {
        Path current = basePath;
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            current = current.resolve(part);
        }
        return current;
    }

    public static String osName() {
        return System.getProperty("os.name", "");
    }
}
