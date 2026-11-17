package dev.frostguard.engine.emulator;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.vision.video.H264FrameDecoder;
import org.opencv.core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Scrcpy-based low-latency H.264 video frame capture transport.
 * Deploys the bundled {@code scrcpy-server-v4.1} to the Android device, forwards
 * the abstract socket via ADB, and decodes live H.264 video into {@link RawImageData}
 * and OpenCV {@link Mat} frames via {@link H264FrameDecoder}.
 */
public class ScrcpyStreamCapture extends VideoStreamCapture implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ScrcpyStreamCapture.class);
    private static final String SERVER_RESOURCE = "/android/scrcpy-server-v4.1";
    static final String SERVER_SHA256 = "deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae";

    public record VideoStreamMeta(String codec, int flags, int width, int height) { }

    private final String adb;
    private final String serial;
    private final String customServer;
    private final String ffmpeg;
    private final int width;
    private final int height;
    private final int bitrate;
    private final int fps;
    private final String remote;

    private volatile RawImageData latestRaw;
    private volatile boolean closed;
    private Process serverProcess;
    private Socket socket;
    private H264FrameDecoder decoder;
    private Thread decoderThread;
    private String remoteSocket;
    private int port;
    private boolean uploaded;
    private VideoStreamMeta streamMeta;

    public ScrcpyStreamCapture(String adb, String serial) {
        this(adb, serial, null, null, 720, 1280, 4_000_000, 30);
    }

    public ScrcpyStreamCapture(String adb, String serial, String server, String ffmpeg,
                               int w, int h, int bitrate, int fps) {
        this.adb = Objects.requireNonNull(adb, "adb path required");
        this.serial = Objects.requireNonNull(serial, "device serial required");
        this.customServer = server;
        this.ffmpeg = ffmpeg;
        this.width = w > 0 ? w : 720;
        this.height = h > 0 ? h : 1280;
        this.bitrate = bitrate > 0 ? bitrate : 4_000_000;
        this.fps = fps > 0 ? fps : 30;
        this.remote = "/data/local/tmp/frostguard-video-" + UUID.randomUUID();
    }

    public static String findScrcpyServer() {
        File dir = new File("lib/scrcpy");
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().startsWith("scrcpy-server")) {
                        return f.getAbsolutePath();
                    }
                }
            }
        }
        if (ScrcpyStreamCapture.class.getResource(SERVER_RESOURCE) != null) {
            return "bundled:" + SERVER_RESOURCE;
        }
        return null;
    }

    public static String findFfmpeg() {
        File f = new File("lib/ffmpeg/ffmpeg.exe");
        if (f.isFile()) {
            return f.getAbsolutePath();
        }
        try {
            H264FrameDecoder.prepareRuntime();
            return "embedded-ffmpeg";
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public synchronized void start() {
        if (running || closed) {
            throw new IllegalStateException("Scrcpy stream already started or closed");
        }
        try {
            checkCancellation();
            H264FrameDecoder.prepareRuntime();
            byte[] binary = loadServerBinary();
            verifyServer(binary);

            String scid = String.format("%08x", new SecureRandom().nextInt(Integer.MAX_VALUE));
            remoteSocket = "localabstract:scrcpy_" + scid;

            Path localBinary = Files.createTempFile("frostguard-video-", ".jar");
            Path localScript = null;
            try {
                localScript = Files.createTempFile("frostguard-video-", ".sh");
                Files.write(localBinary, binary);
                int maxSize = Math.max(width, height);
                Files.writeString(localScript, launchScript(remote, scid, maxSize, fps, bitrate),
                        StandardCharsets.US_ASCII);

                uploaded = true;
                run("push", localBinary.toString(), remote + ".jar");
                run("push", localScript.toString(), remote + ".sh");

                String forwarded = run("forward", "tcp:0", remoteSocket);
                port = parseForwardedPort(forwarded);
                checkCancellation();

                serverProcess = command("exec-out", "sh", remote + ".sh")
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();

                connectSocket();
                initializeDecoder();

                running = true;
                decoderThread = new Thread(this::decodeLoop, "ScrcpyDecoder-" + serial);
                decoderThread.setDaemon(true);
                decoderThread.start();
                LOG.info("Scrcpy stream started for {} (port={}, {}x{}@{}fps)", serial, port, width, height, fps);
            } finally {
                deleteLocal(localBinary);
                if (localScript != null) {
                    deleteLocal(localScript);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
            throw new RuntimeException("Scrcpy stream startup interrupted", e);
        } catch (Exception e) {
            stop();
            lastError = e.getMessage();
            throw new RuntimeException("Failed to start scrcpy stream for " + serial + ": " + e.getMessage(), e);
        }
    }

    private byte[] loadServerBinary() throws IOException {
        if (customServer != null && !customServer.startsWith("bundled:")) {
            File customFile = new File(customServer);
            if (customFile.isFile()) {
                return Files.readAllBytes(customFile.toPath());
            }
        }
        try (InputStream resource = getClass().getResourceAsStream(SERVER_RESOURCE)) {
            if (resource == null) {
                throw new IOException("Bundled scrcpy server resource absent: " + SERVER_RESOURCE);
            }
            return resource.readAllBytes();
        }
    }

    private void connectSocket() throws IOException, InterruptedException {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            checkCancellation();
            if (serverProcess != null && !serverProcess.isAlive()) {
                throw new IOException("Scrcpy server process exited prematurely");
            }
            Socket candidate = new Socket();
            try {
                candidate.connect(new InetSocketAddress("127.0.0.1", port), 300);
                candidate.setTcpNoDelay(true);
                candidate.setSoTimeout(1000);
                int dummy = candidate.getInputStream().read();
                if (dummy != 0) {
                    throw new IOException("Scrcpy video handshake absent (expected dummy byte 0, got " + dummy + ")");
                }
                socket = candidate;
                return;
            } catch (IOException e) {
                candidate.close();
                Thread.sleep(50);
            }
        }
        throw new IOException("Scrcpy video handshake timed out after 15 seconds");
    }

    private void initializeDecoder() throws IOException {
        byte[] headerBytes = new byte[16];
        readExact(socket.getInputStream(), headerBytes);
        streamMeta = parseHeader(headerBytes);
        LOG.debug("Scrcpy stream meta: codec={}, flags=0x{}, {}x{}",
                streamMeta.codec(), Integer.toHexString(streamMeta.flags()),
                streamMeta.width(), streamMeta.height());

        // Reset socket timeout to infinite (0) so quiet/static video periods do not throw SocketTimeoutException
        socket.setSoTimeout(0);

        try {
            decoder = new H264FrameDecoder(socket.getInputStream());
            decoder.start();
        } catch (Exception e) {
            throw new IOException("Failed to start H.264 frame decoder", e);
        }
    }

    private void decodeLoop() {
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                RawImageData frame = decoder.nextFrame();
                if (frame == null) {
                    break;
                }
                latestRaw = frame;
                frameCount++;
            }
        } catch (Exception e) {
            if (running) {
                lastError = e.getMessage();
                LOG.warn("Scrcpy decode loop error for {}: {}", serial, e.getMessage());
            }
        } finally {
            running = false;
        }
    }

    @Override
    public Mat grabFrame() {
        RawImageData raw = latestRaw;
        if (raw == null) {
            return null;
        }
        try {
            return OpenCvPatternLocator.decodePixelsToMat(
                    raw.getData(), raw.getWidth(), raw.getHeight(), raw.getBpp());
        } catch (Exception e) {
            LOG.warn("Failed to convert raw frame to Mat: {}", e.getMessage());
            return null;
        }
    }

    public RawImageData grabRawFrame() {
        return latestRaw;
    }

    public VideoStreamMeta getStreamMeta() {
        return streamMeta;
    }

    @Override
    public synchronized void stop() {
        if (closed) {
            return;
        }
        closed = true;
        running = false;

        if (decoder != null) {
            try {
                decoder.close();
            } catch (Exception ignored) { }
            decoder = null;
        }

        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) { }
            socket = null;
        }

        if (decoderThread != null) {
            decoderThread.interrupt();
            try {
                decoderThread.join(1000);
            } catch (InterruptedException ignored) { }
            decoderThread = null;
        }

        if (uploaded) {
            stopOwnedServer();
            uploaded = false;
        }

        if (port > 0) {
            removePortForward();
            port = 0;
        }

        if (serverProcess != null) {
            serverProcess.destroy();
            try {
                if (!serverProcess.waitFor(500, TimeUnit.MILLISECONDS)) {
                    serverProcess.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                serverProcess.destroyForcibly();
            }
            serverProcess = null;
        }
        latestRaw = null;
    }

    @Override
    public void close() {
        stop();
    }

    private void stopOwnedServer() {
        boolean interrupted = Thread.interrupted();
        try {
            Process cleanup = command("shell", "-T", "sh")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            try (var out = cleanup.getOutputStream()) {
                out.write(cleanupScript(remote).getBytes(StandardCharsets.UTF_8));
            }
            if (!cleanup.waitFor(2000, TimeUnit.MILLISECONDS)) {
                cleanup.destroyForcibly();
            }
        } catch (IOException ignored) {
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void removePortForward() {
        if (port <= 0) {
            return;
        }
        int portToRemove = port;
        port = 0;
        boolean interrupted = Thread.interrupted();
        try {
            BoundedProcessRunner.run(command("forward", "--remove", "tcp:" + portToRemove), Duration.ofSeconds(3));
        } catch (Exception ignored) {
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private ProcessBuilder command(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(adb);
        cmd.add("-s");
        cmd.add(serial);
        cmd.addAll(List.of(args));
        return new ProcessBuilder(cmd);
    }

    private String run(String... args) throws IOException, InterruptedException {
        checkCancellation();
        BoundedProcessRunner.ProcessResult result = BoundedProcessRunner.run(command(args), Duration.ofSeconds(5));
        if (result.timedOut() || result.exitCode() != 0) {
            throw new IOException("ADB command failed (" + String.join(" ", args) + "): " + result.output());
        }
        return result.output();
    }

    private static void checkCancellation() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Scrcpy stream operation cancelled");
        }
    }

    static void verifyServer(byte[] binary) throws IOException {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
            if (!SERVER_SHA256.equals(hash)) {
                throw new IOException("Android video server checksum mismatch; refusing launch");
            }
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 unavailable", error);
        }
    }

    static String launchScript(String remote, String scid, int maxSize, int fps, int bitrate) {
        validateRemote(remote);
        if (!scid.matches("[0-9a-f]{8}")) {
            throw new IllegalArgumentException("Invalid scrcpy socket ID: " + scid);
        }
        return "#!/system/bin/sh\n"
                + "echo \"$$ $(cut -d ' ' -f 22 /proc/$$/stat)\" > " + remote + ".pid || exit 1\n"
                + "CLASSPATH=" + remote + ".jar exec app_process / com.genymobile.scrcpy.Server 4.1 "
                + "scid=" + scid + " video=true audio=false control=false send_device_meta=false send_frame_meta=false "
                + "send_dummy_byte=true tunnel_forward=true max_size=" + maxSize + " max_fps=" + fps
                + " video_bit_rate=" + bitrate + " cleanup=true log_level=warn\n";
    }

    static String cleanupScript(String remote) {
        validateRemote(remote);
        return "if read -r pid started < " + remote + ".pid; then "
                + "case \"$pid:$started\" in *[!0-9:]*|:*) ;; *) "
                + "if [ \"$(cut -d ' ' -f 22 /proc/$pid/stat 2>/dev/null)\" = \"$started\" ] "
                + "; then case \"$(cat /proc/$pid/comm 2>/dev/null)\" in "
                + "app_process|app_process32|app_process64) kill -TERM \"$pid\" ;; esac; fi ;; esac; fi; "
                + "rm -f " + remote + ".pid " + remote + ".sh " + remote + ".jar\n";
    }

    private static void validateRemote(String remote) {
        if (!remote.matches("/data/local/tmp/frostguard-video-[0-9a-f-]{36}")) {
            throw new IllegalArgumentException("Unowned Android video path: " + remote);
        }
    }

    static int parseForwardedPort(String output) {
        if (output == null || output.isBlank()) {
            throw new IllegalArgumentException("No port output received from ADB forward");
        }
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.matches("[0-9]{1,5}")) {
                int port = Integer.parseInt(trimmed);
                if (port >= 1 && port <= 65535) {
                    return port;
                }
            }
        }
        throw new IllegalArgumentException("No valid port found in ADB forward output: " + output);
    }

    static VideoStreamMeta parseHeader(byte[] headerBytes) {
        if (headerBytes == null || headerBytes.length < 16) {
            throw new IllegalArgumentException("Stream header must be at least 16 bytes");
        }
        String codec = new String(headerBytes, 0, 4, StandardCharsets.US_ASCII);
        int flags = be32(headerBytes, 4);
        int width = be32(headerBytes, 8);
        int height = be32(headerBytes, 12);
        return new VideoStreamMeta(codec, flags, width, height);
    }

    static void readExact(InputStream in, byte[] buffer) throws IOException {
        int read = 0;
        while (read < buffer.length) {
            int count = in.read(buffer, read, buffer.length - read);
            if (count < 0) {
                throw new EOFException("Stream ended while reading " + buffer.length + " bytes; read " + read);
            }
            read += count;
        }
    }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static void deleteLocal(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            path.toFile().deleteOnExit();
        }
    }
}
