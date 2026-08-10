package dev.frostguard.engine.emulator.instance;

import dev.frostguard.engine.emulator.EmulatorInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Controls MuMu Player emulator instances through the {@code MuMuManager.exe}
 * CLI.  Handles lifecycle operations (boot / shutdown) and ADB serial
 * resolution using MuMu's port-mapping convention.
 */
public class MuMuEmulatorInstance extends EmulatorInstance {

    private static final Logger log = LoggerFactory.getLogger(MuMuEmulatorInstance.class);

    private static final int ADB_BASE_PORT = 16384;
    private static final int ADB_PORT_STRIDE = 32;
    private static final int PROCESS_TIMEOUT_SECS = 45;
    private static final String RUNNING_TOKEN = "state=start_finished";

    public MuMuEmulatorInstance(String executablePath) {
        super(executablePath);
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
    public void launchEmulator(String identifier) {
        executeManagerAction(identifier, "launch_player");
        log.info("Launch requested for MuMu instance {}", identifier);
    }

    @Override
    public void closeEmulator(String identifier) {
        executeManagerAction(identifier, "shutdown_player");
        log.info("Shutdown requested for MuMu instance {}", identifier);
    }

    @Override
    public boolean isRunning(String identifier) {
        try {
            Process proc = buildManagerProcess(identifier, "player_state");
            boolean running = scanOutputForToken(proc);
            proc.waitFor(30, TimeUnit.SECONDS);
            return running;
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

    private Process buildManagerProcess(String instanceId, String action)
            throws IOException {
        List<String> cmd = List.of(
                resolveManagerBinary().toString(),
                "api", "-v", instanceId, action);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(Paths.get(consolePath).getParent().toFile());
        return pb.start();
    }

    private void executeManagerAction(String instanceId, String action) {
        try {
            Process proc = buildManagerProcess(instanceId, action);
            proc.waitFor(PROCESS_TIMEOUT_SECS, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.error("MuMu action '{}' interrupted", action, ie);
        } catch (IOException ioe) {
            log.error("Failed to execute MuMu action '{}'", action, ioe);
        }
    }

    private boolean scanOutputForToken(Process proc) throws IOException {
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(RUNNING_TOKEN)) {
                    return true;
                }
            }
        }
        return false;
    }
}
