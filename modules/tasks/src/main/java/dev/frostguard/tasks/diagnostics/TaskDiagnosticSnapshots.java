package dev.frostguard.tasks.diagnostics;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;
import dev.frostguard.engine.error.StopExecutionException;
import dev.frostguard.engine.emulator.EmulatorController;
import java.time.Instant;

/** Captures terminal task failures to the workspace diagnostic snapshot store. */
public final class TaskDiagnosticSnapshots {

    private TaskDiagnosticSnapshots() {
    }

    public static String capture(EmulatorController emulator, String emulatorNumber, String activity, String type) {
        try {
            RawImageData frame = emulator.captureScreen(emulatorNumber);
            if (frame == null) {
                return "snapshot=unavailable; reason=no-frame";
            }
            return DiagnosticSnapshotStore.forCurrentWorkspace()
                    .write(frame, activity, type, Instant.now())
                    .map(path -> "snapshot=" + path)
                    .orElse("snapshot=unavailable; reason=write-failed");
        } catch (RuntimeException failure) {
            if (failure instanceof StopExecutionException stop) {
                throw stop;
            }
            return "snapshot=unavailable; reason=" + failure.getClass().getSimpleName();
        }
    }
}
