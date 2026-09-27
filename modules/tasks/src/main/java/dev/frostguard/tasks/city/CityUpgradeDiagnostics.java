package dev.frostguard.tasks.city;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Evidence is scoped to one attempt; navigation invalidates any earlier decision frame. */
final class CityUpgradeDiagnostics {
    private RawImageData frame;
    private Instant capturedAt;
    private String stage = "queue-analysis";
    private final Map<String, String> evidence = new LinkedHashMap<>();
    private int queue;
    private int attempt;

    void begin(int queue, int attempt) {
        this.queue = queue;
        this.attempt = attempt;
        stage("open-city");
        evidence.clear();
    }

    void stage(String stage) {
        this.stage = stage;
        frame = null;
        capturedAt = null;
    }

    void decision(RawImageData frame, String evidence) {
        this.frame = frame;
        this.capturedAt = Instant.now();
        this.evidence.put(stage, evidence);
    }

    void observation(String evidence) {
        this.evidence.put(stage, evidence);
    }

    String context() {
        return "queue=" + queue + "; attempt=" + attempt + "; stage=" + stage + "; evidence=" + evidence;
    }

    String retain(Supplier<DiagnosticSnapshotStore> store, Supplier<RawImageData> capture, String type) {
        String basis = "decision-frame";
        RawImageData retained = frame;
        Instant timestamp = capturedAt;
        // The backend uses bits per pixel (32/16); RawImageData.isValid assumes bytes.
        // Let the shared snapshot writer validate the retained frame's actual encoding.
        if (retained == null) {
            basis = "best-effort-fresh-capture";
            try {
                retained = capture.get();
                timestamp = Instant.now();
            } catch (RuntimeException failure) {
                CityUpgradeFlow.rethrowControlSignal(failure);
                return "snapshot=unavailable; snapshotBasis=capture-failed";
            }
        }
        try {
            var saved = store.get().write(retained, "cityupgrade", type, timestamp);
            return "snapshot=" + saved.orElse("unavailable")
                    + "; snapshotBasis=" + (saved.isPresent() ? basis : "write-failed");
        } catch (RuntimeException failure) {
            CityUpgradeFlow.rethrowControlSignal(failure);
            return "snapshot=unavailable; snapshotBasis=write-failed";
        }
    }
}
