package dev.frostguard.tasks.events;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.schedule.DelayedTask;

/**
 * Fishing Minigame Task â€” Predictive Constant-Velocity (PCV) Algorithm
 *
 * <p>
 * Automates the fishing minigame in Whiteout Survival. Fish have empirically
 * measured <b>constant</b> horizontal velocities (px/ms):
 * <ul>
 *   <li>Pufferfish: 0.030 (1.0 px/frame)</li>
 *   <li>Small fish: 0.017 (0.57 px/frame)</li>
 *   <li>Striped:    0.060 (2.0 px/frame)</li>
 *   <li>Redfish:    0.237 (8.0 px/frame)</li>
 * </ul>
 * All fish scroll upward at 0.690 px/ms (23 px/frame) â€” the hook-fish
 * relative vertical speed. Fish bounce perfectly off screen edges (vx inverts).
 *
 * <p>
 * <b>Algorithm:</b> Template matching is performed every N ticks to detect new
 * fish and correct positional drift. Between scans, fish positions are predicted
 * using constant-velocity physics with wall bouncing. Danger zones are computed
 * from predicted future positions, and the hook is steered into safe gaps.
 */
public class FishingMinigameRoutine extends DelayedTask {

    // â”€â”€ Game area â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private static final int SCREEN_W   = 720;
    private static final int UI_TOP_Y   = 100;   // hook sits at Yâ‰ˆ127 during active play
    private static final int PLAY_BOT_Y = 1200;

    // â”€â”€ Hook geometry â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private static final int HOOK_W = 18;
    private static final int HOOK_H = 19;

    // â”€â”€ Constant fish horizontal velocities (px/ms, absolute) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private static final float VX_PUFFER = 0.030f;   // 1.0 px/frame @ 30fps
    private static final float VX_SMALL  = 0.017f;   // 0.57 px/frame
    private static final float VX_STRIPE = 0.060f;   // 2.0 px/frame
    private static final float VX_RED    = 0.237f;   // 8.0 px/frame

    // â”€â”€ Vertical scroll speed (hook-fish relative, all types) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private static final float VY_SCROLL = 0.690f;   // 23 px/frame

    // â”€â”€ Fish bounding-box sizes (from templates) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private static final int PUFFER_W = 105, PUFFER_H = 49;
    private static final int REDFISH_W = 52, REDFISH_H = 10;
    private static final int SMALL_W  = 24,  SMALL_H  = 12;
    private static final int STRIPE_W = 36,  STRIPE_H = 41;

    // â”€â”€ Detection thresholds (0â€“100) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private static final double THR_HOOK   = 80.0;
    private static final double THR_PUFFER = 72.0;
    private static final double THR_RED    = 75.0;
    private static final double THR_SMALL  = 72.0;
    private static final double THR_STRIPE = 72.0;

    // â”€â”€ Algorithm tuning â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /** Fish ahead to consider for danger zones. */
    private static final int LOOKAHEAD_FISH = 3;
    /** Max template hits per fish type per scan. */
    private static final int MAX_FISH_PER_TYPE = 8;
    /** Game loop tick interval (ms). */
    private static final long TICK_MS = 50L;
    /** Padding added to each side of a danger zone (px). */
    private static final int MARGIN_PX = 5;
    /** Safety timeout for the entire minigame (ms). */
    private static final long MAX_DURATION_MS = 30_000L;
    /** Minimum displacement before issuing a swipe (px). */
    private static final int SWIPE_DEADZONE_PX = 6;
    /** Template scan interval (every N ticks) for drift correction + new fish. */
    private static final int SCAN_INTERVAL = 5;
    /** Max pixel distance to associate a detection with an existing track. */
    private static final float TRACK_MATCH_DIST = 60.0f;
    /** Remove single-detection tracks after this many consecutive missed scans. */
    private static final int MAX_MISSED_SCANS = 10;
    /** Consecutive hook misses allowed before deciding the game ended. */
    private static final int MAX_HOOK_MISS = 50;
    /** Hook moves 1.2Ã— the commanded swipe distance (empirical overshoot). */
    private static final float SWIPE_OVERSHOOT = 1.2f;

    // â”€â”€ Runtime state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private final List<TrackedFish> trackedFish = new ArrayList<>();
    private int nextTrackId = 0;

    public FishingMinigameRoutine(dev.frostguard.api.domain.AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    protected boolean acceptsInjections() {
        return false;
    }

    // =========================================================================
    // MAIN GAME LOOP
    // =========================================================================

    /** Max time (ms) to wait for the hook to appear before giving up. */
    private static final long HOOK_WAIT_TIMEOUT_MS = 10_000L;
    /** Polling interval (ms) while waiting for the hook. */
    private static final long HOOK_WAIT_POLL_MS = 200L;

    @Override
    protected void execute() {
        trackedFish.clear();
        nextTrackId = 0;

        // â”€â”€ 0. Wait for the hook to appear (game loading buffer) â”€â”€â”€â”€â”€â”€â”€â”€â”€
        if (!waitForHook()) {
            logInfo("Fishing Minigame: hook never appeared - aborting.");
            reschedule(LocalDateTime.now().plusHours(6));
            return;
        }
        logInfo("Fishing Minigame: hook detected - entering game loop.");

        long start = System.currentTimeMillis();
        int ticks = 0;
        int hookMisses = 0;
        int hookCx = 0, hookCy = 0; // last known hook position
        int lastSwipeDeltaX = 0;    // signed px of last commanded swipe
        boolean swipedLastTick = false;

        // Timing accumulators for summary
        long totalCapMs   = 0;
        long totalHookMs  = 0;
        long totalScanMs  = 0;
        long totalLogicMs = 0;
        int  hookHits     = 0;
        int  scanTicks    = 0;

        while (System.currentTimeMillis() - start < MAX_DURATION_MS) {
            checkPreemption();
            long now = System.currentTimeMillis();

            // â”€â”€ 1. Physics prediction for all tracked fish â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            for (TrackedFish f : trackedFish) {
                f.predict(now);
            }

            // â”€â”€ 2. Prune fish that scrolled off the top â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            trackedFish.removeIf(f -> f.cy < UI_TOP_Y - f.h);

            // â”€â”€ 3. Capture one screenshot â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            long t0 = System.nanoTime();
            RawImageData raw = emuManager.captureScreen(EMULATOR_NUMBER);
            long t1 = System.nanoTime();
            long capMs = (t1 - t0) / 1_000_000;
            totalCapMs += capMs;

            // â”€â”€ 4. Locate hook (every tick for responsive control) â”€â”€â”€â”€â”€â”€â”€â”€
            long t2 = System.nanoTime();
            ImageSearchResultData hookResult = emuManager.locatePattern(
                    EMULATOR_NUMBER, raw, TemplatesEnum.FISHING_HOOK,
                    new PointData(0, UI_TOP_Y),
                    new PointData(SCREEN_W, PLAY_BOT_Y), THR_HOOK);
            long t3 = System.nanoTime();
            long hookMs = (t3 - t2) / 1_000_000;
            totalHookMs += hookMs;

            if (hookResult != null && hookResult.isFound()) {
                hookCx = hookResult.getPoint().getX();
                hookCy = hookResult.getPoint().getY();
                hookMisses = 0;
                hookHits++;
                swipedLastTick = false;
                double conf = hookResult.getMatchPercentage();
                logInfo(String.format("T%d | HOOK (%d,%d) %.1f%% | cap=%dms hook=%dms",
                        ticks, hookCx, hookCy, conf, capMs, hookMs));
            } else {
                hookMisses++;
                double bestConf = (hookResult != null) ? hookResult.getMatchPercentage() : 0;
                if (hookMisses >= MAX_HOOK_MISS) {
                        logInfo(String.format("T%d | NO HOOK (best=%.1f%%) - lost %d ticks, game ended.",
                            ticks, bestConf, hookMisses));
                    break;
                }
                // Smart prediction: if a swipe just happened, estimate
                // where the hook moved (1.2Ã— overshoot).
                if (swipedLastTick && hookMisses == 1) {
                    int predicted = hookCx + Math.round(lastSwipeDeltaX * SWIPE_OVERSHOOT);
                    predicted = Math.max(HOOK_W / 2, Math.min(SCREEN_W - HOOK_W / 2, predicted));
                        logInfo(String.format("T%d | NO HOOK (best=%.1f%%) - post-swipe predict X=%d (was %d, delta=%+d) | cap=%dms hook=%dms",
                            ticks, bestConf, predicted, hookCx, lastSwipeDeltaX, capMs, hookMs));
                    hookCx = predicted;
                } else {
                        logInfo(String.format("T%d | NO HOOK (best=%.1f%%) - miss %d/%d | cap=%dms hook=%dms",
                            ticks, bestConf, hookMisses, MAX_HOOK_MISS, capMs, hookMs));
                }
                swipedLastTick = false;
                // Fall through - still scan for fish and compute danger zones
                // using last known / predicted hook position.
            }

            // â”€â”€ 5. Periodic template scan â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            long scanMs = 0;
            if (ticks % SCAN_INTERVAL == 0) {
                long s0 = System.nanoTime();
                List<FishDetection> detections = scanForFish(raw, hookCy);
                reconcileTracks(detections, now);
                long s1 = System.nanoTime();
                scanMs = (s1 - s0) / 1_000_000;
                totalScanMs += scanMs;
                scanTicks++;
                // Log each detection position
                StringBuilder detSb = new StringBuilder();
                for (FishDetection d : detections) {
                    detSb.append(String.format("%s@(%d,%d) ", d.label, d.cx, d.cy));
                }
                logInfo(String.format("T%d | SCAN %d det, %d tracked: %s| scan=%dms",
                        ticks, detections.size(), trackedFish.size(), detSb.toString(), scanMs));
            }

            // â”€â”€ 6. Log tracked fish state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            int belowCount = 0;
            StringBuilder fishSb = new StringBuilder();
            for (TrackedFish f : trackedFish) {
                fishSb.append(String.format("%s#%d@(%.0f,%.0f,vx=%.3f) ", f.label, f.id, f.cx, f.cy, f.vx));
                if (f.cy > hookCy) belowCount++;
            }
            if (!trackedFish.isEmpty()) {
                logInfo(String.format("T%d | FISH %d tracked, %d below hookY=%d: %s",
                        ticks, trackedFish.size(), belowCount, hookCy, fishSb.toString()));
            }

            // â”€â”€ 7. Danger zones from predicted positions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            long l0 = System.nanoTime();
            List<int[]> dangers = computeDangerZones(hookCx, hookCy);

            // Log danger zone details
            if (!dangers.isEmpty()) {
                StringBuilder zSb = new StringBuilder();
                for (int[] d : dangers) zSb.append(String.format("[%d,%d] ", d[0], d[1]));
                logInfo(String.format("T%d | DANGER %d zones: %s", ticks, dangers.size(), zSb.toString()));
            }

            // â”€â”€ 8. Pick safe target â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            int targetX = chooseSafeTarget(hookCx, dangers);
            long l1 = System.nanoTime();
            long logicMs = (l1 - l0) / 1_000_000;
            totalLogicMs += logicMs;

            logInfo(String.format("T%d | TARGET=%d hookX=%d delta=%d deadzone=%d",
                    ticks, targetX, hookCx, targetX - hookCx, SWIPE_DEADZONE_PX));

            // â”€â”€ 9. Swipe if needed â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (Math.abs(targetX - hookCx) > SWIPE_DEADZONE_PX) {
                int swipeY = Math.min(hookCy + 60, PLAY_BOT_Y - 10);
                lastSwipeDeltaX = targetX - hookCx;
                emuManager.swipeScreen(EMULATOR_NUMBER,
                        new PointData(hookCx, swipeY),
                        new PointData(targetX, swipeY));
                swipedLastTick = true;
                logInfo(String.format("T%d | SWIPE (%d,%d)->(%d,%d) | delta=%d",
                        ticks, hookCx, swipeY, targetX, swipeY, lastSwipeDeltaX));
            } else {
                swipedLastTick = false;
            }

            ticks++;
            long elapsed = System.currentTimeMillis() - now;
            if (elapsed < TICK_MS) {
                sleepTask(TICK_MS - elapsed);
            }
        }

        // â”€â”€ Summary â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        long totalElapsed = System.currentTimeMillis() - start;
        double fps = ticks > 0 ? (ticks * 1000.0 / totalElapsed) : 0;
        long avgCap   = ticks > 0 ? totalCapMs / ticks : 0;
        long avgHook  = ticks > 0 ? totalHookMs / ticks : 0;
        long avgScan  = scanTicks > 0 ? totalScanMs / scanTicks : 0;
        long avgLogic = hookHits > 0 ? totalLogicMs / hookHits : 0;

        logInfo("=== Fishing Minigame completed ===");
        logInfo(String.format("Ticks: %d | Elapsed: %dms | FPS: %.1f", ticks, totalElapsed, fps));
        logInfo(String.format("Hook hits: %d/%d (%.0f%%) | Fish tracked: %d",
                hookHits, ticks, ticks > 0 ? hookHits * 100.0 / ticks : 0, trackedFish.size()));
        logInfo(String.format("Avg cap: %dms | Avg hook: %dms | Avg scan: %dms | Avg logic: %dms",
                avgCap, avgHook, avgScan, avgLogic));
        reschedule(LocalDateTime.now().plusHours(6));
    }

    // =========================================================================
    // HOOK WAIT
    // =========================================================================

    /**
     * Polls for the hook template every {@link #HOOK_WAIT_POLL_MS} ms for up to
     * {@link #HOOK_WAIT_TIMEOUT_MS} ms. Returns {@code true} as soon as the
     * hook is found, or {@code false} if the timeout expires.
     */
    private boolean waitForHook() {
        long deadline = System.currentTimeMillis() + HOOK_WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            checkPreemption();

            RawImageData raw = emuManager.captureScreen(EMULATOR_NUMBER);
            ImageSearchResultData hookResult = emuManager.locatePattern(
                    EMULATOR_NUMBER, raw, TemplatesEnum.FISHING_HOOK,
                    new PointData(0, UI_TOP_Y),
                    new PointData(SCREEN_W, PLAY_BOT_Y), THR_HOOK);

            if (hookResult != null && hookResult.isFound()) {
                return true;
            }

            logInfo("Waiting for hook... ("
                    + (deadline - System.currentTimeMillis()) / 1000 + "s remaining)");
            sleepTask(HOOK_WAIT_POLL_MS);
        }
        return false;
    }

    // =========================================================================
    // PERIODIC TEMPLATE SCANNING
    // =========================================================================

    /**
     * Scans for all fish types below the hook using template matching.
     * Only right-facing templates are used; detected fish are assigned
     * positive vx. Wall bounces (vx inversion) are handled by physics.
     */
    private List<FishDetection> scanForFish(RawImageData raw, int hookCy) {
        List<FishDetection> result = new ArrayList<>();
        // Always scan the full playable area so fish are tracked even when
        // the hook is near the bottom â€” they'll be ready for the next wave.
        int searchTopY = UI_TOP_Y;

        result.addAll(scanType(raw, TemplatesEnum.FISHING_PUFFERFISH,
                THR_PUFFER, PUFFER_W, PUFFER_H, VX_PUFFER, searchTopY, "puffer"));
        result.addAll(scanType(raw, TemplatesEnum.FISHING_REDFISH,
                THR_RED, REDFISH_W, REDFISH_H, VX_RED, searchTopY, "red"));
        result.addAll(scanType(raw, TemplatesEnum.FISHING_SMALLFISH,
                THR_SMALL, SMALL_W, SMALL_H, VX_SMALL, searchTopY, "small"));
        result.addAll(scanType(raw, TemplatesEnum.FISHING_STRIPEFISH,
                THR_STRIPE, STRIPE_W, STRIPE_H, VX_STRIPE, searchTopY, "stripe"));

        return result;
    }

    private List<FishDetection> scanType(RawImageData raw, TemplatesEnum tmpl,
            double threshold, int w, int h, float absVx, int topY, String label) {
        List<FishDetection> out = new ArrayList<>();
        List<ImageSearchResultData> hits = emuManager.locateAllPatterns(
                EMULATOR_NUMBER, raw, tmpl,
                new PointData(0, topY),
                new PointData(SCREEN_W, PLAY_BOT_Y),
                threshold, MAX_FISH_PER_TYPE);

        if (hits == null) return out;
        for (ImageSearchResultData hit : hits) {
            if (hit.isFound()) {
                FishDetection d = new FishDetection();
                d.label = label;
                d.cx = hit.getPoint().getX();
                d.cy = hit.getPoint().getY();
                d.w = w;
                d.h = h;
                d.absVx = absVx;
                out.add(d);
            }
        }
        return out;
    }

    // =========================================================================
    // TRACK RECONCILIATION
    // =========================================================================

    /**
     * Matches template detections to existing physics-tracked fish.
     * <ul>
     *   <li>Matched tracks: correct positional drift, confirm direction.</li>
     *   <li>Unmatched detections: spawn new tracks (right-facing, vx &gt; 0).</li>
     *   <li>Unmatched tracks: increment miss counter; remove stale
     *       single-detection tracks. Confirmed tracks persist (they may be
     *       left-facing after a bounce and temporarily undetectable).</li>
     * </ul>
     */
    private void reconcileTracks(List<FishDetection> detections, long now) {
        boolean[] detUsed   = new boolean[detections.size()];
        boolean[] tkMatched = new boolean[trackedFish.size()];

        // Build candidate pairs sorted by distance (greedy nearest-neighbour)
        List<int[]> pairs = new ArrayList<>();
        for (int ti = 0; ti < trackedFish.size(); ti++) {
            TrackedFish t = trackedFish.get(ti);
            for (int di = 0; di < detections.size(); di++) {
                FishDetection d = detections.get(di);
                if (!t.label.equals(d.label)) continue;
                float dist = (float) Math.hypot(t.cx - d.cx, t.cy - d.cy);
                if (dist < TRACK_MATCH_DIST) {
                    pairs.add(new int[]{ti, di, (int) (dist * 100)});
                }
            }
        }
        pairs.sort(Comparator.comparingInt(a -> a[2]));

        for (int[] p : pairs) {
            int ti = p[0], di = p[1];
            if (tkMatched[ti] || detUsed[di]) continue;

            TrackedFish t = trackedFish.get(ti);
            FishDetection d = detections.get(di);

            // Drift correction: snap to detected position
            t.cx = d.cx;
            t.cy = d.cy;
            // Right-facing template matched â†’ fish is facing right now
            t.vx = d.absVx;
            t.lastPredictMs = now;
            t.missedScans = 0;
            t.scanCount++;

            tkMatched[ti] = true;
            detUsed[di]   = true;
        }

        // Age unmatched tracks
        for (int ti = 0; ti < trackedFish.size(); ti++) {
            if (!tkMatched[ti]) {
                trackedFish.get(ti).missedScans++;
            }
        }

        // Remove stale single-detection tracks (probable false positives).
        // Confirmed tracks (scanCount > 1) are kept â€” they may be left-facing
        // after a wall bounce and temporarily invisible to right-facing templates.
        trackedFish.removeIf(t -> t.missedScans > MAX_MISSED_SCANS && t.scanCount <= 1);

        // Spawn new tracks for unmatched detections
        for (int di = 0; di < detections.size(); di++) {
            if (detUsed[di]) continue;
            FishDetection d = detections.get(di);

            TrackedFish t = new TrackedFish();
            t.id            = nextTrackId++;
            t.label         = d.label;
            t.cx            = d.cx;
            t.cy            = d.cy;
            t.vx            = d.absVx;   // right-facing â†’ positive vx
            t.absVx         = d.absVx;
            t.w             = d.w;
            t.h             = d.h;
            t.lastPredictMs = now;
            t.missedScans   = 0;
            t.scanCount     = 1;

            trackedFish.add(t);
        }
    }

    // =========================================================================
    // DANGER ZONE COMPUTATION
    // =========================================================================

    /**
     * Computes danger zones for the nearest threats below the hook using
     * predicted positions from constant-velocity physics.
     */
    private List<int[]> computeDangerZones(int hookCx, int hookCy) {
        List<TrackedFish> below = new ArrayList<>();
        for (TrackedFish f : trackedFish) {
            if (f.cy > hookCy) below.add(f);
        }
        below.sort(Comparator.comparingDouble(f -> f.cy));

        List<int[]> dangers = new ArrayList<>();
        int considered = 0;
        for (TrackedFish f : below) {
            if (considered >= LOOKAHEAD_FISH) break;
            int[] zone = computeDangerZone(f, hookCy);
            if (zone != null) {
                dangers.add(zone);
                considered++;
            }
        }
        return dangers;
    }

    /**
     * Computes the horizontal danger zone for a single tracked fish.
     * Projects the fish's X position over the time window when the hook's
     * Y band will overlap the fish's Y band, using constant-velocity physics
     * with wall bouncing.
     *
     * @return {dangerLeft, dangerRight} in screen pixels, or null if not a threat.
     */
    private int[] computeDangerZone(TrackedFish f, int hookCy) {
        float fishTop    = f.cy - f.h / 2.0f;
        float fishBottom = f.cy + f.h / 2.0f;
        float hookBottom = hookCy + HOOK_H / 2.0f;
        float hookTop    = hookCy - HOOK_H / 2.0f;

        // Time window (ms) when hook Y-band overlaps fish Y-band
        float tArrive = (fishTop - hookBottom) / VY_SCROLL;
        float tDepart = (fishBottom - hookTop) / VY_SCROLL;

        if (tDepart <= 0) return null;   // already passed
        tArrive = Math.max(0, tArrive);

        // Sample fish X at several points to catch mid-window bounces
        float xMin = Float.MAX_VALUE, xMax = -Float.MAX_VALUE;
        int samples = 5;
        for (int i = 0; i <= samples; i++) {
            float t = tArrive + (tDepart - tArrive) * i / samples;
            float x = projectX(f.cx, f.vx, f.w, t);
            xMin = Math.min(xMin, x);
            xMax = Math.max(xMax, x);
        }

        float halfPad = f.w / 2.0f + HOOK_W / 2.0f + MARGIN_PX;
        int dangerLeft  = Math.max(0, (int) (xMin - halfPad));
        int dangerRight = Math.min(SCREEN_W, (int) (xMax + halfPad));

        return new int[]{dangerLeft, dangerRight};
    }

    /**
     * Projects a fish's center X forward by {@code timeMs} milliseconds,
     * reflecting perfectly off screen edges. Pure function â€” does not modify
     * any tracked fish state.
     */
    private static float projectX(float x, float vx, int fishW, float timeMs) {
        if (vx == 0 || timeMs <= 0) return x;

        float remaining = timeMs;
        float curX  = x;
        float curVx = vx;
        float halfW = fishW / 2.0f;
        float leftWall  = halfW;
        float rightWall = SCREEN_W - halfW;

        int maxBounces = 100;
        while (remaining > 0 && maxBounces-- > 0) {
            if (curVx > 0) {
                float ttb = (rightWall - curX) / curVx;
                if (ttb < 0) { curVx = -curVx; continue; }
                if (ttb >= remaining) return curX + curVx * remaining;
                curX = rightWall;
                remaining -= ttb;
                curVx = -curVx;
            } else {
                float ttb = (curX - leftWall) / (-curVx);
                if (ttb < 0) { curVx = -curVx; continue; }
                if (ttb >= remaining) return curX + curVx * remaining;
                curX = leftWall;
                remaining -= ttb;
                curVx = -curVx;
            }
        }
        return curX;
    }

    // =========================================================================
    // SAFE TARGET SELECTION
    // =========================================================================

    /**
     * Finds the safest X for the hook given a list of danger zones.
     * <ol>
     *   <li>Computes safe gaps between and around danger zones.</li>
     *   <li>If hook is already in a safe gap, stay put.</li>
     *   <li>Otherwise move to the nearest gap centre.</li>
     * </ol>
     */
    private int chooseSafeTarget(int hookCx, List<int[]> dangers) {
        int minX = HOOK_W / 2;
        int maxX = SCREEN_W - HOOK_W / 2;

        if (dangers.isEmpty()) return hookCx;

        List<int[]> sorted = new ArrayList<>(dangers);
        sorted.sort(Comparator.comparingInt(d -> d[0]));

        List<int[]> gaps = new ArrayList<>();
        int cursor = minX;
        for (int[] d : sorted) {
            if (d[0] > cursor) gaps.add(new int[]{cursor, d[0]});
            cursor = Math.max(cursor, d[1]);
        }
        if (cursor < maxX) gaps.add(new int[]{cursor, maxX});

        if (gaps.isEmpty()) {
            logInfo("No safe gap - falling back to screen center.");
            return SCREEN_W / 2;
        }

        for (int[] g : gaps) {
            if (hookCx >= g[0] && hookCx <= g[1]) return hookCx;
        }

        int bestTarget = SCREEN_W / 2;
        int bestDist   = Integer.MAX_VALUE;
        for (int[] g : gaps) {
            int centre = (g[0] + g[1]) / 2;
            int dist   = Math.abs(centre - hookCx);
            if (dist < bestDist) { bestDist = dist; bestTarget = centre; }
        }
        return bestTarget;
    }

    // =========================================================================
    // INNER DATA CLASSES
    // =========================================================================

    /** Raw template detection from a single scan frame. */
    private static class FishDetection {
        String label;
        int cx, cy;
        int w, h;
        float absVx;
    }

    /**
     * Physics-tracked fish. Position is predicted between template scans
     * using constant-velocity kinematics with perfect wall bouncing.
     */
    private static class TrackedFish {
        int    id;
        String label;
        float  cx, cy;           // predicted screen-coordinate position
        float  vx;               // signed horizontal velocity (px/ms, + = right)
        float  absVx;            // unsigned speed constant for this fish type
        int    w, h;
        long   lastPredictMs;
        int    missedScans;      // consecutive scans without a template match
        int    scanCount;        // total number of scans that confirmed this track

        /**
         * Advances position to {@code nowMs} using constant-velocity physics.
         * Y scrolls upward at {@link #VY_SCROLL}; X bounces off screen walls.
         */
        void predict(long nowMs) {
            long dt = nowMs - lastPredictMs;
            if (dt <= 0) return;

            // Vertical: scroll upward at constant rate
            cy -= VY_SCROLL * dt;

            // Horizontal: constant speed with wall bouncing
            float remaining = dt;
            float halfW     = w / 2.0f;
            float leftWall  = halfW;
            float rightWall = SCREEN_W - halfW;

            int safety = 100;
            while (remaining > 0 && vx != 0 && safety-- > 0) {
                if (vx > 0) {
                    float ttb = (rightWall - cx) / vx;
                    if (ttb < 0) { vx = -vx; continue; }
                    if (ttb >= remaining) { cx += vx * remaining; break; }
                    cx = rightWall;
                    remaining -= ttb;
                    vx = -vx;
                } else {
                    float ttb = (cx - leftWall) / (-vx);
                    if (ttb < 0) { vx = -vx; continue; }
                    if (ttb >= remaining) { cx += vx * remaining; break; }
                    cx = leftWall;
                    remaining -= ttb;
                    vx = -vx;
                }
            }

            lastPredictMs = nowMs;
        }
    }
}
