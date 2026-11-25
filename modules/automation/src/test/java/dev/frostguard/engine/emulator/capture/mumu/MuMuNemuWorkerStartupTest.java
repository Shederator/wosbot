package dev.frostguard.engine.emulator.capture.mumu;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.capture.AdbScreenCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MuMuNemuWorkerStartupTest {
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void actualWorkerLoadFailureFallsBackToTheExactInstanceAndReapsTheChild(@TempDir Path root) throws Exception {
        Path dll = root.resolve("nx_device/15.0/shell/sdk/external_renderer_ipc.dll");
        Files.createDirectories(dll.getParent());
        Files.writeString(dll, "deliberately invalid native library");
        var frame = RawImageData.capture(new byte[16], 2, 2, 32);
        List<String> adbInstances = new ArrayList<>();
        var nativeCapture = new MuMuNemuProcessCapture(root, "2");
        try (var composite = new MuMuNemuScreenCapture(nativeCapture, new AdbScreenCapture(instance -> {
            adbInstances.add(instance);
            return frame;
        }))) {
            assertTrue(nativeCapture.isAvailable("2"));
            var result = composite.capture("2");
            assertSame(frame, result.image());
            assertEquals("adb", result.backend());
            assertEquals(List.of("2"), adbInstances);
            for (String name : List.of("workerProcess", "socket", "in", "out")) {
                var field = MuMuNemuProcessCapture.class.getDeclaredField(name);
                field.setAccessible(true);
                assertNull(field.get(nativeCapture), "Failed startup must clean up " + name);
            }
        }
    }
}
