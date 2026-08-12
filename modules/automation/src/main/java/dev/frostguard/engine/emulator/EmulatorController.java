package dev.frostguard.engine.emulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.*;

import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.api.configs.*;
import dev.frostguard.engine.emulator.instance.*;
import dev.frostguard.api.domain.*;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.schedule.QueuedEmulatorTask;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.ProfileService;
import net.sourceforge.tess4j.TesseractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * Central coordinator between the task scheduler and ADB-backed emulators.
 * Limits concurrent device usage via a slot pool; excess work is priority-queued.
 */
public class EmulatorController {

    private static final Logger LOG = LoggerFactory.getLogger(EmulatorController.class);
    private static final PointData ORIGIN = new PointData(0, 0);
    private static final PointData FULL   = new PointData(720, 1280);

    public static GameVersionEnum GAME = GameVersionEnum.GLOBAL;
    private static EmulatorController inst;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition     cond = lock.newCondition();
    // Serializes emulator boots across profile threads. Multiple InitializeRoutine/SkipTutorialRoutine
    // threads can reach launchEmulator() at the same time; booting 3+ instances concurrently spikes
    // host CPU/RAM/IO and causes random freezes. This lock guarantees one boot at a time, followed by
    // a configurable settle delay before the next launch is permitted.
    private final ReentrantLock emulatorLaunchLock = new ReentrantLock();
    private final PriorityQueue<QueuedEmulatorTask> queue   = new PriorityQueue<>();
    private final Set<Thread>        slots        = new HashSet<>();
    private final Map<String,Thread> dev2thread   = new HashMap<>();
    private final Map<Thread,String> thread2dev   = new HashMap<>();
    private final Map<String,String> dev2profile  = new ConcurrentHashMap<>();
    private final Map<String,Long>   cooldowns    = new HashMap<>();
    private EmulatorInstance backend;
    private int maxSlots = 3;

    private EmulatorController() {}
    public static EmulatorController getInstance() {
        if (inst == null) inst = new EmulatorController();
        return inst;
    }

    // --- init ---

    public void initialize() {
        resetSlots();
        Map<String,String> cfg = ConfigService.obtain().loadGlobalSettings();
        if (cfg == null || cfg.isEmpty())
            throw new IllegalStateException("Config missing — run initial setup");

        // game region
        String regionStr = cfg.getOrDefault(ConfigurationKeyEnum.GAME_VERSION_STRING.name(), "GLOBAL");
        try { GAME = GameVersionEnum.valueOf(regionStr); }
        catch (IllegalArgumentException e) { GAME = GameVersionEnum.GLOBAL; LOG.warn("Bad region '{}', using GLOBAL", regionStr); }

        // emulator backend
        String emuStr = cfg.get(ConfigurationKeyEnum.CURRENT_EMULATOR_STRING.name());
        if (emuStr == null || emuStr.isBlank()) throw new IllegalStateException("No emulator selected");
        EmulatorType kind = EmulatorType.valueOf(emuStr);
        String dir = cfg.get(kind.getConfigKey());
        if (dir == null || dir.isBlank()) throw new IllegalStateException("No path for " + kind.getDisplayName());
        backend = switch (kind) {
            case MUMU     -> new MuMuEmulatorInstance(dir);
            case MEMU     -> new MEmuEmulatorInstance(dir);
            case LDPLAYER -> new LDPlayerEmulatorInstance(dir);
        };
        LOG.info("Backend: {}", kind.getDisplayName());

        // slot capacity
        maxSlots = Optional.ofNullable(cfg.get(ConfigurationKeyEnum.MAX_RUNNING_EMULATORS_INT.name()))
                .map(Integer::parseInt)
                .orElse(Integer.parseInt(ConfigurationKeyEnum.MAX_RUNNING_EMULATORS_INT.getDefaultValue()));
    }

    private void requireBackend() {
        if (backend == null) throw new IllegalStateException("Backend not initialised");
    }

    // --- screen capture ---

    public RawImageData captureScreen(String idx) { requireBackend(); return backend.captureScreenshot(idx); }

    // --- input dispatch ---

    public TapInteractionService tapInteractions(String idx) {
        return tapInteractions(idx, null);
    }

    public TapInteractionService tapInteractions(String idx, TapInteractionService.PreTapCheck preTapCheck) {
        return new TapInteractionService(target -> dispatchTap(idx, target), preTapCheck, null);
    }

    private void dispatchTap(String idx, PointData pt) {
        requireBackend();
        LOG.info("{} tap ({},{}) dev {}", label(idx), pt.getX(), pt.getY(), idx);
        backend.touchArea(idx, pt, pt);
    }

    public void swipeScreen(String idx, PointData from, PointData to) {
        requireBackend();
        LOG.info("{} swipe dev {}", label(idx), idx);
        backend.swipe(idx, from, to);
    }

    public void swipeScreen(String idx, PointData from, PointData to, int durationMs) {
        requireBackend();
        LOG.info("{} swipe duration={}ms dev {}", label(idx), durationMs, idx);
        backend.swipe(idx, from, to, durationMs);
    }

    public void pressBack(String idx) { requireBackend(); LOG.info("{} back dev {}", label(idx), idx); backend.pressBackButton(idx); }
    public void writeText(String idx, String t) { requireBackend(); LOG.info("{} text dev {}", label(idx), idx); backend.writeText(idx, t); }
    public void clearText(String idx, int n)    { requireBackend(); LOG.info("{} erase {} dev {}", label(idx), n, idx); backend.clearText(idx, n); }

    // --- app management (direct delegates) ---

    public boolean isGameInstalled(String i)              { requireBackend(); return backend.isAppInstalled(i, GAME.getPackageName()); }

    /**
     * Launches an emulator instance, serializing concurrent boots.
     *
     * <p>Only one emulator may boot at a time across all profile threads. After a successful
     * launch request the caller holds the launch lock for an additional settle delay
     * ({@link ConfigurationKeyEnum#EMULATOR_LAUNCH_DELAY_MS_INT}, default 30s) so the next
     * emulator does not start until the current one has finished its most resource-intensive
     * boot phase. This prevents the host freezes observed when launching 3+ instances at once,
     * both at initial startup and when idle instances are recycled.
     */
    public void launchEmulator(String i) {
        requireBackend();
        emulatorLaunchLock.lock();
        try {
            LOG.info("{} acquired emulator launch lock (dev {})", label(i), i);
            backend.launchEmulator(i);

            long delayMs = readLaunchDelayMs();
            if (delayMs > 0) {
                LOG.info("Waiting {} ms before allowing another emulator launch...", delayMs);
                Thread.sleep(delayMs);
            }
        } catch (InterruptedException e) {
            // Preserve interrupt status so cooperative task cancellation still works.
            Thread.currentThread().interrupt();
            LOG.warn("Emulator launch wait interrupted for dev {}", i);
        } finally {
            emulatorLaunchLock.unlock();
            LOG.info("{} released emulator launch lock (dev {})", label(i), i);
        }
    }

    public void    closeEmulator(String i)                { requireBackend(); backend.closeEmulator(i); backend.invalidateAllCaches(i); }
    public void    launchApp(String i, String pkg)        { requireBackend(); backend.launchApp(i, pkg); }
    public void    sendGameToBackground(String i)         { requireBackend(); backend.sendGameToBackground(i); }
    public boolean isRunning(String i)                    { requireBackend(); return backend.isRunning(i); }
    public boolean isPackageRunning(String i, String pkg) { requireBackend(); return backend.isPackageRunning(i, pkg); }
    public void    restartAdbServer()                     { requireBackend(); backend.restartAdb(); }
    public boolean performAdbHealthCheck(String i)        { requireBackend(); return backend.performAdbHealthCheck(i); }
    public void    invalidateAllCaches(String i)          { requireBackend(); backend.invalidateAllCaches(i); }
    public String  getAdbPath()                           { requireBackend(); return backend.getAdbPath(); }
    public String  getDeviceSerial(String i)              { requireBackend(); return backend.getPublicDeviceSerial(i); }

    // --- OCR ---

    public String readText(String idx, PointData a, PointData b) throws IOException, TesseractException {
        requireBackend(); return backend.readText(idx, a, b);
    }
    public String readText(String idx, PointData a, PointData b, TesseractSettingsData c)
            throws IOException, TesseractException {
        requireBackend(); return backend.readText(idx, a, b, c);
    }

    // --- template matching ---

    public ImageSearchResultData locatePattern(String idx, TemplatesEnum t,
            PointData tl, PointData br, double th) {
        return locatePattern(idx, captureScreen(idx), t, tl, br, th);
    }

    public ImageSearchResultData locatePattern(String idx, RawImageData frame,
            TemplatesEnum t, PointData tl, PointData br, double th) {
        requireBackend();
        try { OpenCvPatternLocator.setContextLabel(label(idx));
              return OpenCvPatternLocator.locatePattern(frame, regionTpl(t.getTemplate()), tl, br, th);
        } finally { OpenCvPatternLocator.clearContextLabel(); }
    }

    public ImageSearchResultData locatePattern(String idx, TemplatesEnum t, double th) {
        return locatePattern(idx, captureScreen(idx), t, th);
    }
    public ImageSearchResultData locatePattern(String idx, RawImageData frame, TemplatesEnum t, double th) {
        return locatePattern(idx, frame, t, ORIGIN, FULL, th);
    }

    public ImageSearchResultData locatePatternMultiScale(String idx, RawImageData frame,
            TemplatesEnum t, PointData tl, PointData br, double th) {
        requireBackend();
        try { OpenCvPatternLocator.setContextLabel(label(idx));
              return OpenCvPatternLocator.locatePatternMultiScale(frame, regionTpl(t.getTemplate()), tl, br, th);
        } finally { OpenCvPatternLocator.clearContextLabel(); }
    }
    public ImageSearchResultData locatePatternMultiScale(String idx, TemplatesEnum t,
            PointData tl, PointData br, double th) {
        return locatePatternMultiScale(idx, captureScreen(idx), t, tl, br, th);
    }
    public ImageSearchResultData locatePatternMultiScale(String idx, TemplatesEnum t, double th) {
        return locatePatternMultiScale(idx, captureScreen(idx), t, ORIGIN, FULL, th);
    }
    public ImageSearchResultData locatePatternMultiScale(String idx, RawImageData frame, TemplatesEnum t, double th) {
        return locatePatternMultiScale(idx, frame, t, ORIGIN, FULL, th);
    }

    public ImageSearchResultData locatePatternMono(String idx, TemplatesEnum t,
            PointData tl, PointData br, double th) {
        requireBackend(); RawImageData frame = captureScreen(idx);
        try { OpenCvPatternLocator.setContextLabel(label(idx));
              return OpenCvPatternLocator.locatePatternMono(frame, regionTpl(t.getTemplate()), tl, br, th);
        } finally { OpenCvPatternLocator.clearContextLabel(); }
    }
    public ImageSearchResultData locatePatternMono(String idx, TemplatesEnum t, double th) {
        return locatePatternMono(idx, t, ORIGIN, FULL, th);
    }

    public List<ImageSearchResultData> locateAllPatternsMono(String idx, TemplatesEnum t,
            PointData tl, PointData br, double th, int max) {
        requireBackend(); RawImageData frame = captureScreen(idx);
        try { OpenCvPatternLocator.setContextLabel(label(idx));
              return OpenCvPatternLocator.locateAllPatternsMono(frame, regionTpl(t.getTemplate()), tl, br, th, max);
        } finally { OpenCvPatternLocator.clearContextLabel(); }
    }
    public List<ImageSearchResultData> locateAllPatternsMono(String idx, TemplatesEnum t, double th, int max) {
        return locateAllPatternsMono(idx, t, ORIGIN, FULL, th, max);
    }

    public List<ImageSearchResultData> locateAllPatterns(String idx, TemplatesEnum t,
            PointData tl, PointData br, double th, int max) {
        requireBackend(); RawImageData frame = captureScreen(idx);
        try { OpenCvPatternLocator.setContextLabel(label(idx));
              return OpenCvPatternLocator.locateAllPatterns(frame, regionTpl(t.getTemplate()), tl, br, th, max);
        } finally { OpenCvPatternLocator.clearContextLabel(); }
    }
    public List<ImageSearchResultData> locateAllPatterns(String idx, RawImageData frame,
            TemplatesEnum t, PointData tl, PointData br, double th, int max) {
        requireBackend();
        try { OpenCvPatternLocator.setContextLabel(label(idx));
              return OpenCvPatternLocator.locateAllPatterns(frame, regionTpl(t.getTemplate()), tl, br, th, max);
        } finally { OpenCvPatternLocator.clearContextLabel(); }
    }
    public List<ImageSearchResultData> locateAllPatterns(String idx, TemplatesEnum t, double th, int max) {
        return locateAllPatterns(idx, t, ORIGIN, FULL, th, max);
    }

    // --- file-based template searches ---

    public ImageSearchResultData locatePatternFromFile(String idx, String file,
            PointData tl, PointData br, double th) {
        return doFileMatch(idx, file, tl, br, th, false);
    }
    public ImageSearchResultData locatePatternFromFile(String idx, String file, double th) {
        return doFileMatch(idx, file, ORIGIN, FULL, th, false);
    }
    public ImageSearchResultData locatePatternMonoFromFile(String idx, String file,
            PointData tl, PointData br, double th) {
        return doFileMatch(idx, file, tl, br, th, true);
    }
    public ImageSearchResultData locatePatternMonoFromFile(String idx, String file, double th) {
        return doFileMatch(idx, file, ORIGIN, FULL, th, true);
    }

    private ImageSearchResultData doFileMatch(String idx, String file,
            PointData tl, PointData br, double th, boolean mono) {
        requireBackend();
        try {
            byte[] tplBytes = Files.readAllBytes(Path.of(file));
            RawImageData frame = captureScreen(idx);
            if (frame == null) return new ImageSearchResultData(false, null, 0.0);
            OpenCvPatternLocator.setContextLabel(label(idx));
            return mono
                    ? OpenCvPatternLocator.matchFromRawTemplateMono(frame, tplBytes, tl, br, th)
                    : OpenCvPatternLocator.matchFromRawTemplate(frame, tplBytes, tl, br, th);
        } catch (IOException e) {
            LOG.error("Template load failed '{}': {}", file, e.getMessage());
            return new ImageSearchResultData(false, null, 0.0);
        } finally { OpenCvPatternLocator.clearContextLabel(); }
    }

    private String regionTpl(String path) {
        if (GAME != GameVersionEnum.CHINA) return path;
        int dot = path.lastIndexOf('.');
        String cn = dot >= 0 ? path.substring(0, dot) + "_CH" + path.substring(dot) : path + "_CH";
        try (var s = OpenCvPatternLocator.class.getResourceAsStream(cn)) {
            if (s != null) return cn;
        } catch (Exception ignored) {}
        return path;
    }

    // --- slot management ---

    public void adquireEmulatorSlot(AccountDescriptor profile, QueuePositionListener listener)
            throws InterruptedException {
        Thread self = Thread.currentThread();
        String devIdx = profile.getEmulatorNumber();
        if (devIdx == null || devIdx.isBlank()) throw new IllegalArgumentException("Missing emulator number");

        lock.lock();
        try {
            // try reuse existing slot
            if (slots.contains(self)) {
                String mapped = thread2dev.get(self);
                if (devIdx.equals(mapped) && !devConflict(devIdx, self) && backend.isRunning(devIdx)) {
                    dev2profile.put(devIdx, profile.getName());
                    LOG.info("{} reusing slot", profile.getName()); profile.setQueuePosition(0); return;
                }
                slots.remove(self);
                if (mapped != null) {
                    dev2thread.remove(mapped);
                    dev2profile.remove(mapped);
                }
                thread2dev.remove(self);
            }
            // try immediate claim
            if (slots.size() < maxSlots && !devConflict(devIdx, self) && !coolingDown(devIdx)) {
                grant(self, devIdx, profile); return;
            }
            // queue
            QueuedEmulatorTask ticket = new QueuedEmulatorTask(self, profile);
            queue.add(ticket);
            LOG.info("{} queued (slots {}/{})", profile.getName(), slots.size(), maxSlots);
            while (true) {
                boolean atHead = queue.peek() == ticket;
                boolean room = slots.size() < maxSlots;
                boolean free = !devConflict(devIdx, self) && !coolingDown(devIdx);
                if (atHead && room && free) break;
                if (!atHead && room && free) {
                    QueuedEmulatorTask head = queue.peek();
                    if (head != null && devConflict(head.getEmulatorNumber(), head.getThread())) break;
                }
                cond.await(1, TimeUnit.SECONDS);
                int pos = rankOf(ticket); profile.setQueuePosition(pos); listener.onQueuePositionChanged(self, pos);
            }
            queue.remove(ticket);
            grant(self, devIdx, profile);
        } finally { lock.unlock(); }
    }

    public void releaseEmulatorSlot(AccountDescriptor profile) {
        Thread self = Thread.currentThread();
        lock.lock();
        try {
            profile.setQueuePosition(Integer.MAX_VALUE);
            String devIdx = thread2dev.get(self);
            if (slots.remove(self) && devIdx != null) {
                dev2thread.remove(devIdx); thread2dev.remove(self);
                dev2profile.remove(devIdx);
                long cd = readCooldownMs();
                if (cd > 0) cooldowns.put(devIdx, System.currentTimeMillis() + cd);
                LOG.info("{} released dev {}, {}/{}", profile.getName(), devIdx, slots.size(), maxSlots);
            }
            cond.signalAll();
        } finally { lock.unlock(); }
    }

    public void resetSlots() {
        lock.lock();
        try { queue.clear(); slots.clear(); dev2thread.clear(); thread2dev.clear(); dev2profile.clear(); cooldowns.clear(); cond.signalAll(); }
        finally { lock.unlock(); }
    }

    private void grant(Thread t, String d, AccountDescriptor p) {
        slots.add(t); dev2thread.put(d, t); thread2dev.put(t, d); dev2profile.put(d, p.getName()); p.setQueuePosition(0); cond.signalAll();
        LOG.info("{} got slot dev {}, {}/{}", p.getName(), d, slots.size(), maxSlots);
    }

    private boolean devConflict(String d, Thread t) {
        if (d == null || d.isBlank()) return false;
        Thread owner = dev2thread.get(d);
        return owner != null && !owner.equals(t);
    }

    private boolean coolingDown(String d) {
        Long exp = cooldowns.get(d);
        return exp != null && System.currentTimeMillis() < exp;
    }

    private int rankOf(QueuedEmulatorTask target) {
        int r = 1;
        for (QueuedEmulatorTask e : queue) { if (e.equals(target)) return r; r++; }
        return 0;
    }

    private long readCooldownMs() {
        return Optional.ofNullable(ConfigService.obtain().loadGlobalSettings())
                .map(c -> c.get(ConfigurationKeyEnum.PROFILE_SWITCH_COOLDOWN_MS_INT.name()))
                .map(Long::parseLong)
                .orElse(Long.parseLong(ConfigurationKeyEnum.PROFILE_SWITCH_COOLDOWN_MS_INT.getDefaultValue()));
    }

    private long readLaunchDelayMs() {
        try {
            return Optional.ofNullable(ConfigService.obtain().loadGlobalSettings())
                    .map(c -> c.get(ConfigurationKeyEnum.EMULATOR_LAUNCH_DELAY_MS_INT.name()))
                    .map(Long::parseLong)
                    .orElse(Long.parseLong(ConfigurationKeyEnum.EMULATOR_LAUNCH_DELAY_MS_INT.getDefaultValue()));
        } catch (NumberFormatException e) {
            long fallback = Long.parseLong(ConfigurationKeyEnum.EMULATOR_LAUNCH_DELAY_MS_INT.getDefaultValue());
            LOG.warn("Invalid emulator launch delay config, using default {} ms", fallback);
            return fallback;
        }
    }

    private String label(String idx) {
        try {
            String active = dev2profile.get(idx);
            if (active != null && !active.isBlank()) {
                return active;
            }
            return ProfileService.obtain().fetchAllAccounts().stream()
                    .filter(p -> idx.equals(p.getEmulatorNumber()))
                    .map(AccountDescriptor::getName).findFirst().orElse("Unknown");
        } catch (Exception e) { return "Unknown"; }
    }
}
