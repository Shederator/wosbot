package dev.frostguard.tasks.lifecycle;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.IOException;
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
    void retainsDecodableBitDepthDecisionFrameWithoutAnotherCapture() throws IOException {
        DiagnosticSnapshotStore store = new DiagnosticSnapshotStore(workspace);
        AtomicInteger freshCaptures = new AtomicInteger();
        RawImageData decisionFrame = frameWithInvalidLegacyVerdict();
        assertFalse(decisionFrame.isValid());

        StartupBlockerSnapshots.Retention retention = StartupBlockerSnapshots.retain(
                store,
                decisionFrame,
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
                "logs/snapshot/initialize/20260924T000334.136Z-initialize-blocked.png",
                retention.relativePath());
        assertTrue(Files.exists(workspace.resolve(retention.relativePath())));
        assertEquals(2, ImageIO.read(workspace.resolve(retention.relativePath()).toFile()).getWidth());
    }

    @Test
    void missingDecisionFrameUsesOneBestEffortBitDepthCapture() {
        DiagnosticSnapshotStore store = new DiagnosticSnapshotStore(workspace);
        AtomicInteger freshCaptures = new AtomicInteger();
        RawImageData freshFrame = frame(16);
        assertTrue(freshFrame.isValid());

        StartupBlockerSnapshots.Retention retention = StartupBlockerSnapshots.retain(
                store,
                null,
                () -> {
                    freshCaptures.incrementAndGet();
                    return freshFrame;
                },
                StartupBlockerSnapshots.TYPE_INITIALIZE_BLOCKED,
                CAPTURED_AT);

        assertTrue(retention.saved());
        assertEquals(1, freshCaptures.get());
        assertEquals("best-effort-fresh-capture", retention.basis());
    }

    @Test
    void undecodableDecisionFrameUsesOneFreshBitDepthCapture() {
        DiagnosticSnapshotStore store = new DiagnosticSnapshotStore(workspace);
        AtomicInteger freshCaptures = new AtomicInteger();
        RawImageData truncatedFrame = RawImageData.capture(new byte[1], 2, 2, 32);

        StartupBlockerSnapshots.Retention retention = StartupBlockerSnapshots.retain(
                store,
                truncatedFrame,
                () -> {
                    freshCaptures.incrementAndGet();
                    return frame();
                },
                StartupBlockerSnapshots.TYPE_INITIALIZE_BLOCKED,
                CAPTURED_AT);

        assertTrue(retention.saved());
        assertEquals("best-effort-fresh-capture", retention.basis());
        assertEquals(1, freshCaptures.get());
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
        return frame(32);
    }

    private static RawImageData frame(int bitsPerPixel) {
        return RawImageData.capture(new byte[2 * 2 * (bitsPerPixel / 8)], 2, 2, bitsPerPixel);
    }

    private static RawImageData frameWithInvalidLegacyVerdict() {
        RawImageData frame = new RawImageData() {
            @Override
            public boolean isValid() {
                return false;
            }
        };
        frame.setFrameBytes(new byte[2 * 2 * 4]);
        frame.setScanlineWidth(2);
        frame.setScanlineCount(2);
        frame.setColorDepth(32);
        return frame;
    }
}
