package dev.frostguard.engine.emulator.instance;

import dev.frostguard.engine.emulator.EmulatorInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Controls MuMu Player emulator instances through the {@code MuMuManager.exe}
 * CLI.  Handles lifecycle operations (boot / shutdown) and ADB serial
 * resolution using MuMu's port-mapping convention.
 */
public class MuMuEmulatorInstance extends EmulatorInstance {

    private static final Logger log = LoggerFactory.getLogger(MuMuEmulatorInstance.class);

    private static final int ADB_BASE_PORT = 16384;
    private static final int ADB_PORT_STRIDE = 32;
    private final MuMuManagerClient manager;

    public MuMuEmulatorInstance(String executablePath) {
        this(executablePath, new MuMuManagerClient(Path.of(executablePath)));
    }

    MuMuEmulatorInstance(String executablePath, MuMuManagerClient manager) {
        super(executablePath);
        this.manager = manager;
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
        executeManagerAction(identifier, true);
    }

    @Override
    public void closeEmulator(String identifier) {
        executeManagerAction(identifier, false);
    }

    @Override
    public boolean isRunning(String identifier) {
        try {
            MuMuManagerClient.State state = manager.state(identifier);
            if (state == MuMuManagerClient.State.UNKNOWN) {
                log.warn("MuMu state unknown for instance {}; running is not confirmed", identifier);
            }
            return state == MuMuManagerClient.State.RUNNING;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.debug("State probe interrupted for MuMu #{}", identifier);
        } catch (IOException ioe) {
            log.error("Cannot query MuMu state for instance #{}", identifier, ioe);
        }
        return false;
    }

    // ── internal helpers ─────────────────────────────────────────────

    private void executeManagerAction(String instanceId, boolean launch) {
        String action = launch ? "launch" : "shutdown";
        try {
            manager.request(instanceId, launch);
            invalidateAllCaches(instanceId);
            log.info("MuMu {} request accepted for instance {}; transition completion is not confirmed",
                    action, instanceId);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MuMu " + action + " interrupted for instance " + instanceId, ie);
        } catch (IOException ioe) {
            throw new IllegalStateException("MuMu " + action + " failed for instance " + instanceId
                    + ": " + ioe.getMessage(), ioe);
        }
    }

}
