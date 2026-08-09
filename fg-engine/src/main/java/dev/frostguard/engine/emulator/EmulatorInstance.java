package dev.frostguard.engine.emulator;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import dev.frostguard.vision.ocr.TesseractOcrProvider;
import dev.frostguard.api.configs.GameVersionEnum;
import dev.frostguard.engine.error.ADBConnectionException;
import dev.frostguard.api.domain.*;
import com.android.ddmlib.*;
import net.sourceforge.tess4j.TesseractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * Abstract ADB-backed device driver.  Provides screenshot capture, input dispatch,
 * OCR, app lifecycle, and a 3-phase escalating retry (standard → emulator restart → post-restart).
 * Subclasses supply serial mapping and platform-specific commands.
 */
public abstract class EmulatorInstance {

    private static final Logger LOG = LoggerFactory.getLogger(EmulatorInstance.class);

    protected static final int RETRY_CEILING = 10, INTER_RETRY_MS = 3000;
    protected static final int BRIDGE_PROBE_LOOPS = 10, BRIDGE_PROBE_WAIT = 500;

    protected String consolePath;
    protected AndroidDebugBridge bridge;

    private final ConcurrentHashMap<String, IDevice>      devCache  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>         devExpiry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean>      runCache  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>         runExpiry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RawImageData> lastFrame = new ConcurrentHashMap<>();
    private final ReentrantLock bridgeMtx = new ReentrantLock();

    protected abstract String  getDeviceSerial(String idx);
    public    abstract void    launchEmulator(String idx);
    public    abstract void    closeEmulator(String idx);
    public    abstract boolean isRunning(String idx);

    protected EmulatorInstance(String consolePath) {
        this.consolePath = consolePath;
        initBridge();
    }

    // --- ADB bridge lifecycle ---

    protected void initBridge() {
        if (bridge != null) return;
        teardown(); AndroidDebugBridge.init(false);
        bridge = AndroidDebugBridge.createBridge(adbPath(), true, 5000, TimeUnit.MILLISECONDS);
    }

    public void restartAdb() {
        if (!bridgeMtx.tryLock()) { LOG.debug("ADB restart in progress elsewhere"); return; }
        try {
            teardown(); AndroidDebugBridge.init(false);
            bridge = AndroidDebugBridge.createBridge(adbPath(), true, 5000, TimeUnit.MILLISECONDS);
            devCache.clear(); devExpiry.clear(); runCache.clear(); runExpiry.clear();
            LOG.info("ADB bridge restarted");
        } finally { bridgeMtx.unlock(); }
    }

    private void teardown() {
        AndroidDebugBridge.disconnectBridge(5000, TimeUnit.MILLISECONDS);
        AndroidDebugBridge.terminate();
    }

    private String adbPath() {
        String wd = System.getProperty("user.dir");
        for (String sub : new String[]{"tools", "fg-app"}) {
            File f = new File(wd, sub + File.separator + "adb" + File.separator + "adb.exe");
            if (f.exists()) return f.getAbsolutePath();
        }
        return consolePath + File.separator + "adb.exe";
    }

    private void killAdb() {
        try {
            Process p = new ProcessBuilder(adbPath(), "kill-server")
                    .directory(new File(adbPath()).getParentFile()).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (Exception e) { LOG.error("kill-server failed: {}", e.getMessage()); }
    }

    public String getAdbPath()                     { return adbPath(); }
    public String getPublicDeviceSerial(String idx) { return getDeviceSerial(idx); }

    // --- device discovery ---

    private void waitForBridge() throws InterruptedException {
        for (int i = 0; i < BRIDGE_PROBE_LOOPS; i++) {
            if (bridge != null && bridge.hasInitialDeviceList()) return;
            Thread.sleep(BRIDGE_PROBE_WAIT);
        }
    }

    protected IDevice findDevice(String idx) throws InterruptedException {
        waitForBridge();
        String serial = getDeviceSerial(idx);
        IDevice d = scan(serial);
        if (d != null) return d;

        LOG.info("Device {} not in bridge, connecting", serial);
        if (!adbConnect(serial)) { LOG.warn("Cannot reach {}", serial); return null; }
        for (int i = 0; i < 5; i++) {
            d = scan(serial);
            if (d != null) { LOG.info("Connected {}", serial); return d; }
            Thread.sleep(1000);
        }
        LOG.warn("Device {} still absent after connect", serial);
        return null;
    }

    private IDevice scan(String serial) {
        for (IDevice d : bridge.getDevices()) if (serial.equals(d.getSerialNumber())) return d;
        return null;
    }

    private boolean adbConnect(String serial) {
        if (Thread.currentThread().isInterrupted()) return false;
        try {
            String ep = serial.startsWith("emulator-") ? "127.0.0.1:" + serial.substring(9) : serial;
            Process p = new ProcessBuilder(adbPath(), "connect", ep)
                    .directory(new File(adbPath()).getParentFile()).start();
            String out = new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
            return p.waitFor() == 0 && out != null && (out.contains("connected") || out.contains("already connected"));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
          catch (Exception e) { LOG.error("Connect error {}: {}", serial, e.getMessage()); }
        return false;
    }

    // --- caching ---

    protected IDevice getCachedDevice(String idx) throws InterruptedException {
        long now = System.currentTimeMillis();
        IDevice d = devCache.get(idx); Long ts = devExpiry.get(idx);
        if (d != null && ts != null && now - ts < 30_000 && d.isOnline()) return d;
        d = findDevice(idx);
        if (d != null) { devCache.put(idx, d); devExpiry.put(idx, now); }
        return d;
    }

    protected void invalidateDeviceCache(String idx) { devCache.remove(idx); devExpiry.remove(idx); }

    protected boolean isRunningCached(String idx) {
        long now = System.currentTimeMillis();
        Boolean v = runCache.get(idx); Long ts = runExpiry.get(idx);
        if (v != null && ts != null && now - ts < 5_000) return v;
        boolean live = isRunning(idx);
        runCache.put(idx, live); runExpiry.put(idx, now);
        return live;
    }

    protected void invalidateRunningStatusCache(String idx) { runCache.remove(idx); runExpiry.remove(idx); }
    public void invalidateAllCaches(String idx) { invalidateDeviceCache(idx); invalidateRunningStatusCache(idx); }

    // --- 3-phase retry ---

    protected <T> T withRetries(String idx, Function<IDevice, T> action, String tag) {
        if (!isRunning(idx)) LOG.warn("Device {} appears offline before {} — will attempt retries", idx, tag);

        // Phase 1: standard retries
        T result = retryLoop(idx, action, tag, "");
        if (result != null) return result;

        // Phase 2: emulator restart
        if (Thread.currentThread().isInterrupted())
            throw new ADBConnectionException("Interrupted before recovery for " + tag);
        LOG.warn("Recovering emulator for {} on dev {}", tag, idx);
        try { closeEmulator(idx); sleep(5000); launchEmulator(idx); sleep(15000); }
        catch (Exception e) { throw new ADBConnectionException("Recovery failed for " + tag, e); }

        // Phase 3: post-restart retries
        result = retryLoop(idx, action, tag, "post-restart ");
        if (result != null) return result;

        throw new ADBConnectionException("Exhausted retries for " + tag + " on " + idx);
    }

    private <T> T retryLoop(String idx, Function<IDevice, T> action, String tag, String prefix) {
        for (int i = 1; i <= RETRY_CEILING; i++) {
            if (Thread.currentThread().isInterrupted())
                throw new ADBConnectionException("Interrupted during " + prefix + tag);
            try {
                IDevice d = findDevice(idx);
                if (d == null) { if (i == RETRY_CEILING / 2) restartAdb(); sleep(INTER_RETRY_MS / 2); continue; }
                if (!d.isOnline()) { sleep(2000); continue; }
                return action.apply(d);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ADBConnectionException("Interrupted during " + prefix + tag, e);
            } catch (Exception e) {
                LOG.warn("{}Attempt {}/{} for {}: {}", prefix, i, RETRY_CEILING, tag, e.getMessage());
                if (i >= RETRY_CEILING - 2) { try { restartAdb(); } catch (Exception ignored) {} sleep(INTER_RETRY_MS); }
                else sleep(INTER_RETRY_MS / 2);
            }
        }
        return null;
    }

    // --- ADB health check ---

    public boolean performAdbHealthCheck(String idx) {
        LOG.info("ADB health check for dev {}", idx);
        if (probe(idx)) return true;

        LOG.warn("Failed — restarting bridge");
        try { restartAdb(); Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        if (probe(idx)) return true;

        LOG.warn("Still failing — kill-server + restart");
        try { killAdb(); Thread.sleep(2000); restartAdb(); Thread.sleep(3000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        return probe(idx);
    }

    private boolean probe(String idx) {
        try {
            invalidateDeviceCache(idx);
            IDevice d = findDevice(idx);
            if (d == null || !d.isOnline()) return false;
            CollectingOutputReceiver r = new CollectingOutputReceiver();
            d.executeShellCommand("echo adb_ok", r, 5000, TimeUnit.MILLISECONDS);
            return r.getOutput().trim().contains("adb_ok");
        } catch (Exception e) { return false; }
    }

    // --- screen capture ---

    public RawImageData captureScreenshot(String idx) {
        String serial = getDeviceSerial(idx);
        if (serial == null) throw new ADBConnectionException("No serial for dev " + idx);
        IDevice d;
        try { d = getCachedDevice(idx); } catch (Exception e) { throw new RuntimeException(e); }
        if (d == null || !d.isOnline()) throw new ADBConnectionException("Dev " + serial + " offline");

        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(720 * 1280 * 4 + 64);
            d.executeShellCommand("screencap", new CollectingOutputReceiver() {
                @Override public void addOutput(byte[] data, int off, int len) { buf.write(data, off, len); }
            }, 2000, TimeUnit.MILLISECONDS);
            byte[] raw = buf.toByteArray();
            if (raw.length < 12) throw new RuntimeException("screencap too small");
            int w = le32(raw, 0), h = le32(raw, 4), fmt = le32(raw, 8);
            int bitsPerPixel = (fmt == 1) ? 32 : 16;
            int bytesPerPixel = bitsPerPixel / 8;
            int headerLength = raw.length - (w * h * bytesPerPixel);
            if (headerLength < 12 || headerLength > 64) {
                headerLength = 12;
            }
            byte[] px = new byte[raw.length - headerLength];
            System.arraycopy(raw, headerLength, px, 0, px.length);
            RawImageData frame = RawImageData.capture(px, w, h, bitsPerPixel);
            lastFrame.put(idx, frame);
            return frame;
        } catch (com.android.ddmlib.TimeoutException e) { throw new RuntimeException("screencap timeout", e); }
          catch (Exception e) { throw new RuntimeException("capture error", e); }
    }

    private static int le32(byte[] b, int o) {
        return (b[o]&0xFF) | ((b[o+1]&0xFF)<<8) | ((b[o+2]&0xFF)<<16) | ((b[o+3]&0xFF)<<24);
    }

    // --- input ---

    // Package-private on purpose: only EmulatorController (same package) may
    // dispatch physical taps, and it is itself restricted to the shared input
    // layer by the repository-wide TapInputArchitectureTest rules.
    boolean touchArea(String idx, PointData a, PointData b)                    { return tap(idx, a, b, 1, 0); }
    boolean touchArea(String idx, PointData a, PointData b, int n, int delMs) { return tap(idx, a, b, n, delMs); }

    private boolean tap(String idx, PointData c1, PointData c2, int reps, int delMs) {
        return withRetries(idx, dev -> {
            Random rng = new Random();
            int x0 = Math.min(c1.getX(), c2.getX()), x1 = Math.max(c1.getX(), c2.getX());
            int y0 = Math.min(c1.getY(), c2.getY()), y1 = Math.max(c1.getY(), c2.getY());
            for (int t = 0; t < reps; t++) {
                int tx = x0 + rng.nextInt(Math.max(1, x1 - x0 + 1));
                int ty = y0 + rng.nextInt(Math.max(1, y1 - y0 + 1));
                try { dev.executeShellCommand("input tap " + tx + " " + ty, new NullOutputReceiver()); Thread.sleep(delMs); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
            return Boolean.TRUE;
        }, "tap");
    }

    public void swipe(String idx, PointData from, PointData to) {
        withRetries(idx, dev -> {
            try { dev.executeShellCommand("input swipe " + from.getX() + " " + from.getY() + " " + to.getX() + " " + to.getY(), new NullOutputReceiver()); }
            catch (Exception e) { throw new RuntimeException(e); }
            return Boolean.TRUE;
        }, "swipe");
    }

    public void pressBackButton(String idx) {
        withRetries(idx, dev -> {
            try { dev.executeShellCommand("input keyevent KEYCODE_BACK", new NullOutputReceiver()); }
            catch (Exception e) { throw new RuntimeException(e); }
            return Boolean.TRUE;
        }, "back");
    }

    public void writeText(String idx, String text) {
        withRetries(idx, dev -> {
            try { dev.executeShellCommand("input text \"" + esc(text) + "\"", new NullOutputReceiver()); }
            catch (Exception e) { throw new RuntimeException(e); }
            return Boolean.TRUE;
        }, "text");
    }

    public void clearText(String idx, int n) {
        withRetries(idx, dev -> {
            try { for (int c = 0; c < n; c++) { dev.executeShellCommand("input keyevent KEYCODE_DEL", new NullOutputReceiver()); Thread.sleep(50); } }
            catch (Exception e) { throw new RuntimeException(e); }
            return Boolean.TRUE;
        }, "clear");
    }

    private String esc(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace(" ", "%s").replace("\"", "\\\"").replace("'", "\\'")
                .replace("$", "\\$").replace("`", "\\`").replace("\\", "\\\\")
                .replace("&", "\\&").replace("|", "\\|").replace(";", "\\;")
                .replace("<", "\\<").replace(">", "\\>").replace("(", "\\(").replace(")", "\\)");
    }

    // --- app management ---

    public boolean isAppInstalled(String idx, String pkg) {
        return withRetries(idx, dev -> {
            try {
                StringBuilder out = new StringBuilder();
                dev.executeShellCommand("pm list packages | grep " + pkg, new IShellOutputReceiver() {
                    public void addOutput(byte[] d, int o, int l) { out.append(new String(d, o, l)); }
                    public void flush() {} public boolean isCancelled() { return false; }
                });
                return !out.toString().trim().isEmpty();
            } catch (Exception e) { throw new RuntimeException(e); }
        }, "installed");
    }

    public boolean isPackageRunning(String idx, String pkg) {
        return withRetries(idx, dev -> {
            try {
                for (String cmd : List.of("dumpsys window windows", "dumpsys window displays",
                        "dumpsys window", "dumpsys activity activities")) {
                    CollectingOutputReceiver r = new CollectingOutputReceiver();
                    dev.executeShellCommand(cmd, r, 5, TimeUnit.SECONDS);
                    for (String line : r.getOutput().split("\\r?\\n")) {
                        String t = line.trim();
                        boolean win = cmd.contains("window"), act = cmd.contains("activity");
                        if (win && (t.contains("mCurrentFocus") || t.contains("mFocusedApp")) && t.contains(pkg + "/")) return true;
                        if (act && t.contains("mResumedActivity") && t.contains(pkg + "/")) return true;
                    }
                }
                return false;
            } catch (Exception e) { throw new RuntimeException(e); }
        }, "running");
    }

    public void launchApp(String idx, String pkg) {
        withRetries(idx, dev -> {
            try { dev.executeShellCommand("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1", new NullOutputReceiver()); LOG.info("Launched {} on {}", pkg, idx); }
            catch (Exception e) { throw new RuntimeException(e); }
            return Boolean.TRUE;
        }, "launch");
    }

    public void sendGameToBackground(String idx) {
        withRetries(idx, dev -> {
            try { dev.executeShellCommand("input keyevent KEYCODE_HOME", new NullOutputReceiver()); }
            catch (Exception e) { throw new RuntimeException(e); }
            return Boolean.TRUE;
        }, "home");
    }

    // --- OCR ---

    public String readText(String idx, PointData a, PointData b) throws IOException, TesseractException {
        RawImageData scr = captureScreenshot(idx);
        if (scr == null) throw new IOException("Capture null");
        String lang = (EmulatorController.GAME == GameVersionEnum.CHINA) ? "eng+chi_sim" : "eng";
        return TesseractOcrProvider.recognizeText(scr, a, b, lang);
    }

    public String readText(String idx, PointData a, PointData b, TesseractSettingsData cfg) throws IOException, TesseractException {
        RawImageData scr = (cfg != null && cfg.isReuseLastImage()) ? lastFrame.getOrDefault(idx, captureScreenshot(idx)) : captureScreenshot(idx);
        if (scr == null) throw new IOException("Capture null");
        return TesseractOcrProvider.recognizeText(scr, a, b, cfg);
    }

    protected int getColorComponent(RawImage img, int base, int bit) {
        return bit == -1 ? 0 : img.data[base + (bit / 8)] & 0xFF;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
