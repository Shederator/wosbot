package dev.frostguard.engine.diagnostics;

import java.time.Instant;
import java.util.Optional;

/**
 * Optional desktop frame for any task. The global setting is checked here,
 * so a caller cannot force a capture while the checkbox is off.
 */
public final class DesktopSnapshot {

    private DesktopSnapshot() {
    }

    public static Optional<String> captureIfEnabled(String type) {
        if (!DesktopSnapshotSettings.enabled()) {
            return Optional.empty();
        }
        return DiagnosticSnapshotStore.forCurrentWorkspace()
                .captureDesktop(type, Instant.now());
    }
}
