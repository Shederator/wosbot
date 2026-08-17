package dev.frostguard.engine.emulator;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedProcessRunnerTest {

    @Test
    void capturesMergedOutputAfterSuccessfulExit() throws Exception {
        BoundedProcessRunner.ProcessResult result = BoundedProcessRunner.run(
                childProcess("output"), Duration.ofSeconds(5));

        assertFalse(result.timedOut());
        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("stdout marker"));
        assertTrue(result.output().contains("stderr marker"));
    }

    @Test
    void killsProcessThatExceedsTimeout() throws Exception {
        long startedAt = System.nanoTime();

        BoundedProcessRunner.ProcessResult result = BoundedProcessRunner.run(
                childProcess("block"), Duration.ofMillis(200));

        assertTrue(result.timedOut());
        assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).compareTo(Duration.ofSeconds(3)) < 0);
        long childPid = Long.parseLong(result.output().trim());
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
    }

    private ProcessBuilder childProcess(String action) {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(
                javaExecutable,
                "-cp",
                System.getProperty("java.class.path"),
                ChildProcess.class.getName(),
                action);
    }

    static final class ChildProcess {
        public static void main(String[] args) throws Exception {
            if ("output".equals(args[0])) {
                System.out.println("stdout marker");
                System.err.println("stderr marker");
                return;
            }
            System.out.println(ProcessHandle.current().pid());
            System.out.flush();
            Thread.sleep(30_000);
        }
    }
}
