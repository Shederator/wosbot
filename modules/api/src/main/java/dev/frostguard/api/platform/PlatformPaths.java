package dev.frostguard.api.platform;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Resolves platform-specific native tool paths (ADB, BlueStacks) for the runtime.
 * Distinct from {@code UpdatePlatform}, which classifies update artifacts.
 */
public final class PlatformPaths {

    public enum OperatingSystem {
        WINDOWS, MACOS, LINUX, OTHER
    }

    private PlatformPaths() {
    }

    public static OperatingSystem currentOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return OperatingSystem.WINDOWS;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return OperatingSystem.MACOS;
        }
        if (os.contains("nux")) {
            return OperatingSystem.LINUX;
        }
        return OperatingSystem.OTHER;
    }

    public static boolean isWindows() {
        return currentOs() == OperatingSystem.WINDOWS;
    }

    public static boolean isMacOs() {
        return currentOs() == OperatingSystem.MACOS;
    }

    public static String adbExecutableName() {
        return isWindows() ? "adb.exe" : "adb";
    }

    /**
     * Resolves the ADB executable used by ddmlib and shell subprocesses.
     *
     * @param emulatorInstallDir optional emulator directory (Windows fallback: sibling {@code adb.exe})
     */
    public static String resolveAdbPath(String emulatorInstallDir) {
        String fromProject = findBundledAdb();
        if (fromProject != null) {
            return fromProject;
        }

        String fromSdk = findAndroidSdkAdb();
        if (fromSdk != null) {
            return fromSdk;
        }

        String fromPath = findOnPath(adbExecutableName());
        if (fromPath != null) {
            return fromPath;
        }

        if (isWindows() && emulatorInstallDir != null && !emulatorInstallDir.isBlank()) {
            File sibling = new File(emulatorInstallDir, "adb.exe");
            if (sibling.isFile()) {
                return sibling.getAbsolutePath();
            }
        }

        throw new IllegalStateException(
                "ADB not found. Install Android platform-tools, enable BlueStacks hd-adb on macOS, "
                        + "or place adb under lib/adb/.");
    }

    /**
     * Installed BlueStacks app bundle on macOS (e.g. {@code /Applications/BlueStacks.app}).
     */
    public static String detectBlueStacksMacBundle() {
        String[] appNames = {"BlueStacks.app", "BlueStacks Air.app"};
        String[] baseDirs = {"/Applications", System.getProperty("user.home") + "/Applications"};
        for (String base : baseDirs) {
            for (String app : appNames) {
                File bundle = new File(base, app);
                if (bundle.isDirectory()) {
                    return bundle.getAbsolutePath();
                }
            }
        }
        return null;
    }

    /**
     * {@code hd-adb} shipped with BlueStacks for Mac, if present.
     */
    public static String resolveBlueStacksHdAdb(String bundlePath) {
        if (bundlePath == null || bundlePath.isBlank()) {
            bundlePath = detectBlueStacksMacBundle();
        }
        if (bundlePath == null) {
            return null;
        }
        File hdAdb = new File(bundlePath, "Contents/MacOS/hd-adb");
        return hdAdb.isFile() ? hdAdb.getAbsolutePath() : null;
    }

    /**
     * Directories where Tess4J/JNA may find libtesseract on macOS (bundle first, then Homebrew).
     */
    public static List<String> macNativeLibraryDirs() {
        List<String> dirs = new ArrayList<>();
        String override = System.getenv("FROSTGUARD_TESSERACT_LIB_DIR");
        if (override != null && !override.isBlank()) {
            dirs.add(override);
        }
        String wd = System.getProperty("user.dir", "");
        if (!wd.isBlank()) {
            dirs.add(wd + "/lib/tesseract/native");
            dirs.add(wd + "/lib/native/tesseract");
            dirs.add(wd + "/tools/tesseract/native/mac");
        }
        dirs.add("/opt/homebrew/lib");
        dirs.add("/usr/local/lib");
        return dirs;
    }

    public static void configureMacJnaLibraryPath() {
        if (!isMacOs()) {
            return;
        }
        String existing = System.getProperty("jna.library.path", "");
        List<String> parts = new ArrayList<>();
        if (existing != null && !existing.isBlank()) {
            parts.add(existing);
        }
        for (String dir : macNativeLibraryDirs()) {
            if (!parts.contains(dir) && Files.isDirectory(Paths.get(dir))) {
                parts.add(dir);
            }
        }
        if (!parts.isEmpty()) {
            System.setProperty("jna.library.path", String.join(File.pathSeparator, parts));
        }
    }

    private static String findBundledAdb() {
        String name = adbExecutableName();
        String wd = System.getProperty("user.dir");
        String[] candidates = {
                wd + "/lib/adb/" + name,
                wd + "/tools/adb/" + name,
                wd + "/tools/adb/mac/" + name,
                wd + "/packaging/desktop/target/input/lib/adb/" + name,
                wd + "/modules/desktop/target/lib/adb/" + name,
        };
        for (String candidate : candidates) {
            File f = new File(candidate);
            if (f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    private static String findAndroidSdkAdb() {
        List<String> roots = new ArrayList<>();
        String home = System.getenv("ANDROID_HOME");
        if (home != null && !home.isBlank()) {
            roots.add(home);
        }
        String sdkRoot = System.getenv("ANDROID_SDK_ROOT");
        if (sdkRoot != null && !sdkRoot.isBlank()) {
            roots.add(sdkRoot);
        }
        if (isMacOs()) {
            roots.add(System.getProperty("user.home") + "/Library/Android/sdk");
        }

        String exe = adbExecutableName();
        for (String root : roots) {
            Path adb = Paths.get(root, "platform-tools", exe);
            if (Files.isRegularFile(adb)) {
                return adb.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private static String findOnPath(String executable) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null && !pathEnv.isBlank()) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                File candidate = new File(dir, executable);
                if (candidate.isFile() && candidate.canExecute()) {
                    return candidate.getAbsolutePath();
                }
            }
        }

        if (isMacOs()) {
            for (String dir : new String[]{"/opt/homebrew/bin", "/usr/local/bin"}) {
                File candidate = new File(dir, executable);
                if (candidate.isFile()) {
                    return candidate.getAbsolutePath();
                }
            }
        }
        return null;
    }

    public static String resolveMacJava21Home() {
        if (!isMacOs()) {
            return null;
        }

        String javaHomeEnv = System.getenv("JAVA_HOME");
        if (looksLikeJava21Home(javaHomeEnv)) {
            return javaHomeEnv;
        }

        String[] candidates = {
                "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home",
                "/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home",
                "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home",
                "/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home",
        };
        for (String candidate : candidates) {
            if (looksLikeJava21Home(candidate)) {
                return candidate;
            }
        }

        String fromJavaHomeTool = readFirstLine(new ProcessBuilder("/usr/libexec/java_home", "-v", "21"));
        if (looksLikeJava21Home(fromJavaHomeTool)) {
            return fromJavaHomeTool;
        }
        return null;
    }

    private static boolean looksLikeJava21Home(String javaHome) {
        if (javaHome == null || javaHome.isBlank()) {
            return false;
        }
        File java = new File(javaHome, "bin/java");
        if (!java.isFile()) {
            return false;
        }
        String version = readAll(new ProcessBuilder(java.getAbsolutePath(), "-version"));
        return version != null && version.contains("version \"21");
    }

    private static String readFirstLine(ProcessBuilder processBuilder) {
        String output = readAll(processBuilder);
        if (output == null || output.isBlank()) {
            return null;
        }
        String[] lines = output.strip().split("\\R");
        return lines.length == 0 ? null : lines[0].trim();
    }

    private static String readAll(ProcessBuilder processBuilder) {
        try {
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
                return output.toString();
            }
        } catch (Exception ignored) {
            return null;
        }
    }
}
