package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopJarLocatorTest {

    @TempDir
    Path tempDir;

    @Test
    void findsDesktopJarFromNestedSourceLauncherDirectory() throws Exception {
        Path target = Files.createDirectories(tempDir.resolve("modules/desktop/target"));
        Path expected = Files.createFile(target.resolve("frostguard-desktop-3.0.2.jar"));
        Path launcherDirectory = Files.createDirectories(
                tempDir.resolve("packaging/desktop/src/main/windows"));

        assertEquals(expected.toAbsolutePath().normalize(),
                DesktopJarLocator.findFrom(launcherDirectory).orElseThrow());
    }

    @Test
    void prefersMostRecentlyBuiltRegularArtifact() throws Exception {
        Path target = Files.createDirectories(tempDir.resolve("modules/desktop/target"));
        Path stale = Files.createFile(target.resolve("frostguard-desktop-10.0.0.jar"));
        Path current = Files.createFile(target.resolve("frostguard-desktop-3.0.2.jar"));
        Files.setLastModifiedTime(stale, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(current, FileTime.fromMillis(2_000));
        Files.createFile(target.resolve("frostguard-desktop-99.0.0-sources.jar"));
        Files.createFile(target.resolve("frostguard-desktop-99.0.0-shaded.jar"));

        assertEquals(current.toAbsolutePath().normalize(),
                DesktopJarLocator.findFrom(tempDir).orElseThrow());
    }

    @Test
    void returnsEmptyWhenNoDesktopArtifactExists() {
        assertTrue(DesktopJarLocator.findFrom(tempDir).isEmpty());
    }
}
