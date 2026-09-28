package dev.frostguard.tasks.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import dev.frostguard.engine.error.ADBConnectionException;
import dev.frostguard.engine.error.StopExecutionException;

class TaskDiagnosticSnapshotsTest {

    @Test
    void rethrowsAWrappedAdbFailureInsteadOfRecordingADomainMiss() {
        ADBConnectionException adb = new ADBConnectionException("offline");

        assertThrows(ADBConnectionException.class, () -> TaskDiagnosticSnapshots.capture(
                () -> {
                    throw new RuntimeException("screencap failed", adb);
                },
                "crystallaboratory",
                "ocr-failed"));
    }

    @Test
    void rethrowsAStopSignalFromTheCaptureItself() {
        assertThrows(StopExecutionException.class, () -> TaskDiagnosticSnapshots.capture(
                () -> {
                    throw new StopExecutionException("halt");
                },
                "mercenaryevent",
                "task-error"));
    }

    @Test
    void reportsAnOrdinaryCaptureFailureAsUnavailable() {
        assertEquals("snapshot=unavailable; reason=RuntimeException",
                TaskDiagnosticSnapshots.capture(() -> {
                    throw new RuntimeException("io");
                }, "research", "queue-ocr"));
        assertEquals("snapshot=unavailable; reason=no-frame",
                TaskDiagnosticSnapshots.capture(() -> null, "research", "queue-ocr"));
    }
}
