package dev.frostguard.tasks.lifecycle;

import dev.frostguard.engine.schedule.LaunchPoint;


import java.time.LocalDateTime;

import org.opencv.core.Mat;

import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.engine.emulator.ScrcpyStreamCapture;
import dev.frostguard.engine.emulator.ScreenRecordStreamCapture;
import dev.frostguard.engine.emulator.VideoStreamCapture;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.schedule.DelayedTask;

/**
 * Test Hook Loop Task â€” High-FPS Stream Edition
 *
 * <p>
 * Diagnostic task that continuously captures frames from the emulator and
 * performs template matching for the fishing hook image every frame.
 *
 * <h3>Capture modes (auto-selected, in priority order)</h3>
 * <ol>
 *   <li><b>Scrcpy stream</b> â€” Uses scrcpy-server H.264 video stream
 *       decoded by FFmpeg. <b>30â€“60 FPS</b>, 10â€“40 ms latency.
 *       <br>Requires: {@code lib/scrcpy/scrcpy-server*} + {@code lib/ffmpeg/ffmpeg.exe}</li>
 *   <li><b>Screenrecord stream</b> â€” Uses Android's built-in {@code screenrecord}
 *       piped through FFmpeg. <b>15â€“30 FPS</b>, ~30â€“60 ms latency.
 *       <br>Requires: {@code lib/ffmpeg/ffmpeg.exe} only</li>
 *   <li><b>ADB screencap</b> (fallback) â€” Classic per-frame ADB capture.
 *       ~3â€“5 FPS due to ~3.5 MB raw transfer per frame.</li>
 * </ol>
 */
public class TestHookLoopRoutine extends DelayedTask {

    // ---- Search area ----
    private static final int SCREEN_W = 720;
    private static final int SCREEN_H = 1280;
    private static final double HOOK_THRESHOLD = 80.0;

    // ---- Pre-allocated ROI points (avoid GC pressure) ----
    private static final PointData ROI_TOP_LEFT = new PointData(0, 0);
    private static final PointData ROI_BOTTOM_RIGHT = new PointData(SCREEN_W, SCREEN_H);

    // ---- Loop control ----
    private static final long MAX_DURATION_MS = 60_000L;
    private static final int CONFIG_CHECK_INTERVAL = 50;

    // ---- Stream configs ----
    private static final int STREAM_BIT_RATE = 4_000_000;  // 4 Mbps
    private static final int STREAM_MAX_FPS  = 30;         // 30 fps (software encoder on emulator)

    public TestHookLoopRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    protected boolean acceptsInjections() {
        return false;
    }

    @Override
    protected void execute() {

        logInfo("Will run for up to " + (MAX_DURATION_MS / 1000) + " seconds.");

        // â”€â”€ Pre-cache the template Mat once â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        Mat hookTemplate = OpenCvPatternLocator.getReferenceMatrix(TemplatesEnum.FISHING_HOOK.getTemplate());
        if (hookTemplate == null || hookTemplate.empty()) {
            logWarning("Could not load hook template - aborting.");
            reschedule(LocalDateTime.now().plusMinutes(5));
            return;
        }
        logInfo(String.format("Template loaded: %dx%d", hookTemplate.cols(), hookTemplate.rows()));

        // â”€â”€ Try stream capture in priority order â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        VideoStreamCapture stream = null;
        String streamMode = null;

        // Ordered list of stream factories to try
        String[][] streamAttempts = { {"SCRCPY"}, {"SCREENRECORD"} };

        for (String[] attempt : streamAttempts) {
            String mode = attempt[0];

            // Start the stream
            VideoStreamCapture candidate = null;
            if ("SCRCPY".equals(mode)) {
                candidate = tryStartScrcpy();
            } else if ("SCREENRECORD".equals(mode)) {
                candidate = tryStartScreenRecord();
            }
            if (candidate == null) continue;

            // Warm up â€” wait for first decoded frame
            int warmupMs = "SCRCPY".equals(mode) ? 30000 : 20000;
            logInfo(">>> " + mode + " STREAM MODE - warming up (max " + (warmupMs / 1000) + "s)... <<<");
            Mat warmup = candidate.waitForFrame(warmupMs);
            if (warmup != null) {
                warmup.release();
                logInfo("Stream warmed up - first frame received.");
                stream = candidate;
                streamMode = mode;
                break;
            } else {
                String err = candidate.getLastError();
                logWarning("No frame from " + mode + " after " + (warmupMs / 1000) + "s" +
                        (err != null ? " (" + err + ")" : "") +
                        " - trying next capture method.");
                candidate.stop();
            }
        }

        // 4. Fall back to ADB screencap if no stream worked
        if (stream == null) {
            logInfo(">>> ADB SCREENCAP MODE (fallback) - expecting 3-5 FPS <<<");
            logInfo("To enable streaming: place ffmpeg.exe in lib/ffmpeg/ (screenrecord) " +
                    "or also add scrcpy-server in lib/scrcpy/ (scrcpy).");
        }

        // â”€â”€ Main loop â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        try {
            if (stream != null) {
                runStreamLoop(stream, hookTemplate, streamMode);
            } else {
                runAdbLoop(hookTemplate);
            }
        } finally {
            if (stream != null) {
                stream.stop();
            }
        }

        reschedule(LocalDateTime.now().plusHours(24));
    }

    // ======================================================================
    // Stream loop â€” works with any VideoStreamCapture (scrcpy or screenrecord)
    // ======================================================================

    private void runStreamLoop(VideoStreamCapture stream, Mat hookTemplate, String modeName) {
        long loopStart = System.currentTimeMillis();
        int frameCount = 0;
        long totalGrabMs  = 0;
        long totalMatchMs = 0;
        int  nullFrames   = 0;

        while (System.currentTimeMillis() - loopStart < MAX_DURATION_MS) {

            if (Thread.currentThread().isInterrupted()) {
                logInfo("Thread interrupted - stopping loop.");
                break;
            }

            if (!stream.isRunning()) {
                String err = stream.getLastError();
                logWarning("Stream died" + (err != null ? ": " + err : "") + " - stopping loop.");
                break;
            }

            // Periodic config check
            if (frameCount % CONFIG_CHECK_INTERVAL == 0) {
                if (!isHookLoopEnabled()) break;
                checkPreemption();
            }

            // â”€â”€ 1. Grab decoded frame â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            long t0 = System.nanoTime();
            Mat frame = stream.grabFrame();
            long t1 = System.nanoTime();

            if (frame == null) {
                nullFrames++;
                try { Thread.sleep(1); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            // â”€â”€ 2. Template matching â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            long t2 = System.nanoTime();
            ImageSearchResultData hookResult = OpenCvPatternLocator.matchDirect(
                    frame, hookTemplate, ROI_TOP_LEFT, ROI_BOTTOM_RIGHT, HOOK_THRESHOLD);
            long t3 = System.nanoTime();

            frame.release();
            frameCount++;

            long grabMs  = (t1 - t0) / 1_000_000;
            long matchMs = (t3 - t2) / 1_000_000;
            totalGrabMs  += grabMs;
            totalMatchMs += matchMs;

            logFrameResult(frameCount, hookResult, grabMs, 0, matchMs);
        }

        // â”€â”€ Summary â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        long elapsed = System.currentTimeMillis() - loopStart;
        int totalDecoded = stream.getDecodedFrameCount();
        double fps = frameCount > 0 ? (frameCount * 1000.0 / elapsed) : 0;
        double decodeFps = totalDecoded > 0 ? (totalDecoded * 1000.0 / elapsed) : 0;
        long avgGrab  = frameCount > 0 ? totalGrabMs / frameCount : 0;
        long avgMatch = frameCount > 0 ? totalMatchMs / frameCount : 0;

        logInfo("=== " + modeName + " STREAM â€” Test Hook Loop completed ===");
        logInfo(String.format("Processed: %d frames | Decoded: %d | Null polls: %d",
                frameCount, totalDecoded, nullFrames));
        logInfo(String.format("Elapsed: %dms | Process FPS: %.1f | Decode FPS: %.1f",
                elapsed, fps, decodeFps));
        logInfo(String.format("Avg grab: %dms | Avg match: %dms | Avg total: %dms",
                avgGrab, avgMatch, avgGrab + avgMatch));
    }

    // ======================================================================
    // ADB screencap loop â€” fallback, 3-5 FPS
    // ======================================================================

    private void runAdbLoop(Mat hookTemplate) {
        long loopStart = System.currentTimeMillis();
        int frameCount = 0;
        long totalCaptureMs = 0;
        long totalConvertMs = 0;
        long totalMatchMs   = 0;

        while (System.currentTimeMillis() - loopStart < MAX_DURATION_MS) {

            if (Thread.currentThread().isInterrupted()) {
                logInfo("Thread interrupted - stopping loop.");
                break;
            }

            if (frameCount % CONFIG_CHECK_INTERVAL == 0) {
                if (!isHookLoopEnabled()) break;
                checkPreemption();
            }

            long t0 = System.nanoTime();
            RawImageData raw = emuManager.captureScreen(EMULATOR_NUMBER);
            long t1 = System.nanoTime();

            if (raw == null) {
                logWarning("Frame " + frameCount + " | ADB returned null.");
                sleepTask(200);
                continue;
            }

            long t2 = System.nanoTime();
            Mat frame = OpenCvPatternLocator.decodePixelsToMat(
                    raw.getData(), raw.getWidth(), raw.getHeight(), raw.getBpp());
            long t3 = System.nanoTime();

            long t4 = System.nanoTime();
            ImageSearchResultData hookResult = OpenCvPatternLocator.matchDirect(
                    frame, hookTemplate, ROI_TOP_LEFT, ROI_BOTTOM_RIGHT, HOOK_THRESHOLD);
            long t5 = System.nanoTime();

            frame.release();
            frameCount++;

            long captureMs = (t1 - t0) / 1_000_000;
            long convertMs = (t3 - t2) / 1_000_000;
            long matchMs   = (t5 - t4) / 1_000_000;
            totalCaptureMs += captureMs;
            totalConvertMs += convertMs;
            totalMatchMs   += matchMs;

            logFrameResult(frameCount, hookResult, captureMs, convertMs, matchMs);
        }

        long elapsed = System.currentTimeMillis() - loopStart;
        double fps = frameCount > 0 ? (frameCount * 1000.0 / elapsed) : 0;
        long avgCapture = frameCount > 0 ? totalCaptureMs / frameCount : 0;
        long avgConvert = frameCount > 0 ? totalConvertMs / frameCount : 0;
        long avgMatch   = frameCount > 0 ? totalMatchMs / frameCount : 0;

        logInfo("=== ADB SCREENCAP - Test Hook Loop completed ===");
        logInfo(String.format("Frames: %d | Elapsed: %dms | FPS: %.2f", frameCount, elapsed, fps));
        logInfo(String.format("Avg ADB: %dms | Avg convert: %dms | Avg match: %dms | Avg total: %dms",
                avgCapture, avgConvert, avgMatch, avgCapture + avgConvert + avgMatch));
    }

    // ======================================================================
    // Stream startup helpers
    // ======================================================================

    /**
     * Tries to start scrcpy-server stream. Returns {@code null} on failure.
     */
    private VideoStreamCapture tryStartScrcpy() {
        String scrcpyPath = ScrcpyStreamCapture.findScrcpyServer();
        String ffmpegPath = ScrcpyStreamCapture.findFfmpeg();

        if (scrcpyPath == null || ffmpegPath == null) {
            if (scrcpyPath == null) logInfo("scrcpy-server not found in lib/scrcpy/");
            if (ffmpegPath == null) logInfo("ffmpeg not found in lib/ffmpeg/");
            return null;
        }

        logInfo("Found scrcpy-server: " + scrcpyPath);
        logInfo("Found ffmpeg: " + ffmpegPath);

        try {
            String adbPath      = emuManager.getAdbPath();
            String deviceSerial = emuManager.getDeviceSerial(EMULATOR_NUMBER);

            if (deviceSerial == null || deviceSerial.isEmpty()) {
                logWarning("Could not resolve device serial for emulator " + EMULATOR_NUMBER);
                return null;
            }

            logInfo("Starting scrcpy stream: device=" + deviceSerial + " " +
                    SCREEN_W + "x" + SCREEN_H + " @" + STREAM_MAX_FPS + "fps " +
                    (STREAM_BIT_RATE / 1_000_000) + "Mbps");

            ScrcpyStreamCapture capture = new ScrcpyStreamCapture(
                    adbPath, deviceSerial, scrcpyPath, ffmpegPath,
                    SCREEN_W, SCREEN_H, STREAM_BIT_RATE, STREAM_MAX_FPS);
            capture.start();
            return capture;

        } catch (Exception e) {
            logWarning("Scrcpy stream failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Tries to start screenrecord-based stream. Returns {@code null} on failure.
     * Only needs FFmpeg (no scrcpy-server).
     */
    private VideoStreamCapture tryStartScreenRecord() {
        String ffmpegPath = ScrcpyStreamCapture.findFfmpeg();
        if (ffmpegPath == null) {
            logInfo("ffmpeg not found - cannot use screenrecord streaming");
            return null;
        }

        try {
            String adbPath      = emuManager.getAdbPath();
            String deviceSerial = emuManager.getDeviceSerial(EMULATOR_NUMBER);

            if (deviceSerial == null || deviceSerial.isEmpty()) {
                logWarning("Could not resolve device serial for emulator " + EMULATOR_NUMBER);
                return null;
            }

            logInfo("Starting screenrecord stream: device=" + deviceSerial + " " +
                    SCREEN_W + "x" + SCREEN_H + " @" + (STREAM_BIT_RATE / 1_000_000) + "Mbps");

            ScreenRecordStreamCapture capture = new ScreenRecordStreamCapture(
                    adbPath, deviceSerial, ffmpegPath,
                    SCREEN_W, SCREEN_H, STREAM_BIT_RATE, STREAM_MAX_FPS);
            capture.start();
            return capture;

        } catch (Exception e) {
            logWarning("Screenrecord stream failed: " + e.getMessage());
            return null;
        }
    }

    // ======================================================================
    // Utility helpers
    // ======================================================================

    private boolean isHookLoopEnabled() {
        try {
            Boolean enabled = dev.frostguard.engine.service.ProfileService.obtain().fetchAllAccounts().stream()
                    .filter(p -> p.getId().equals(profile.getId()))
                    .findFirst()
                    .map(p -> p.getConfig(ConfigurationKeyEnum.TEST_HOOK_LOOP_ENABLED_BOOL, Boolean.class))
                    .orElse(false);
            if (enabled != null && !enabled) {
                logInfo("Test Hook Loop disabled in configs - stopping.");
                return false;
            }
        } catch (Exception e) { /* continue */ }
        return true;
    }

    private void logFrameResult(int frameNum, ImageSearchResultData result,
                                 long captureMs, long convertMs, long matchMs) {
        long total = captureMs + convertMs + matchMs;
        if (result != null && result.isFound()) {
            int x = result.getPoint().getX();
            int y = result.getPoint().getY();
            double conf = result.getMatchPercentage();
            if (convertMs > 0) {
                logInfo(String.format(
                        "F%d | HOOK (%d,%d) %.1f%% | cap=%dms cvt=%dms match=%dms total=%dms",
                        frameNum, x, y, conf, captureMs, convertMs, matchMs, total));
            } else {
                logInfo(String.format(
                        "F%d | HOOK (%d,%d) %.1f%% | grab=%dms match=%dms total=%dms",
                        frameNum, x, y, conf, captureMs, matchMs, total));
            }
        } else {
            double conf = (result != null) ? result.getMatchPercentage() : 0;
            if (convertMs > 0) {
                logInfo(String.format(
                        "F%d | NO HOOK (best=%.1f%%) | cap=%dms cvt=%dms match=%dms total=%dms",
                        frameNum, conf, captureMs, convertMs, matchMs, total));
            } else {
                logInfo(String.format(
                        "F%d | NO HOOK (best=%.1f%%) | grab=%dms match=%dms total=%dms",
                        frameNum, conf, captureMs, matchMs, total));
            }
        }
    }

}
