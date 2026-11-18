package dev.frostguard.engine.emulator.instance;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.frostguard.engine.emulator.BoundedProcessRunner;
import dev.frostguard.engine.emulator.BoundedProcessRunner.ProcessResult;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Version-independent lifecycle commands; acceptance does not prove a completed boot or stop. */
final class MuMuManagerClient {
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration ACTION_TIMEOUT = Duration.ofSeconds(45);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern UNSUPPORTED_INFO = Pattern.compile(
            "(?im)^\\s*(?:unknown cmd|unknown command|unrecognized command):?\\s+['\"]?info['\"]?\\s*$");
    private static final Pattern LEGACY_STATE = Pattern.compile(
            "(?m)^\\s*(?:check player state:\\s*)?state=([a-z_]+)\\s*$");
    private static final Pattern LEGACY_RESULT = Pattern.compile(
            "(?m)^\\s*player (?:launch|shutdown):\\s*result=(-?\\d+)\\s*$");
    private final Path directory;
    private final Runner runner;
    private volatile Family family;

    enum State { RUNNING, STARTING, STOPPING, STOPPED, UNKNOWN }
    private enum Family { MODERN, LEGACY }

    @FunctionalInterface
    interface Runner {
        ProcessResult run(ProcessBuilder process, Duration timeout) throws IOException, InterruptedException;
    }

    MuMuManagerClient(Path directory) {
        this(directory, BoundedProcessRunner::run);
    }

    MuMuManagerClient(Path directory, Runner runner) {
        this.directory = directory;
        this.runner = runner;
    }

    State state(String instance) throws IOException, InterruptedException {
        validateInstance(instance);
        if (family == Family.LEGACY) {
            return legacyState(run(PROBE_TIMEOUT, "api", "-v", instance, "player_state"));
        }
        ProcessResult result = run(PROBE_TIMEOUT, "info", "-v", instance);
        if (family == null && !result.timedOut() && UNSUPPORTED_INFO.matcher(result.output()).find()) {
            family = Family.LEGACY;
            return legacyState(run(PROBE_TIMEOUT, "api", "-v", instance, "player_state"));
        }
        JsonNode response = response(result);
        family = Family.MODERN;
        return modernState(response, instance);
    }

    void request(String instance, boolean launch) throws IOException, InterruptedException {
        validateInstance(instance);
        if (family == null) {
            state(instance);
        }
        String action = launch ? "launch" : "shutdown";
        if (family == Family.MODERN) {
            JsonNode response = response(run(ACTION_TIMEOUT, "control", "-v", instance, action));
            requireSuccessfulCode(response);
            return;
        }
        ProcessResult result = run(ACTION_TIMEOUT, "api", "-v", instance, action + "_player");
        requireCompleted(result);
        Matcher acknowledgement = LEGACY_RESULT.matcher(result.output());
        while (acknowledgement.find()) {
            if (!"0".equals(acknowledgement.group(1))) {
                throw new IOException("Legacy MuMu " + action + " rejected: result=" + acknowledgement.group(1));
            }
        }
        // Legacy zero-exit log output may not acknowledge an action. Confirm the
        // target's expected transition rather than guessing from unrelated logs.
        long deadline = System.nanoTime() + PROBE_TIMEOUT.toNanos();
        do {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            State observed = legacyState(run(Duration.ofNanos(remaining), "api", "-v", instance, "player_state"));
            if (launch ? observed == State.STARTING || observed == State.RUNNING
                    : observed == State.STOPPING || observed == State.STOPPED) {
                return;
            }
            if (observed == State.UNKNOWN || System.nanoTime() >= deadline) {
                break;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new IOException("Legacy MuMu " + action + " request was not confirmed by target state");
    }

    private ProcessResult run(Duration timeout, String... args) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("MuMu operation cancelled");
        }
        var command = new java.util.ArrayList<String>();
        command.add(directory.resolve("MuMuManager.exe").toString());
        command.addAll(List.of(args));
        return runner.run(new ProcessBuilder(command).directory(directory.toFile()), timeout);
    }

    static State legacyState(ProcessResult result) throws IOException {
        requireCompleted(result);
        Matcher matcher = LEGACY_STATE.matcher(result.output());
        if (!matcher.find()) {
            return State.UNKNOWN;
        }
        State state = namedState(matcher.group(1));
        return matcher.find() ? State.UNKNOWN : state;
    }

    private static State modernState(JsonNode node, String instance) throws IOException {
        requireSuccessfulCode(node);
        if (!node.path("index").isTextual() || !instance.equals(node.path("index").textValue())) {
            throw new IOException("MuMu info target index is absent or mismatched");
        }
        JsonNode process = node.path("is_process_started");
        JsonNode android = node.path("is_android_started");
        if (!process.isBoolean() || !android.isBoolean()) {
            return State.UNKNOWN;
        }
        if (!process.booleanValue() && !android.booleanValue()) {
            return State.STOPPED;
        }
        State state = namedState(node.path("player_state").asText(""));
        if (state == State.RUNNING) {
            if (node.has("launch_err_code") && !zero(node.get("launch_err_code"))) {
                return State.UNKNOWN;
            }
            return process.booleanValue() && android.booleanValue() ? state : State.UNKNOWN;
        }
        return process.booleanValue() ? state : State.UNKNOWN;
    }

    private static State namedState(String name) {
        return switch (name) {
            case "start_finished" -> State.RUNNING;
            case "starting", "starting_rom", "starting_vm", "starting_android" -> State.STARTING;
            case "stopping" -> State.STOPPING;
            case "stopped", "not_started" -> State.STOPPED;
            default -> State.UNKNOWN;
        };
    }

    private static JsonNode response(ProcessResult result) throws IOException {
        requireCompleted(result);
        JsonNode node = JSON.readTree(result.output());
        if (node == null || !node.isObject()) {
            throw new IOException("MuMu returned an absent or malformed JSON object");
        }
        return node;
    }

    private static void requireSuccessfulCode(JsonNode node) throws IOException {
        boolean found = false;
        for (String key : List.of("errcode", "error_code")) {
            if (node.has(key)) {
                found = true;
                if (!zero(node.get(key))) {
                    throw new IOException("MuMu rejected command: " + key + "=" + node.get(key));
                }
            }
        }
        if (!found) {
            throw new IOException("MuMu response has no explicit success code");
        }
    }

    private static boolean zero(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToInt() && node.intValue() == 0;
    }

    private static void requireCompleted(ProcessResult result) throws IOException {
        if (result.timedOut()) {
            throw new IOException("MuMu command timed out; manager subprocess terminated");
        }
        if (result.exitCode() != 0) {
            throw new IOException("MuMu command failed: exit=" + result.exitCode());
        }
    }

    private static void validateInstance(String instance) {
        if (instance == null || !instance.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("One non-negative MuMu instance index is required");
        }
        Integer.parseInt(instance);
    }
}
