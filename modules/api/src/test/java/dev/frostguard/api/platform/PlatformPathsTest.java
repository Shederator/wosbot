package dev.frostguard.api.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformPathsTest {

    @TempDir
    Path tempDir;

    @Test
    void adbExecutableNameMatchesOs() {
        if (PlatformPaths.isWindows()) {
            assertEquals("adb.exe", PlatformPaths.adbExecutableName());
        } else {
            assertEquals("adb", PlatformPaths.adbExecutableName());
        }
    }

    @Test
    void resolveAdbPathFindsBundledBinaryUnderLibAdb() throws Exception {
        Path libAdb = tempDir.resolve("lib").resolve("adb");
        Files.createDirectories(libAdb);
        Path adb = libAdb.resolve(PlatformPaths.adbExecutableName());
        Files.writeString(adb, "stub");
        adb.toFile().setExecutable(true);

        String previous = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
            String resolved = PlatformPaths.resolveAdbPath(null);
            assertEquals(adb.toAbsolutePath().toString(), resolved);
        } finally {
            System.setProperty("user.dir", previous);
        }
    }

    @Test
    void resolveBlueStacksHdAdbLooksInsideAppBundle() throws Exception {
        Path bundle = tempDir.resolve("BlueStacks.app");
        Path macOs = bundle.resolve("Contents").resolve("MacOS");
        Files.createDirectories(macOs);
        Path hdAdb = macOs.resolve("hd-adb");
        Files.writeString(hdAdb, "stub");
        hdAdb.toFile().setExecutable(true);

        String resolved = PlatformPaths.resolveBlueStacksHdAdb(bundle.toString());
        assertEquals(hdAdb.toAbsolutePath().toString(), resolved);
    }

    @Test
    void currentOsIsRecognized() {
        assertTrue(
                PlatformPaths.currentOs() == PlatformPaths.OperatingSystem.WINDOWS
                        || PlatformPaths.currentOs() == PlatformPaths.OperatingSystem.MACOS
                        || PlatformPaths.currentOs() == PlatformPaths.OperatingSystem.LINUX
                        || PlatformPaths.currentOs() == PlatformPaths.OperatingSystem.OTHER);
    }
}
