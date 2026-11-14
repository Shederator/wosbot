package dev.frostguard.engine.diagnostics;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorInstance;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

/** Captures best-effort diagnostic frames when an emulator shutdown is anomalous. */
public final class EmulatorShutdownDiagnostics {

    private static final String ACTIVITY = "emulator-stop";
    private static final String TYPE = "shutdown-anomaly";

    private final DiagnosticSnapshotStore snapshotStore;
    private final Function<String, RawImageData> emulatorCapture;

    EmulatorShutdownDiagnostics(
            DiagnosticSnapshotStore snapshotStore,
            Function<String, RawImageData> emulatorCapture) {
        this.snapshotStore = snapshotStore;
        this.emulatorCapture = emulatorCapture;
    }

    /**
     * Attempts one emulator frame and, when enabled, one desktop frame. This
     * method is intended to run before any shutdown fallback is issued.
     */
    public static CaptureResult capture(EmulatorInstance emulator, String instanceId) {
        return new EmulatorShutdownDiagnostics(
                DiagnosticSnapshotStore.forCurrentWorkspace(), emulator::captureConnectedScreenshot)
                .capture(instanceId);
    }

    CaptureResult capture(String instanceId) {
        if (!snapshotStore.isEnabled()) {
            return new CaptureResult(Optional.empty(), Optional.empty(), "disabled", "disabled");
        }
        Instant capturedAt = Instant.now();
        Optional<String> emulatorPath = Optional.empty();
        Optional<String> desktopPath = Optional.empty();
        String emulatorFailure = null;
        String desktopFailure = null;

        try {
            RawImageData frame = emulatorCapture.apply(instanceId);
            emulatorPath = snapshotStore.writeWithoutDesktop(frame, ACTIVITY, TYPE, capturedAt);
            if (emulatorPath.isEmpty()) {
                emulatorFailure = "emulator frame was unavailable or could not be saved";
            }
        } catch (RuntimeException failure) {
            emulatorFailure = "emulator capture failed (" + failure.getClass().getSimpleName() + ")";
        }

        try {
            desktopPath = snapshotStore.captureDesktop(TYPE, capturedAt);
            if (desktopPath.isEmpty()) {
                desktopFailure = "desktop frame was disabled, unavailable, or could not be saved";
            }
        } catch (RuntimeException failure) {
            desktopFailure = "desktop capture failed (" + failure.getClass().getSimpleName() + ")";
        }

        return new CaptureResult(emulatorPath, desktopPath, emulatorFailure, desktopFailure);
    }

    /** Workspace-relative paths and any reasons a requested capture was unavailable. */
    public record CaptureResult(
            Optional<String> emulatorPath,
            Optional<String> desktopPath,
            String emulatorFailure,
            String desktopFailure) {
    }
}
