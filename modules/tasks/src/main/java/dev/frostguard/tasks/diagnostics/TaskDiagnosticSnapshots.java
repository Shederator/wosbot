package dev.frostguard.tasks.diagnostics;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;
import dev.frostguard.engine.emulator.EmulatorController;

import java.time.Instant;
import java.util.function.Supplier;

/** Captures terminal task failures to the workspace diagnostic snapshot store. */
public final class TaskDiagnosticSnapshots {

    private TaskDiagnosticSnapshots() {
    }

    public static String capture(EmulatorController emulator, String emulatorNumber, String activity, String type) {
        return capture(() -> emulator.captureScreen(emulatorNumber), activity, type);
    }

    static String capture(Supplier<RawImageData> takeFrame, String activity, String type) {
        return capture(takeFrame, activity, type, DiagnosticSnapshotStore.forCurrentWorkspace());
    }

    static String capture(Supplier<RawImageData> takeFrame, String activity, String type,
            DiagnosticSnapshotStore store) {
        try {
            if (!store.isEnabled()) {
                return "snapshot=disabled";
            }
            RawImageData frame = takeFrame.get();
            if (frame == null) {
                return "snapshot=unavailable; reason=no-frame";
            }
            return store.write(frame, activity, type, Instant.now())
                    .map(path -> "snapshot=" + path)
                    .orElse("snapshot=unavailable; reason=write-failed");
        } catch (RuntimeException failure) {
            TaskControlSignals.rethrowControlSignal(failure);
            return "snapshot=unavailable; reason=" + failure.getClass().getSimpleName();
        }
    }
}
