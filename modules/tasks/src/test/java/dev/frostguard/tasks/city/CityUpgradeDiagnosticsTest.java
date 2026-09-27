package dev.frostguard.tasks.city;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;
import dev.frostguard.engine.error.StopExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CityUpgradeDiagnosticsTest {
    @TempDir Path workspace;

    @Test
    void retainsDecisionFrameBeforeNavigationAndClearsItForRecoveryFailure() throws Exception {
        var diagnostics = new CityUpgradeDiagnostics();
        var store = new DiagnosticSnapshotStore(workspace);
        diagnostics.begin(1, 2);
        diagnostics.stage("recognize-building");
        diagnostics.decision(frame(), "upgrade=MISS; train=not-searched");
        String retained = diagnostics.retain(() -> store, () -> { fail("Must reuse decision frame"); return null; },
                "control-not-recognized");
        assertTrue(retained.contains("snapshot=logs/snapshot/"));
        assertTrue(retained.contains("snapshotBasis=decision-frame"));
        assertTrue(diagnostics.context().contains("queue=1; attempt=2"));
        assertTrue(diagnostics.context().contains("train=not-searched"));
        diagnostics.stage("recover-any");
        assertTrue(diagnostics.retain(() -> store, CityUpgradeDiagnosticsTest::frame, "root-recovery-failed")
                .contains("snapshotBasis=best-effort-fresh-capture"));
        try (var files = Files.list(store.directory())) { assertEquals(2, files.count()); }
    }

    @Test
    void writeFailureDoesNotReplaceOriginalOrPreventRecovery() throws Exception {
        Path notDirectory = Files.writeString(workspace.resolve("file"), "occupied");
        var diagnostics = new CityUpgradeDiagnostics();
        var original = new IllegalStateException("missing button");
        List<String> events = new ArrayList<>();
        assertSame(original, assertThrows(IllegalStateException.class, () -> CityUpgradeFlow.execute(
                () -> { throw original; }, new CityUpgradeFlow.Recovery() {
                    public void retainFailure(String type) {
                        events.add(diagnostics.retain(() -> new DiagnosticSnapshotStore(notDirectory),
                                CityUpgradeDiagnosticsTest::frame, type));
                    }
                    public void recoverRoot() { events.add("root"); }
                    public void reportRecovery(boolean recovered, RuntimeException failure, RuntimeException recoveryFailure) {}
                })));
        assertEquals(List.of("snapshot=unavailable; snapshotBasis=write-failed", "root"), events);
    }

    @Test
    void captureFailureIsReportedButStopSignalIsPreserved() {
        var diagnostics = new CityUpgradeDiagnostics();
        assertEquals("snapshot=unavailable; snapshotBasis=capture-failed", diagnostics.retain(
                () -> new DiagnosticSnapshotStore(workspace), () -> { throw new IllegalStateException(); }, "failure"));
        assertThrows(StopExecutionException.class, () -> diagnostics.retain(
                () -> new DiagnosticSnapshotStore(workspace), () -> { throw StopExecutionException.userCancelled(); }, "failure"));
    }

    private static RawImageData frame() { return RawImageData.capture(new byte[16], 2, 2, 32); }
}
