package dev.frostguard.engine.emulator.instance;

import dev.frostguard.engine.emulator.EmulatorInstance;
import dev.frostguard.engine.emulator.BoundedProcessRunner;
import dev.frostguard.engine.emulator.EmulatorStopCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

// Drives MEmu Player via memuc.exe.  ADB port = 21503 + (index * 10).
public class MEmuEmulatorInstance extends EmulatorInstance {

    private static final Logger LOG = LoggerFactory.getLogger(MEmuEmulatorInstance.class);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration STATE_PROBE_TIMEOUT = Duration.ofSeconds(3);

    public MEmuEmulatorInstance(String path) { super(path); }

    @Override protected String getDeviceSerial(String id) {
        return "127.0.0.1:" + (21503 + Integer.parseInt(id) * 10);
    }

    @Override public void launchEmulator(String id) { memuc("start", id, 60); LOG.info("MEmu {} starting", id); }

    @Override public boolean isRunning(String id) {
        try {
            BoundedProcessRunner.ProcessResult result = BoundedProcessRunner.run(
                    process("isvmrunning", id), STATE_PROBE_TIMEOUT);
            String output = result.output().trim();
            return !result.timedOut() && result.exitCode() == 0
                    && !output.isEmpty() && !"Not Running".equalsIgnoreCase(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException failure) {
            LOG.warn("MEmu running-state probe could not start for {}: {}", id,
                    failure.getClass().getSimpleName());
        }
        return false;
    }

    @Override protected EmulatorStopCycle.CommandOutcome requestVendorStop(String id) throws InterruptedException {
        try {
            BoundedProcessRunner.ProcessResult result = BoundedProcessRunner.run(
                    process("stop", id), STOP_TIMEOUT);
            if (result.timedOut()) {
                return EmulatorStopCycle.CommandOutcome.failure("MEmu stop timed out");
            }
            if (result.exitCode() != 0) {
                return EmulatorStopCycle.CommandOutcome.failure(
                        "MEmu stop exited with code " + result.exitCode());
            }
            return new EmulatorStopCycle.CommandOutcome(true, "MEmu stop command exited successfully");
        } catch (IOException failure) {
            return EmulatorStopCycle.CommandOutcome.failure(
                    "MEmu stop could not start: " + failure.getClass().getSimpleName());
        }
    }

    @Override protected EmulatorStopCycle.Probe probeVendorState(String id) throws InterruptedException {
        try {
            BoundedProcessRunner.ProcessResult result = BoundedProcessRunner.run(
                    process("isvmrunning", id), STATE_PROBE_TIMEOUT);
            if (result.timedOut() || result.exitCode() != 0) {
                return EmulatorStopCycle.Probe.UNKNOWN;
            }
            String state = result.output().trim();
            if ("running".equalsIgnoreCase(state)) {
                return EmulatorStopCycle.Probe.RUNNING;
            }
            if ("not running".equalsIgnoreCase(state) || "stopped".equalsIgnoreCase(state)
                    || "not_running".equalsIgnoreCase(state)) {
                return EmulatorStopCycle.Probe.STOPPED;
            }
            return EmulatorStopCycle.Probe.UNKNOWN;
        } catch (IOException failure) {
            LOG.warn("MEmu state probe could not start for {}: {}", id,
                    failure.getClass().getSimpleName());
            return EmulatorStopCycle.Probe.UNKNOWN;
        }
    }

    private ProcessBuilder process(String action, String id) {
        return new ProcessBuilder(cli(), action, "-i", id)
                .directory(new File(consolePath).getParentFile());
    }

    private void memuc(String action, String id, int timeoutSec) {
        try {
            Process p = process(action, id).start();
            p.waitFor(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
          catch (IOException e)          { LOG.error("memuc {} failed", action, e); }
    }

    private String cli() { return Paths.get(consolePath, "memuc.exe").toString(); }
}
