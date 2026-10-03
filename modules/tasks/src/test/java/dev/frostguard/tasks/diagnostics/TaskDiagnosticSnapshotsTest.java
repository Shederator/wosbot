package dev.frostguard.tasks.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.engine.error.ADBConnectionException;
import dev.frostguard.engine.error.StopExecutionException;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;

import java.nio.file.Path;

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
}
