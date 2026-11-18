package dev.frostguard.engine.emulator.instance;

import dev.frostguard.engine.emulator.BoundedProcessRunner.ProcessResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static dev.frostguard.engine.emulator.instance.MuMuManagerClient.State.*;
import static org.junit.jupiter.api.Assertions.*;

class MuMuManagerClientTest {
    private static final String STOPPED_INFO = "{\"error_code\":0,\"index\":\"0\","
            + "\"is_process_started\":false,\"is_android_started\":false}";
    private static final String RUNNING_INFO = "{\"error_code\":0,\"index\":\"0\","
            + "\"is_process_started\":true,\"is_android_started\":true,\"player_state\":\"start_finished\"}";

    @Test
    void usesModernLaunchAndShutdownAfterReadOnlyCapabilityProbe() throws Exception {
        Script script = new Script(STOPPED_INFO, "{\"errcode\":0}", "{\"errcode\":0}");
        script.client.request("0", true);
        script.client.request("0", false);
        assertEquals(List.of(List.of("info", "-v", "0"), List.of("control", "-v", "0", "launch"),
                List.of("control", "-v", "0", "shutdown")), script.commands);
        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(45), Duration.ofSeconds(45)), script.timeouts);
    }

    @Test
    void usesLegacyOnlyAfterExplicitUnsupportedInfoAndConfirmsTransitions() throws Exception {
        Script script = new Script("unknown cmd: info", "state=stopped", "player launch: result=0",
                "check player state: state=starting_rom", "", "state=stopped");
        script.client.request("0", true);
        script.client.request("0", false);
        assertEquals(List.of("api", "-v", "0", "launch_player"), script.commands.get(2));
        assertEquals(List.of("api", "-v", "0", "shutdown_player"), script.commands.get(4));
        assertEquals(6, script.commands.size());
        assertTrue(script.timeouts.get(3).compareTo(Duration.ofSeconds(5)) <= 0);
    }

    @Test
    void doesNotTryAnotherCommandFamilyAfterModernRejection() throws Exception {
        Script script = new Script(STOPPED_INFO, "{\"errcode\":-1,\"errmsg\":\"not handle cmd\"}");
        assertThrows(IOException.class, () -> script.client.request("0", true));
        assertEquals(2, script.commands.size());
    }

    @Test
    void rejectsMissingMalformedOrAmbiguousModernAcknowledgements() {
        for (String output : List.of("", "text", "{}", "[]", "{\"errcode\":\"0\"}",
                "{\"errcode\":false}", "{\"errcode\":0.0}", "{\"errcode\":4294967296}",
                "{\"errcode\":0,\"error_code\":1}", "{\"errcode\":0} trailing")) {
            Script script = new Script(STOPPED_INFO, output);
            assertThrows(IOException.class, () -> script.client.request("0", true), output);
            assertEquals(2, script.commands.size());
        }
    }

    @Test
    void doesNotFallbackOnTimeoutNonzeroExitOrMalformedProbe() {
        for (ProcessResult response : List.of(new ProcessResult(0, STOPPED_INFO, true),
                new ProcessResult(7, STOPPED_INFO, false), ok(""), ok("garbage"),
                ok("{\"errcode\":-200,\"errmsg\":\"player index not found\"}"))) {
            Script script = new Script();
            script.responses.add(response);
            assertThrows(IOException.class, () -> script.client.request("0", true));
            assertEquals(1, script.commands.size());
        }
    }

    @Test
    void rejectsFailedOrTimedOutActionsDespiteSuccessPayload() {
        for (ProcessResult result : List.of(new ProcessResult(7, "{\"errcode\":0}", false),
                new ProcessResult(0, "{\"errcode\":0}", true))) {
            Script script = new Script(STOPPED_INFO);
            script.responses.add(result);
            assertThrows(IOException.class, () -> script.client.request("0", true));
        }
    }

    @Test
    void parsesModernStoppedStartingRunningAndUnknownStates() throws Exception {
        assertEquals(STOPPED, new Script(STOPPED_INFO).client.state("0"));
        assertEquals(RUNNING, new Script(RUNNING_INFO).client.state("0"));
        assertEquals(STARTING, new Script(RUNNING_INFO.replace("start_finished", "starting_rom")
                .replace("\"is_android_started\":true", "\"is_android_started\":false")).client.state("0"));
        for (String output : List.of(RUNNING_INFO.replace("\"is_android_started\":true", "\"is_android_started\":false"),
                RUNNING_INFO.replace("start_finished", "unexpected"),
                RUNNING_INFO.replace("\"is_process_started\":true", "\"is_process_started\":\"true\""),
                RUNNING_INFO.replace("\"error_code\":0", "\"error_code\":0,\"launch_err_code\":1"))) {
            assertEquals(UNKNOWN, new Script(output).client.state("0"));
        }
    }

    @Test
    void rejectsMismatchedOrMissingTargetIndex() {
        for (String output : List.of(RUNNING_INFO.replace("\"index\":\"0\"", "\"index\":\"1\""),
                RUNNING_INFO.replace("\"index\":\"0\",", ""))) {
            assertThrows(IOException.class, () -> new Script(output).client.state("0"));
        }
    }

    @Test
    void rejectsLegacyNegativeAcknowledgementWithoutLaunchingAgain() {
        Script script = new Script("unknown cmd: info", "state=stopped", "player launch: result=-2");
        assertThrows(IOException.class, () -> script.client.request("0", true));
        assertEquals(3, script.commands.size());
    }

    @Test
    void rejectsLegacyUnknownStateAfterZeroExitAction() {
        Script script = new Script("unknown cmd: info", "state=stopped", "player launch: result=0", "state=");
        assertThrows(IOException.class, () -> script.client.request("0", true));
        assertEquals(4, script.commands.size());
    }

    @Test
    void doesNotAcceptTokensInsideNoiseOrConflictingStates() throws Exception {
        for (String output : List.of("error: state=start_finished", "state=start_finished_bad",
                "state=start_finished\nstate=stopped", "state=")) {
            assertEquals(UNKNOWN, MuMuManagerClient.legacyState(ok(output)));
        }
    }

    @Test
    void rejectsNonSingleInstanceTargetsBeforeExecuting() {
        for (String target : List.of("all", "0,1", "-1", "01", "0 launch", "999999999999999")) {
            Script script = new Script();
            assertThrows(IllegalArgumentException.class, () -> script.client.request(target, true));
            assertTrue(script.commands.isEmpty());
        }
    }

    @Test
    void propagatesCancellationWithoutIssuingCommands() throws Exception {
        Script script = new Script(STOPPED_INFO);
        script.client.state("0");
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> script.client.request("0", true));
            assertEquals(1, script.commands.size());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void exposesRejectedLaunchAndShutdownToBackendCallers() {
        for (boolean launch : List.of(true, false)) {
            Script script = new Script(STOPPED_INFO, "{\"errcode\":-1}");
            MuMuEmulatorInstance backend = backend(script.client);
            assertThrows(IllegalStateException.class, () -> {
                if (launch) {
                    backend.launchEmulator("0");
                } else {
                    backend.closeEmulator("0");
                }
            });
            assertEquals(2, script.commands.size());
        }
    }

    @Test
    void backendPreservesCancellationAndDoesNotSubmitLaunch() {
        Script script = new Script();
        MuMuEmulatorInstance backend = backend(script.client);
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class, () -> backend.launchEmulator("0"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(script.commands.isEmpty());
        } finally {
            Thread.interrupted();
        }
    }

    private static MuMuEmulatorInstance backend(MuMuManagerClient client) {
        return new MuMuEmulatorInstance("test-manager", client) {
            @Override
            protected void initBridge() { }
        };
    }

    private static ProcessResult ok(String output) {
        return new ProcessResult(0, output, false);
    }

    private static class Script {
        final ArrayDeque<ProcessResult> responses = new ArrayDeque<>();
        final List<List<String>> commands = new ArrayList<>();
        final List<Duration> timeouts = new ArrayList<>();
        final MuMuManagerClient client = new MuMuManagerClient(Path.of("test-manager"), (process, timeout) -> {
            commands.add(List.copyOf(process.command().subList(1, process.command().size())));
            timeouts.add(timeout);
            return responses.remove();
        });

        Script(String... outputs) {
            for (String output : outputs) {
                responses.add(ok(output));
            }
        }
    }
}
