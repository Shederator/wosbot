package dev.frostguard.tasks.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.error.ADBConnectionException;
import dev.frostguard.engine.error.StopExecutionException;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;

class TaskDiagnosticSnapshotsTest {
    @TempDir
    Path workspace;

    @Test
    void rethrowsAWrappedAdbFailureInsteadOfRecordingADomainMiss() {
        ADBConnectionException adb = new ADBConnectionException("offline");

        assertThrows(ADBConnectionException.class, () -> TaskDiagnosticSnapshots.capture(
                () -> {
                    throw new RuntimeException("screencap failed", adb);
                },
                "crystallaboratory",
                "ocr-failed", new DiagnosticSnapshotStore(workspace)));
    }

    @Test
    void rethrowsAStopSignalFromTheCaptureItself() {
        assertThrows(StopExecutionException.class, () -> TaskDiagnosticSnapshots.capture(
                () -> {
                    throw new StopExecutionException("halt");
                },
                "mercenaryevent",
                "task-error", new DiagnosticSnapshotStore(workspace)));
    }

    @Test
    void reportsAnOrdinaryCaptureFailureAsUnavailable() {
        assertEquals("snapshot=unavailable; reason=RuntimeException",
                TaskDiagnosticSnapshots.capture(() -> {
                    throw new RuntimeException("io");
                }, "research", "queue-ocr", new DiagnosticSnapshotStore(workspace)));
        assertEquals("snapshot=unavailable; reason=no-frame",
                TaskDiagnosticSnapshots.capture(() -> null, "research", "queue-ocr",
                        new DiagnosticSnapshotStore(workspace)));
    }

    @Test
    void retainsNormalThirtyTwoBitScreencapWhenValidityUsesBytesPerPixel()
            throws IOException {
        RawImageData frame = RawImageData.capture(new byte[] {
                (byte) 0xff, 0, 0, (byte) 0xff,
                0, (byte) 0xff, 0, (byte) 0xff,
                0, 0, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff
        }, 2, 2, 32);
        assertEquals(32, frame.getColorDepth());
        assertEquals(4, frame.getFrameBytes().length / frame.pixelCount());
        assertTrue(frame.isValid());

        String result = TaskDiagnosticSnapshots.capture(
                () -> frame, "nomadicmerchant", "resource-claim", new DiagnosticSnapshotStore(workspace));

        assertTrue(result.startsWith("snapshot=logs/snapshot/nomadicmerchant/"), result);
        String relativePath = result.substring("snapshot=".length());
        var snapshot = workspace.resolve(relativePath);
        assertTrue(Files.isRegularFile(snapshot), snapshot.toString());
        BufferedImage saved = ImageIO.read(snapshot.toFile());
        assertNotNull(saved, "saved diagnostic must be a readable PNG");
        assertEquals(2, saved.getWidth());
        assertEquals(2, saved.getHeight());
    }
}
