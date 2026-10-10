package dev.frostguard.engine.emulator.instance;

import dev.frostguard.engine.emulator.BoundedProcessRunner;
import dev.frostguard.engine.emulator.EmulatorInstance;
import dev.frostguard.engine.emulator.EmulatorStopCycle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Controls MuMu Player emulator instances through the {@code MuMuManager.exe}
 * CLI.  Handles lifecycle operations (boot / shutdown) and ADB serial
 * resolution using MuMu's port-mapping convention.
 */
public class MuMuEmulatorInstance extends EmulatorInstance {

    private static final Logger log = LoggerFactory.getLogger(MuMuEmulatorInstance.class);

    private static final int ADB_BASE_PORT = 16384;
    private static final int ADB_PORT_STRIDE = 32;
    private static final Duration STATE_PROBE_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration ACTION_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration HOST_PROBE_TIMEOUT = Duration.ofSeconds(3);
    private static final ObjectMapper JSON = new ObjectMapper();

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
    protected EmulatorStopCycle.CommandOutcome requestVendorStop(String identifier) throws InterruptedException {
        try {
            BoundedProcessRunner.ProcessResult result = BoundedProcessRunner.run(
                    buildManagerProcess(identifier, "shutdown_player"), STOP_TIMEOUT);
            if (result.timedOut()) {
                return EmulatorStopCycle.CommandOutcome.failure("MuMu shutdown timed out");
            }
            if (result.exitCode() != 0) {
                return EmulatorStopCycle.CommandOutcome.failure(
                        "MuMu shutdown exited with code " + result.exitCode());
            }
            return new EmulatorStopCycle.CommandOutcome(true, "MuMu shutdown command exited successfully");
        } catch (IOException failure) {
            return EmulatorStopCycle.CommandOutcome.failure(
                    "MuMu shutdown could not start: " + failure.getClass().getSimpleName());
        }
    }

    @Override
    public boolean isRunning(String identifier) {
        try {
            return readRunningState(
                    buildManagerProcess(identifier, "player_state"), Duration.ofSeconds(5));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException failure) {
            log.warn("MuMu running-state probe could not start for #{}: {}", identifier,
                    failure.getClass().getSimpleName());
        }
        return false;
    }

    @Override
    protected EmulatorStopCycle.Probe probeVendorState(String identifier) throws InterruptedException {
        try {
            EmulatorStopCycle.Probe state = readVendorState(
                    buildInfoProcess(identifier), STATE_PROBE_TIMEOUT);
            if (state != EmulatorStopCycle.Probe.UNKNOWN) {
                return state;
            }
            return readVendorState(buildManagerProcess(identifier, "player_state"), STATE_PROBE_TIMEOUT);
        } catch (IOException failure) {
            log.warn("MuMu state probe could not start for #{}: {}", identifier,
                    failure.getClass().getSimpleName());
            return EmulatorStopCycle.Probe.UNKNOWN;
        }
    }

    @Override
    protected EmulatorStopCycle.Probe probeHostState(String identifier) throws InterruptedException {
        String script = "$p=Get-CimInstance Win32_Process -Filter \"Name='MuMuVMMHeadless.exe'\"; "
                + "if ($null -eq $p) { exit 0 }; "
                + "$p | ForEach-Object { if ($null -eq $_.CommandLine) { 'FG_NULL_COMMAND_LINE' } "
                + "else { $_.CommandLine } }";
        ProcessBuilder process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-Command", script);
        try {
            BoundedProcessRunner.ProcessResult result = BoundedProcessRunner.run(process, HOST_PROBE_TIMEOUT);
            if (result.timedOut() || result.exitCode() != 0) {
                return EmulatorStopCycle.Probe.UNKNOWN;
            }
            return hostStateFromCommandLines(result.output(), identifier);
        } catch (IOException failure) {
            log.warn("MuMu host probe could not start: {}", failure.getClass().getSimpleName());
            return EmulatorStopCycle.Probe.UNKNOWN;
        }
    }

    static EmulatorStopCycle.Probe hostStateFromCommandLines(String output, String identifier) {
        if (output == null || output.isBlank()) {
            return EmulatorStopCycle.Probe.STOPPED;
        }
        Pattern comment = Pattern.compile(
                "--comment\\s+\"?MuMuPlayerGlobal-[^\\s\"]+-(\\d+)(?:\\s|\"|$)",
                Pattern.CASE_INSENSITIVE);
        boolean unknown = false;
        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            var match = comment.matcher(line);
            if (match.find()) {
                if (identifier.equals(match.group(1))) {
                    return EmulatorStopCycle.Probe.RUNNING;
                }
            } else {
                unknown = true;
            }
        }
        return unknown ? EmulatorStopCycle.Probe.UNKNOWN : EmulatorStopCycle.Probe.STOPPED;
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

    private ProcessBuilder buildInfoProcess(String instanceId) {
        List<String> cmd = List.of(resolveManagerBinary().toString(), "info", "-v", instanceId);
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
        return readVendorState(managerProcess, timeout) == EmulatorStopCycle.Probe.RUNNING;
    }

    static EmulatorStopCycle.Probe readVendorState(ProcessBuilder managerProcess, Duration timeout)
            throws IOException, InterruptedException {
        BoundedProcessRunner.ProcessResult result = BoundedProcessRunner.run(managerProcess, timeout);
        if (result.timedOut()) {
            log.warn("MuMu state probe timed out after {} seconds; killed the manager process",
                    timeout.toSeconds());
            return EmulatorStopCycle.Probe.UNKNOWN;
        }
        if (result.exitCode() != 0) {
            return EmulatorStopCycle.Probe.UNKNOWN;
        }
        try {
            JsonNode root = JSON.readTree(result.output());
            if (root != null && root.path("error_code").asInt(Integer.MIN_VALUE) == 0
                    && root.path("is_process_started").isBoolean()) {
                return root.path("is_process_started").booleanValue()
                        ? EmulatorStopCycle.Probe.RUNNING : EmulatorStopCycle.Probe.STOPPED;
            }
        } catch (IOException ignored) {
            // Older manager versions can return a plain state token.
        }
        for (String line : result.output().split("\\R")) {
            String state = line.trim();
            if (state.equalsIgnoreCase("state=start_finished")) {
                return EmulatorStopCycle.Probe.RUNNING;
            }
            if (state.equalsIgnoreCase("state=stopped") || state.equalsIgnoreCase("state=stop_finished")) {
                return EmulatorStopCycle.Probe.STOPPED;
            }
        }
        return EmulatorStopCycle.Probe.UNKNOWN;
    }
}
