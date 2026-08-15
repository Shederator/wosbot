package dev.frostguard.tasks.events;

import java.time.LocalDateTime;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;

/**
 * matt/2026-08-12: "event slop" routine -- rotating limited-time Events-tab events
 * (Hall of Chiefs, Defeat Nearby Beasts, and whatever else shows up) where the bot's
 * only job is to notice a ready "Claim" and hit it, over and over, until there's
 * nothing left. The actual PROGRESS toward each target is earned through normal
 * player actions elsewhere (training troops, gathering, etc.) -- this task never
 * tries to advance anything, only collects what's already sitting there.
 *
 * <p>
 * One instance per event type, parameterized by {@link EventKind} (which tab icon to
 * search for). Every event shares the exact same green "Claim" button skin
 * (confirmed live against Hall of Chiefs), so a single reusable template covers the
 * claim-loop for all of them.
 *
 * <p>
 * Navigation is fully image-search based -- the World-map "Events" icon is fixed UI
 * chrome (not a map building), so unlike Monument this has none of the camera-drift
 * problem. If the event's tab icon isn't found (rotated out, not currently running),
 * this closes cleanly and rechecks later rather than assuming anything is wrong.
 *
 * <p>
 * <b>Live-verified 2026-08-12</b>: Hall of Chiefs tab detection + the Claim button
 * template, confirmed against the live account (a real 5,360,000-point milestone
 * with an unclaimed reward and a red-dot badge). Defeat Nearby Beasts' tab icon was
 * cropped from a live screenshot but that event's 2-day window expired mid-session
 * before the claim loop itself could be exercised against it -- flagged honestly,
 * not assumed correct; the next time it's active is the first real test.
 */
public class EventClaimRoutine extends DelayedTask {

    public enum EventKind {
        HALL_OF_CHIEFS("Hall of Chiefs", TemplatesEnum.EVENTS_TAB_HALL_OF_CHIEFS),
        DEFEAT_NEARBY_BEASTS("Defeat Nearby Beasts", TemplatesEnum.EVENTS_TAB_DEFEAT_BEASTS),
        // matt/2026-08-13: live-verified by hand -- tab icon cropped fresh from the live account,
        // Claim buttons already green/ready with a real 1,004,289-point ranking behind them (4 tiers
        // claimable at once: 1,000 / 33,000 / 83,000 / 167,000). Same shared Claim-button skin as
        // Hall of Chiefs, so this reuses the exact same claim loop below -- only the tab template
        // differs.
        BROTHERS_IN_ARMS("Brothers in Arms", TemplatesEnum.EVENTS_TAB_BROTHERS_IN_ARMS);

        private final String label;
        private final TemplatesEnum tabTemplate;

        EventKind(String label, TemplatesEnum tabTemplate) {
            this.label = label;
            this.tabTemplate = tabTemplate;
        }

        public String getLabel() {
            return label;
        }

        public TemplatesEnum getTabTemplate() {
            return tabTemplate;
        }
    }

    private static final int MAX_CLAIM_LOOPS = 10;
    private static final int PANEL_SETTLE_MS = 1200;
    private static final int ACTION_SETTLE_MS = 900;

    private final EventKind eventKind;

    public EventClaimRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask, EventKind eventKind) {
        super(profile, tpDailyTask);
        this.eventKind = eventKind;
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

        tapPoint(eventsBtn.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        ImageSearchResultData tab = templateSearchHelper.locatePattern(
                eventKind.getTabTemplate(), SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!tab.isFound()) {
            LocalDateTime next = nextNoon();
            logInfo(logLine(eventKind.getLabel() + " tab not currently showing (probably not running "
                    + "right now). Closing and rechecking at noon: " + next.format(DATETIME_FORMATTER) + "."));
            pressBack();
            reschedule(next);
            return;
        }

        logInfo(logLine(eventKind.getLabel() + " tab found. Claiming any ready rewards."));
        tapPoint(tab.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        int claimed = 0;
        for (int i = 0; i < MAX_CLAIM_LOOPS; i++) {
            ImageSearchResultData claimBtn = templateSearchHelper.locatePattern(
                    TemplatesEnum.EVENTS_CLAIM_BUTTON, SearchConfigConstants.QUICK_SEARCH);
            if (!claimBtn.isFound()) {
                break;
            }
            tapPoint(claimBtn.getPoint());
            sleepTask(ACTION_SETTLE_MS);
            claimed++;
            if (i == MAX_CLAIM_LOOPS - 1) {
                logWarning(logLine("Hit the claim-loop safety cap (" + MAX_CLAIM_LOOPS + ")."));
            }
        }
        logInfo(logLine(eventKind.getLabel() + ": claimed " + claimed + " reward(s)."));

        pressBack();
        sleepTask(ACTION_SETTLE_MS);
        pressBack();

        if (claimed > 0) {
            StatisticsService.obtain().addToCounter(profile, eventKind.getLabel() + " Claimed", claimed);
        }

        LocalDateTime next = nextNoon();
        logInfo(logLine("Rechecking at noon: " + next.format(DATETIME_FORMATTER) + "."));
        reschedule(next);
    }

    // matt/2026-08-14: "have Hall of Chiefs and other rewards claimed at noon everyday, every
    // 24h" -- was rescheduling on a rolling "+12h from whenever it last ran" basis, which drifts
    // (a 3:47pm run lands the next check at 3:47am, then 3:47pm again, never settling on a fixed
    // time of day). Anchoring to the next real noon instead so this always lands at the same
    // clock time, once a day.
    private LocalDateTime nextNoon() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime noon = now.toLocalDate().atTime(12, 0);
        if (!noon.isAfter(now)) {
            noon = noon.plusDays(1);
        }
        return noon;
    }

    private String logLine(String note) {
        return "EventClaimRoutine[" + eventKind.getLabel() + "] | " + note;
    }
}
