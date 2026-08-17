package dev.frostguard.tasks.events;

import java.time.LocalDateTime;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;

/**
 * matt/2026-08-13: the Lucky Wheel tab (under Events) has a "Lucky Chip Supply" icon
 * top-left that opens a "Lucky Chip Pack" shop screen. The top row, "Free Lucky Chip
 * Pack," has a genuinely free "Free" button, once a day. This only ever claims that
 * free row -- it never touches the paid packs below it.
 *
 * <p>
 * <b>Live-verified 2026-08-13</b>: Events -> Lucky Wheel tab -> Lucky Chip Supply
 * icon -> Free button -> reward reveal (real resources collected) -> button greyed
 * out afterward, confirming the claim registered. The Lucky Chip Supply icon's own
 * red-dot badge was also confirmed gone on the Lucky Wheel screen afterward.
 */
public class LuckyChipSupplyRoutine extends DelayedTask {

    private static final PointData LUCKY_CHIP_SUPPLY_ICON = new PointData(85, 380);
    private static final PointData REWARD_REVEAL_TAP_ANYWHERE = new PointData(358, 1182);
    /** matt/2026-08-13: same tab-strip swipe BankRoutine already uses -- a fresh app
     *  launch doesn't reliably reopen Events on the last-viewed tab. */
    private static final PointData TAB_SWIPE_START = new PointData(630, 143);
    private static final PointData TAB_SWIPE_END = new PointData(2, 128);
    private static final int MAX_TAB_SWIPES = 3;

    private static final int PANEL_SETTLE_MS = 1200;
    private static final int ACTION_SETTLE_MS = 900;

    public LuckyChipSupplyRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
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
        ImageSearchResultData eventsBtn = templateSearchHelper.locatePattern(
                TemplatesEnum.HOME_EVENTS_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!eventsBtn.isFound()) {
            LocalDateTime next = nextNoon();
            logInfo(logLine("Events icon not found. Rechecking at noon: " + next.format(DATETIME_FORMATTER) + "."));
            reschedule(next);
            return;
        }
        tapNear(eventsBtn.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        ImageSearchResultData tab = findLuckyWheelTab();
        if (!tab.isFound()) {
            LocalDateTime next = nextNoon();
            logInfo(logLine("Lucky Wheel tab not found even after swiping the tab strip. Closing "
                    + "and rechecking at noon: " + next.format(DATETIME_FORMATTER) + "."));
            pressBack();
            reschedule(next);
            return;
        }
        tapNear(tab.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        // matt/2026-08-13: Lucky Chip Supply is a fixed-position icon within the
        // Lucky Wheel screen itself (not a searchable template yet) -- the Lucky
        // Wheel tab match just above already confirms we're on the right screen
        // before this fixed tap fires.
        tapNear(LUCKY_CHIP_SUPPLY_ICON);
        sleepTask(PANEL_SETTLE_MS);

        ImageSearchResultData freeBtn = templateSearchHelper.locatePattern(
                TemplatesEnum.EVENTS_LUCKY_CHIP_FREE_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (freeBtn.isFound()) {
            logInfo(logLine("Free Lucky Chip Pack ready. Claiming."));
            tapNear(freeBtn.getPoint());
            sleepTask(ACTION_SETTLE_MS);
            tapNear(REWARD_REVEAL_TAP_ANYWHERE);
            sleepTask(ACTION_SETTLE_MS);
            StatisticsService.obtain().addToCounter(profile, "Lucky Chip Supply Claimed", 1);
        } else {
            logInfo(logLine("Free Lucky Chip Pack not available (already claimed today, or not ready)."));
        }

        pressBack();
        sleepTask(ACTION_SETTLE_MS);
        pressBack();

        LocalDateTime next = nextNoon();
        logInfo(logLine("Rechecking at noon: " + next.format(DATETIME_FORMATTER) + "."));
        reschedule(next);
    }

    // matt/2026-08-14: "have Hall of Chiefs and other rewards claimed at noon everyday, every
    // 24h" -- was rescheduling on a rolling "+24h from whenever it last ran" basis, which drifts
    // across DST/late-night runs. Anchoring to the next real noon instead.
    private LocalDateTime nextNoon() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime noon = now.toLocalDate().atTime(12, 0);
        if (!noon.isAfter(now)) {
            noon = noon.plusDays(1);
        }
        return noon;
    }

    private String logLine(String note) {
        return "LuckyChipSupplyRoutine | " + note;
    }

    private ImageSearchResultData findLuckyWheelTab() {
        ImageSearchResultData tab = templateSearchHelper.locatePattern(
                TemplatesEnum.EVENTS_TAB_LUCKY_WHEEL, SearchConfigConstants.SINGLE_WITH_RETRIES);
        for (int i = 0; i < MAX_TAB_SWIPES && !tab.isFound(); i++) {
            swipe(TAB_SWIPE_START, TAB_SWIPE_END, 400);
            sleepTask(ACTION_SETTLE_MS);
            tab = templateSearchHelper.locatePattern(
                    TemplatesEnum.EVENTS_TAB_LUCKY_WHEEL, SearchConfigConstants.SINGLE_WITH_RETRIES);
        }
        return tab;
    }
}
