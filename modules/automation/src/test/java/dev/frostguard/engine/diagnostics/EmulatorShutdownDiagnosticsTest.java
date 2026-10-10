package dev.frostguard.engine.diagnostics;

import dev.frostguard.api.domain.RawImageData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmulatorShutdownDiagnosticsTest {

    @TempDir
    Path workspace;

    @Test
    void capturesDesktopWhenEmulatorFrameCannotBeSaved() throws IOException {
        AtomicInteger desktopAttempts = new AtomicInteger();
        DiagnosticSnapshotStore store = new DiagnosticSnapshotStore(
                workspace,
                () -> true,
                () -> {
                    desktopAttempts.incrementAndGet();
                    return Optional.of(desktopImage());
                });
        EmulatorShutdownDiagnostics diagnostics = new EmulatorShutdownDiagnostics(
                store, ignored -> RawImageData.capture(new byte[0], 0, 0, 0));

        EmulatorShutdownDiagnostics.CaptureResult result = diagnostics.capture("instance-1");

        assertTrue(result.emulatorPath().isEmpty());
        assertTrue(result.emulatorFailure() != null);
        assertEquals(1, desktopAttempts.get());
        assertTrue(result.desktopPath().orElseThrow().startsWith("logs/snapshot/desktop/"));
        assertTrue(Files.exists(workspace.resolve(result.desktopPath().orElseThrow())));
    }

    @Test
    void successfulEmulatorWriteCapturesDesktopOnce() throws IOException {
        AtomicInteger desktopAttempts = new AtomicInteger();
        DiagnosticSnapshotStore store = new DiagnosticSnapshotStore(
                workspace,
                () -> true,
                () -> {
                    desktopAttempts.incrementAndGet();
                    return Optional.of(desktopImage());
                });
        RawImageData normalScreencap = frame(2, 2);
        assertFalse(normalScreencap.isValid(), "the test simulates rejected validity metadata");
        EmulatorShutdownDiagnostics diagnostics = new EmulatorShutdownDiagnostics(
                store, ignored -> normalScreencap);

        EmulatorShutdownDiagnostics.CaptureResult result = diagnostics.capture("instance-0");

        assertTrue(result.emulatorPath().orElseThrow().startsWith("logs/snapshot/emulatorstop/"));
        assertTrue(result.desktopPath().orElseThrow().startsWith("logs/snapshot/desktop/"));
        assertEquals(1, desktopAttempts.get());
        assertEquals(1, pngCount(workspace.resolve("logs/snapshot/desktop")));
    }

    private static BufferedImage desktopImage() {
        return new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
    }

    private static RawImageData frame(int width, int height) {
        RawImageData frame = new RawImageData() {
            @Override
            public boolean isValid() {
                return false;
            }
        };
        frame.setFrameBytes(new byte[width * height * 4]);
        frame.setScanlineWidth(width);
        frame.setScanlineCount(height);
        frame.setColorDepth(32);
        return frame;
    }

    private static long pngCount(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".png")).count();
        }
    }
}
