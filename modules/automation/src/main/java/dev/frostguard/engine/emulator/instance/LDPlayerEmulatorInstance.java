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

// Drives LDPlayer via its ldconsole.exe CLI.  ADB port = 5555 + (index * 2).
public class LDPlayerEmulatorInstance extends EmulatorInstance {

    private static final Logger LOG = LoggerFactory.getLogger(LDPlayerEmulatorInstance.class);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration STATE_PROBE_TIMEOUT = Duration.ofSeconds(3);

    public LDPlayerEmulatorInstance(String path) { super(path); }

    @Override protected String getDeviceSerial(String id) {
        return "127.0.0.1:" + (5555 + Integer.parseInt(id) * 2);
    }

    @Override public void launchEmulator(String id) {
        exec("launch", "--index", id);
        LOG.info("LDPlayer {} boot requested", id);
    }

    private void exec(String... args) {
        try {
            BoundedProcessRunner.run(pb(args), Duration.ofSeconds(30));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException failure) {
            LOG.error("LDPlayer CLI could not start: {}", failure.getClass().getSimpleName());
        }
    }

    @Override protected EmulatorStopCycle.CommandOutcome requestVendorStop(String id) throws InterruptedException {
        try {
            BoundedProcessRunner.ProcessResult result = BoundedProcessRunner.run(
                    pb("quit", "--index", id), STOP_TIMEOUT);
            if (result.timedOut()) {
                return EmulatorStopCycle.CommandOutcome.failure("LDPlayer quit timed out");
            }
            if (result.exitCode() != 0) {
                return EmulatorStopCycle.CommandOutcome.failure(
                        "LDPlayer quit exited with code " + result.exitCode());
            }
            return new EmulatorStopCycle.CommandOutcome(true, "LDPlayer quit command exited successfully");
        } catch (IOException failure) {
            return EmulatorStopCycle.CommandOutcome.failure(
                    "LDPlayer quit could not start: " + failure.getClass().getSimpleName());
        }
    }

    @Override public boolean isRunning(String id) {
        try {
            return probeVendorState(id) == EmulatorStopCycle.Probe.RUNNING;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override protected EmulatorStopCycle.Probe probeVendorState(String id) throws InterruptedException {
        try {
            BoundedProcessRunner.ProcessResult result = BoundedProcessRunner.run(
                    pb("isrunning", "--index", id), STATE_PROBE_TIMEOUT);
            if (result.timedOut() || result.exitCode() != 0) {
                return EmulatorStopCycle.Probe.UNKNOWN;
            }
            String state = result.output().trim();
            if ("running".equalsIgnoreCase(state)) {
                return EmulatorStopCycle.Probe.RUNNING;
            }
            if ("stopped".equalsIgnoreCase(state) || "not running".equalsIgnoreCase(state)
                    || "not_running".equalsIgnoreCase(state) || "stop".equalsIgnoreCase(state)) {
                return EmulatorStopCycle.Probe.STOPPED;
            }
            return EmulatorStopCycle.Probe.UNKNOWN;
        } catch (IOException failure) {
            LOG.warn("LDPlayer state probe could not start for {}: {}", id,
                    failure.getClass().getSimpleName());
            return EmulatorStopCycle.Probe.UNKNOWN;
        }
    }

    private ProcessBuilder pb(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = cli();
        System.arraycopy(args, 0, cmd, 1, args.length);
        return new ProcessBuilder(cmd).directory(new File(consolePath));
    }

    private String cli() { return Paths.get(consolePath, "ldconsole.exe").toString(); }
}
