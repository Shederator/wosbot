package dev.frostguard.tasks.city;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.vision.convert.RegexNumberParser;

/**
 * Task responsible for the Monument / "Explore the World" Atlas feature: claiming
 * ready milestone rewards and cracking open owned Scene Fragment Packs from the
 * shared Fragment Backpack.
 *
 * <p>
 * matt/2026-08-14: the original {@code LaunchPoint.HOME}-then-pan-search approach
 * (see the old header below) turned out to be unreliable in practice -- camera pan
 * position drifts run to run depending on whatever the prior task left it at, so the
 * badge was often not where the pan sweep expected. Real fix, live-verified on the
 * Testing profile before merging here: anchor off Lancer Camp instead (a fixed
 * building, always reachable the same way via the left-menu queue list), then a
 * single confirmed 300px right swipe brings Monument's badge into view every time.
 * See {@link #findAndOpenBadgeViaLancer()}.
 *
 * <p>
 * <b>Flow (live-verified 2026-08-14, screenshot-confirmed at every step):</b>
 * <pre>
 * Home -> open left-menu City section -> tap Lancer row -> tap the camp building
 * -> wait 5s -> swipe right 300px -> template-search the reward badge, tap it,
 * VERIFY it's no longer detectable (confirms something actually opened, instead of
 * assuming) -> claim any ready rows -> X close -> back arrow -> Tundra Albums hub
 * -> Fragment Backpack (bottom-right of the hub) -> open every owned pack (rescanning
 * from scratch after each open, since the panel reflows) -> close -> milestone chest
 * track -> back arrow -> Home, badge cleared.
 * </pre>
 *
 * <p>
 * <b>Fragment Backpack bug fixed 2026-08-14:</b> this used to fire one screen too
 * early, at a coordinate (470,1275) that doesn't correspond to a real button on the
 * screen the badge tap actually leads to -- it always missed, read garbled OCR text,
 * and silently skipped the whole backpack pass every single run. Moved to fire AFTER
 * the back-arrow (on the real Tundra Albums hub) at the hub's own Fragment Backpack
 * button (628,1197) -- live-verified hand-driven, screenshot-confirmed: opened 2 real
 * packs (General Album, Nature's Strength), rewards actually landed (fragment count
 * advanced 827/1347 -> 846/1347), panel reflow after each open behaved exactly as the
 * candidate-rescan logic below expects.
 *
 * <p>
 * <b>Alliance Trade deliberately NOT run automatically (matt's call, 2026-08-14):</b>
 * the request/send logic below is real and was live-verified working correctly (Ally
 * Requests skip owned:1 rows and only send genuine duplicates, confirmed against a
 * live panel) -- but matt wants to handle Alliance Trade manually for now, so
 * {@code execute()} no longer calls it. Left in place, unused, for a future re-enable
 * rather than deleted.
 *
 * <p>
 * <b>Known gaps (not built):</b> Ally Requests list is only scanned for rows already
 * visible on open (no deep-scroll dedup), matching the same scroll-list limitation
 * already known in ChatCaptureRoutine.
 */
public class MonumentRoutine extends DelayedTask {

    // ========== Stray-popup clearing (game-rendered modals ignore Android back) ==========
    /** Every close-X position observed so far across different tasks' leftover
     *  panels (Resource Stockpile Scan's "Overview", the "Resource &amp; Speedup
     *  Summary" panel) -- tapped every round alongside back presses. Not exhaustive
     *  by design; the round-and-recheck loop in clearStrayPopups() is what actually
     *  makes this generic, this list just gives it a head start on known cases. */
    private static final PointData[] KNOWN_STRAY_PANEL_CLOSE_SPOTS = {
            new PointData(690, 358),
            new PointData(665, 258),
    };

    // ========== Lancer-relative navigation to Monument (matt/2026-08-14) ==========
    // Same coordinates as TrainingRoutine.LANCER_AREA_VALUE / TRAINING_CAMP_TAP_MIN/MAX_VALUE --
    // the Lancer row in the left-menu City queue list, then the camp building itself.
    private static final PointData LANCER_AREA_TOP_LEFT = new PointData(161, 636);
    private static final PointData LANCER_AREA_BOTTOM_RIGHT = new PointData(289, 664);
    private static final PointData CAMP_TAP_TOP_LEFT = new PointData(310, 650);
    private static final PointData CAMP_TAP_BOTTOM_RIGHT = new PointData(450, 730);
    private static final int POST_LANCER_WAIT_MS = 5000;
    private static final PointData SWIPE_RIGHT_START = new PointData(550, 700);
    private static final PointData SWIPE_RIGHT_END = new PointData(250, 700);
    private static final int SWIPE_DURATION_MS = 400;
    private static final int POST_SWIPE_WAIT_MS = 1000;

    // ========== Quest-list modal + Atlas grid (shared skin across categories) ==========
    private static final PointData MODAL_CLOSE_X = new PointData(662, 157);
    private static final PointData ATLAS_BACK_ARROW = new PointData(41, 52);
    private static final int MAX_CLAIM_LOOPS = 10;

    // ========== Tundra Albums hub ==========
    private static final PointData ALBUMS_BACK_ARROW = new PointData(41, 52);
    private static final PointData ALBUMS_FRAGMENT_BACKPACK_BTN = new PointData(626, 1197);
    private static final PointData ALBUMS_ALLIANCE_TRADE_BTN = new PointData(448, 1197);

    // ========== Fragment Backpack panel (shared across all Atlas categories) ==========
    private static final PointData BACKPACK_CLOSE_X = new PointData(662, 138);
    private static final PointData BACKPACK_TITLE_TL = new PointData(215, 105);
    private static final PointData BACKPACK_TITLE_BR = new PointData(505, 145);
    /** First pack row icon tap point. Rows below repeat at ~ROW_SPACING. */
    private static final PointData BACKPACK_FIRST_ROW_ICON = new PointData(358, 284);
    private static final PointData BACKPACK_FIRST_ROW_OWNED_TL = new PointData(320, 315);
    private static final PointData BACKPACK_FIRST_ROW_OWNED_BR = new PointData(400, 345);
    private static final int BACKPACK_ROW_SPACING = 227;
    private static final int BACKPACK_MAX_ROWS = 4;
    private static final int BACKPACK_MAX_OPENS_PER_ROW = 20;

    // ========== Fragment Pack detail (Enable) screen ==========
    /** Quantity defaults to the full owned count already -- one Enable tap consumes
     *  all of them (confirmed live twice: stacks of 2 fully consumed in one tap). */
    private static final PointData PACK_DETAIL_ENABLE_BTN = new PointData(358, 905);
    /** "Tap anywhere to close" reward-reveal screen -- tap near the text, not dead-center. */
    private static final PointData REWARD_REVEAL_TAP_ANYWHERE = new PointData(358, 1198);

    // ========== Alliance Trade panel ==========
    private static final PointData TRADE_CLOSE_X = new PointData(662, 155);
    private static final PointData MY_REQUESTS_REQUEST_BTN = new PointData(358, 370);
    private static final PointData MY_REQUESTS_LEFT_TL = new PointData(200, 268);
    private static final PointData MY_REQUESTS_LEFT_BR = new PointData(560, 300);
    private static final PointData PIECE_PICKER_REQUEST_BTN = new PointData(543, 891);
    private static final PointData PIECE_PICKER_TIPS_CONFIRM = new PointData(358, 789);
    private static final int MAX_REQUEST_LOOPS = 5;

    private static final PointData ALLY_FIRST_ROW_SEND_BTN = new PointData(583, 712);
    private static final PointData ALLY_FIRST_ROW_OWNED_TL = new PointData(580, 665);
    private static final PointData ALLY_FIRST_ROW_OWNED_BR = new PointData(700, 695);
    private static final int ALLY_ROW_SPACING = 237;
    private static final int ALLY_MAX_VISIBLE_ROWS = 3;

    private static final int IDLE_RECHECK_MINUTES = 60;
    private static final int PANEL_SETTLE_MS = 1200;
    private static final int ACTION_SETTLE_MS = 900;
    /** The reward-reveal animation after Enable runs noticeably longer than a normal
     *  panel transition -- matt/2026-08-12, root cause of an earlier stuck-owned-count bug. */
    private static final int PACK_OPEN_SETTLE_MS = 1800;

    private static final TesseractSettingsData PANEL_TITLE_OCR_SETTINGS = TesseractSettingsData.assembler()
            .stripBackground(true)
            .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ ")
            .pageAnalysis(TesseractSettingsData.PageAnalysis.SINGLE_LINE)
            .build();

    private static final TesseractSettingsData OWNED_COUNT_OCR_SETTINGS = TesseractSettingsData.assembler()
            .stripBackground(true)
            .charWhitelist("OwnedOWNED:0123456789 ")
            .pageAnalysis(TesseractSettingsData.PageAnalysis.SINGLE_LINE)
            .build();

    public MonumentRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    public boolean provideDailyMissionProgress() {
        return true;
    }

    @Override
    protected void execute() {
        // matt/2026-08-12: a prior task (Resource Stockpile Scan, in particular) can
        // leave its own popup open when Monument's turn comes up. Clear it first,
        // unconditionally, before searching for anything.
        clearStrayPopups();

        if (!findAndOpenBadgeViaLancer()) {
            logInfo(logLine("No rewards-ready badge found right now. Rechecking in "
                    + IDLE_RECHECK_MINUTES + " minutes."));
            reschedule(LocalDateTime.now().plusMinutes(IDLE_RECHECK_MINUTES));
            return;
        }

        logInfo(logLine("Badge opened. Claiming any ready rows."));
        claimAllReadyRows();

        tapPoint(MODAL_CLOSE_X);
        sleepTask(PANEL_SETTLE_MS);

        tapPoint(ATLAS_BACK_ARROW);
        sleepTask(ACTION_SETTLE_MS);

        // matt/2026-08-14: moved here (was called before the back-arrow, at a coordinate that
        // doesn't exist on that screen -- see class header). This is the real Tundra Albums
        // hub, where Fragment Backpack's actual button lives.
        logInfo(logLine("On Tundra Albums. Processing the shared Fragment Backpack."));
        processFragmentBackpack();

        logInfo(logLine("Checking the milestone chest track."));
        claimMilestoneChestsIfReady();

        // matt/2026-08-14: Alliance Trade deliberately not run automatically -- matt wants to
        // handle it manually for now. processAllianceTradeRequests()/processAllianceTradeSends()
        // are live-verified working correctly (see class header) and left in place for a future
        // re-enable, just not called here.

        tapPoint(ALBUMS_BACK_ARROW);
        sleepTask(ACTION_SETTLE_MS);

        StatisticsService.obtain().addToCounter(profile, "Monument Pass Completed", 1);
        logInfo(logLine("Monument pass complete. Rechecking in " + IDLE_RECHECK_MINUTES + " minutes."));
        reschedule(LocalDateTime.now().plusMinutes(IDLE_RECHECK_MINUTES));
    }

    /**
     * matt/2026-08-14: replaces the old direct-search-then-pan approach (kept below as
     * {@link #findBadgeWithPanFallback()}, tried second) -- navigates to Lancer Camp (a fixed,
     * always-reachable building) then a single confirmed 300px right swipe brings Monument's
     * badge into view, live-verified repeatedly. Taps the badge, then VERIFIES it's no longer
     * detectable before proceeding -- a tap that misses returns false here instead of the
     * caller assuming success and cascading into blind taps on whatever's actually on screen
     * (this is exactly what happened live: a missed badge tap once led straight into
     * accidentally opening the Events tab).
     */
    private boolean findAndOpenBadgeViaLancer() {
        marchHelper.openLeftMenuCitySection(true);
        sleepTask(500);

        tapRandomPoint(LANCER_AREA_TOP_LEFT, LANCER_AREA_BOTTOM_RIGHT, 1, 500);
        tapRandomPoint(CAMP_TAP_TOP_LEFT, CAMP_TAP_BOTTOM_RIGHT, 1, 300);
        sleepTask(POST_LANCER_WAIT_MS);

        swipe(SWIPE_RIGHT_START, SWIPE_RIGHT_END, SWIPE_DURATION_MS);
        sleepTask(POST_SWIPE_WAIT_MS);

        ImageSearchResultData badge = templateSearchHelper.locatePattern(
                TemplatesEnum.MONUMENT_REWARD_BADGE, SearchConfigConstants.RESILIENT);
        if (!badge.isFound()) {
            logInfo(logLine("Badge not found via the Lancer-relative route -- falling back to the "
                    + "direct-search-and-pan method."));
            badge = findBadgeWithPanFallback();
            if (!badge.isFound()) {
                return false;
            }
        }

        tapPoint(badge.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        ImageSearchResultData badgeStillThere = templateSearchHelper.locatePattern(
                TemplatesEnum.MONUMENT_REWARD_BADGE, SearchConfigConstants.QUICK_SEARCH);
        if (badgeStillThere.isFound()) {
            logWarning(logLine("Tapped the badge at " + badge.getPoint()
                    + " but it's still detectable on screen afterward -- nothing opened. "
                    + "Stopping here instead of cascading into the rest of the chain blind."));
            return false;
        }

        return true;
    }

    private String logLine(String note) {
        return "MonumentRoutine | " + note;
    }

    /**
     * matt/2026-08-12: confirmed live that Resource Stockpile Scan's "Overview"
     * panel does NOT close on a back press -- it's a game-rendered modal, not a
     * native Android view, so the system back key is simply ignored by it. A
     * different task later left a DIFFERENT panel open ("Resource &amp; Speedup
     * Summary") proving one hardcoded close spot will never keep up with however
     * many other tasks can leave something open -- rebuilt as a real loop: repeat
     * (press back several times, tap every known stray-panel close spot) and
     * re-check via search after each round, stopping the moment the badge or a
     * clean Home screen is confirmed.
     *
     * <p>
     * matt/2026-08-14: root-caused live, by watching the actual screen after a run --
     * this game's own back-button behavior on the City/Home view is to ZOOM OUT to
     * the World strategic map, not to close nothing-there / exit. With nothing open
     * (the overwhelmingly common case), the blind {@code pressBack()} x3 x4-rounds
     * batch below zoomed the camera all the way out to World every single time --
     * confirmed by a live screenshot immediately after a run landing squarely on the
     * World map. From World, Monument's badge/building templates (Home-only) can
     * never match again, so it got permanently stuck until some unrelated task
     * happened to navigate back to Home for its own purposes. This is exactly what
     * matt saw and described as the app "freaking out" / "almost exiting."
     * <p>
     * Fix: stop reinventing Home-recovery with raw back-presses. The framework
     * already has {@link dev.frostguard.engine.helper.NavigationHelper#ensureCorrectScreenLocation}
     * for exactly this -- it tells Home and World apart by template, taps the
     * correct zoom icon to get back to Home instead of guessing with more back
     * presses, and only falls back to a cautious single back-press-and-recheck loop
     * when the screen is genuinely unrecognized. Still try the known stray-panel
     * close spots first (real game-rendered modals that DON'T respond to back at
     * all), then hand recovery to the framework instead of a local back-press loop.
     */
    private void clearStrayPopups() {
        if (isScreenClear()) {
            logInfo(logLine("Screen already clear, no clearing needed."));
            return;
        }

        for (PointData closeSpot : KNOWN_STRAY_PANEL_CLOSE_SPOTS) {
            tapPoint(closeSpot);
            sleepTask(300);
            if (isScreenClear()) {
                logInfo(logLine("Screen confirmed clear after a stray-panel close tap."));
                return;
            }
        }

        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);

        if (isScreenClear()) {
            logInfo(logLine("Screen confirmed clear after ensureCorrectScreenLocation(HOME)."));
        } else {
            logInfo(logLine("Home confirmed by ensureCorrectScreenLocation, but neither the reward badge "
                    + "nor the Monument building anchor matched -- proceeding anyway, the badge search "
                    + "right after this will catch a genuinely blocked screen."));
        }
    }

    private boolean isScreenClear() {
        return templateSearchHelper.locatePattern(
                TemplatesEnum.MONUMENT_REWARD_BADGE, SearchConfigConstants.QUICK_SEARCH).isFound()
                || templateSearchHelper.locatePattern(
                TemplatesEnum.MONUMENT_BUILDING_ANCHOR, SearchConfigConstants.QUICK_SEARCH).isFound();
    }

    // ========== Camera-pan fallback (matt/2026-08-13) ==========
    // matt/2026-08-13: root-caused live, by hand, comparing a real screenshot against what the bot
    // was actually seeing -- ensureCorrectScreenLocation(HOME) only confirms the camera is ZOOMED to
    // the City view (via the Furnace anchor), not that it's PANNED to wherever Monument's building
    // happens to sit. Repeated automated runs never had Monument in the viewport at all; a manual
    // screenshot did. The original header comment's claim that the badge is "directly visible with no
    // panning needed" only held for whatever camera position that walkthrough happened to be at --
    // not a guarantee for every session. Real fix: after the direct look comes up empty, systematically
    // pan the camera in each cardinal direction and re-check after every pan, instead of assuming the
    // first look is the only look. Drags are controlled (explicit duration), not flicks, to avoid
    // triggering momentum scrolling far past the intended distance.
    // matt/2026-08-13, Part 2: live-verified by hand -- from the app's own default camera position,
    // Monument needed a SINGLE pan roughly up-and-left (finger drag from ~(550,800) to ~(300,500), i.e.
    // dx=-250/dy=-300) to come fully into view; the original 280px reach undershot that in the
    // corresponding diagonal direction, and the 90-threshold recheck was tight enough that a
    // partially-settled frame could still miss. Widened the reach, and each direction now takes TWO
    // steps (so effective max reach is 2x the single-step distance) before giving up on that heading,
    // re-checking after every single step rather than only at the end.
    private static final PointData PAN_CENTER = new PointData(360, 650);
    private static final int PAN_DISTANCE_PX = 320;
    private static final int PAN_DRAG_DURATION_MS = 400;
    private static final int PAN_SETTLE_MS = 600;

    /** (dx, dy) pan directions tried in order -- the diagonals moved first since that's the confirmed
     *  real direction, cardinals as a wider net after. */
    private static final int[][] PAN_DIRECTIONS = {
            {-PAN_DISTANCE_PX, -PAN_DISTANCE_PX}, // confirmed live: this is the real direction
            {PAN_DISTANCE_PX, PAN_DISTANCE_PX},
            {PAN_DISTANCE_PX, -PAN_DISTANCE_PX},
            {-PAN_DISTANCE_PX, PAN_DISTANCE_PX},
            {0, PAN_DISTANCE_PX},   // reveal what's above (drag content up)
            {0, -PAN_DISTANCE_PX},  // reveal what's below
            {PAN_DISTANCE_PX, 0},   // reveal what's to the left
            {-PAN_DISTANCE_PX, 0},  // reveal what's to the right
    };

    // matt/2026-08-14: caught live watching the app -- "it just randomly searches around the
    // screen." Root cause chain: (1) MONUMENT_BUILDING_ANCHOR was stale (captured against an old
    // building skin, live-verified 0.35 score against the actual current golden-ringed Monument --
    // recaptured), which made isScreenClear() unreliable and pushed almost every run into the full
    // pan fallback; (2) camera pan position genuinely drifts between runs depending on whatever the
    // PRIOR task left it at (ensureCorrectScreenLocation only confirms zoom level, not pan position
    // -- a known, documented limitation, not new). With nothing actually claimable for the last 12
    // hours straight (confirmed by hand, multiple screens, the building itself only showing its own
    // multi-day construction timer, no reward badge anywhere), the full 8-direction x2-step (16 tap)
    // sweep every single run is what LOOKS like erratic wandering even when it's technically correct.
    // Cut runtime/visual noise roughly in half: keep the one CONFIRMED real direction at full
    // strength, everything else drops to a single step -- still a real safety net, just not a
    // performance every hour.
    private static final int PAN_STEPS_PRIMARY_DIRECTION = 2;
    private static final int PAN_STEPS_SECONDARY_DIRECTION = 1;

    private ImageSearchResultData findBadgeWithPanFallback() {
        ImageSearchResultData badge = templateSearchHelper.locatePattern(
                TemplatesEnum.MONUMENT_REWARD_BADGE, SearchConfigConstants.RESILIENT);
        if (badge.isFound()) {
            return badge;
        }

        logInfo(logLine("Badge not visible at the default camera position -- panning to search for it "
                + "(Monument's on-screen position isn't guaranteed by ensureCorrectScreenLocation)."));

        int panned = 0;
        for (int dirIndex = 0; dirIndex < PAN_DIRECTIONS.length; dirIndex++) {
            int[] direction = PAN_DIRECTIONS[dirIndex];
            int stepsThisDirection = dirIndex == 0 ? PAN_STEPS_PRIMARY_DIRECTION : PAN_STEPS_SECONDARY_DIRECTION;
            PointData start = new PointData(PAN_CENTER.getX(), PAN_CENTER.getY());
            PointData end = new PointData(PAN_CENTER.getX() + direction[0], PAN_CENTER.getY() + direction[1]);

            for (int step = 0; step < stepsThisDirection; step++) {
                swipe(start, end, PAN_DRAG_DURATION_MS);
                sleepTask(PAN_SETTLE_MS);
                panned++;

                badge = templateSearchHelper.locatePattern(
                        TemplatesEnum.MONUMENT_REWARD_BADGE, SearchConfigConstants.HIGH_SENSITIVITY);
                if (badge.isFound()) {
                    logInfo(logLine("Badge found after " + panned + " pan step(s)."));
                    return badge;
                }
            }

            // Undo this direction's steps before trying the next, so every direction is tried from
            // the same known starting position instead of compounding into unpredictable drift.
            for (int step = 0; step < stepsThisDirection; step++) {
                swipe(end, start, PAN_DRAG_DURATION_MS);
                sleepTask(PAN_SETTLE_MS);
            }
        }

        logInfo(logLine("Badge still not found after panning " + panned + " step(s) across all directions; "
                + "camera restored to the starting position."));
        return badge;
    }

    private void claimAllReadyRows() {
        for (int i = 0; i < MAX_CLAIM_LOOPS; i++) {
            ImageSearchResultData claimBtn = templateSearchHelper.locatePattern(
                    TemplatesEnum.MONUMENT_ATLAS_CLAIM_BUTTON, SearchConfigConstants.QUICK_SEARCH);
            if (!claimBtn.isFound()) {
                logInfo(logLine("No more Claim buttons visible (" + i + " claimed)."));
                return;
            }
            tapPoint(claimBtn.getPoint());
            sleepTask(ACTION_SETTLE_MS);
            if (i == MAX_CLAIM_LOOPS - 1) {
                logWarning(logLine("Hit the claim-loop safety cap (" + MAX_CLAIM_LOOPS + ")."));
            }
        }
    }

    // matt/2026-08-13: caught live -- the Tundra Albums hub has its own fragment-count milestone
    // chest track at the top (separate from the per-category Atlas rewards handled above) that this
    // routine's own header comment had flagged as a known, unhandled gap. Live-verified by hand: the
    // currently-claimable chest is visually lit/glowing; tapping it opens a real "Rewards" popup
    // (confirmed: 100 diamonds + 2 mystery chests on a live claim), and the whole row scrolls left
    // afterward as the next threshold becomes the new rightmost slot. Only one calibration pass was
    // possible tonight, so this scans a few plausible slot positions along the row rather than
    // trusting a single fixed point -- the row's exact scroll offset at any given moment isn't fully
    // characterized yet.
    private static final PointData[] MILESTONE_CHEST_CANDIDATES = {
            new PointData(245, 178),
            new PointData(340, 178),
            new PointData(428, 178),
    };
    private static final PointData MILESTONE_REWARDS_TAP_ANYWHERE = new PointData(360, 1198);
    private static final int MAX_MILESTONE_CHEST_CLAIMS = 6;

    private void claimMilestoneChestsIfReady() {
        for (int claimed = 0; claimed < MAX_MILESTONE_CHEST_CLAIMS; claimed++) {
            boolean claimedThisPass = false;
            for (PointData candidate : MILESTONE_CHEST_CANDIDATES) {
                tapPoint(candidate);
                sleepTask(600);

                String popupTitle = stringHelper.attemptRecognition(
                        new PointData(200, 260), new PointData(520, 340),
                        2, 150L, PANEL_TITLE_OCR_SETTINGS,
                        s -> s != null && !s.isBlank(),
                        s -> s);
                if (popupTitle != null && popupTitle.toLowerCase().contains("reward")) {
                    logInfo(logLine("Milestone chest ready at " + candidate + " -- claimed. Rewards: '"
                            + popupTitle + "'."));
                    tapPoint(MILESTONE_REWARDS_TAP_ANYWHERE);
                    sleepTask(ACTION_SETTLE_MS);
                    claimedThisPass = true;
                    break;
                }
            }
            if (!claimedThisPass) {
                if (claimed == 0) {
                    logInfo(logLine("No milestone chest currently ready."));
                } else {
                    logInfo(logLine("Milestone chest track exhausted after " + claimed + " claim(s)."));
                }
                return;
            }
        }
        logWarning(logLine("Hit the milestone-chest safety cap (" + MAX_MILESTONE_CHEST_CLAIMS + ")."));
    }

    // matt/2026-08-13: caught live -- ALBUMS_FRAGMENT_BACKPACK_BTN was documented as the Tundra
    // Albums hub's own button, but this method used to fire BEFORE the back-arrow tap, on a
    // screen where that coordinate doesn't correspond to a real button at all -- 0 rows ever
    // opened, with no error, because the panel-title check below just correctly declined every
    // time. matt/2026-08-14: fixed by moving the call to after the back-arrow (see execute()) so
    // this now genuinely runs on the Tundra Albums hub, where ALBUMS_FRAGMENT_BACKPACK_BTN is
    // the real button -- live-verified hand-driven, screenshot-confirmed.

    // matt/2026-08-13, live-verified by hand, full real clear-out (General Album -> Daybreak Island
    // x3 -> The Labyrinth, 5 packs total): the fixed-row model above was wrong on two counts.
    // (1) A row with multiple pack types side by side (e.g. Daybreak Island showing 3 colors at
    // once) RE-CENTERS its remaining icons after each one is opened -- tapping a fixed per-slot X
    // stops matching reality after the first tap in that row. (2) Once every pack in a row is gone,
    // that row collapses to an empty "No such Scene Fragment Pack owned" placeholder and the NEXT
    // category compacts upward into where the row above used to be -- so a fixed per-row Y doesn't
    // hold either, and previously-hidden categories (Rekindled Flames, Song of Heroes) can scroll
    // into view that BACKPACK_MAX_ROWS never accounted for. Real fix: don't trust any fixed slot.
    // Read the owned-count badge under each of the positions actually observed live across that
    // clear-out, tap whichever one genuinely shows a count, and rescan from scratch after every
    // single open (since everything can reflow) instead of marching through fixed rows.
    private static final PointData[] BACKPACK_ICON_CANDIDATES = {
            new PointData(360, 280),  // top slot, single icon (nothing above it)
            new PointData(220, 548), new PointData(360, 548), new PointData(490, 548), // 3-across row
            new PointData(360, 587),  // top slot when an empty placeholder sits above it
            new PointData(360, 765),  // 3rd visual row, single icon
    };
    /** Owned-count badge sits just under each candidate icon; read box is centered on that offset. */
    private static final int BACKPACK_BADGE_Y_OFFSET = 62;
    private static final int BACKPACK_BADGE_HALF_WIDTH = 45;
    private static final int BACKPACK_BADGE_HALF_HEIGHT = 18;
    private static final int BACKPACK_MAX_TOTAL_OPENS = 40;

    private void processFragmentBackpack() {
        tapPoint(ALBUMS_FRAGMENT_BACKPACK_BTN);
        sleepTask(PANEL_SETTLE_MS);

        // Confirm the tap actually landed on the Fragment Backpack panel before spending any time
        // looping rows on what might be the wrong screen -- makes a future coordinate drift loud in
        // the logs instead of silently doing nothing, which is exactly what happened here.
        String panelTitle = stringHelper.attemptRecognition(
                BACKPACK_TITLE_TL, BACKPACK_TITLE_BR,
                2, 150L, PANEL_TITLE_OCR_SETTINGS,
                s -> s != null && !s.isBlank(),
                s -> s);
        if (panelTitle == null || !panelTitle.toLowerCase().contains("fragment")) {
            logWarning(logLine("Fragment Backpack panel not confirmed after tapping "
                    + ALBUMS_FRAGMENT_BACKPACK_BTN + " (read: '" + panelTitle
                    + "') -- skipping the backpack pass this run rather than guessing blindly on the "
                    + "wrong screen."));
            return;
        }

        int opened = 0;
        while (opened < BACKPACK_MAX_TOTAL_OPENS) {
            if (!waitForFragmentBackpackPanel()) {
                logWarning(logLine("Fragment Backpack panel didn't come back after the last pack open "
                        + "-- stopping rather than reading a stale screen. Opened " + opened + " total."));
                break;
            }

            PointData target = findAnyOwnedPackIcon();
            if (target == null) {
                logInfo(logLine("No more owned packs found. Opened " + opened + " total."));
                break;
            }

            opened++;
            logInfo(logLine("Opening pack " + opened + " at " + target + "."));
            tapPoint(target);
            sleepTask(ACTION_SETTLE_MS);
            tapPoint(PACK_DETAIL_ENABLE_BTN);
            sleepTask(PACK_OPEN_SETTLE_MS);

            // matt/2026-08-13: the reward-reveal screen has two visual variants -- a quick single-icon
            // flash for small stacks, and a slower multi-piece grid intro for bigger ones -- and the
            // grid variant is still mid-animation (not yet tappable) right when a single "tap anywhere"
            // would have landed before. Tap twice with a settle between; harmless no-op on the fast
            // variant since it's already closed by the second tap, required for the slow one.
            tapPoint(REWARD_REVEAL_TAP_ANYWHERE);
            sleepTask(PACK_OPEN_SETTLE_MS);
            tapPoint(REWARD_REVEAL_TAP_ANYWHERE);
            sleepTask(PACK_OPEN_SETTLE_MS);
        }

        if (opened >= BACKPACK_MAX_TOTAL_OPENS) {
            logWarning(logLine("Hit the total-opens safety cap (" + BACKPACK_MAX_TOTAL_OPENS + ")."));
        }

        tapPoint(BACKPACK_CLOSE_X);
        sleepTask(ACTION_SETTLE_MS);
    }

    /** Scans every known icon position for a real owned-count badge and returns the first one found,
     *  or null if nothing owned is visible anywhere on the current panel state. */
    private PointData findAnyOwnedPackIcon() {
        for (PointData candidate : BACKPACK_ICON_CANDIDATES) {
            PointData badgeTl = new PointData(candidate.getX() - BACKPACK_BADGE_HALF_WIDTH,
                    candidate.getY() + BACKPACK_BADGE_Y_OFFSET - BACKPACK_BADGE_HALF_HEIGHT);
            PointData badgeBr = new PointData(candidate.getX() + BACKPACK_BADGE_HALF_WIDTH,
                    candidate.getY() + BACKPACK_BADGE_Y_OFFSET + BACKPACK_BADGE_HALF_HEIGHT);
            Integer owned = readNumberValue(badgeTl, badgeBr, OWNED_COUNT_OCR_SETTINGS);
            if (owned != null && owned > 0) {
                return candidate;
            }
        }
        return null;
    }

    /** Waits (with extra retries) for the Fragment Backpack title to actually be back
     *  on screen after a pack-open cycle, instead of assuming a fixed sleep was enough. */
    private boolean waitForFragmentBackpackPanel() {
        for (int attempt = 0; attempt < 3; attempt++) {
            String title = stringHelper.attemptRecognition(
                    BACKPACK_TITLE_TL, BACKPACK_TITLE_BR,
                    2, 150L, PANEL_TITLE_OCR_SETTINGS,
                    s -> s != null && !s.isBlank(),
                    s -> s);
            if (title != null && title.toLowerCase().contains("fragment")) {
                return true;
            }
            sleepTask(PACK_OPEN_SETTLE_MS);
        }
        return false;
    }

    private void processAllianceTradeRequests() {
        for (int i = 0; i < MAX_REQUEST_LOOPS; i++) {
            String leftText = readStringValueSafe(MY_REQUESTS_LEFT_TL, MY_REQUESTS_LEFT_BR);
            Integer requestsLeft = leftText == null ? null : RegexNumberParser.extractByPattern(
                    leftText, Pattern.compile("\\((\\d+)\\s*/"));
            if (requestsLeft == null || requestsLeft <= 0) {
                logInfo(logLine("No My Requests left today (or couldn't read the counter). Moving on."));
                return;
            }

            tapPoint(MY_REQUESTS_REQUEST_BTN);
            sleepTask(PANEL_SETTLE_MS);
            tapPoint(PIECE_PICKER_REQUEST_BTN);
            sleepTask(ACTION_SETTLE_MS);
            // The "Confirm daily requests remaining" Tips dialog only appears the first
            // time per session -- harmless no-op tap if it's not there.
            tapPoint(PIECE_PICKER_TIPS_CONFIRM);
            sleepTask(ACTION_SETTLE_MS);
        }
        logWarning(logLine("Hit the request-loop safety cap (" + MAX_REQUEST_LOOPS + ")."));
    }

    private void processAllianceTradeSends() {
        for (int row = 0; row < ALLY_MAX_VISIBLE_ROWS; row++) {
            int rowOffset = row * ALLY_ROW_SPACING;
            PointData sendBtn = new PointData(ALLY_FIRST_ROW_SEND_BTN.getX(),
                    ALLY_FIRST_ROW_SEND_BTN.getY() + rowOffset);
            PointData ownedTl = new PointData(ALLY_FIRST_ROW_OWNED_TL.getX(),
                    ALLY_FIRST_ROW_OWNED_TL.getY() + rowOffset);
            PointData ownedBr = new PointData(ALLY_FIRST_ROW_OWNED_BR.getX(),
                    ALLY_FIRST_ROW_OWNED_BR.getY() + rowOffset);

            String ownedText = readStringValueSafe(ownedTl, ownedBr);
            Integer owned = ownedText == null ? null : RegexNumberParser.extractByPattern(
                    ownedText, Pattern.compile("(\\d+)"));

            // matt, 2026-08-12: only send when a duplicate is actually owned (>=2) --
            // "Owned: 1" means it's their only copy, leave it alone.
            if (owned != null && owned >= 2) {
                logInfo(logLine("Ally Requests row " + row + ": owned " + owned + ", sending."));
                tapPoint(sendBtn);
                sleepTask(ACTION_SETTLE_MS);
            } else {
                logInfo(logLine("Ally Requests row " + row + ": owned " + owned + ", skipping."));
            }
        }
    }

    private String readStringValueSafe(PointData tl, PointData br) {
        return stringHelper.attemptRecognition(
                tl, br, 2, 150L, OWNED_COUNT_OCR_SETTINGS,
                s -> s != null && !s.isBlank(),
                s -> s);
    }
}
