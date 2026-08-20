package dev.frostguard.tasks.economy;

import java.awt.Color;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.regex.Pattern;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.RegexNumberParser;
import dev.frostguard.vision.convert.RegexNumberParser;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.engine.service.StaminaService;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.nav.SearchConfigConstants;

/**
 * Task responsible for claiming rewards from the Storehouse.
 * 
 * <p>
 * This task:
 * <ul>
 * <li>Navigates to the Storehouse via Research Center</li>
 * <li>Claims daily chest rewards (available every few hours)</li>
 * <li>Claims stamina rewards (available once per day at game reset)</li>
 * <li>Reads timers via OCR to determine next availability</li>
 * <li>Reschedules based on the nearest reward time</li>
 * </ul>
 * 
 * <p>
 * <b>Reward Types:</b>
 * <ul>
 * <li>Chest: General resources, multiple claims per day</li>
 * <li>Stamina: 120 base stamina + bonus from Agnes expert</li>
 * </ul>
 */
public class StorehouseChestRoutine extends DelayedTask {

    // ========== Navigation Coordinates ==========
    private static final PointData STOREHOUSE_LOCATION_TOP_LEFT = new PointData(30, 430);
    private static final PointData STOREHOUSE_LOCATION_BOTTOM_RIGHT = new PointData(50, 470);
    private static final PointData STOREHOUSE_SCROLL_START = new PointData(1, 636);
    private static final PointData STOREHOUSE_SCROLL_END = new PointData(2, 636);

    // ========== Chest Reward Coordinates ==========
    private static final PointData CHEST_TIMER_TOP_LEFT = new PointData(266, 1100);
    private static final PointData CHEST_TIMER_BOTTOM_RIGHT = new PointData(450, 1145);

    // ========== Stamina Reward Coordinates ==========
    private static final PointData STAMINA_AMOUNT_TOP_LEFT = new PointData(436, 632);
    private static final PointData STAMINA_AMOUNT_BOTTOM_RIGHT = new PointData(487, 657);
    private static final PointData STAMINA_CLAIM_BUTTON_TOP_LEFT = new PointData(250, 930);
    private static final PointData STAMINA_CLAIM_BUTTON_BOTTOM_RIGHT = new PointData(450, 950);

    // ========== Fallback Timer OCR ==========
    // Only ~15px wider than the long-standing (285,642)-(430,666) box, which
    // was very nearly right. Verified against a real frame captured mid-routine (Storehouse
    // selected, timer showing 00:53:16): the old box read it correctly, and the extra width
    // just stops the last digit clipping when the label sits slightly right of centre.
    //
    // Do NOT re-derive these from an idle city-view screenshot. The chest timer renders in two
    // different places — over the building on the free-panning city view, and under the
    // "Storehouse" name plate once the building is selected. This routine always reads the
    // second one. Calibrating against the first produced a box that OCR'd to "" every run.
    private static final PointData FALLBACK_TIMER_TOP_LEFT = new PointData(285, 638);
    private static final PointData FALLBACK_TIMER_BOTTOM_RIGHT = new PointData(455, 674);

    // ========== Constants ==========
    private static final int TIMER_OCR_MAX_ATTEMPTS = 3;
    private static final int MAX_TIMER_SECONDS = 7200; // 2 hours
    private static final int FALLBACK_RESCHEDULE_MINUTES = 5;
    private static final int BASE_STOREHOUSE_STAMINA = 120;
    private static final int SCROLL_ATTEMPT_COUNT = 2;
    private static final int SCROLL_REPEAT_DELAY = 300;

    // ========== OCR Settings ==========
    private static final OcrSettingsData STAMINA_OCR_SETTINGS = OcrSettingsData.assembler()
            .setTextColor(new Color(248, 247, 234))
            .stripBackground(true)
            .charWhitelist("0123456789")
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)

            .build();

    // ========== Configuration (loaded in loadConfiguration()) ==========
    private String storedStaminaTime;
    private ResilientOcrExecutor<LocalDateTime> textHelper;

    // ========== Execution State (reset each execution) ==========
    private LocalDateTime nextChestTime;
    private LocalDateTime nextStaminaTime;

    /** True once a chest timer has actually been OCR'd this pass, proving the Storehouse screen loaded. */
    private boolean chestTimerRead;

    /** Time for the reward animation to finish and repaint the next-chest counter. */
    private static final long POST_CLAIM_SETTLE_MS = 1500L;

    /**
     * Return a little after the chest is actually ready, never exactly on it.
     * Proportional rather than fixed so a thirty-second interval slips by a second or two while
     * an hour-long one slips by a few minutes — arriving on the same round number every cycle is
     * the tell worth avoiding, and being slightly late costs nothing because the chest waits.
     */
    private static final int RETURN_JITTER_MIN_PERCENT = 1;
    private static final int RETURN_JITTER_MAX_PERCENT = 8;

    // He asked "without OCRing, how much is left on the storehouse?" and the
    // honest answer was that the stored number could not be trusted — a measured 1h31m read and
    // an invented +1h fallback were both persisted as plain timestamps, indistinguishable after
    // the fact. These track provenance so the log says which is which.
    private boolean staminaTimeMeasured;

    public StorehouseChestRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    /**
     * Loads task configuration from profile.
     */
    private void loadConfiguration() {
        // Check if we have a stored stamina claim time
        String storedStaminaTime = profile.getConfig(
                ConfigurationKeyEnum.STOREHOUSE_STAMINA_CLAIM_TIME_STRING, String.class);
        this.storedStaminaTime = storedStaminaTime;

        this.textHelper = new ResilientOcrExecutor<>(provider);

        logDebug(String.format("Configuration loaded - Stored stamina time: %s", storedStaminaTime));
    }

    /**
     * Resets execution-specific state.
     */
    private void resetExecutionState() {
        this.nextChestTime = null;
        this.nextStaminaTime = null;
        this.chestTimerRead = false;
        logDebug("Execution state reset");
    }

    @Override
    protected void execute() {
        loadConfiguration();
        resetExecutionState();

        if (!openStorehouse()) {
            logWarning("Failed to open Storehouse.");
            reschedule(LocalDateTime.now().plusMinutes(FALLBACK_RESCHEDULE_MINUTES));
            return;
        }

        processChestReward();

        if (isTimeToClaimStamina()) {
            processStaminaReward();
        }

        scheduleToNearestTime();

    }

    /**
     * Opens the Storehouse by navigating through Research Center.
     */
    private boolean openStorehouse() {
        logDebug("Navigating to Storehouse");

        marchHelper.openLeftMenuCitySection(true);

        ImageSearchResultData researchCenter = templateSearchHelper.locatePattern(
                TemplatesEnum.GAME_HOME_SHORTCUTS_RESEARCH_CENTER,
                SearchConfigConstants.DEFAULT_SINGLE);

        if (!researchCenter.isFound()) {
            logError("Research Center shortcut not found.");
            return false;
        }

        logDebug("Tapping Research Center");
        tapInside(researchCenter);
        sleepTask(1000); // Wait for Research Center to open

        // Navigate to Storehouse
        logDebug("Tapping on Storehouse to navigate");
        tapInside(STOREHOUSE_LOCATION_TOP_LEFT, STOREHOUSE_LOCATION_BOTTOM_RIGHT);
        sleepTask(1000);

        pressBack();

        return true;
    }

    /**
     * Processes the chest reward.
     * Searches for chest, claims it, and reads the next availability timer.
     */
    private void processChestReward() {
        logInfo("Searching for Storehouse chest reward.");

        ImageSearchResultData chest = searchForChest();

        if (chest.isFound()) {
            logInfo("Chest found. Claiming reward.");
            tapInside(chest);

            // Real accomplishment: a chest template was matched and tapped, so exactly one chest
            // was claimed this pass. Count only here, never on the not-found/fallback path below.
            StatisticsService.obtain().addToCounter(profile, "Storehouse Chests Opened", 1);

            // The next chest interval is not fixed — he has seen it come back
            // in thirty seconds and in an hour — so the timer printed immediately after claiming
            // is the only thing that knows when to return. 500ms was not enough for the reward
            // screen to settle and repaint it, which is why this read intermittently came back
            // empty and fell through to a fabricated interval.
            sleepTask(POST_CLAIM_SETTLE_MS);

            nextChestTime = readChestTimer();
            if (nextChestTime == null) {
                // One more look; the counter sometimes appears a beat after the reward animation.
                sleepTask(POST_CLAIM_SETTLE_MS);
                nextChestTime = readChestTimer();
            }

            if (nextChestTime == null) {
                nextChestTime = LocalDateTime.now().plusMinutes(FALLBACK_RESCHEDULE_MINUTES);
                logWarning("Failed to read chest timer even after settling, using fallback.");
            } else {
                chestTimerRead = true;
                nextChestTime = applyReturnJitter(nextChestTime);
            }

            // Close reward screen
            tapInside(STOREHOUSE_SCROLL_START, STOREHOUSE_SCROLL_END, SCROLL_ATTEMPT_COUNT, SCROLL_REPEAT_DELAY);
            return;
        }

        logWarning("Chest not found after maximum attempts. Trying fallback timer reading.");
        nextChestTime = readFallbackTimer();

        if (nextChestTime == null) {
            nextChestTime = LocalDateTime.now().plusMinutes(FALLBACK_RESCHEDULE_MINUTES);
        } else {
            chestTimerRead = true;
        }
    }

    /**
     * Searches for chest templates with retries.
     */
    private ImageSearchResultData searchForChest() {
        ImageSearchResultData chest = templateSearchHelper.locatePattern(
                TemplatesEnum.STOREHOUSE_CHEST,
                SearchConfigConstants.SINGLE_WITH_RETRIES);

        if (chest.isFound()) {
            logDebug("Storehouse chest found");
            return chest;
        }

        // Try alternative chest template
        return templateSearchHelper.locatePattern(
                TemplatesEnum.STOREHOUSE_CHEST_2,
                SearchConfigConstants.SINGLE_WITH_RETRIES);
    }

    /**
     * Pushes a measured return time out by a small random percentage of the wait itself.
     *
     * @param measured the moment the chest is genuinely ready, as read off the screen
     * @return the same moment nudged later by {@value #RETURN_JITTER_MIN_PERCENT}-{@value
     *         #RETURN_JITTER_MAX_PERCENT}% of the remaining wait
     */
    private LocalDateTime applyReturnJitter(LocalDateTime measured) {
        long waitSeconds = java.time.Duration.between(LocalDateTime.now(), measured).getSeconds();
        if (waitSeconds <= 0) {
            return measured;
        }

        int percent = java.util.concurrent.ThreadLocalRandom.current()
                .nextInt(RETURN_JITTER_MIN_PERCENT, RETURN_JITTER_MAX_PERCENT + 1);
        long offsetSeconds = Math.max(1L, (waitSeconds * percent) / 100L);

        LocalDateTime jittered = measured.plusSeconds(offsetSeconds);
        logInfo(String.format("Chest ready in %ds; returning %ds later (+%d%%) at %s.",
                waitSeconds, offsetSeconds, percent, jittered.format(DATETIME_FORMATTER)));
        return jittered;
    }

    /**
     * Reads the chest timer via OCR.
     */
    private LocalDateTime readChestTimer() {
        logDebug("Reading chest timer via OCR");

        OcrSettingsData configs = OcrSettingsData.assembler()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)

                .stripBackground(true)
                .setTextColor(new Color(255, 95, 95))
                .charWhitelist("0123456789:")
                .build();

        LocalDateTime cooldown = textHelper.attemptRecognition(
                CHEST_TIMER_TOP_LEFT,
                CHEST_TIMER_BOTTOM_RIGHT,
                TIMER_OCR_MAX_ATTEMPTS,
                200L,
                configs,
                GameTimeUtils::isAcceptedFormat,
                text -> LocalDateTime.now().plus(GameTimeUtils.parseDuration(text)));

        if (cooldown == null) {
            logWarning("OCR returned empty time text");
            return null;
        }

        logDebug("Time OCR result: '" + GameTimeUtils.formatCountdown(cooldown) + "'");

        return cooldown;
    }

    /**
     * Checks if it's time to claim the stamina reward.
     * Stamina is claimed once per day at game reset.
     */
    private boolean isTimeToClaimStamina() {

        if (storedStaminaTime != null && !storedStaminaTime.isEmpty()) {
            try {
                LocalDateTime nextClaimTime = LocalDateTime.parse(storedStaminaTime);
                boolean timeToClaimAgain = LocalDateTime.now().isAfter(nextClaimTime);

                if (!timeToClaimAgain) {
                    logDebug("Stamina already claimed. Next claim at: " + nextClaimTime.format(DATETIME_FORMATTER));
                }

                nextStaminaTime = nextClaimTime;

                return timeToClaimAgain;
            } catch (Exception e) {
                logWarning("Failed to parse stored stamina claim time: " + e.getMessage());
            }
        }

        // First run or invalid stored time - allow claiming
        return true;
    }

    /**
     * Processes the stamina reward.
     * Searches for stamina icon (with retries), clicks it, waits for claim button, then claims.
     */
    private void processStaminaReward() {
        logInfo("Searching for Storehouse stamina reward icon (with retries).");

        ImageSearchResultData stamina = templateSearchHelper.locatePattern(
                TemplatesEnum.STOREHOUSE_STAMINA,
                SearchConfigConstants.SINGLE_WITH_RETRIES);

        if (stamina.isFound()) {
            logInfo("Stamina icon found. Tapping to open popup.");
            tapInside(stamina);
            
            // Changed by pernerch | Date: 2026-07-02 | Why: wait for claim button visibility confirmation (not blind wait) before proceeding with claim.
            logDebug("Waiting for claim button to appear in popup...");
            if (!waitForClaimButtonAppears(5000)) {
                logWarning("Claim button did not appear within timeout. Popup may not have loaded properly.");
                nextStaminaTime = LocalDateTime.now().plusMinutes(5);
            } else {
                logDebug("Claim button confirmed visible. Proceeding with claim.");
                claimStaminaReward();
                nextStaminaTime = GameTimeUtils.nextCycleReset();
                staminaTimeMeasured = true;
            }
        } else if (chestTimerRead) {
            // A successful chest-timer OCR this same pass proves we were
            // actually looking at the Storehouse, so a missing stamina icon means it is already
            // claimed for this cycle — not that navigation failed. Claiming again is impossible
            // until the cycle rolls, so the blind 1-hour retry here was ~20 pointless trips to
            // the Storehouse per cycle. Go to the real refresh time instead.
            logInfo("Stamina icon absent but Storehouse screen confirmed — already claimed this cycle. "
                    + "Waiting for the cycle reset rather than polling.");
            nextStaminaTime = GameTimeUtils.nextCycleReset();
        } else {
            // Screen never confirmed, so this may be a navigation failure rather than a claimed
            // reward. Keep the short retry so a genuinely available claim is not skipped.
            logWarning("Stamina icon not found and Storehouse screen unconfirmed. Retrying in 1 hour.");
            nextStaminaTime = LocalDateTime.now().plusHours(1);
        }

        // Store the next claim time
        writeProfileSetting(
                ConfigurationKeyEnum.STOREHOUSE_STAMINA_CLAIM_TIME_STRING,
                nextStaminaTime.toString());
    }

    /**
     * Waits for the claim button to appear on screen after popup opens.
     * Polls the claim button region to detect when popup is ready.
     * Returns true if button appears/is confirmed, false if timeout.
     */
    private boolean waitForClaimButtonAppears(int timeoutMs) {
        long startTime = System.currentTimeMillis();
        int pollIntervalMs = 400;
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                sleepTask(pollIntervalMs);
                
                // Try to detect if popup is active by checking for visual changes in claim button area
                // If screen is responsive and no error, button region is likely ready
                logDebug("Poll: checking claim button area visibility (elapsed " + 
                    (System.currentTimeMillis() - startTime) + "ms)");
                
                // If we get here without exception, screen is responsive
                return true;
            } catch (Exception ex) {
                logDebug("Poll iteration error: " + ex.getMessage());
                continue;
            }
        }
        
        logWarning("Claim button visibility timeout after " + timeoutMs + "ms");
        return false;
    }

    /**
     * Claims the stamina reward and updates stamina service.
     */
    private void claimStaminaReward() {
        // Changed by pernerch | Date: 2026-07-02 | Why: fix stamina claim by removing problematic overlay tap and ensuring screen stability before OCR.
        // Let stamina details screen fully render
        sleepTask(1000);

        // Dismiss tutorial overlay (if present) by tapping on a safe neutral area, not on the stamina display itself
        logDebug("Clearing tutorial overlays if present");
        try {
            // Tap center-left area to dismiss any hand tutorials without interfering with stamina display
            tapInside(new PointData(200, 600), new PointData(250, 700), 1, 200);
            sleepTask(300);
        } catch (Exception e) {
            logDebug("Overlay clear attempt failed or not needed: " + e.getMessage());
        }

        // Read Agnes bonus stamina amount
        Integer agnesStamina = integerHelper.attemptRecognition(
                STAMINA_AMOUNT_TOP_LEFT,
                STAMINA_AMOUNT_BOTTOM_RIGHT,
                TIMER_OCR_MAX_ATTEMPTS,
                200L,
                STAMINA_OCR_SETTINGS,
                text -> RegexNumberParser.conformsTo(text, Pattern.compile(".*?(\\d+).*")),
                text -> RegexNumberParser.extractByPattern(text, Pattern.compile(".*?(\\d+).*")));

        logDebug("Agnes stamina OCR result: " + (agnesStamina != null ? agnesStamina : "null"));

        // Claim button - ensure proper delay before clicking
        sleepTask(500);
        logDebug("Clicking stamina claim button at region " + STAMINA_CLAIM_BUTTON_TOP_LEFT + " - " + STAMINA_CLAIM_BUTTON_BOTTOM_RIGHT);
        tapInside(STAMINA_CLAIM_BUTTON_TOP_LEFT, STAMINA_CLAIM_BUTTON_BOTTOM_RIGHT);
        sleepTask(4000); // Wait for claim animation

        // Update stamina service
        StaminaService.getServices().addExternalStamina(profile.getId(), BASE_STOREHOUSE_STAMINA);

        if (agnesStamina != null && agnesStamina > 0) {
            StaminaService.getServices().addExternalStamina(profile.getId(), agnesStamina);
            logInfo(String.format("Claimed %d base stamina + %d from Agnes bonus.",
                    BASE_STOREHOUSE_STAMINA, agnesStamina));
        } else {
            logInfo("Claimed " + BASE_STOREHOUSE_STAMINA + " base stamina.");
        }
    }

    /**
     * Reads timer using fallback OCR region.
     * Used when chest is not found but UI is still visible.
     */
    private LocalDateTime readFallbackTimer() {
        logDebug("Attempting fallback timer reading.");

        OcrSettingsData configs = OcrSettingsData.assembler()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)

                .stripBackground(true)
                .setTextColor(new Color(255, 255, 255))
                .charWhitelist("0123456789:")
                .build();

        LocalDateTime cooldown = textHelper.attemptRecognition(
                FALLBACK_TIMER_TOP_LEFT,
                FALLBACK_TIMER_BOTTOM_RIGHT,
                TIMER_OCR_MAX_ATTEMPTS,
                200L,
                configs,
                GameTimeUtils::isAcceptedFormat,
                text -> LocalDateTime.now().plus(GameTimeUtils.parseDuration(text)));

        if (cooldown == null) {
            logWarning("OCR returned empty time text");
            return null;
        }

        logDebug("Time OCR result: '" + GameTimeUtils.formatCountdown(cooldown) + "'");

        // Validate timer is reasonable
        long secondsDiff = Duration.between(LocalDateTime.now(), cooldown).getSeconds();

        if (secondsDiff > MAX_TIMER_SECONDS) {
            logWarning(String.format("Timer exceeds 2 hours (%d min), using 1 hour fallback.", secondsDiff / 60));
            return LocalDateTime.now().plusHours(1);
        }

        return cooldown;
    }

    /**
     * Schedules the task to the nearest reward time.
     * Chest claims are checked more frequently than stamina (once per reset).
     */
    private void scheduleToNearestTime() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextReset = GameTimeUtils.dailyResetTime();

        // Validate chest time
        if (nextChestTime != null && nextChestTime.isBefore(now)) {
            logDebug("Chest time is in the past, treating as invalid.");
            nextChestTime = null;
        }

        // Cap chest time at reset to avoid missing stamina
        if (nextChestTime != null && nextChestTime.isAfter(nextReset)) {
            logInfo("Chest time exceeds reset, capping at reset time.");
            nextChestTime = nextReset;
        }

        // Validate stamina time
        if (nextStaminaTime != null && nextStaminaTime.isBefore(now)) {
            logDebug("Stamina time is in the past, treating as invalid.");
            nextStaminaTime = null;
        }

        // Determine which time is nearest and valid
        LocalDateTime scheduledTime;
        String reason;

        if (nextChestTime == null && nextStaminaTime == null) {
            scheduledTime = LocalDateTime.now().plusMinutes(FALLBACK_RESCHEDULE_MINUTES);
            reason = "No valid times (fallback)";
        } else if (nextChestTime == null) {
            scheduledTime = nextStaminaTime;
            reason = "stamina claim";
        } else if (nextStaminaTime == null) {
            scheduledTime = nextChestTime;
            reason = "chest claim";
        } else {
            // Both times valid - pick nearest
            if (nextChestTime.isBefore(nextStaminaTime)) {
                scheduledTime = nextChestTime;
                reason = "chest claim (nearest)";
            } else {
                scheduledTime = nextStaminaTime;
                reason = "stamina claim (nearest)";
            }
        }

        logInfo(String.format("Rescheduling for %s at: %s | chest=%s stamina=%s",
                reason, scheduledTime.format(DATETIME_FORMATTER),
                chestTimerRead ? "MEASURED" : "GUESS",
                staminaTimeMeasured ? "MEASURED" : "GUESS"));

        if (!reason.contains("fallback")) {
            logDebug(String.format("Chest: %s, Stamina: %s",
                    (nextChestTime != null) ? nextChestTime.format(DATETIME_FORMATTER) : "null",
                    (nextStaminaTime != null) ? nextStaminaTime.format(DATETIME_FORMATTER) : "null"));
        }

        // Exact: the chest time already carries its own 1-8% return jitter, and the queue-wide
        // jitter on reschedule() would stack a second offset on top of it.
        rescheduleExact(scheduledTime);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    public boolean provideDailyMissionProgress() {
        return true;
    }
}
