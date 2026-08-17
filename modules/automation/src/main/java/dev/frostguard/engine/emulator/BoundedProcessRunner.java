package dev.frostguard.engine.emulator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

final class BoundedProcessRunner {

    private static final long TERMINATION_GRACE_MILLIS = 1_000;

    private BoundedProcessRunner() {
    }

    static ProcessResult run(ProcessBuilder builder, Duration timeout) throws IOException, InterruptedException {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        Path outputPath = Files.createTempFile("frostguard-process-", ".log");
        Process process = null;
        try {
            process = builder
                    .redirectErrorStream(true)
                    .redirectOutput(outputPath.toFile())
                    .start();

            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS);
            }

            String output = process.isAlive()
                    ? ""
                    : Files.readString(outputPath, StandardCharsets.UTF_8);
            int exitCode = process.isAlive() ? -1 : process.exitValue();
            return new ProcessResult(exitCode, output, !completed);
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            throw e;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            deleteTemporaryOutput(outputPath);
        }
    }

    private static void deleteTemporaryOutput(Path outputPath) {
        try {
            Files.deleteIfExists(outputPath);
        } catch (IOException e) {
            outputPath.toFile().deleteOnExit();
        }
    }

    record ProcessResult(int exitCode, String output, boolean timedOut) {
    }
}
