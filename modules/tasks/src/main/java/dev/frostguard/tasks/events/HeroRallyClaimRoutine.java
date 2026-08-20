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
 * Hero Rally lives under the in-game "Deals" panel (not "Events"),
 * a leveled reward track filled by normal play -- the bot's only job is finding a
 * ready Claim and hitting it, same "event slop" idea as EventClaimRoutine.
 *
 * <p>
 * <b>Live-verified 2026-08-13 (Claim All case)</b>: Deals icon -> Hero Rally tab ->
 * Claim All -> reward reveal (700 gems + assorted items collected for real) -> every
 * row showed a green checkmark afterward, confirming full depletion.
 *
 * <p>
 * <b>caught live</b>: "Claim All" only appears when multiple levels
 * are ready at once and batch together -- the far more common state is a single
 * level ready with a plain green "Claim" button next to it (screenshot: Lv.16,
 * ordinary "Claim" pill, no "All"). The original build only searched for
 * EVENTS_HERO_RALLY_CLAIM_ALL, so it walked right past this and nothing got
 * claimed. Now checks for either, and loops the plain "Claim" case since more than
 * one level's reward can be sitting ready at a time. Deliberately does NOT touch the
 * "Purchase Level" / "Obtain Points" buttons further down the track -- those are paid
 * CTAs, not something a free claim pass should ever tap.
 */
public class HeroRallyClaimRoutine extends DelayedTask {

    private static final PointData REWARD_REVEAL_TAP_ANYWHERE = new PointData(358, 1182);
    private static final int MAX_CLAIM_LOOPS = 10;
    /** A fresh app launch doesn't reliably reopen the Deals panel on
     *  the last-viewed tab -- confirmed live, the Hero Rally tab search came up empty
     *  even though nothing about the game had changed since it was open moments
     *  earlier by hand. Same tab-strip swipe BankRoutine already uses to reveal its
     *  own tab, reused here and re-searched after each swipe. */
    private static final PointData TAB_SWIPE_START = new PointData(630, 143);
    private static final PointData TAB_SWIPE_END = new PointData(2, 128);
    private static final int MAX_TAB_SWIPES = 3;

    private static final int PANEL_SETTLE_MS = 1200;
    private static final int ACTION_SETTLE_MS = 900;

    public HeroRallyClaimRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
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
        ImageSearchResultData dealsBtn = templateSearchHelper.locatePattern(
                TemplatesEnum.HOME_DEALS_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!dealsBtn.isFound()) {
            LocalDateTime next = nextNoon();
            logInfo(logLine("Deals icon not found. Rechecking at noon: " + next.format(DATETIME_FORMATTER) + "."));
            reschedule(next);
            return;
        }
        tapNear(dealsBtn.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        ImageSearchResultData tab = findHeroRallyTab();
        if (!tab.isFound()) {
            LocalDateTime next = nextNoon();
            logInfo(logLine("Hero Rally tab not found even after swiping the tab strip. Closing "
                    + "and rechecking at noon: " + next.format(DATETIME_FORMATTER) + "."));
            pressBack();
            reschedule(next);
            return;
        }
        tapNear(tab.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        ImageSearchResultData claimAll = templateSearchHelper.locatePattern(
                TemplatesEnum.EVENTS_HERO_RALLY_CLAIM_ALL, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (claimAll.isFound()) {
            logInfo(logLine("Claim All found. Claiming."));
            tapNear(claimAll.getPoint());
            sleepTask(ACTION_SETTLE_MS);
            tapNear(REWARD_REVEAL_TAP_ANYWHERE);
            sleepTask(ACTION_SETTLE_MS);
            StatisticsService.obtain().addToCounter(profile, "Hero Rally Claimed", 1);
        }

        int claimedThisPass = claimAllReadySingleRows();
        if (!claimAll.isFound() && claimedThisPass == 0) {
            logInfo(logLine("Nothing ready to claim right now."));
        }

        pressBack();
        sleepTask(ACTION_SETTLE_MS);
        pressBack();

        LocalDateTime next = nextNoon();
        logInfo(logLine("Rechecking at noon: " + next.format(DATETIME_FORMATTER) + "."));
        reschedule(next);
    }

    // "have Hall of Chiefs and other rewards claimed at noon everyday, every
    // 24h" -- was rescheduling on a rolling "+12h from whenever it last ran" basis, which drifts.
    // Anchoring to the next real noon instead so this always lands at the same clock time.
    private LocalDateTime nextNoon() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime noon = now.toLocalDate().atTime(12, 0);
        if (!noon.isAfter(now)) {
            noon = noon.plusDays(1);
        }
        return noon;
    }

    /** The common single-level case -- a plain "Claim" pill (not
     *  "Claim All"). Loops since more than one level's reward can be ready at once,
     *  each claim collapsing the row and potentially revealing the next one. */
    private int claimAllReadySingleRows() {
        int claimed = 0;
        for (int i = 0; i < MAX_CLAIM_LOOPS; i++) {
            ImageSearchResultData claim = templateSearchHelper.locatePattern(
                    TemplatesEnum.EVENTS_CLAIM_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
            if (!claim.isFound()) {
                break;
            }
            logInfo(logLine("Claim found (row " + (claimed + 1) + "). Claiming."));
            tapNear(claim.getPoint());
            sleepTask(ACTION_SETTLE_MS);
            tapNear(REWARD_REVEAL_TAP_ANYWHERE);
            sleepTask(ACTION_SETTLE_MS);
            claimed++;
        }
        if (claimed > 0) {
            StatisticsService.obtain().addToCounter(profile, "Hero Rally Claimed", claimed);
        }
        return claimed;
    }

    private String logLine(String note) {
        return "HeroRallyClaimRoutine | " + note;
    }

    private ImageSearchResultData findHeroRallyTab() {
        ImageSearchResultData tab = templateSearchHelper.locatePattern(
                TemplatesEnum.EVENTS_TAB_HERO_RALLY, SearchConfigConstants.SINGLE_WITH_RETRIES);
        for (int i = 0; i < MAX_TAB_SWIPES && !tab.isFound(); i++) {
            swipe(TAB_SWIPE_START, TAB_SWIPE_END, 400);
            sleepTask(ACTION_SETTLE_MS);
            tab = templateSearchHelper.locatePattern(
                    TemplatesEnum.EVENTS_TAB_HERO_RALLY, SearchConfigConstants.SINGLE_WITH_RETRIES);
        }
        return tab;
    }
}
