package dev.frostguard.tasks.lifecycle;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Retains one screen only when Initialize declares a terminal blocker.
 * Passive checks and recovered startup states must not call {@link #retain}.
 */
final class StartupBlockerSnapshots {

    static final String ACTIVITY = "initialize";
    static final String TYPE_INITIALIZE_BLOCKED = "initialize-blocked";
    static final String TYPE_PLAY_STORE_REDIRECT = "play-store-redirect";
    static final String TYPE_RESOURCE_DOWNLOAD_TIMEOUT = "resource-download-timeout";
    static final String TYPE_UPDATE_FOLLOW_UP = "update-follow-up";

    private StartupBlockerSnapshots() {
    }

    static Retention retain(
            DiagnosticSnapshotStore store,
            RawImageData decisionFrame,
            Supplier<RawImageData> freshCapture,
            String type,
            Instant capturedAt) {
        RawImageData frame = usable(decisionFrame) ? decisionFrame : null;
        String basis = "decision-frame";
        if (frame == null) {
            basis = "best-effort-fresh-capture";
            try {
                frame = freshCapture == null ? null : freshCapture.get();
            } catch (RuntimeException failure) {
                return Retention.captureFailed();
            }
            if (!usable(frame)) {
                return Retention.captureFailed();
            }
        }
        try {
            Optional<String> relativePath = store.write(frame, ACTIVITY, type, capturedAt);
            if (relativePath.isEmpty()) {
                return Retention.writeFailed();
            }
            return new Retention(relativePath.get(), basis);
        } catch (RuntimeException failure) {
            return Retention.writeFailed();
        }
    }

    private static boolean usable(RawImageData frame) {
        return frame != null && frame.isValid();
    }

    record Retention(String relativePath, String basis) {
        static Retention captureFailed() {
            return new Retention("", "capture-failed");
        }

        static Retention writeFailed() {
            return new Retention("", "write-failed");
        }

        boolean saved() {
            return relativePath != null && !relativePath.isBlank();
        }

        String logToken() {
            return saved() ? relativePath : "unavailable";
        }
    }
}
