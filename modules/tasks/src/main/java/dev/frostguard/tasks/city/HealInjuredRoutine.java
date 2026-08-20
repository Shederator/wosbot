package dev.frostguard.tasks.city;

import java.awt.Color;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.RegexNumberParser;

/**
 * Task responsible for auto-healing injured troops.
 *
 * <p>
 * On the World map, an icon over "My City" shows a healing-progress
 * percentage whenever troops are injured. Tapping it opens the "Heal Injured" panel,
 * which lists severely-injured troops by type with per-type heal queues (up to a few
 * running in parallel, mirroring how Training splits by camp). "Quick Select" + "Heal"
 * starts the highest-priority queue; once started, the same button becomes "Help" --
 * tapping it requests alliance assistance, which measured live cut a real 14:21 timer
 * down to 1:45 (roughly 90% reduction). The World-map badge percentage itself ("1%",
 * sometimes "2%" or higher by design) is NOT used for the actual decision here -- the
 * panel's own "Severely Injured X/Y" count is read instead, since that's the number
 * that actually determines whether there's anything to heal.
 *
 * <p>
 * <b>Known gap :</b> a real backpack-item Speedup ("if needed") is not
 * wired up yet -- Help alone handled the observed case (90% reduction), so this was left
 * as a manual the operator action for now rather than adding a whole Backpack-inventory read for
 * a case that may not come up often. Flagged as a clean follow-up, not forgotten.
 *
 * <p>
 * <b>Live-tested 2026-08-12</b> up through Quick Select -> Heal -> Help, all three
 * confirmed live in that exact session. The OCR reads (Severely Injured count, Healing
 * countdown) and the idle/no-injuries path were written from the same session's
 * screenshots but have NOT been separately live-verified -- flagged honestly, not
 * assumed correct.
 */
public class HealInjuredRoutine extends DelayedTask {

    // ========== Navigation ==========
    /** World-map icon over My City, tap to open the Heal Injured panel. Only present
     *  when troops are actually injured -- if nothing opens, treated as "nothing to heal". */
    private static final PointData HEAL_ICON_POINT = new PointData(560, 1035);

    // ========== Heal Injured panel (fixed layout, live-captured coordinates) ==========
    private static final PointData PANEL_TITLE_TOP_LEFT = new PointData(230, 240);
    private static final PointData PANEL_TITLE_BOTTOM_RIGHT = new PointData(460, 280);

    private static final PointData SEVERELY_INJURED_TOP_LEFT = new PointData(170, 305);
    private static final PointData SEVERELY_INJURED_BOTTOM_RIGHT = new PointData(340, 355);

    private static final PointData QUICK_SELECT_BTN = new PointData(134, 850);
    /** Same screen position serves as "Heal" (before starting) and "Help" (after
     *  starting) -- the panel swaps the button's label/action in place. */
    private static final PointData PRIMARY_ACTION_BTN = new PointData(517, 850);
    private static final PointData PANEL_CLOSE_BTN = new PointData(598, 257);

    private static final PointData HEALING_TIMER_TOP_LEFT = new PointData(400, 750);
    private static final PointData HEALING_TIMER_BOTTOM_RIGHT = new PointData(560, 780);

    // ========== Constants ==========
    /** "check every 30 minutes for the healing" when idle. */
    private static final int IDLE_RECHECK_MINUTES = 30;
    private static final int PANEL_SETTLE_MS = 1500;
    private static final int ACTION_SETTLE_MS = 1000;
    private static final int MAX_TIMER_HOURS_SANITY = 12;

    private static final OcrSettingsData WHITE_TEXT_SETTINGS = OcrSettingsData.assembler()
            .stripBackground(true)
            .setTextColor(new Color(255, 255, 255))
            .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ ")
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
            .build();

    private static final OcrSettingsData INJURED_COUNT_SETTINGS = OcrSettingsData.assembler()
            .stripBackground(true)
            .setTextColor(new Color(255, 255, 255))
            .charWhitelist("0123456789,/")
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
            .build();

    private static final OcrSettingsData TIMER_SETTINGS = OcrSettingsData.assembler()
            .stripBackground(true)
            .setTextColor(new Color(255, 255, 255))
            .charWhitelist("0123456789:")
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
            .build();

    public HealInjuredRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    @Override
    public boolean provideDailyMissionProgress() {
        return true;
    }

    @Override
    protected void execute() {
        tapNear(HEAL_ICON_POINT);
        sleepTask(PANEL_SETTLE_MS);

        if (!isHealPanelOpen()) {
            logInfo("Heal Injured panel didn't open -- nothing currently injured (or the "
                    + "World-map icon wasn't where expected). Rechecking in "
                    + IDLE_RECHECK_MINUTES + " minutes.");
            reschedule(LocalDateTime.now().plusMinutes(IDLE_RECHECK_MINUTES));
            return;
        }

        Integer injuredCount = readSeverelyInjuredCount();
        if (injuredCount == null) {
            logWarning("Could not OCR the Severely Injured count. Closing panel and "
                    + "rechecking in " + IDLE_RECHECK_MINUTES + " minutes rather than guessing.");
            closePanel();
            reschedule(LocalDateTime.now().plusMinutes(IDLE_RECHECK_MINUTES));
            return;
        }

        if (injuredCount == 0) {
            logInfo("Zero injured troops. Rechecking in " + IDLE_RECHECK_MINUTES + " minutes.");
            closePanel();
            reschedule(LocalDateTime.now().plusMinutes(IDLE_RECHECK_MINUTES));
            return;
        }

        logInfo("Severely Injured: " + injuredCount + ". Starting heal + requesting help.");

        tapNear(QUICK_SELECT_BTN);
        sleepTask(ACTION_SETTLE_MS);

        // First tap on the shared button position: starts healing ("Heal" -> queue begins).
        tapNear(PRIMARY_ACTION_BTN);
        sleepTask(ACTION_SETTLE_MS);

        // Second tap on the same position: the button has swapped to "Help" now that a
        // queue is running -- this requests alliance assistance on the timer.
        tapNear(PRIMARY_ACTION_BTN);
        sleepTask(ACTION_SETTLE_MS);

        StatisticsService.obtain().addToCounter(profile, "Heal Injured Started", 1);

        Duration remaining = readHealingTimer();
        closePanel();

        if (remaining == null) {
            logWarning("Could not OCR the healing countdown after starting. Falling back "
                    + "to the " + IDLE_RECHECK_MINUTES + "-minute idle recheck.");
            reschedule(LocalDateTime.now().plusMinutes(IDLE_RECHECK_MINUTES));
            return;
        }

        LocalDateTime nextCheck = LocalDateTime.now().plus(remaining);
        logInfo("Healing queue running, ready at " + nextCheck.format(DATETIME_FORMATTER)
                + ". Rescheduling to that time (more injured troops may be queued after).");
        reschedule(nextCheck);
    }

    private boolean isHealPanelOpen() {
        String title = stringHelper.attemptRecognition(
                PANEL_TITLE_TOP_LEFT, PANEL_TITLE_BOTTOM_RIGHT,
                3, 200L, WHITE_TEXT_SETTINGS,
                s -> s != null && !s.isBlank(),
                s -> s);
        return title != null && title.toLowerCase().contains("heal");
    }

    private Integer readSeverelyInjuredCount() {
        String text = stringHelper.attemptRecognition(
                SEVERELY_INJURED_TOP_LEFT, SEVERELY_INJURED_BOTTOM_RIGHT,
                3, 200L, INJURED_COUNT_SETTINGS,
                s -> s != null && s.matches(".*\\d.*"),
                s -> s);

        if (text == null) {
            return null;
        }

        // Format observed live: "382/79,300" -- the count BEFORE the slash is what's
        // actually queued/needing heal right now.
        Pattern beforeSlash = Pattern.compile("(\\d[\\d,]*)\\s*/");
        String digits = RegexNumberParser.extractByPattern(text, beforeSlash) != null
                ? String.valueOf(RegexNumberParser.extractByPattern(text, beforeSlash))
                : null;

        if (digits == null) {
            logWarning("Severely Injured text didn't match the expected 'N/total' shape: '"
                    + text + "'");
            return null;
        }

        try {
            return Integer.parseInt(digits.replace(",", ""));
        } catch (NumberFormatException e) {
            logWarning("Could not parse injured count from: '" + text + "'");
            return null;
        }
    }

    private Duration readHealingTimer() {
        String text = stringHelper.attemptRecognition(
                HEALING_TIMER_TOP_LEFT, HEALING_TIMER_BOTTOM_RIGHT,
                3, 200L, TIMER_SETTINGS,
                GameTimeUtils::isAcceptedFormat,
                s -> s);

        if (text == null) {
            return null;
        }

        try {
            Duration parsed = GameTimeUtils.parseDuration(text);
            if (parsed.toHours() > MAX_TIMER_HOURS_SANITY) {
                logWarning("Healing timer read implausibly long (" + parsed
                        + "), discarding rather than trusting it.");
                return null;
            }
            return parsed;
        } catch (Exception e) {
            logWarning("Could not parse healing timer from: '" + text + "'");
            return null;
        }
    }

    private void closePanel() {
        tapNear(PANEL_CLOSE_BTN);
        sleepTask(ACTION_SETTLE_MS);
    }
}
