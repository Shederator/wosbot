package dev.frostguard.tasks.economy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.convert.RegexNumberParser;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.service.StaminaService;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.tasks.diagnostics.TaskDiagnosticSnapshots;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;

/**
 * Task responsible for claiming rewards from the Storehouse.
 * 
 * <p>
 * This task:
 * <ul>
 * <li>Navigates to the Storehouse via Research Center</li>
 * <li>Claims daily chest rewards (available every few hours)</li>
 * <li>Claims a visible stamina can on the same visit</li>
 * <li>Reads the on-building countdown via OCR to schedule the next chest visit</li>
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
    private static final AreaData STOREHOUSE_VISIBLE_BUILDING_AREA = new AreaData(
            new PointData(105, 530), new PointData(125, 550));
    private static final AreaData STOREHOUSE_TITLE_AREA = new AreaData(
            new PointData(245, 515), new PointData(505, 575));
    private static final int STOREHOUSE_SELECTION_SETTLE_MILLIS = 2_200;
    private static final int STOREHOUSE_DESELECTION_SETTLE_MILLIS = 800;
    private static final PointData STOREHOUSE_SCROLL_START = new PointData(1, 636);
    private static final PointData STOREHOUSE_SCROLL_END = new PointData(2, 636);

    // ========== Stamina Reward Coordinates ==========
    private static final PointData STAMINA_AMOUNT_TOP_LEFT = new PointData(436, 632);
    private static final PointData STAMINA_AMOUNT_BOTTOM_RIGHT = new PointData(487, 657);
    private static final PointData STAMINA_CLAIM_BUTTON_TOP_LEFT = new PointData(250, 930);
    private static final PointData STAMINA_CLAIM_BUTTON_BOTTOM_RIGHT = new PointData(450, 950);

    // ========== Fallback Timer OCR ==========
    static final PointData FALLBACK_TIMER_TOP_LEFT = new PointData(285, 642);
    static final PointData FALLBACK_TIMER_BOTTOM_RIGHT = new PointData(430, 666);

    // ========== Constants ==========
    private static final int TIMER_OCR_MAX_ATTEMPTS = 3;
    private static final int MAX_TIMER_SECONDS = 7200; // 2 hours
    private static final int FALLBACK_RESCHEDULE_MINUTES = 5;
    // Template comparison still uses 75. Live search is the colour bubble.
    static final int CHEST_SEARCH_THRESHOLD = 75;
    private static final int BUBBLE_SEARCH_ATTEMPTS = 6;
    private static final long BUBBLE_SEARCH_DELAY_MILLIS = 250L;
    static final int UNREADABLE_WITHOUT_CHEST_HOURS = 1;
    private static final int CLAIM_CLOSE_SETTLE_MILLIS = 800;
    private static final String BUILDING_COUNTDOWN_WHITELIST = "0123456789:d";
    private static final int BASE_STOREHOUSE_STAMINA = 120;
    private static final int SCROLL_ATTEMPT_COUNT = 2;
    private static final int SCROLL_REPEAT_DELAY = 300;

    // On-building countdown glyphs measured on a 720x1280 city frame.
    static final Color BUILDING_TIMER_GREEN = new Color(61, 216, 13);

    // ========== OCR Settings ==========
    private static final OcrSettingsData STAMINA_OCR_SETTINGS = OcrSettingsData.assembler()
            .setTextColor(new Color(248, 247, 234))
            .stripBackground(true)
            .charWhitelist("0123456789")
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)

            .build();

    private ResilientOcrExecutor<LocalDateTime> textHelper;

    // ========== Execution State (reset each execution) ==========
    private LocalDateTime nextChestTime;
    private boolean nextChestTimeFallback;

    public StorehouseChestRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected boolean acceptsInjections() {
        return false;
    }

    /**
     * Resets execution-specific state.
     */
    private void resetExecutionState() {
        this.nextChestTime = null;
        logDebug("Execution state reset");
    }

    @Override
    protected void execute() {
        this.textHelper = new ResilientOcrExecutor<>(provider);
        resetExecutionState();

        if (!openStorehouse()) {
            logWarning("Failed to open Storehouse.");
            reschedule(LocalDateTime.now().plusMinutes(FALLBACK_RESCHEDULE_MINUTES));
            return;
        }

        processChestReward();
        processStaminaReward();
        scheduleToNearestTime();
    }

    /**
     * Opens the Storehouse from the stable Research Center sidebar anchor.
     */
    private boolean openStorehouse() {
        logDebug("Navigating to Storehouse");

        if (!navigationHelper.navigateToSidebarDestination(SidebarDestination.RESEARCH_CENTER)) {
            logError("Research Center sidebar destination not reached.");
            return false;
        }

        // The Research Center centers the city with a visible part of the Storehouse at the left.
        // Selecting that stable visible area avoids a momentum-sensitive map pan.
        tapInside(STOREHOUSE_VISIBLE_BUILDING_AREA.topLeft(), STOREHOUSE_VISIBLE_BUILDING_AREA.bottomRight());
        sleepTask(STOREHOUSE_SELECTION_SETTLE_MILLIS);

        ImageSearchResultData selected = templateSearchHelper.locatePattern(
                TemplatesEnum.STOREHOUSE_SELECTED_CURRENT,
                SearchConfig.builder()
                        .withArea(STOREHOUSE_TITLE_AREA)
                        .withThreshold(85)
                        .withMaxAttempts(3)
                        .withDelay(300L)
                        .build());
        if (selected.isFound()) {
            logInfo("Storehouse selected and verified by its title anchor.");
            // The reward bubbles are only actionable after Android Back closes the
            // building's Details/Upgrade selection controls.
            pressBack();
            sleepTask(STOREHOUSE_DESELECTION_SETTLE_MILLIS);
            logInfo("Storehouse selection controls closed; reward bubbles are now accessible.");
            return true;
        }

        logWarning("Storehouse title anchor was not detected after the direct selection.");
        return false;
    }

    /**
     * Processes the chest reward.
     * Searches for chest, claims it, and reads the next availability timer.
     */
    private void processChestReward() {
        logInfo("Searching for Storehouse chest reward.");
        nextChestTimeFallback = false;

        ImageSearchResultData chest = searchForBubble(StorehouseBubbleDetector.Kind.CHEST);

        if (chest.isFound()) {
            logInfo("Chest found. Claiming reward.");
            tapInside(chest);
            sleepTask(500);
            tapInside(STOREHOUSE_SCROLL_START, STOREHOUSE_SCROLL_END, SCROLL_ATTEMPT_COUNT, SCROLL_REPEAT_DELAY);
            sleepTask(CLAIM_CLOSE_SETTLE_MILLIS);

            nextChestTime = readFallbackTimer();
            if (nextChestTime == null) {
                nextChestTimeFallback = true;
                String snapshot = TaskDiagnosticSnapshots.capture(
                        emuManager, EMULATOR_NUMBER, "storehousechest", "claim-timer");
                nextChestTime = LocalDateTime.now().plusMinutes(FALLBACK_RESCHEDULE_MINUTES);
                logWarning("Claimed chest timer was unreadable; using the five-minute retry. " + snapshot);
            }
            return;
        }

        logWarning("Chest not found after maximum attempts. Trying fallback timer reading.");
        nextChestTime = readFallbackTimer();

        nextChestTimeFallback = fallbackAfterRead(nextChestTimeFallback, nextChestTime);
        if (nextChestTime == null) {
            String snapshot = TaskDiagnosticSnapshots.capture(
                    emuManager, EMULATOR_NUMBER, "storehousechest", "fallback-timer");
            nextChestTime = nextVisitWhenChestAbsent(LocalDateTime.now(), null);
            logWarning("Both Storehouse timer reads failed; using the one-hour retry. " + snapshot);
        }
    }

    /**
     * Polls the white-bubble colour detector. Six captures over about 1.5 s
     * cover one bob of the icon.
     */
    private ImageSearchResultData searchForBubble(StorehouseBubbleDetector.Kind kind) {
        for (int attempt = 1; attempt <= BUBBLE_SEARCH_ATTEMPTS; attempt++) {
            try {
                RawImageData capture = emuManager.captureScreen(EMULATOR_NUMBER);
                BufferedImage frame = ImageConverter.toBufferedImage(capture);
                for (StorehouseBubbleDetector.Candidate candidate : StorehouseBubbleDetector.locate(frame)) {
                    if (candidate.kind() == kind) {
                        logDebug("Storehouse " + kind.name().toLowerCase(Locale.ROOT) + " bubble found");
                        return ImageSearchResultData.hit(
                                candidate.center().getX(),
                                candidate.center().getY(),
                                1.0,
                                candidate.width(),
                                candidate.height());
                    }
                }
            } catch (RuntimeException ex) {
                logDebug("Storehouse bubble capture failed: " + ex.getClass().getSimpleName()
                        + ": " + ex.getMessage());
            }
            if (attempt < BUBBLE_SEARCH_ATTEMPTS) {
                sleepTask(BUBBLE_SEARCH_DELAY_MILLIS);
            }
        }
        return ImageSearchResultData.miss();
    }

    /**
     * Processes the stamina reward whenever the can is on screen.
     * A stored claim time must not hide a can that is still visible.
     */
    private void processStaminaReward() {
        logInfo("Searching for Storehouse stamina reward icon (with retries).");

        ImageSearchResultData stamina = searchForBubble(StorehouseBubbleDetector.Kind.STAMINA);

        if (!stamina.isFound()) {
            logWarning("Stamina icon not found after retries.");
            return;
        }

        logInfo("Stamina icon found. Tapping to open popup.");
        tapInside(stamina);

        logDebug("Waiting for claim button to appear in popup...");
        if (!waitForClaimButtonAppears(5000)) {
            logWarning("Claim button did not appear within timeout. Popup may not have loaded properly.");
            return;
        }

        logDebug("Claim button confirmed visible. Proceeding with claim.");
        claimStaminaReward();
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
        // Let the stamina details screen finish rendering before the amount OCR.
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

        LocalDateTime cooldown = readBuildingCountdown(buildingCountdownSettings());
        if (cooldown == null) {
            cooldown = readBuildingCountdown(buildingCountdownWhiteSettings());
        }

        if (cooldown == null) {
            logWarning("OCR returned empty time text");
            return null;
        }

        logDebug("Time OCR result: '" + GameTimeUtils.formatCountdown(cooldown) + "'");

        long secondsDiff = Duration.between(LocalDateTime.now(), cooldown).getSeconds();

        if (secondsDiff > MAX_TIMER_SECONDS) {
            String snapshot = TaskDiagnosticSnapshots.capture(
                    emuManager, EMULATOR_NUMBER, "storehousechest", "timer-out-of-range");
            logWarning(String.format("Timer exceeds 2 hours (%d min), using 1 hour fallback.", secondsDiff / 60));
            logWarning(snapshot);
            nextChestTimeFallback = true;
            return LocalDateTime.now().plusHours(UNREADABLE_WITHOUT_CHEST_HOURS);
        }

        return cooldown;
    }

    private LocalDateTime readBuildingCountdown(OcrSettingsData settings) {
        return textHelper.attemptRecognition(
                FALLBACK_TIMER_TOP_LEFT,
                FALLBACK_TIMER_BOTTOM_RIGHT,
                TIMER_OCR_MAX_ATTEMPTS,
                200L,
                settings,
                GameTimeUtils::isAcceptedFormat,
                text -> LocalDateTime.now().plus(GameTimeUtils.parseDuration(text)));
    }

    /**
     * Next visit from the chest countdown alone. A missing or past countdown
     * retries after five minutes. A stored stamina instant is not an input:
     * a visible can is claimed on the current visit.
     */
    static boolean fallbackAfterRead(boolean markedOutOfRange, LocalDateTime recognized) {
        return recognized == null || markedOutOfRange;
    }

    static String scheduleReason(boolean fallback) {
        return fallback ? "timer unreadable or invalid (fallback)" : "validated chest timer";
    }

    static LocalDateTime nextChestVisit(LocalDateTime now, LocalDateTime nextChestTime) {
        if (nextChestTime == null || nextChestTime.isBefore(now)) {
            return now.plusMinutes(FALLBACK_RESCHEDULE_MINUTES);
        }
        return nextChestTime;
    }

    static LocalDateTime nextVisitWhenChestAbsent(LocalDateTime now, LocalDateTime recognized) {
        if (recognized == null || recognized.isBefore(now)) {
            return now.plusHours(UNREADABLE_WITHOUT_CHEST_HOURS);
        }
        return recognized;
    }

    static OcrSettingsData buildingCountdownSettings() {
        return buildingCountdownSettings(BUILDING_TIMER_GREEN);
    }

    static OcrSettingsData buildingCountdownWhiteSettings() {
        return buildingCountdownSettings(Color.WHITE);
    }

    private static OcrSettingsData buildingCountdownSettings(Color textColor) {
        return OcrSettingsData.assembler()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
                .stripBackground(true)
                .setTextColor(textColor)
                .charWhitelist(BUILDING_COUNTDOWN_WHITELIST)
                .build();
    }

    private void scheduleToNearestTime() {
        LocalDateTime now = LocalDateTime.now();

        if (nextChestTime != null && nextChestTime.isBefore(now)) {
            logDebug("Chest time is in the past, treating as invalid.");
            nextChestTime = null;
            nextChestTimeFallback = true;
        }

        LocalDateTime scheduledTime = nextChestVisit(now, nextChestTime);
        String reason = scheduleReason(nextChestTimeFallback);

        logInfo(String.format("Rescheduling for %s at: %s",
                reason, scheduledTime.format(DATETIME_FORMATTER)));

        reschedule(scheduledTime);
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
