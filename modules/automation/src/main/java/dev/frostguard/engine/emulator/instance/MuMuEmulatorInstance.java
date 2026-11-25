package dev.frostguard.engine.emulator.instance;

import dev.frostguard.api.configs.CaptureMethodEnum;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.emulator.BoundedProcessRunner;
import dev.frostguard.engine.emulator.EmulatorInstance;
import dev.frostguard.engine.emulator.capture.AdbScreenCapture;
import dev.frostguard.engine.emulator.capture.CaptureResult;
import dev.frostguard.engine.emulator.capture.ScreenCapture;
import dev.frostguard.engine.emulator.capture.ScreenCaptureException;
import dev.frostguard.engine.emulator.capture.mumu.MuMuNemuScreenCapture;
import dev.frostguard.engine.emulator.capture.mumu.NemuTouchInput;
import dev.frostguard.engine.error.ADBConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Controls MuMu Player emulator instances through the {@code MuMuManager.exe}
 * CLI.  Handles lifecycle operations (boot / shutdown) and ADB serial
 * resolution using MuMu's port-mapping convention.
 */
public class MuMuEmulatorInstance extends EmulatorInstance {

    private static final Logger log = LoggerFactory.getLogger(MuMuEmulatorInstance.class);

    private static final int ADB_BASE_PORT = 16384;
    private static final int ADB_PORT_STRIDE = 32;
    private static final Duration STATE_PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration ACTION_TIMEOUT = Duration.ofSeconds(45);
    private static final String RUNNING_TOKEN = "state=start_finished";

    private final ConcurrentHashMap<String, ScreenCapture> captureBackends = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> effectiveCaptureBackends = new ConcurrentHashMap<>();
    private final CaptureMethodEnum captureMethod;
    private final boolean nativeTouchEnabled;
    private final ConcurrentHashMap<String, String> effectiveInputBackends = new ConcurrentHashMap<>();

    public MuMuEmulatorInstance(String executablePath) {
        this(executablePath, CaptureMethodEnum.ADB);
    }

    public MuMuEmulatorInstance(String executablePath, CaptureMethodEnum captureMethod) {
        this(executablePath, captureMethod, false);
    }

    public MuMuEmulatorInstance(String executablePath, CaptureMethodEnum captureMethod, boolean nativeTouchEnabled) {
        super(executablePath);
        this.captureMethod = java.util.Objects.requireNonNull(captureMethod, "captureMethod");
        this.nativeTouchEnabled = nativeTouchEnabled;
    }

    /**
     * MuMu ADB port formula: {@code 16384 + index × 32}.
     */
    @Override
    protected String getDeviceSerial(String identifier) {
        int idx = Integer.parseInt(identifier);
        return "127.0.0.1:" + (ADB_BASE_PORT + idx * ADB_PORT_STRIDE);
    }

    @Override
    public RawImageData captureScreenshot(String idx) {
        if (captureMethod == CaptureMethodEnum.ADB) {
            return captureAdbScreenshot(idx);
        }
        ScreenCapture capture = captureBackends.computeIfAbsent(idx, this::createCaptureBackend);
        try {
            CaptureResult result = capture.capture(idx);
            String previous = effectiveCaptureBackends.put(idx, result.backend());
            if (!result.backend().equals(previous)) {
                log.info("Screenshot backend for instance {}: {} (requested={})",
                        idx, result.backend(), captureMethod.name());
            }
            RawImageData frame = result.image();
            recordLastFrame(idx, frame);
            return frame;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ADBConnectionException.forDevice(getDeviceSerial(idx), "Capture interrupted", ie);
        } catch (ScreenCaptureException failure) {
            throw ADBConnectionException.forDevice(getDeviceSerial(idx), "Capture failed: " + failure.getMessage(), failure);
        }
    }

    protected RawImageData captureAdbScreenshot(String instanceId) {
        return super.captureScreenshot(instanceId);
    }

    protected ScreenCapture createCaptureBackend(String instanceId) {
        return new MuMuNemuScreenCapture(Paths.get(consolePath), instanceId,
                new AdbScreenCapture(this::captureAdbScreenshot));
    }

    @Override
    protected boolean tryNativeTouch(String instance, PointData from, PointData to, int durationMs) {
        if (Thread.currentThread().isInterrupted()) {
            throw ADBConnectionException.forDevice(getDeviceSerial(instance), "Touch cancelled");
        }
        boolean nativeUsed = false;
        if (nativeTouchEnabled) {
            ScreenCapture capture = captureBackends.computeIfAbsent(instance, this::createCaptureBackend);
            ScreenCapture primary = capture instanceof MuMuNemuScreenCapture composite ? composite.getPrimary() : capture;
            if (primary instanceof NemuTouchInput touch) {
                try {
                    nativeUsed = touch.tryTouch(instance, from.getX(), from.getY(), to.getX(), to.getY(), durationMs);
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    throw ADBConnectionException.forDevice(getDeviceSerial(instance), "Touch cancelled; not replayed", cancelled);
                } catch (ScreenCaptureException failure) {
                    throw ADBConnectionException.forDevice(getDeviceSerial(instance), failure.getMessage(), failure);
                }
            }
        }
        String effective = nativeUsed ? "mumu_ipc" : "adb";
        if (!effective.equals(effectiveInputBackends.put(instance, effective))) {
            log.info("Touch backend for instance {}: {} (nativeRequested={})", instance, effective, nativeTouchEnabled);
        }
        return nativeUsed;
    }

    @Override
    public void closeCaptureSessions() {
        IllegalStateException failure = null;
        for (var entry : captureBackends.entrySet()) {
            try {
                entry.getValue().close();
                captureBackends.remove(entry.getKey(), entry.getValue());
                effectiveCaptureBackends.remove(entry.getKey());
                effectiveInputBackends.remove(entry.getKey());
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = new IllegalStateException("MuMu capture worker cleanup was incomplete");
                }
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void launchEmulator(String identifier) {
        executeManagerAction(identifier, "launch_player");
        log.info("Launch requested for MuMu instance {}", identifier);
    }

    @Override
    public void closeEmulator(String identifier) {
        ScreenCapture sc = captureBackends.remove(identifier);
        effectiveCaptureBackends.remove(identifier);
        effectiveInputBackends.remove(identifier);
        if (sc != null) {
            try {
                sc.close();
            } catch (Exception e) {
                log.debug("Error closing capture backend for instance {}", identifier, e);
            }
        }
        executeManagerAction(identifier, "shutdown_player");
        log.info("Shutdown requested for MuMu instance {}", identifier);
    }

    @Override
    public boolean isRunning(String identifier) {
        try {
            return readRunningState(
                    buildManagerProcess(identifier, "player_state"), STATE_PROBE_TIMEOUT);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.debug("State probe interrupted for MuMu #{}", identifier);
        } catch (IOException ioe) {
            log.error("Cannot query MuMu state for instance #{}", identifier, ioe);
        }
        return false;
    }

    // ── internal helpers ─────────────────────────────────────────────

    private Path resolveManagerBinary() {
        return Paths.get(consolePath, "MuMuManager.exe");
    }

    private ProcessBuilder buildManagerProcess(String instanceId, String action) {
        List<String> cmd = List.of(
                resolveManagerBinary().toString(),
                "api", "-v", instanceId, action);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(Paths.get(consolePath).getParent().toFile());
        return pb;
    }

    private void executeManagerAction(String instanceId, String action) {
        try {
            BoundedProcessRunner.ProcessResult result = BoundedProcessRunner.run(
                    buildManagerProcess(instanceId, action), ACTION_TIMEOUT);
            if (result.timedOut()) {
                log.error("MuMu action '{}' timed out after {} seconds; killed the manager process",
                        action, ACTION_TIMEOUT.toSeconds());
            } else if (result.exitCode() != 0) {
                log.error("MuMu action '{}' exited with code {}", action, result.exitCode());
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.debug("MuMu action '{}' interrupted; killed the manager process", action);
        } catch (IOException ioe) {
            log.error("Failed to execute MuMu action '{}'", action, ioe);
        }
    }

    static boolean readRunningState(ProcessBuilder managerProcess, Duration timeout)
            throws IOException, InterruptedException {
        BoundedProcessRunner.ProcessResult result = BoundedProcessRunner.run(managerProcess, timeout);
        if (result.timedOut()) {
            log.warn("MuMu state probe timed out after {} seconds; killed the manager process",
                    timeout.toSeconds());
            return false;
        }
        return result.exitCode() == 0 && result.output().contains(RUNNING_TOKEN);
    }
}
