package dev.frostguard.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class ProcessCommandRunner implements CommandRunner {
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    @Override
    public CommandResult run(List<String> command, Map<String, String> environment, Duration timeout)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Command timed out after " + timeout.toSeconds() + " seconds");
        }
        byte[] output = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES);
        return new CommandResult(process.exitValue(), new String(output, StandardCharsets.UTF_8).trim());
    }
}
