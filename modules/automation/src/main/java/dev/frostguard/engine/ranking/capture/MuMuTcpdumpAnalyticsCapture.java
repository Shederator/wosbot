package dev.frostguard.engine.ranking.capture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Captures one ranking directly on MuMu's virtual network interface. */
public final class MuMuTcpdumpAnalyticsCapture {

    private static final DateTimeFormatter SESSION_TIME = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final CaptureDecoder decoder;
    private final Clock clock;
    private ActiveCapture active;

    public MuMuTcpdumpAnalyticsCapture() {
        this(new PowerRankingCaptureDecoder()::decode, Clock.systemUTC());
    }

    MuMuTcpdumpAnalyticsCapture(CaptureDecoder decoder, Clock clock) {
        this.decoder = decoder;
        this.clock = clock;
    }

    public synchronized Path start(Path workspaceRoot, GameAnalyticsCollectionType type,
                                   String adbPath, String deviceSerial)
            throws IOException, InterruptedException {
        if (active != null) {
            throw new IllegalStateException("Game analytics traffic capture is already running");
        }
        if (workspaceRoot == null || type == null || adbPath == null || adbPath.isBlank()
                || deviceSerial == null || deviceSerial.isBlank()) {
            throw new IllegalArgumentException("capture arguments must not be null or blank");
        }

        Path directory = workspaceRoot.toAbsolutePath().normalize().resolve("diagnostics")
                .resolve("game-analytics")
                .resolve(SESSION_TIME.format(Instant.now(clock)) + "-" + UUID.randomUUID());
        Files.createDirectories(directory);
        Path capture = directory.resolve("capture.pcap");
        Path error = directory.resolve("tcpdump-error.log");
        String remoteId = UUID.randomUUID().toString();
        String remoteCapture = "/data/local/tmp/frostguard-analytics-" + remoteId + ".pcap";

        run(adbPath, deviceSerial, List.of("root"), 15_000);
        run(adbPath, deviceSerial, List.of("wait-for-device"), 15_000);
        CommandResult existing = run(adbPath, deviceSerial,
                List.of("shell", "pidof", "tcpdump"), 5_000, false);
        if (!existing.output().isBlank()) {
            throw new IOException("Another tcpdump process is already running in MuMu");
        }
        CommandResult route = run(adbPath, deviceSerial,
                List.of("shell", "ip", "route", "get", "1.1.1.1"), 5_000);
        String networkInterface = defaultRouteInterface(route.output());

        Process process = new ProcessBuilder(adbPath, "-s", deviceSerial, "shell", "tcpdump",
                "-i", networkInterface, "-s", "0", "-U", "-w", remoteCapture,
                "tcp", "port", "30101")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(error.toFile())
                .start();
        Thread.sleep(750);
        CommandResult pid = run(adbPath, deviceSerial,
                List.of("shell", "pidof", "tcpdump"), 5_000, false);
        if (!process.isAlive() || pid.output().isBlank()) {
            process.destroyForcibly();
            throw new IOException("MuMu tcpdump exited during startup: " + readError(error));
        }
        String remotePid = pid.output().trim().split("\\s+")[0];
        active = new ActiveCapture(directory, capture, error, adbPath, deviceSerial,
                remotePid, remoteCapture, process, type);
        return directory;
    }

    public synchronized AllianceRankingCaptureResult stopAndDecode()
            throws IOException, InterruptedException {
        ActiveCapture capture = requireActive();
        try {
            run(capture.adbPath(), capture.deviceSerial(),
                    List.of("shell", "kill", "-2", capture.remotePid()), 5_000);
            waitForRemoteStop(capture, 10_000);
            if (!capture.process().waitFor(5, TimeUnit.SECONDS)) {
                capture.process().destroyForcibly();
                throw new IOException("ADB tcpdump session did not stop cleanly");
            }
            pull(capture, capture.remoteCapture(), capture.pcap());
            removeRemoteFiles(capture);
            AllianceRankingCaptureResult result = decoder.decode(capture.pcap(), capture.type());
            boolean empty = capture.type() == GameAnalyticsCollectionType.POWER
                    ? result.power().isEmpty() : result.labyrinth().isEmpty();
            if (empty) {
                throw new IOException("No " + capture.type().name().toLowerCase()
                        + " ranking response was decoded. Capture files remain in " + capture.directory());
            }
            retainLatestDevelopmentPowerCapture(capture);
            cleanup(capture);
            return result;
        } finally {
            active = null;
        }
    }

    public synchronized void cancel() {
        ActiveCapture capture = active;
        if (capture == null) return;
        try {
            run(capture.adbPath(), capture.deviceSerial(),
                    List.of("shell", "kill", "-2", capture.remotePid()), 3_000);
            waitForRemoteStop(capture, 3_000);
            if (!capture.process().waitFor(3, TimeUnit.SECONDS)) capture.process().destroyForcibly();
        } catch (Exception ignored) {
            capture.process().destroyForcibly();
        } finally {
            pullIfPresent(capture.adbPath(), capture.deviceSerial(), capture.remoteCapture(), capture.pcap());
            removeRemoteFiles(capture);
            active = null;
        }
    }

    public synchronized boolean isRunning() {
        return active != null;
    }

    static String defaultRouteInterface(String route) throws IOException {
        if (route != null) {
            String[] tokens = route.trim().split("\\s+");
            for (int index = 0; index + 1 < tokens.length; index++) {
                if ("dev".equals(tokens[index]) && !tokens[index + 1].isBlank()) {
                    return tokens[index + 1];
                }
            }
        }
        throw new IOException("Could not determine MuMu's active network interface");
    }

    private ActiveCapture requireActive() {
        if (active == null) throw new IllegalStateException("Game analytics capture is not running");
        return active;
    }

    private CommandResult run(String adbPath, String serial, List<String> arguments, long timeoutMillis)
            throws IOException, InterruptedException {
        return run(adbPath, serial, arguments, timeoutMillis, true);
    }

    private CommandResult run(String adbPath, String serial, List<String> arguments,
                              long timeoutMillis, boolean requireSuccess)
            throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>();
        command.add(adbPath);
        command.add("-s");
        command.add(serial);
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("ADB command timed out: " + String.join(" ", arguments));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (requireSuccess && process.exitValue() != 0) {
            throw new IOException("ADB command failed: " + output);
        }
        return new CommandResult(process.exitValue(), output);
    }

    private void waitForRemoteStop(ActiveCapture capture, long timeoutMillis)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            CommandResult result = run(capture.adbPath(), capture.deviceSerial(),
                    List.of("shell", "pidof", "tcpdump"), 2_000, false);
            if (result.output().isBlank()) return;
            Thread.sleep(200);
        }
        throw new IOException("MuMu tcpdump did not stop cleanly");
    }

    private void pull(ActiveCapture capture, String remote, Path local)
            throws IOException, InterruptedException {
        run(capture.adbPath(), capture.deviceSerial(),
                List.of("pull", remote, local.toString()), 15_000);
    }

    private void pullIfPresent(String adbPath, String serial, String remote, Path local) {
        try {
            run(adbPath, serial, List.of("pull", remote, local.toString()), 10_000);
        } catch (Exception ignored) {
            // Optional diagnostic output may not have been created.
        }
    }

    private void removeRemoteFiles(ActiveCapture capture) {
        try {
            run(capture.adbPath(), capture.deviceSerial(), List.of("shell", "rm", "-f",
                    capture.remoteCapture()), 5_000);
        } catch (Exception ignored) {
            // Remote cleanup is best-effort after the local capture has been pulled.
        }
    }

    private String readError(Path error) {
        try {
            return Files.isRegularFile(error) ? Files.readString(error).trim() : "";
        } catch (IOException ignored) {
            return "";
        }
    }

    private void cleanup(ActiveCapture capture) {
        removeRemoteFiles(capture);
        try {
            Files.deleteIfExists(capture.pcap());
            Files.deleteIfExists(capture.error());
            Files.deleteIfExists(capture.directory());
        } catch (IOException ignored) {
            // Decoded data is already in memory; cleanup is best-effort.
        }
    }

    private void retainLatestDevelopmentPowerCapture(ActiveCapture capture) throws IOException {
        if (capture.type() != GameAnalyticsCollectionType.POWER
                || !"development".equals(System.getProperty("frostguard.channel"))) {
            return;
        }
        Path latest = capture.directory().getParent().resolve("latest-power.pcap");
        Files.copy(capture.pcap(), latest, StandardCopyOption.REPLACE_EXISTING);
    }

    interface CaptureDecoder {
        AllianceRankingCaptureResult decode(Path capture, GameAnalyticsCollectionType type) throws IOException;
    }

    private record ActiveCapture(Path directory, Path pcap, Path error, String adbPath,
                                 String deviceSerial, String remotePid, String remoteCapture,
                                 Process process,
                                 GameAnalyticsCollectionType type) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
