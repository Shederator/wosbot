package dev.frostguard.tasks.lifecycle;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupBlockerSnapshotsTest {

    private static final Instant CAPTURED_AT = Instant.parse("2026-09-24T00:03:34.136Z");

    @TempDir
    Path workspace;

    @Test
    void terminalCapturePrefersTheDecisionFrameAndDoesNotTakeAnother() {
        DiagnosticSnapshotStore store = new DiagnosticSnapshotStore(workspace);
        AtomicInteger freshCaptures = new AtomicInteger();

        StartupBlockerSnapshots.Retention retention = StartupBlockerSnapshots.retain(
                store,
                frame(),
                () -> {
                    freshCaptures.incrementAndGet();
                    return frame();
                },
                StartupBlockerSnapshots.TYPE_INITIALIZE_BLOCKED,
                CAPTURED_AT);

        assertTrue(retention.saved());
        assertEquals("decision-frame", retention.basis());
        assertEquals(0, freshCaptures.get());
        assertEquals(
                "logs/snapshot/20260924T000334.136Z-initialize-initialize-blocked.png",
                retention.relativePath());
        assertTrue(Files.exists(workspace.resolve(retention.relativePath())));
    }

    @Test
    void missingDecisionFrameUsesOneBestEffortCapture() {
        DiagnosticSnapshotStore store = new DiagnosticSnapshotStore(workspace);
        AtomicInteger freshCaptures = new AtomicInteger();

        StartupBlockerSnapshots.Retention retention = StartupBlockerSnapshots.retain(
                store,
                null,
                () -> {
                    freshCaptures.incrementAndGet();
                    return frame();
                },
                StartupBlockerSnapshots.TYPE_INITIALIZE_BLOCKED,
                CAPTURED_AT);

        assertTrue(retention.saved());
        assertEquals(1, freshCaptures.get());
        assertEquals("best-effort-fresh-capture", retention.basis());
    }

    @Test
    void captureFailureStaysEmptyAndDoesNotThrow() {
        DiagnosticSnapshotStore store = new DiagnosticSnapshotStore(workspace);

        StartupBlockerSnapshots.Retention retention = StartupBlockerSnapshots.retain(
                store,
                null,
                () -> {
                    throw new IllegalStateException("device offline serial=127.0.0.1:16384");
                },
                StartupBlockerSnapshots.TYPE_INITIALIZE_BLOCKED,
                CAPTURED_AT);

        assertFalse(retention.saved());
        assertEquals("capture-failed", retention.basis());
        assertEquals("unavailable", retention.logToken());
        assertFalse(retention.basis().contains("127.0.0.1"));
    }

    private static RawImageData frame() {
        return RawImageData.capture(new byte[2 * 2 * 4], 2, 2, 4);
    }
}
