package dev.frostguard.engine.emulator.instance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class MuMuEmulatorInstanceHeadlessTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsSuccessfulControlResponseJson() {
        assertTrue(MuMuEmulatorInstance.isControlSuccessful(0, "{\n  \"errcode\": 0,\n  \"errmsg\": \"\"\n}"));
        assertTrue(MuMuEmulatorInstance.isControlSuccessful(0, "{\"errcode\":0,\"errmsg\":\"\"}"));
        assertTrue(MuMuEmulatorInstance.isControlSuccessful(0, "{\"error_code\": 0}"));
        assertTrue(MuMuEmulatorInstance.isControlSuccessful(0, "{\"error_code\":0}"));
        assertTrue(MuMuEmulatorInstance.isControlSuccessful(0, ""));
    }

    @Test
    void rejectsFailedOrNegativeControlResponseJson() {
        assertFalse(MuMuEmulatorInstance.isControlSuccessful(0, "{\n  \"errcode\": -201,\n  \"errmsg\": \"player not running\"\n}"));
        assertFalse(MuMuEmulatorInstance.isControlSuccessful(0, "{\"error_code\": -1}"));
        assertFalse(MuMuEmulatorInstance.isControlSuccessful(1, "{\n  \"errcode\": 0\n}"));
        assertFalse(MuMuEmulatorInstance.isControlSuccessful(2, ""));
    }

    @Test
    void buildsControlProcessWithExpectedCliArguments() {
        ProcessBuilder pb = MuMuEmulatorInstance.buildControlProcess(tempDir, "2", "hide_window");

        List<String> cmd = pb.command();
        assertEquals(5, cmd.size());
        assertTrue(cmd.get(0).endsWith("MuMuManager.exe"));
        assertEquals("control", cmd.get(1));
        assertEquals("-v", cmd.get(2));
        assertEquals("2", cmd.get(3));
        assertEquals("hide_window", cmd.get(4));
    }

    @Test
    void acceptsSuccessfulControlExecution() throws Exception {
        boolean ok = MuMuEmulatorInstance.readControlResponse(
                childProcess("success", tempDir.resolve("success.pid")), Duration.ofSeconds(5));
        assertTrue(ok);
    }

    @Test
    void rejectsFailedControlExecution() throws Exception {
        boolean ok = MuMuEmulatorInstance.readControlResponse(
                childProcess("error", tempDir.resolve("error.pid")), Duration.ofSeconds(5));
        assertFalse(ok);
    }

    @Test
    void timesOutAndKillsHangingControlCommand() throws Exception {
        Path pidFile = tempDir.resolve("timeout.pid");
        long startedAt = System.nanoTime();

        boolean ok = MuMuEmulatorInstance.readControlResponse(
                childProcess("hang", pidFile), Duration.ofSeconds(2));

        assertFalse(ok);
        assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).compareTo(Duration.ofSeconds(5)) < 0);
        assertProcessExited(readPid(pidFile));
    }

    @Test
    void interruptionKillsControlProcessAndRestoresStatus() throws Exception {
        Path pidFile = tempDir.resolve("interrupt.pid");
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                MuMuEmulatorInstance.readControlResponse(
                        childProcess("hang", pidFile), Duration.ofSeconds(30));
            } catch (InterruptedException expected) {
                interrupted.set(true);
            } catch (Throwable unexpected) {
                failure.set(unexpected);
            }
        });

        worker.start();
        long childPid = readPid(pidFile);
        worker.interrupt();
        worker.join(2_000);

        assertFalse(worker.isAlive());
        assertTrue(interrupted.get());
        assertNull(failure.get());
        assertProcessExited(childPid);
    }

    private ProcessBuilder childProcess(String action, Path pidFile) {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(
                javaExecutable,
                "-cp",
                System.getProperty("java.class.path"),
                ControlChildProcess.class.getName(),
                action,
                pidFile.toString());
    }

    private long readPid(Path pidFile) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(pidFile)) {
                String pid = Files.readString(pidFile).trim();
                if (!pid.isEmpty()) {
                    return Long.parseLong(pid);
                }
            }
            Thread.sleep(20);
        }
        return fail("child process did not publish its PID before the deadline");
    }

    private void assertProcessExited(long pid) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    static final class ControlChildProcess {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
            if ("error".equals(args[0])) {
                System.out.println("{\"errcode\": -201, \"errmsg\": \"player not running\"}");
                return;
            }
            if ("failed_exit".equals(args[0])) {
                System.exit(5);
            }
            if ("hang".equals(args[0])) {
                Thread.sleep(30_000);
                return;
            }
            System.out.println("{\"errcode\": 0, \"errmsg\": \"\"}");
        }
    }
}
