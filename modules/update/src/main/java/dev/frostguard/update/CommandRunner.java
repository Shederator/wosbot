package dev.frostguard.update;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

interface CommandRunner {
    CommandResult run(List<String> command, Map<String, String> environment, Duration timeout)
            throws IOException, InterruptedException;

    record CommandResult(int exitCode, String output) {
    }
}
