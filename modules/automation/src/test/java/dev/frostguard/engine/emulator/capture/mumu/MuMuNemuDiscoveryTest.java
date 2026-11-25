package dev.frostguard.engine.emulator.capture.mumu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MuMuNemuDiscoveryTest {

    @Test
    void neverTreatsDarwinAsWindowsEvenWhenADllPathExists(@TempDir Path root) throws IOException {
        Path dll = root.resolve("nx_device/15.0/shell/sdk/external_renderer_ipc.dll");
        Files.createDirectories(dll.getParent());
        Files.writeString(dll, "not a native library");
        String previous = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Darwin");
            assertFalse(MuMuNemuDiscovery.isWindows());
            assertTrue(MuMuNemuDiscovery.discover(root).isEmpty());
            System.setProperty("os.name", "Linux");
            assertFalse(MuMuNemuDiscovery.isWindows());
            assertTrue(MuMuNemuDiscovery.discover(root).isEmpty());
        } finally {
            if (previous == null) System.clearProperty("os.name");
            else System.setProperty("os.name", previous);
        }
    }

    @Test
    void returnsEmptyOnNullOrNonExistentPath() {
        assertTrue(MuMuNemuDiscovery.discover(null).isEmpty());
        assertTrue(MuMuNemuDiscovery.discover(Path.of("non-existent-mumu-path-12345")).isEmpty());
    }

    @Test
    void discoversDllInNxDeviceDirectory(@TempDir Path tempDir) throws IOException {
        if (!MuMuNemuDiscovery.isWindows()) {
            return; // Discovery contract only triggers on Windows
        }

        Path dllDir = tempDir.resolve("nx_device/15.0/shell/sdk".replace('/', File.separatorChar));
        Files.createDirectories(dllDir);
        Path dllFile = dllDir.resolve("external_renderer_ipc.dll");
        Files.writeString(dllFile, "mock dll");

        Path nxMain = tempDir.resolve("nx_main");
        Files.createDirectories(nxMain);

        Optional<MuMuNemuDiscovery.NemuPaths> result = MuMuNemuDiscovery.discover(tempDir);
        assertTrue(result.isPresent());
        assertEquals(tempDir.toAbsolutePath(), result.get().root().toAbsolutePath());
        assertEquals(dllFile.toAbsolutePath(), result.get().dllPath().toAbsolutePath());
        assertTrue(result.get().searchPaths().contains(dllDir));
        assertTrue(result.get().searchPaths().contains(nxMain));
    }

    @Test
    void discoversDllWhenProvidedNxMainPath(@TempDir Path tempDir) throws IOException {
        if (!MuMuNemuDiscovery.isWindows()) {
            return;
        }

        Path dllDir = tempDir.resolve("nx_device/15.0/shell/sdk".replace('/', File.separatorChar));
        Files.createDirectories(dllDir);
        Path dllFile = dllDir.resolve("external_renderer_ipc.dll");
        Files.writeString(dllFile, "mock dll");

        Path nxMain = tempDir.resolve("nx_main");
        Files.createDirectories(nxMain);

        Optional<MuMuNemuDiscovery.NemuPaths> result = MuMuNemuDiscovery.discover(nxMain);
        assertTrue(result.isPresent());
        assertEquals(tempDir.toAbsolutePath(), result.get().root().toAbsolutePath());
        assertEquals(dllFile.toAbsolutePath(), result.get().dllPath().toAbsolutePath());
    }
}
