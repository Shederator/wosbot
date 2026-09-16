package dev.frostguard.engine.emulator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.video.H264FrameDecoder;

/** Per-task Android recording transport with a single replaceable frame, not a growing queue. */
public final class AndroidFrameStream implements AutoCloseable {
    public record Frame(RawImageData image, long sequence, long receivedNanos) { }

    private final String adb;
    private final String serial;
    private final String ownershipFile = "/data/local/tmp/frostguard-video-" + UUID.randomUUID() + ".pid";
    private volatile Frame latest;
    private volatile String failure;
    private volatile boolean stopping;
    private Process process;
    private Thread decoderThread;
    private boolean scriptUploaded;

    public AndroidFrameStream(String adb, String serial) {
        this.adb = Objects.requireNonNull(adb);
        this.serial = Objects.requireNonNull(serial);
        if (adb.isBlank() || serial.isBlank()) throw new IllegalArgumentException("ADB path and serial required");
    }

    public void start() throws IOException {
        if (stopping || process != null) throw new IllegalStateException("Stream closed or already started");
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Recording cancelled");
        try {
            H264FrameDecoder.prepareRuntime();
        } catch (Exception | LinkageError error) {
            throw new IOException("Video decoder unavailable; recording not started", error);
        }
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Recording cancelled");
        try {
            uploadRecordingScript();
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Recording cancelled");
            process = new ProcessBuilder(ownedCommand(adb, serial, ownershipFile))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
        } catch (IOException error) {
            close();
            throw error;
        }
        decoderThread = new Thread(this::decode, "AndroidFrameStream");
        decoderThread.setDaemon(true);
        decoderThread.start();
    }

    static List<String> command(String adb, String serial) {
        return List.of(adb, "-s", serial, "exec-out", "screenrecord", "--output-format=h264",
                "--size", "720x1280", "--bit-rate", "4000000", "--time-limit", "120", "-");
    }

    static List<String> ownedCommand(String adb, String serial, String ownershipFile) {
        validateOwnershipFile(ownershipFile);
        // Windows ADB shell output converted LF to CRLF even with -T in live MuMu checks.
        // exec-out preserves video bytes; a script file avoids nested Windows argv quoting.
        return List.of(adb, "-s", serial, "exec-out", "sh", scriptPath(ownershipFile));
    }

    static String recordingScript(String ownershipFile) {
        validateOwnershipFile(ownershipFile);
        return "echo \"$$ $(cut -d ' ' -f 22 /proc/$$/stat)\" > " + ownershipFile
                + " || exit 1; exec screenrecord --output-format=h264 --size 720x1280 --bit-rate 4000000 --time-limit 120 -\n";
    }

    private static String scriptPath(String ownershipFile) {
        validateOwnershipFile(ownershipFile);
        return ownershipFile.substring(0, ownershipFile.length() - 4) + ".sh";
    }

    private void uploadRecordingScript() throws IOException {
        var source = Files.createTempFile("frostguard-recording-", ".sh");
        Process upload = null;
        try {
            Files.writeString(source, recordingScript(ownershipFile), StandardCharsets.UTF_8);
            upload = new ProcessBuilder(adb, "-s", serial, "push", source.toString(), scriptPath(ownershipFile))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            scriptUploaded = true; // A timed-out upload may still have created the remote file.
            if (!upload.waitFor(3000, TimeUnit.MILLISECONDS) || upload.exitValue() != 0) {
                throw new IOException("Recording script upload failed or timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("Recording script upload cancelled");
        } finally {
            if (upload != null && upload.isAlive()) upload.destroyForcibly();
            Files.deleteIfExists(source);
        }
    }

    static List<String> cleanupCommand(String adb, String serial, String ownershipFile) {
        validateOwnershipFile(ownershipFile);
        return List.of(adb, "-s", serial, "shell", "-T", "sh");
    }

    static String cleanupScript(String ownershipFile) {
        validateOwnershipFile(ownershipFile);
        // Check process start time as well as PID: an expired recording must not kill a reused PID.
        return "if read -r pid started < " + ownershipFile + "; then "
                + "case \"$pid:$started\" in *[!0-9:]*|:*) ;; *) "
                + "if [ \"$(cut -d ' ' -f 22 /proc/$pid/stat 2>/dev/null)\" = \"$started\" ] "
                + "&& [ \"$(cat /proc/$pid/comm 2>/dev/null)\" = screenrecord ]; then kill -INT \"$pid\"; fi ;; esac; "
                + "rm -f " + ownershipFile + "; fi; rm -f " + scriptPath(ownershipFile) + "\n";
    }

    private static void validateOwnershipFile(String path) {
        if (!path.matches("/data/local/tmp/frostguard-video-[0-9a-f-]{36}\\.pid")) {
            throw new IllegalArgumentException("Invalid recording ownership path");
        }
    }

    private void stopOwnedRecorder() {
        Process cleanup = null;
        boolean interrupted = Thread.interrupted();
        try {
            cleanup = new ProcessBuilder(cleanupCommand(adb, serial, ownershipFile))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (var commands = cleanup.getOutputStream()) {
                commands.write(cleanupScript(ownershipFile).getBytes(StandardCharsets.UTF_8));
            }
            if (!cleanup.waitFor(1000, TimeUnit.MILLISECONDS)) cleanup.destroyForcibly();
        } catch (IOException ignored) {
            // A disconnected emulator cannot be reached; screenrecord still has its bounded timeout.
        } catch (InterruptedException error) {
            interrupted = true;
            if (cleanup != null) cleanup.destroyForcibly();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void decode() {
        try (var input = process.getInputStream(); var decoder = new H264FrameDecoder(input)) {
            decoder.start();
            long sequence = 0;
            while (!stopping) {
                RawImageData frame = decoder.nextFrame();
                if (frame == null) break;
                if (frame.getWidth() != 720 || frame.getHeight() != 1280) {
                    throw new IllegalStateException("Unexpected video resolution");
                }
                if (stopping) break;
                latest = new Frame(frame, ++sequence, System.nanoTime());
            }
            if (!stopping) failure = "Android video stream ended";
        } catch (Exception | LinkageError error) {
            if (!stopping) failure = error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    public Frame latestAfter(long sequence) {
        Frame frame = latest;
        return frame != null && frame.sequence() > sequence ? frame : null;
    }

    public Frame latest() {
        return latest;
    }

    public String failure() {
        return failure;
    }

    @Override
    public void close() {
        stopping = true;
        if (scriptUploaded) {
            stopOwnedRecorder();
            scriptUploaded = false;
        }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                process.getInputStream().close();
                if (decoderThread != null) decoderThread.join(1500);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            } catch (IOException ignored) {
                // The ADB pipe may already have closed when the recording exits.
            }
        }
        latest = null;
    }
}
