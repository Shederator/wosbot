package dev.frostguard.tasks.city;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
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
 * single confirmed 300px right swipe brings Monument into view every time.
 * See {@link #findAndOpenBadgeViaLancer()}.
 *
 * <p>
 * matt/2026-08-18: even that swipe still fell back into a full camera-pan sweep
 * whenever a template search for the reward badge came up empty right after landing
 * -- "it pans to the right of lancer, you see monument, then it just starts
 * scrolling around." The 8-direction pan-fallback (the actual "scrolling around")
 * is removed entirely. A first attempt to also drop the template search itself in
 * favor of a fixed-pixel tap was WRONG -- that coordinate was guessed from an old
 * debug frame instead of a confirmed one, and it mis-tapped the Archer Camp instead
 * of Monument. Fixed for real this time: {@link #MONUMENT_BADGE_TAP_POINT} is
 * verified against 3 real screenshots (shop-debug/monument_find.png,
 * monument_check2.png, monument_check3.png) all showing the scroll-with-a-feather
 * badge in the identical spot after this exact swipe, and matt confirmed the
 * reference frame live before this landed. No template search, no pan fallback.
 *
 * <p>
 * <b>Flow:</b>
 * <pre>
 * Home -> open left-menu City section -> tap Lancer row -> tap the camp building
 * -> wait 5s -> swipe right 300px -> tap the fixed badge point, VERIFY the badge
 * is no longer detectable (confirms something actually opened, instead of
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

    // matt/2026-08-18, THIRD pass: (471,550) was wrong -- the Events-tab guard added earlier never
    // fired (all 5 EVENTS_TAB_* templates came back "not found" on the very runs matt watched fail
    // live), which means the tap wasn't landing on Events at all -- it was missing the badge
    // entirely, and the untouched blind chain after that (MODAL_CLOSE_X etc.) is what wandered onto
    // Events. Recalibrated from TWO fresh live screenshots matt sent this session showing the exact
    // current post-swipe frame, badge clearly visible well to the right of where (471,550) tapped --
    // measured as a fraction of frame (badge center ~76.6% across, ~42.3% down) and applied to the
    // known 720x1280 game resolution: real center is ~(551, 541), about 80px right of the old point.
    // Given that's a measurement off a screenshot, not a pixel-exact capture, this uses a generous
    // tolerant box via tapInside(...) instead of a pinpoint tapNear(...) -- the old approach had zero
    // margin for exactly this kind of error.
    private static final PointData MONUMENT_BADGE_TAP_TOP_LEFT = new PointData(516, 506);
    private static final PointData MONUMENT_BADGE_TAP_BOTTOM_RIGHT = new PointData(586, 576);


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

    private static final OcrSettingsData PANEL_TITLE_OCR_SETTINGS = OcrSettingsData.assembler()
            .stripBackground(true)
            .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ ")
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
            .build();

    private static final OcrSettingsData OWNED_COUNT_OCR_SETTINGS = OcrSettingsData.assembler()
            .stripBackground(true)
            .charWhitelist("OwnedOWNED:0123456789 ")
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
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

        tapNear(MODAL_CLOSE_X);
        sleepTask(PANEL_SETTLE_MS);

        tapNear(ATLAS_BACK_ARROW);
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

        tapNear(ALBUMS_BACK_ARROW);
        sleepTask(ACTION_SETTLE_MS);

        StatisticsService.obtain().addToCounter(profile, "Monument Pass Completed", 1);
        logInfo(logLine("Monument pass complete. Rechecking in " + IDLE_RECHECK_MINUTES + " minutes."));
        reschedule(LocalDateTime.now().plusMinutes(IDLE_RECHECK_MINUTES));
    }

    // matt/2026-08-18 (second live failure, ~50% of runs): "moved right three hundred pixels...
    // then it just, like, went to the events tab." Root cause found from evidence, not guessed --
    // the ONLY post-tap check below was "is the old badge template no longer visible", which is a
    // bad proxy for "the tap actually opened something": a tap that misses the badge (scroll drift,
    // a slightly different swipe landing spot, whatever) also makes the old badge unmatchable, so
    // this check reported false success just as often as real success. On false success, execute()
    // cascades straight into tapNear(MODAL_CLOSE_X) at (662,157) -- which sits almost exactly on
    // the real Events-tab icon's screen position. That's the mechanism: missed tap -> false "opened"
    // -> blind tap at (662,157) -> Events tab opens. This was flagged as a known risk in this same
    // method's comment history before it was actually observed live.
    //
    // Fix: EVENTS_TAB_HALL_OF_CHIEFS / _DEFEAT_BEASTS / _BROTHERS_IN_ARMS / _HERO_RALLY /
    // _LUCKY_WHEEL are the Events panel's own tab-selector icons -- all of them render together
    // across the top of the Events panel regardless of which sub-tab is active (see
    // EventClaimRoutine), so any one of them matching is a reliable "we ended up in Events, not
    // Monument" signal, independent of whether the Monument badge template happens to still be
    // readable. Checked explicitly before treating the badge tap as a success; on a hit this backs
    // out with pressBack() (which already carries the quit-dialog guard) instead of ever tapping
    // MODAL_CLOSE_X on a screen that isn't the Atlas panel.
    private static final TemplatesEnum[] EVENTS_TAB_LANDING_SIGNS = {
            TemplatesEnum.EVENTS_TAB_HALL_OF_CHIEFS,
            TemplatesEnum.EVENTS_TAB_DEFEAT_BEASTS,
            TemplatesEnum.EVENTS_TAB_BROTHERS_IN_ARMS,
            TemplatesEnum.EVENTS_TAB_HERO_RALLY,
            TemplatesEnum.EVENTS_TAB_LUCKY_WHEEL,
    };

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

        tapInside(LANCER_AREA_TOP_LEFT, LANCER_AREA_BOTTOM_RIGHT, 1, 500);
        tapInside(CAMP_TAP_TOP_LEFT, CAMP_TAP_BOTTOM_RIGHT, 1, 300);
        sleepTask(POST_LANCER_WAIT_MS);

        swipe(SWIPE_RIGHT_START, SWIPE_RIGHT_END, SWIPE_DURATION_MS);
        sleepTask(POST_SWIPE_WAIT_MS);

        // matt/2026-08-18: "it pans to the right of lancer, you see monument, then it just starts
        // scrolling around." Pulled the 8-direction pan-fallback entirely -- that's the actual
        // "scrolling around" behavior. See MONUMENT_BADGE_TAP_TOP_LEFT/BOTTOM_RIGHT above for the
        // coordinate history (two wrong single-point guesses before this tolerant-box version).
        tapInside(MONUMENT_BADGE_TAP_TOP_LEFT, MONUMENT_BADGE_TAP_BOTTOM_RIGHT);
        sleepTask(PANEL_SETTLE_MS);

        for (TemplatesEnum landingSign : EVENTS_TAB_LANDING_SIGNS) {
            if (templateSearchHelper.locatePattern(landingSign, SearchConfigConstants.QUICK_SEARCH).isFound()) {
                logWarning(logLine("Badge tap in " + MONUMENT_BADGE_TAP_TOP_LEFT + "-" + MONUMENT_BADGE_TAP_BOTTOM_RIGHT
                        + " missed and landed on the "
                        + "Events tab instead (matched " + landingSign + ") -- backing out instead of "
                        + "cascading blind taps onto the wrong screen. Recovering toward Home."));
                recoverTowardHome();
                return false;
            }
        }

        ImageSearchResultData badgeStillThere = templateSearchHelper.locatePattern(
                TemplatesEnum.MONUMENT_REWARD_BADGE, SearchConfigConstants.QUICK_SEARCH);
        if (badgeStillThere.isFound()) {
            logWarning(logLine("Tapped inside " + MONUMENT_BADGE_TAP_TOP_LEFT + "-" + MONUMENT_BADGE_TAP_BOTTOM_RIGHT
                    + " but the badge is still detectable on screen afterward -- nothing opened. "
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
            tapNear(closeSpot);
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

    // matt/2026-08-18: the camera-pan fallback that used to live here (findBadgeWithPanFallback(),
    // an 8-direction, up-to-16-tap sweep) is removed entirely -- "it pans to the right of lancer,
    // you see monument, then it just starts scrolling around." Removed for good; on a miss the
    // routine now just stops and reschedules (see findAndOpenBadgeViaLancer() above) instead of
    // panning around OR guessing at an unverified fixed-pixel coordinate.

    private void claimAllReadyRows() {
        for (int i = 0; i < MAX_CLAIM_LOOPS; i++) {
            ImageSearchResultData claimBtn = templateSearchHelper.locatePattern(
                    TemplatesEnum.MONUMENT_ATLAS_CLAIM_BUTTON, SearchConfigConstants.QUICK_SEARCH);
            if (!claimBtn.isFound()) {
                logInfo(logLine("No more Claim buttons visible (" + i + " claimed)."));
                return;
            }
            tapNear(claimBtn.getPoint());
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
                tapNear(candidate);
                sleepTask(600);

                String popupTitle = stringHelper.attemptRecognition(
                        new PointData(200, 260), new PointData(520, 340),
                        2, 150L, PANEL_TITLE_OCR_SETTINGS,
                        s -> s != null && !s.isBlank(),
                        s -> s);
                if (popupTitle != null && popupTitle.toLowerCase().contains("reward")) {
                    logInfo(logLine("Milestone chest ready at " + candidate + " -- claimed. Rewards: '"
                            + popupTitle + "'."));
                    tapNear(MILESTONE_REWARDS_TAP_ANYWHERE);
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
    // matt/2026-08-18: real live log evidence, not a guess -- the 3-across row below
    // (220/360/490, 548) assumed an old side-by-side icon layout. A live screenshot showed the
    // actual current layout is single-column (General Album stacked directly above Tundra
    // Alliance, both centered around x=360), and the run log confirmed the failure mode exactly:
    // OCR read a bogus "2" at the wrong (220, 548) candidate -- empty wood panel, nothing there --
    // tapped it, and the Fragment Backpack panel never came back because nothing was actually hit.
    private static final PointData[] BACKPACK_ICON_CANDIDATES = {
            new PointData(360, 280),  // row 1 icon (General Album, confirmed by live screenshot)
            new PointData(360, 545),  // row 2 icon (Tundra Alliance, confirmed by live screenshot)
            new PointData(360, 587),  // legacy candidate, kept for a placeholder-shifted layout
            new PointData(360, 765),  // 3rd visual row, single icon
    };
    /** Owned-count badge sits just under each candidate icon; read box is centered on that offset. */
    private static final int BACKPACK_BADGE_Y_OFFSET = 62;
    private static final int BACKPACK_BADGE_HALF_WIDTH = 45;
    private static final int BACKPACK_BADGE_HALF_HEIGHT = 18;
    private static final int BACKPACK_MAX_TOTAL_OPENS = 40;

    // matt/2026-08-14, caught live: findAnyOwnedPackIcon() false-positived on candidate (360,587) --
    // the OCR "owned count" read at that offset actually landed on the unrelated Labyrinth hub's own
    // milestone-chest track digits, not a real pack. The bot tapped it, tapped where Enable should be,
    // tapped where a reward-reveal close should be -- all blind, all on the wrong screen -- and ended
    // up stuck on a completely different "Rewards ... Tap anywhere to exit" chest-reveal screen (a
    // different UI skin REWARD_REVEAL_TAP_ANYWHERE doesn't clear) for 7+ minutes until matt manually
    // quit. Two real fixes: (1) a hard wall-clock time budget on the whole pass, so an unrecognized
    // screen can never again silently eat minutes; (2) active recovery (repeated back-presses, which
    // already carry the quit-game-dialog safety net) instead of one blind close-tap that assumes
    // we're still on the screen it expects.
    private static final long BACKPACK_PASS_TIME_BUDGET_MS = 90_000;

    private void processFragmentBackpack() {
        long deadline = System.currentTimeMillis() + BACKPACK_PASS_TIME_BUDGET_MS;

        tapNear(ALBUMS_FRAGMENT_BACKPACK_BTN);
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
            recoverTowardHome();
            return;
        }

        int opened = 0;
        while (opened < BACKPACK_MAX_TOTAL_OPENS) {
            if (System.currentTimeMillis() > deadline) {
                logWarning(logLine("Fragment Backpack pass exceeded its " + (BACKPACK_PASS_TIME_BUDGET_MS / 1000)
                        + "s time budget -- something is stuck on a screen this code doesn't recognize. "
                        + "Aborting and recovering rather than hanging. Opened " + opened + " total."));
                recoverTowardHome();
                return;
            }

            if (!waitForFragmentBackpackPanel()) {
                logWarning(logLine("Fragment Backpack panel didn't come back after the last pack open "
                        + "-- likely tapped something that wasn't actually a pack. Recovering instead of "
                        + "assuming the normal close tap still applies. Opened " + opened + " total."));
                recoverTowardHome();
                return;
            }

            PointData target = findAnyOwnedPackIcon();
            if (target == null) {
                logInfo(logLine("No more owned packs found. Opened " + opened + " total."));
                break;
            }

            opened++;
            logInfo(logLine("Opening pack " + opened + " at " + target + "."));
            tapNear(target);
            sleepTask(ACTION_SETTLE_MS);
            tapNear(PACK_DETAIL_ENABLE_BTN);
            sleepTask(PACK_OPEN_SETTLE_MS);

            // matt/2026-08-13: the reward-reveal screen has two visual variants -- a quick single-icon
            // flash for small stacks, and a slower multi-piece grid intro for bigger ones -- and the
            // grid variant is still mid-animation (not yet tappable) right when a single "tap anywhere"
            // would have landed before. Tap twice with a settle between; harmless no-op on the fast
            // variant since it's already closed by the second tap, required for the slow one.
            tapNear(REWARD_REVEAL_TAP_ANYWHERE);
            sleepTask(PACK_OPEN_SETTLE_MS);
            tapNear(REWARD_REVEAL_TAP_ANYWHERE);
            sleepTask(PACK_OPEN_SETTLE_MS);
        }

        if (opened >= BACKPACK_MAX_TOTAL_OPENS) {
            logWarning(logLine("Hit the total-opens safety cap (" + BACKPACK_MAX_TOTAL_OPENS + ")."));
        }

        tapNear(BACKPACK_CLOSE_X);
        sleepTask(ACTION_SETTLE_MS);
    }

    /** Active recovery back toward Home when the Fragment Backpack flow lands somewhere unrecognized --
     *  several back-presses (each carrying the shared quit-game-dialog safety net) rather than a single
     *  blind tap at a coordinate that assumed a screen state that turned out to be wrong. */
    private void recoverTowardHome() {
        for (int i = 0; i < 4 && !isScreenClear(); i++) {
            pressBack();
            sleepTask(500);
        }
        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
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

            tapNear(MY_REQUESTS_REQUEST_BTN);
            sleepTask(PANEL_SETTLE_MS);
            tapNear(PIECE_PICKER_REQUEST_BTN);
            sleepTask(ACTION_SETTLE_MS);
            // The "Confirm daily requests remaining" Tips dialog only appears the first
            // time per session -- harmless no-op tap if it's not there.
            tapNear(PIECE_PICKER_TIPS_CONFIRM);
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
                tapNear(sendBtn);
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
