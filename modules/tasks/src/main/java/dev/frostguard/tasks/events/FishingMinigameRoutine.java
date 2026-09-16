package dev.frostguard.tasks.events;

import java.time.LocalDateTime;
import java.util.regex.Pattern;
import dev.frostguard.api.configs.*;
import dev.frostguard.api.domain.*;
import dev.frostguard.engine.emulator.AndroidFrameStream;
import dev.frostguard.engine.emulator.AndroidTouchSession;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.vision.match.ImageRegionStability;
import dev.frostguard.vision.ocr.OcrEngine;

/** Free Winter casts only; unknown screens never authorize another cast or premium spending. */
public class FishingMinigameRoutine extends DelayedTask {
    // Animated shortcut art is weaker evidence than the separately verified cast controls.
    static final int HOME_ENTRY_MATCH_THRESHOLD = 85;
    private static final long BAIT_CONFIRMATION_TIMEOUT_NANOS = 4_000_000_000L;
    private static final Pattern BAIT = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");
    private AndroidFrameStream stream;
    private AndroidTouchSession realtimeInput;
    private AndroidFrameStream.Frame observation;
    private long sequence;
    private FishingSteeringFeedback steering;
    private boolean contactCalibrated;
    private boolean restartRequested, restartEnabled, darkCornerEnabled;
    private int restartsUsed, maxRestarts, catchTarget;
    private boolean useLantern, useStabilizer, requireSpecialItems, activeLantern;
    private int specialCastsUsed, maxSpecialCasts;

    public FishingMinigameRoutine(AccountDescriptor profile, TpDailyTaskEnum type) {
        super(profile, type);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() { return LaunchPoint.ANY; }

    @Override
    protected boolean acceptsInjections() { return false; }

    @Override
    protected void execute() {
        sequence = 0;
        String adb = emuManager.getAdbPath(), serial = emuManager.getDeviceSerial(EMULATOR_NUMBER);
        int castLimit = FishingSessionPolicy.castLimit(getProfile().getConfig(
                ConfigurationKeyEnum.FISHING_MAX_CASTS_INT, Integer.class));
        maxRestarts = FishingSessionPolicy.restartLimit(getProfile().getConfig(ConfigurationKeyEnum.FISHING_MAX_RESTARTS_INT, Integer.class));
        catchTarget = FishingSessionPolicy.catchTarget(getProfile().getConfig(ConfigurationKeyEnum.FISHING_TARGET_CATCHES_INT, Integer.class), 100);
        restartEnabled = Boolean.TRUE.equals(getProfile().getConfig(ConfigurationKeyEnum.FISHING_SHORT_HAUL_RESTART_ENABLED_BOOL, Boolean.class));
        darkCornerEnabled = Boolean.TRUE.equals(getProfile().getConfig(ConfigurationKeyEnum.FISHING_DARK_CORNER_ENABLED_BOOL, Boolean.class));
        restartsUsed = 0;
        specialCastsUsed = 0;
        maxSpecialCasts = FishingSessionPolicy.specialCastLimit(getProfile().getConfig(ConfigurationKeyEnum.FISHING_MAX_SPECIAL_CASTS_INT, Integer.class));
        useLantern = Boolean.TRUE.equals(getProfile().getConfig(ConfigurationKeyEnum.FISHING_USE_LANTERN_BOOL, Boolean.class));
        useStabilizer = Boolean.TRUE.equals(getProfile().getConfig(ConfigurationKeyEnum.FISHING_USE_STABILIZER_BOOL, Boolean.class));
        requireSpecialItems = Boolean.TRUE.equals(getProfile().getConfig(ConfigurationKeyEnum.FISHING_REQUIRE_SPECIAL_ITEMS_BOOL, Boolean.class));
        activeLantern = false;
        boolean retryOwnedStage = false, recoveryOnly = false;
        int verifiedStageMaximum = 0;
        try {
            for (int cast = 0; cast < castLimit;) {
                // Each retry resets observation/haul filters and owns a new bounded recording.
                try (var capture = new AndroidFrameStream(adb, serial);
                        var depth = new FishingDepthMonitor(capture, retryOwnedStage ? verifiedStageMaximum : 0);
                        var input = AndroidTouchSession.open(adb, serial)) {
                    sequence = 0;
                    steering = new FishingSteeringFeedback();
                    stream = capture;
                    realtimeInput = input;
                    capture.start();
                    freshFrame();
                    awaitDepthMonitor(depth);
                    freshFrame();
                    boolean resumed = resumeExistingCast();
                    if (resumed) recoveryOnly |= !retryOwnedStage;
                    else if (retryOwnedStage) {
                        throw new IllegalStateException("Suspended retry stage unverified; no replacement bait authorized");
                    } else if (exitVerifiedHaul()) {
                        defer(30, "Existing haul recovered; preserving remaining bait");
                        return;
                    }
                    if (!resumed) {
                        freshFrame();
                        if (!find(TemplatesEnum.FISHING_TITLE, AreaData.of(85, 0, 500, 80)).isFound()) {
                            var entry = waitFor(TemplatesEnum.FISHING_HOME_ICON, CommonGameAreas.FISHING_HOME_ENTRY, 8000);
                            if (!entry.isFound()) { defer(15, "Fishing entry unavailable"); return; }
                            safeTap(entry);
                        }
                        if (!waitFor(TemplatesEnum.FISHING_TITLE, AreaData.of(85, 0, 500, 80), 8000).isFound()) {
                            defer(5, "Fishing page unverified"); return;
                        }
                        // Reopening from home can reveal a paid stage that was invisible at task entry.
                        resumed = resumeExistingCast();
                        if (resumed) recoveryOnly = true;
                        else if (exitVerifiedHaul()) {
                            defer(30, "Existing haul recovered; preserving remaining bait");
                            return;
                        }
                    }
                    if (!resumed) {
                        // The common event title also appears on the remembered Club/leaderboard tabs.
                        if (!find(TemplatesEnum.FISHING_ICE_BUTTON, CommonGameAreas.FISHING_ICE_CAST_BUTTON).isFound()) {
                            checkPreemption();
                            requireRecentObservation();
                            tapInside(CommonGameAreas.FISHING_TOURNAMENT_TAB);
                            if (!waitFor(TemplatesEnum.FISHING_ICE_BUTTON, CommonGameAreas.FISHING_ICE_CAST_BUTTON, 5000).isFound()) {
                                defer(5, "Fishing overview controls unverified; no bait selected"); return;
                            }
                        }
                        int bait = readFreeBait();
                        if (bait <= 0) { defer(bait == 0 ? 60 : 5, bait == 0 ? "No free bait remains" : "Free bait unverified"); return; }
                        long maximumDeadline = System.nanoTime() + 4_000_000_000L;
                        while (depth.verifiedMaximum() == 0 && System.nanoTime() < maximumDeadline) freshFrame();
                        if (depth.verifiedMaximum() == 0) { defer(5, "Line setup unverified; no bait selected"); return; }
                        verifiedStageMaximum = depth.verifiedMaximum();
                        if (!prepareLoadout()) { defer(5, "Configured item loadout unavailable or unverified; no bait selected"); return; }
                        if (!startFreeCast()) { defer(5, "Safe Normal Cast controls unverified"); return; }
                    }
                    boolean sufficientDescent = playAndExit(depth);
                    if (restartRequested) { retryOwnedStage = true; continue; }
                    logInfo("Fishing haul exited; cast=" + (cast + 1) + " sufficientDescent=" + sufficientDescent
                            + " maximumLineConfirmed=" + depth.lineExhausted());
                    if (recoveryOnly) {
                        defer(30, "Previously paid cast recovered; no new bait authorized during recovery");
                        return;
                    }
                    if (!sufficientDescent) { defer(30, "80% descent unconfirmed; preserving remaining bait"); return; }
                    cast++;
                    retryOwnedStage = false;
                    restartsUsed = 0;
                }
            }
            defer(60, "Fishing cast safety limit reached");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            checkPreemption();
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Fishing video/input transport failed", error);
        } finally {
            stream = null;
            realtimeInput = null;
            observation = null;
            steering = null;
        }
    }

    private void defer(int minutes, String reason) {
        logInfo(reason + "; next check in " + minutes + " minutes. No premium spending authorized.");
        reschedule(LocalDateTime.now().plusMinutes(minutes));
    }

    private int readFreeBait() {
        var region = CommonGameAreas.FISHING_FREE_BAIT;
        var confirmation = new FishingBaitConfirmation();
        try (var ocr = new dev.frostguard.vision.ocr.TesseractOcrSession(CommonOCRSettings.STAMINA_FRACTION_SETTINGS)) {
            ocr.prepare();
            long deadline = System.nanoTime() + BAIT_CONFIRMATION_TIMEOUT_NANOS;
            while (System.nanoTime() < deadline) {
                freshFrame();
                if (!find(TemplatesEnum.FISHING_TITLE, AreaData.of(85, 0, 500, 80)).isFound()
                        || !find(TemplatesEnum.FISHING_ICE_BUTTON, CommonGameAreas.FISHING_ICE_CAST_BUTTON).isFound()) return -1;
                String text;
                try {
                    text = ocr.recognize(observation.image(), region);
                } catch (dev.frostguard.vision.ocr.OcrException error) {
                    text = null;
                }
                int bait = confirmation.accept(text, observation.sequence());
                if (bait >= 0) {
                    logInfo("Free bait verified on two fresh frames: " + bait);
                    return bait;
                }
                sleepTask(100);
            }
            return -1;
        }
    }

    static int parseBait(String text) {
        if (text == null) return -1;
        var matcher = BAIT.matcher(text.trim());
        if (!matcher.matches()) return -1;
        try {
            int count = Integer.parseInt(matcher.group(1)), capacity = Integer.parseInt(matcher.group(2));
            return count >= 0 && count <= capacity && capacity > 0 && capacity <= 99 ? count : -1;
        } catch (NumberFormatException ignored) { return -1; }
    }

    private boolean startFreeCast() {
        var ice = waitFor(TemplatesEnum.FISHING_ICE_BUTTON, AreaData.of(370, 1140, 675, 1240), 5000);
        if (!ice.isFound()) return false;
        safeTap(ice);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            freshFrame();
            var normal = normalCastControl(observation, loadoutHasItems, System::nanoTime);
            if (!normal.isFound()) continue;
            safeTap(normal);
            if (loadoutHasItems) specialCastsUsed++;
            logInfo("Started one verified Normal Cast; special items=" + loadoutHasItems + " boosted casts=" + specialCastsUsed + "/" + maxSpecialCasts);
            return true;
        }
        return false;
    }

    static ImageSearchResultData normalCastControl(AndroidFrameStream.Frame frame, boolean hasItems,
            java.util.function.LongSupplier clock) {
        if (frame == null) return ImageSearchResultData.miss();
        var normal = OpenCvPatternLocator.locatePattern(frame.image(), TemplatesEnum.FISHING_NORMAL_CAST.getTemplate(),
                AreaData.of(50, 760, 360, 885).topLeft(), AreaData.of(50, 760, 360, 885).bottomRight(), 90);
        if (!normal.isFound()) return normal;
        // A selected loadout is independently verified on the overview before opening this dialog.
        if (!hasItems && !OpenCvPatternLocator.locatePattern(frame.image(), TemplatesEnum.FISHING_NO_SPECIAL_ITEM.getTemplate(),
                AreaData.of(100, 580, 650, 730).topLeft(), AreaData.of(100, 580, 650, 730).bottomRight(), 90).isFound()) {
            return ImageSearchResultData.miss();
        }
        // Both matches can outlive the input freshness budget. Poll another frame, never tap that old result.
        return clock.getAsLong() - frame.receivedNanos() > 250_000_000L ? ImageSearchResultData.miss() : normal;
    }

    private boolean loadoutHasItems;

    private boolean prepareLoadout() {
        loadoutHasItems = false;
        activeLantern = false;
        try (var itemOcr = new dev.frostguard.vision.ocr.TesseractOcrSession(CommonOCRSettings.STAMINA_FRACTION_SETTINGS)) {
            itemOcr.prepare();
            boolean missingRequired = false;
            for (var item : FishingLoadoutPolicy.Item.values()) {
                freshFrame();
                if (!find(TemplatesEnum.FISHING_ICE_BUTTON, AreaData.of(370, 1140, 675, 1240)).isFound()
                        || !find(TemplatesEnum.FISHING_TITLE, AreaData.of(85, 0, 500, 80)).isFound()) return false;
                boolean selected = FishingLoadoutPolicy.selected(observation.image(), item);
                boolean wanted = FishingLoadoutPolicy.wants(item, useLantern, useStabilizer, specialCastsUsed, maxSpecialCasts);
                if (wanted && !selected) {
                    var recognized = observation.image();
                    int count = FishingLoadoutPolicy.count(itemOcr.recognize(recognized, item.countArea()));
                    freshFrame();
                    int confirmed = FishingLoadoutPolicy.count(itemOcr.recognize(observation.image(), item.countArea()));
                    if (count < 0 || confirmed != count) return false;
                    if (count == 0) { missingRequired = true; wanted = false; }
                    freshFrame();
                    if (!ImageRegionStability.unchanged(recognized, observation.image(), item.cardArea())) return false;
                }
                if (selected != wanted) {
                    checkPreemption();
                    requireRecentObservation();
                    tapInside(item.cardArea());
                    long deadline = System.nanoTime() + 2_000_000_000L;
                    do { freshFrame(); } while (FishingLoadoutPolicy.selected(observation.image(), item) != wanted
                            && System.nanoTime() < deadline);
                    if (FishingLoadoutPolicy.selected(observation.image(), item) != wanted) return false;
                }
                loadoutHasItems |= wanted;
                if (item == FishingLoadoutPolicy.Item.LANTERN) activeLantern = wanted;
            }
            return !requireSpecialItems || !missingRequired && (specialCastsUsed < maxSpecialCasts
                    || !useLantern && !useStabilizer);
        } catch (dev.frostguard.vision.ocr.OcrException error) { return false; }
    }

    private boolean resumeExistingCast() {
        if (find(TemplatesEnum.FISHING_PAUSE, AreaData.of(645, 0, 720, 85)).isFound()) {
            logInfo("Active Fishing HUD verified; recovering already-paid stage without new bait.");
            return true;
        }
        var continuing = find(TemplatesEnum.FISHING_CONTINUE, AreaData.of(390, 570, 535, 710));
        if (continuing.isFound()
                && find(TemplatesEnum.FISHING_PAUSE_EXIT, AreaData.of(185, 570, 330, 710)).isFound()) {
            safeTap(continuing);
            if (!waitFor(TemplatesEnum.FISHING_PAUSE, AreaData.of(645, 0, 720, 85), 8000).isFound()) {
                throw new IllegalStateException("Paused Fishing stage did not resume; no new bait authorized");
            }
            logInfo("Verified paused Fishing stage resumed without selecting a new cast.");
            return true;
        }
        if (!find(TemplatesEnum.FISHING_TITLE, AreaData.of(85, 0, 500, 80)).isFound()) return false;
        var goFish = find(TemplatesEnum.FISHING_GO_FISH, AreaData.of(270, 1040, 465, 1130));
        if (!goFish.isFound()) return false;
        FishingRetryNavigator.resume(retryPort());
        logInfo("Verified Go Fish recovered the already-paid stage; no fresh Normal Cast selected.");
        return true;
    }

    private void awaitDepthMonitor(FishingDepthMonitor depth) {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (!depth.ready()) {
            checkPreemption();
            if (depth.failure() != null) throw new IllegalStateException("Fishing HUD OCR failed: " + depth.failure());
            if (stream.failure() != null) throw new IllegalStateException(stream.failure());
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Fishing HUD OCR not ready; no new cast authorized");
            }
            sleepTask(20);
        }
    }

    private boolean playAndExit(FishingDepthMonitor depth) throws java.io.IOException, InterruptedException {
        long deadline = System.nanoTime() + 90_000_000_000L, previousTime = 0, nextResultCheck = 0;
        int previousHookY = 0;
        double hookVy = 0, latency = 150;
        int responseFailures = 0;
        contactCalibrated = false;
        restartRequested = false;
        boolean returning = false;
        var darkness = new FishingDarknessPolicy(java.util.concurrent.ThreadLocalRandom.current().nextBoolean());
        FishingVision.Hook previousHook = null;
        var phase = new FishingPhaseTracker();
        var tracker = new FishingMotionTracker();
            while (System.nanoTime() < deadline) {
                freshFrame();
                if (depth.failure() != null) throw new IllegalStateException("Fishing HUD OCR failed: " + depth.failure());
                if (!find(TemplatesEnum.FISHING_PAUSE, AreaData.of(645, 0, 720, 85)).isFound()) {
                    realtimeInput.endSteering();
                    contactCalibrated = false;
                    if (System.nanoTime() >= nextResultCheck) {
                        nextResultCheck = System.nanoTime() + 500_000_000L;
                        if (exitVerifiedHaul()) return depth.sufficientDescent()
                                || depth.haul() != null && depth.haul().full();
                    }
                    // Unknown first-time tutorial screens receive no blind tap.
                    var skip = find(TemplatesEnum.SKIP_TUTORIAL_BUTTON, AreaData.of(550, 0, 720, 180));
                    if (skip.isFound()) safeTap(skip);
                    continue;
                }
                var reading = depth.latest();
                boolean freshDepth = reading != null && System.nanoTime() >= reading.receivedNanos()
                        && System.nanoTime() - reading.receivedNanos() < 1_500_000_000L;
                // A previously verified ascent and fresh HUD suffice even when catches now hide the hook.
                if (FishingSessionPolicy.shouldRestart(restartEnabled, restartsUsed, maxRestarts,
                        phase.phase(), reading, depth.haul(), System.nanoTime(), catchTarget)) {
                    restartsUsed++;
                    suspendForRetry();
                    restartRequested = true;
                    return false;
                }
                var hook = FishingVision.hook(observation.image(), previousHook);
                if (hook == null) continue;
                previousHook = hook;
                long now = observation.receivedNanos() / 1_000_000;
                int x = hook.point().getX(), y = hook.point().getY();
                if (steering.observe(x, observation.sequence(), observation.receivedNanos())) contactCalibrated = true;
                if (previousTime > 0 && now > previousTime) hookVy = 0.5 * hookVy + 0.5 * (y - previousHookY) / (now - previousTime);
                previousTime = now;
                previousHookY = y;
                var fish = tracker.update(FishingVision.obstacles(observation.image(), x, y), now, hookVy);
                if (freshDepth) phase.observe(reading.depth(), reading.sequence(), tracker.relativeVerticalVelocity());
                phase.observeHook(y, observation.sequence());
                if (phase.phase() == FishingPhaseTracker.Phase.ASCENDING) {
                    if (!returning) {
                        realtimeInput.endSteering();
                        steering = new FishingSteeringFeedback();
                        contactCalibrated = false;
                        responseFailures = 0;
                        returning = true;
                        logInfo("Fishing return observed; peak=" + depth.peak() + "m maximumLineConfirmed=" + depth.lineExhausted());
                    }
                    var haul = depth.haul();
                    boolean currentHaul = haul != null && System.nanoTime() - haul.receivedNanos() < 1_500_000_000L;
                    boolean returnMotion = tracker.relativeVerticalVelocity() != null
                            && tracker.relativeVerticalVelocity() > 0.05;
                    if (!currentHaul || haul.freeSlots() <= 0 || !freshDepth && !returnMotion) {
                        realtimeInput.endSteering();
                        contactCalibrated = false;
                        continue;
                    }
                    if (steering.waitingForResponse(System.nanoTime())) continue;
                    if (steering.takeTimedOut()) {
                        realtimeInput.endSteering();
                        contactCalibrated = false;
                        responseFailures++;
                        logInfo("Fishing return input timed out; remaining haul slots=" + haul.freeSlots());
                    }
                    // Preserve the reward even if steering repeatedly fails: observe the result, not another cast.
                    if (responseFailures >= 3) continue;
                    if (!realtimeInput.isSteering()) {
                        checkPreemption();
                        requireRecentObservation();
                        realtimeInput.primeSteering(x, Math.min(1190, y + FishingVision.HOOK_BODY_OFFSET_Y));
                        contactCalibrated = false;
                        continue;
                    }
                    var target = FishingHarvestPlanner.fillTarget(x, y + FishingVision.HOOK_BODY_OFFSET_Y,
                            tracker.catchOpportunities(now, hookVy), latency, haul.freeSlots());
                    if (target.isPresent() && Math.abs(target.getAsInt() - x) > 18) {
                        long before = System.nanoTime();
                        swipe(x, y, target.getAsInt(), true);
                        latency = Math.min(600, Math.max(100, (System.nanoTime() - before) / 1_000_000.0));
                    }
                    continue;
                }
                if (returning) continue;
                if (!phase.canSteer(freshDepth, tracker.relativeVerticalVelocity())
                        && !phase.canPrimeContact(freshDepth)) {
                    realtimeInput.endSteering();
                    contactCalibrated = false;
                    continue;
                }
                if (steering.waitingForResponse(System.nanoTime())) continue;
                if (steering.takeTimedOut()) {
                    realtimeInput.endSteering();
                    contactCalibrated = false;
                    responseFailures++;
                    logInfo(responseFailures >= 3 ? "Fishing descent steering disabled after three failures; preserving this paid stage"
                            : "Fishing input response window expired; re-establishing contact from a fresh hook");
                }
                if (responseFailures >= 3) continue;
                if (!realtimeInput.isSteering() && y <= 250 && reading != null && reading.depth().current() > 0
                        && phase.canPrimeContact(freshDepth)) {
                    checkPreemption();
                    requireRecentObservation();
                    realtimeInput.primeSteering(x, y + FishingVision.HOOK_BODY_OFFSET_Y);
                    contactCalibrated = false;
                    // Contact setup consumes time. Never execute the old frame's route afterwards.
                    continue;
                }
                // OCR gaps need independently measured current descent motion, not stale phase alone.
                if (!phase.canSteer(freshDepth, tracker.relativeVerticalVelocity())) {
                    if (!phase.canPrimeContact(freshDepth)) {
                        realtimeInput.endSteering();
                        contactCalibrated = false;
                    }
                    continue;
                }
                boolean blind = darkness.observe(observation.image(), observation.sequence());
                int target = darkCornerEnabled && !activeLantern && blind ? darkness.corner()
                        : FishingAvoidancePlanner.target(x, y + FishingVision.HOOK_BODY_OFFSET_Y, fish, latency);
                if (Math.abs(target - x) > 18) {
                    long before = System.nanoTime();
                    swipe(x, y, target, false);
                    latency = Math.min(600, Math.max(100, (System.nanoTime() - before) / 1_000_000.0));
                }
            }
        throw new IllegalStateException("Fishing round/Haul unverified after 90 seconds; no further cast authorized");
    }

    private void suspendForRetry() throws java.io.IOException, InterruptedException {
        FishingRetryNavigator.suspend(retryPort());
        logInfo("Fishing short-haul retry reserved=" + restartsUsed + "/" + maxRestarts + "; existing bait only");
    }

    private FishingRetryNavigator.Port retryPort() {
        return new FishingRetryNavigator.Port() {
            public AndroidFrameStream.Frame frame() { return observation; }
            public void fresh() { freshFrame(); }
            public void release() throws java.io.IOException { realtimeInput.endSteering(); }
            public void tap(AreaData area) { tapInside(area); }
            public void checkCancellation() { checkPreemption(); }
        };
    }

    private boolean exitVerifiedHaul() {
        try {
            var recognized = observation.image();
            var heading = CommonGameAreas.FISHING_HAUL_HEADING;
            if (!OcrEngine.recognizeText(observation.image(), heading.topLeft(), heading.bottomRight(),
                    CommonOCRSettings.FISHING_RESULT_SETTINGS).trim().equalsIgnoreCase("Haul")) return false;
            var label = CommonGameAreas.FISHING_HAUL_EXIT_LABEL;
            if (!OcrEngine.recognizeText(observation.image(), label.topLeft(), label.bottomRight(),
                    CommonOCRSettings.FISHING_RESULT_SETTINGS).trim().equalsIgnoreCase("Exit")) return false;
            // Slow OCR must not authorize input against a screen that changed during recognition.
            freshFrame();
            if (!haulControlsStillVisible(recognized, observation.image())) return false;
            checkPreemption();
            requireRecentObservation();
            tapInside(CommonGameAreas.FISHING_HAUL_EXIT_BUTTON);
            return waitFor(TemplatesEnum.FISHING_ICE_BUTTON, AreaData.of(370, 1140, 675, 1240), 8000).isFound()
                    && find(TemplatesEnum.FISHING_TITLE, AreaData.of(85, 0, 500, 80)).isFound();
        } catch (dev.frostguard.vision.ocr.OcrException error) { return false; }
    }

    static boolean haulControlsStillVisible(dev.frostguard.api.domain.RawImageData before,
            dev.frostguard.api.domain.RawImageData after) {
        // The title background animates; re-identify its glyphs instead of freezing the whole light effect.
        var heading = CommonGameAreas.FISHING_HAUL_HEADING;
        return ImageRegionStability.unchanged(before, after, CommonGameAreas.FISHING_HAUL_EXIT_BUTTON)
                && OpenCvPatternLocator.locatePattern(after, TemplatesEnum.FISHING_HAUL_TITLE.getTemplate(),
                        heading.topLeft(), heading.bottomRight(), 90).isFound();
    }

    private void swipe(int x, int hookY, int target, boolean ascending) throws java.io.IOException, InterruptedException {
        checkPreemption();
        requireRecentObservation();
        int y = Math.max(110, Math.min(1190, hookY + FishingVision.HOOK_BODY_OFFSET_Y));
        int drag = steering.dragTo(x, target, contactCalibrated);
        if (ascending) drag = FishingHarvestPlanner.boundedDrag(x, drag);
        if (drag == 0) return;
        if (!realtimeInput.canContinueSteering(drag)) {
            realtimeInput.endSteering();
            realtimeInput.primeSteering(x, y);
            contactCalibrated = false;
            return;
        }
        int actualDrag = realtimeInput.steer(x, y, drag, 150);
        // Wait for frames received after input completion before learning its response.
        var afterInput = stream.latest();
        steering.sent(x, actualDrag, afterInput == null ? sequence : afterInput.sequence(),
                System.nanoTime(), contactCalibrated);
    }

    private void safeTap(ImageSearchResultData target) {
        checkPreemption();
        requireRecentObservation();
        tapInside(target);
    }

    private void requireRecentObservation() {
        if (observation == null || stream.failure() != null || System.nanoTime() - observation.receivedNanos() > 250_000_000L) {
            throw new IllegalStateException("Fishing observation stale; no input sent");
        }
    }

    private ImageSearchResultData find(TemplatesEnum template, AreaData area) {
        int threshold = template == TemplatesEnum.FISHING_HOME_ICON ? HOME_ENTRY_MATCH_THRESHOLD : 90;
        return OpenCvPatternLocator.locatePattern(observation.image(), template.getTemplate(), area.topLeft(), area.bottomRight(), threshold);
    }

    private ImageSearchResultData waitFor(TemplatesEnum template, AreaData area, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000;
        while (System.nanoTime() < deadline) {
            freshFrame();
            var result = find(template, area);
            if (result.isFound()) return result;
        }
        return ImageSearchResultData.miss();
    }

    private void freshFrame() {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            checkPreemption();
            if (stream.failure() != null) throw new IllegalStateException(stream.failure());
            var frame = stream.latestAfter(sequence);
            if (frame != null && System.nanoTime() - frame.receivedNanos() < 250_000_000L) {
                observation = frame;
                sequence = frame.sequence();
                return;
            }
            sleepTask(10);
        }
        throw new IllegalStateException("No fresh Fishing frame within five seconds");
    }
}
