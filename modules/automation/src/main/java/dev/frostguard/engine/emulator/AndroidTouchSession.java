package dev.frostguard.engine.emulator;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Owned, control-only scrcpy 4.1 connection. No desktop window, video, audio, clipboard, or power changes. */
public final class AndroidTouchSession implements AutoCloseable {
    private static final String SERVER_RESOURCE = "/android/scrcpy-server-v4.1";
    static final String SERVER_SHA256 = "deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae";
    private final String adb, serial, remote;
    private Process server;
    private Socket socket;
    private AndroidTouchController controller;
    private String remoteSocket;
    private int port;
    private boolean uploaded, closed;

    private AndroidTouchSession(String adb, String serial) {
        if (adb == null || serial == null || adb.isBlank() || serial.isBlank()) {
            throw new IllegalArgumentException("ADB path and device serial required");
        }
        this.adb = adb; this.serial = serial;
        remote = "/data/local/tmp/frostguard-touch-" + UUID.randomUUID();
    }

    public static AndroidTouchSession open(String adb, String serial) throws IOException, InterruptedException {
        var session = new AndroidTouchSession(adb, serial);
        try {
            session.start();
            return session;
        } catch (IOException | InterruptedException | RuntimeException error) {
            session.close();
            throw error;
        }
    }

    private void start() throws IOException, InterruptedException {
        checkCancellation();
        byte[] binary;
        try (var resource = AndroidTouchSession.class.getResourceAsStream(SERVER_RESOURCE)) {
            if (resource == null) throw new IOException("Bundled Android control server absent");
            binary = resource.readAllBytes();
        }
        verifyServer(binary);
        String scid = String.format("%08x", new java.security.SecureRandom().nextInt(Integer.MAX_VALUE));
        remoteSocket = "localabstract:scrcpy_" + scid;
        Path localBinary = Files.createTempFile("frostguard-touch-", ".jar");
        Path localScript = null;
        try {
            localScript = Files.createTempFile("frostguard-touch-", ".sh");
            Files.write(localBinary, binary);
            Files.writeString(localScript, launchScript(remote, scid), StandardCharsets.US_ASCII);
            // A timed-out push can still have created a partial owned file.
            uploaded = true;
            run("push", localBinary.toString(), remote + ".jar");
            run("push", localScript.toString(), remote + ".sh");
            String forwarded = run("forward", "tcp:0", remoteSocket).trim();
            try { port = Integer.parseInt(forwarded); }
            catch (NumberFormatException error) { throw new IOException("ADB returned an invalid control port", error); }
            if (port < 1 || port > 65535) throw new IOException("ADB control port out of range");
            checkCancellation();
            server = command("exec-out", "sh", remote + ".sh")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            connect();
        } finally {
            deleteLocal(localBinary);
            if (localScript != null) deleteLocal(localScript);
        }
    }

    private void connect() throws IOException, InterruptedException {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            checkCancellation();
            if (!server.isAlive()) throw new IOException("Android control server exited");
            var candidate = new Socket();
            try {
                candidate.connect(new InetSocketAddress("127.0.0.1", port), 300);
                candidate.setTcpNoDelay(true);
                candidate.setSoTimeout(300);
                if (candidate.getInputStream().read() != 0) throw new IOException("Android control handshake absent");
                checkCancellation();
                socket = candidate;
                controller = new AndroidTouchController(socket.getOutputStream());
                return;
            } catch (IOException error) {
                candidate.close();
                Thread.sleep(50);
            } catch (InterruptedException error) {
                candidate.close();
                throw error;
            }
        }
        throw new IOException("Android control handshake timed out");
    }

    public int steer(int hookX, int hookY, int delta, int durationMs) throws IOException, InterruptedException {
        if (closed || controller == null || !server.isAlive()) throw new IOException("Android touch session unavailable");
        return controller.steer(hookX, hookY, delta, durationMs);
    }

    public void endSteering() throws IOException {
        if (controller != null) controller.endSteering();
    }

    public boolean isSteering() { return !closed && controller != null && controller.isSteering(); }

    public boolean canContinueSteering(int delta) {
        return !closed && controller != null && controller.canContinueSteering(delta);
    }

    public void primeSteering(int hookX, int hookY) throws IOException, InterruptedException {
        if (closed || controller == null || !server.isAlive()) throw new IOException("Android touch session unavailable");
        controller.primeSteering(hookX, hookY);
    }

    static void verifyServer(byte[] binary) throws IOException {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
            if (!SERVER_SHA256.equals(hash)) throw new IOException("Android control server checksum mismatch; refusing launch");
        } catch (NoSuchAlgorithmException error) { throw new IOException("SHA-256 unavailable", error); }
    }

    static String launchScript(String remote, String scid) {
        validateRemote(remote);
        if (!scid.matches("[0-9a-f]{8}")) throw new IllegalArgumentException("Invalid control socket ID");
        return "#!/system/bin/sh\n"
                + "echo \"$$ $(cut -d ' ' -f 22 /proc/$$/stat)\" > " + remote + ".pid || exit 1\n"
                + "CLASSPATH=" + remote + ".jar exec app_process / com.genymobile.scrcpy.Server 4.1 "
                + "scid=" + scid + " video=false audio=false control=true tunnel_forward=true "
                + "send_device_meta=false clipboard_autosync=false power_on=false stay_awake=false "
                + "cleanup=true log_level=warn\n";
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
        if (!remote.matches("/data/local/tmp/frostguard-touch-[0-9a-f-]{36}")) {
            throw new IllegalArgumentException("Unowned Android control path");
        }
    }

    static List<Integer> ownedForwardPorts(String listing, String serial, String remoteSocket) {
        var ports = new ArrayList<Integer>();
        for (String line : listing.split("\\R")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length != 3 || !fields[0].equals(serial) || !fields[2].equals(remoteSocket)
                    || !fields[1].matches("tcp:[0-9]{1,5}")) continue;
            int candidate = Integer.parseInt(fields[1].substring(4));
            if (candidate > 0 && candidate <= 65535 && !ports.contains(candidate)) ports.add(candidate);
        }
        return List.copyOf(ports);
    }

    private ProcessBuilder command(String... args) {
        var arguments = new ArrayList<String>();
        arguments.add(adb); arguments.add("-s"); arguments.add(serial); arguments.addAll(List.of(args));
        return new ProcessBuilder(arguments);
    }

    private String run(String... args) throws IOException, InterruptedException {
        checkCancellation();
        var result = BoundedProcessRunner.run(command(args), Duration.ofSeconds(3));
        if (result.timedOut() || result.exitCode() != 0) throw new IOException("Android control setup/cleanup failed");
        return result.output();
    }

    private static void checkCancellation() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Android control cancelled");
    }

    private static void deleteLocal(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException error) { path.toFile().deleteOnExit(); }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        boolean interrupted = Thread.interrupted();
        try {
            if (controller != null) try { controller.close(); } catch (IOException ignored) { }
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
            if (uploaded) stopOwnedServer();
            interrupted |= Thread.interrupted();
            // If forward allocation timed out, its port may exist despite missing command output.
            // Recover only forwards matching this exact serial and randomly owned socket name.
            if (port == 0 && remoteSocket != null) try {
                for (int owned : ownedForwardPorts(run("forward", "--list"), serial, remoteSocket)) {
                    run("forward", "--remove", "tcp:" + owned);
                }
            } catch (IOException ignored) { }
            catch (InterruptedException error) { interrupted = true; Thread.interrupted(); }
            if (port > 0 && port <= 65535) try { run("forward", "--remove", "tcp:" + port); }
            catch (IOException ignored) { } catch (InterruptedException error) { interrupted = true; Thread.interrupted(); }
        } finally {
            if (server != null && server.isAlive()) server.destroyForcibly();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void stopOwnedServer() {
        Process cleanup = null;
        try {
            cleanup = command("shell", "-T", "sh").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (var input = cleanup.getOutputStream()) { input.write(cleanupScript(remote).getBytes(StandardCharsets.US_ASCII)); }
            if (!cleanup.waitFor(1000, TimeUnit.MILLISECONDS)) cleanup.destroyForcibly();
        } catch (IOException ignored) {
            // Best effort only when the emulator disconnects; never kill other Android sessions.
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally { if (cleanup != null && cleanup.isAlive()) cleanup.destroyForcibly(); }
    }
}
