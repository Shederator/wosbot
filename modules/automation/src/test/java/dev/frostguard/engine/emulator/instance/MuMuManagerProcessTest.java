package dev.frostguard.engine.emulator.instance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MuMuManagerProcessTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsRunningStateWhenManagerExits() throws Exception {
        boolean running = MuMuEmulatorInstance.readRunningState(
                childProcess("running", tempDir.resolve("normal.pid")), Duration.ofSeconds(5));

        assertTrue(running);
    }

    @Test
    void timesOutAndKillsManagerThatKeepsOutputOpen() throws Exception {
        Path pidFile = tempDir.resolve("timeout.pid");
        long startedAt = System.nanoTime();

        boolean running = MuMuEmulatorInstance.readRunningState(
                childProcess("hang", pidFile), Duration.ofSeconds(3));

        assertFalse(running);
        assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).compareTo(Duration.ofSeconds(6)) < 0);
        assertProcessExited(readPid(pidFile));
    }

    @Test
    void interruptionKillsManagerAndReturnsControl() throws Exception {
        Path pidFile = tempDir.resolve("interrupt.pid");
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                MuMuEmulatorInstance.readRunningState(
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
                ChildProcess.class.getName(),
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

    static final class ChildProcess {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
            System.out.println("state=start_finished");
            System.out.flush();
            if ("hang".equals(args[0])) {
                Thread.sleep(30_000);
            }
        }
    }
}
