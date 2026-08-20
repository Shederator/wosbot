package dev.frostguard.tasks.exploration;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.input.TapJitterPolicy;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.ocr.OcrEngine;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Task responsible for completing daily labyrinth challenges.
 * This task navigates to the labyrinth menu and executes appropriate challenges
 * based on the current day of the week.
 */
public class DailyLabyrinthRoutine extends DelayedTask {

    // =========================== CONSTANTS ===========================

    // Navigation points
    private static final PointData SKIP_BUTTON = new PointData(71, 827);
    private static final PointData RESULT_SKIP_BUTTON = new PointData(640, 175);

    // Timing constants
    private static final int MENU_NAVIGATION_DELAY = 1000;
    private static final int TAB_SWITCH_DELAY = 500;
    private static final int BATTLE_COMPLETION_DELAY = 3000;
    private static final int LABYRINTH_LOAD_DELAY = 2000;

    // ===================================================================
    // Land-of-Heroes formation-setup flow (matt/2026-08-10)
    // ===================================================================
    // ALL coordinates below are BEST-ESTIMATE from 720x1280 screenshots and are marked
    // "LIVE-TUNE" — the orchestrator will calibrate each one via ADB before this runs for real.
    // Gated behind LABYRINTH_FORMATION_TEST_BOOL. This flow sets up (and SAVES) the formation
    // only; it deliberately STOPS before Deploy/battle, because battling burns a daily attempt
    // while formation-setup is free.

    // -- Land of Heroes stage screen --
    /** LIVE-TUNE: "Challenge" button on the Land-of-Heroes stage screen. */
    private static final PointData LOH_CHALLENGE_BTN = new PointData(360, 1218);

    // -- Labyrinth map: Land of Heroes zone banner (purple) + its label for the open/locked check --
    /** LIVE-TUNE: tap point on the "Land of Heroes" purple banner to enter the zone. */
    private static final PointData LOH_ZONE_BANNER = new PointData(460, 337);
    /** LIVE-TUNE: OCR region over the Land-of-Heroes label (name + timer). A LOCKED zone's line reads
     *  "Opens in …"; an OPEN zone shows just name + a bare countdown. matt's rule: "Opens in" ⇒ skip. */
    private static final PointData LOH_ZONE_LABEL_TL = new PointData(358, 302);
    private static final PointData LOH_ZONE_LABEL_BR = new PointData(562, 372);

    // matt/2026-08-13: same formation-setup extended to Cave of Monsters and Charm Mine ("we're up
    // to like three now"). Calibrated live via ADB from the Labyrinth zone map (same map screen as
    // Land of Heroes, different scroll position). Banner = tap point on the zone's structure
    // graphic; label = OCR box over its name+timer banner, same "Opens in" open/locked rule.
    private static final PointData CAVE_ZONE_BANNER = new PointData(195, 340);
    private static final PointData CAVE_ZONE_LABEL_TL = new PointData(80, 465);
    private static final PointData CAVE_ZONE_LABEL_BR = new PointData(330, 525);
    private static final PointData CHARM_ZONE_BANNER = new PointData(505, 550);
    private static final PointData CHARM_ZONE_LABEL_TL = new PointData(390, 595);
    private static final PointData CHARM_ZONE_LABEL_BR = new PointData(640, 655);

    // matt/2026-08-16: Gaia Heart -- live-calibrated via ADB the same day (a Sunday, its actual open
    // rotation), not guessed. Confirmed it renders on the DEFAULT unscrolled map view (no scroll
    // needed) at the bottom of the frame, at least while open -- unconfirmed whether it still renders
    // here on a day it's closed.
    private static final PointData GAIA_ZONE_BANNER = new PointData(300, 1030);
    private static final PointData GAIA_ZONE_LABEL_TL = new PointData(140, 1005);
    private static final PointData GAIA_ZONE_LABEL_BR = new PointData(460, 1080);
    /** White label text over the map/banner. */
    private static final OcrSettingsData ZONE_LABEL_SETTINGS =
            OcrSettingsData.assembler()
                    .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 :")
                    .textLayout(TextLayout.SINGLE_LINE)
                    .stripBackground(true)
                    .setTextColor(new java.awt.Color(255, 255, 255))
                    .build();

    // -- Squad Config screen --
    /** "Quick Deploy" button on the Squad Config screen (auto-fills heroes + troops IN PLACE). */
    private static final PointData LOH_QUICK_DEPLOY_BTN = new PointData(197, 1193);
    /** Squad-1 "Edit Formation" button on the Squad Config screen -> opens the troop-detail screen.
     *  (Quick Deploy only fills the squad in place; the ratio lives one screen deeper.) */
    private static final PointData LOH_EDIT_FORMATION_SQUAD1_BTN = new PointData(360, 357);

    // -- Troop-detail screen (post Edit Formation) --
    /** "Balance" button on the troop-detail screen that opens the troop-ratio popup. matt/2026-08-13:
     *  re-calibrated 1195->1183 live via ADB on Cave of Monsters -- 1195 landed on the Backpack nav
     *  icon underneath (a stray Alliance Vote popup had been interfering with earlier attempts and
     *  masked this; confirmed twice clean at 1183 with no popups in the way). */
    private static final PointData LOH_BALANCE_BTN = new PointData(330, 1183);
    /** LIVE-TUNE: "Edit Formation" button that SAVES the formation (final step before STOP). */
    private static final PointData LOH_EDIT_FORMATION_BTN = new PointData(575, 1285);

    // -- Balance popup: 3 troop rows, each with a minus (~x213) / plus (~x538) nudge + a % readout --
    /** LIVE-TUNE: minus button X, shared by all three rows. */
    private static final int LOH_MINUS_X = 202;
    /** LIVE-TUNE: plus button X, shared by all three rows. */
    private static final int LOH_PLUS_X = 511;
    /** LIVE-TUNE: row Y centres for Infantry / Lancer / Marksman. */
    private static final int LOH_INFANTRY_ROW_Y = 530;
    private static final int LOH_LANCER_ROW_Y = 675;
    private static final int LOH_MARKSMAN_ROW_Y = 820;
    /** LIVE-TUNE: "Use as default" checkbox in the Balance popup. */
    private static final PointData LOH_USE_AS_DEFAULT_CHECKBOX = new PointData(105, 903);
    /** "Confirm" button in the Balance popup. */
    private static final PointData LOH_CONFIRM_BTN = new PointData(360, 978);
    /** Back arrow (top-left) on the troop-detail screen — exiting triggers the Save-and-Exit dialog. */
    private static final PointData LOH_FORMATION_BACK_ARROW = new PointData(40, 40);
    /** "Save and Exit" (blue, right) button on the "save the formation first?" confirmation dialog.
     *  This is what actually persists the ratio — Confirm on the Balance popup alone does not. */
    private static final PointData LOH_SAVE_AND_EXIT_BTN = new PointData(511, 788);

    // -- % readout OCR crops (top-left / bottom-right), one per troop row --
    /** LIVE-TUNE: Infantry % box. */
    // matt/2026-08-20: these boxes spanned the value pill's full width INCLUDING its dark rounded
    // border, and the border survives the white-outline isolation as extra digits. Measured live off
    // lab_d0_balance_set_1787233252229.png, where the popup genuinely showed 50/10/40 and the app's
    // own raw reads were:
    //     Infantry (really 50) -> "690"   rejected, >100
    //     Lancer   (really 10) -> "10"    accepted -- the short digit sits clear of the border
    //     Marksman (really 40) -> "407"   rejected, >100
    // which is exactly the null/10/null readback in that run. The pill measures x 556-634; the DIGITS
    // occupy x 580-612, y 519-539 (Infantry), with the rows 145px apart. Tightened to the digits with
    // a small pad so the border is outside the box entirely.
    private static final PointData LOH_INF_PCT_TL = new PointData(576, 513);
    private static final PointData LOH_INF_PCT_BR = new PointData(618, 546);
    /** LIVE-TUNE: Lancer % box. */
    private static final PointData LOH_LAN_PCT_TL = new PointData(576, 658);
    private static final PointData LOH_LAN_PCT_BR = new PointData(618, 691);
    /** LIVE-TUNE: Marksman % box. */
    private static final PointData LOH_MRK_PCT_TL = new PointData(576, 803);
    private static final PointData LOH_MRK_PCT_BR = new PointData(618, 836);

    // ===================================================================
    // Gaia Heart formation flow (matt/2026-08-16)
    // ===================================================================
    // Live-calibrated the same day Gaia Heart was actually open (a Sunday). Genuinely two-squad, same
    // shape as Land of Heroes -- BUT the commit mechanism is DIFFERENT and was verified live: the
    // troop-detail screen's own bottom-right "Edit Formation" button commits the ratio DIRECTLY and
    // returns to Squad Config -- no back-arrow, no "save the formation first?" dialog, no separate
    // Save-and-Exit tap. Confirmed by round-trip: set 60/40/0, tapped this button, backed all the way
    // out to The Labyrinth map and fully re-entered the zone -- the ratio was still 60/40/0. That's why
    // Gaia gets its own setup method (setupGaiaZone) instead of reusing driveBalanceAndSave, which
    // assumes Land of Heroes' back-arrow+dialog commit.
    //
    // Also: the Balance popup's row Y positions read slightly different from Land of Heroes/Cave/Charm
    // (530/655/800 here vs. 530/675/820 there) -- same popup component, just enough vertical offset
    // that reusing the LOH constants would tap the wrong row. Confirmed live: floor+fill against these
    // Y values landed exactly on target (60/40/0) with zero correction-pass nudges needed.
    private static final PointData GAIA_QUICK_DEPLOY_BTN = new PointData(197, 1193);
    private static final PointData[] GAIA_SQUAD_EDIT_BTNS = new PointData[] {
            new PointData(360, 357),   // Squad 1
            new PointData(360, 700),   // Squad 2
            // Squad 3 unlocks at Stage 15-10 -- not live-verified (still locked on matt's account as
            // of 2026-08-16), so no coordinate here yet. setupGaiaZone() only ever processes 2 squads
            // until this is added AND verified against the real unlocked screen.
    };
    private static final PointData GAIA_BALANCE_BTN = new PointData(330, 1195);
    /** Commits the ratio directly (no dialog) and returns to Squad Config -- see class-level note above. */
    private static final PointData GAIA_EDIT_FORMATION_COMMIT_BTN = new PointData(548, 1213);
    private static final int GAIA_INFANTRY_ROW_Y = 530;
    private static final int GAIA_LANCER_ROW_Y = 655;
    private static final int GAIA_MARKSMAN_ROW_Y = 800;
    // ESTIMATED from the same relative offset as the LOH pct boxes -- NOT live-verified against Gaia's
    // actual popup (the live test that confirmed 60/40/0 landed exactly via open-loop tap counts alone,
    // so the correction pass these boxes feed never had to fire). Safe either way: readPercent()
    // already treats an OCR miss as "leave as-is" rather than guessing a correction.
    private static final PointData GAIA_INF_PCT_TL = new PointData(558, 508);
    private static final PointData GAIA_INF_PCT_BR = new PointData(632, 552);
    private static final PointData GAIA_LAN_PCT_TL = new PointData(558, 633);
    private static final PointData GAIA_LAN_PCT_BR = new PointData(632, 677);
    private static final PointData GAIA_MRK_PCT_TL = new PointData(558, 778);
    private static final PointData GAIA_MRK_PCT_BR = new PointData(632, 822);

    // Per-squad target troop ratios {Infantry, Lancer, Marksman} are read from config at run time
    // (set on the Labyrinth tab). These are the fallback defaults if config is missing/unreadable:
    // research-seeded (Squad 1 = frontline/tank, Squad 2 = marksman/hybrid).
    private static final int[][] LOH_DEFAULT_SQUAD_RATIOS = new int[][] {
            { 60, 40, 0 },   // Squad 1
            { 50, 0, 50 },   // Squad 2
    };
    // Squad-N "Edit Formation" buttons on the Squad Config screen (index-aligned with the ratios).
    private static final PointData[] LOH_SQUAD_EDIT_BTNS = new PointData[] {
            new PointData(360, 357),   // Squad 1
            new PointData(360, 700),   // Squad 2
    };

    // Slider-drive tuning.
    /** Minus taps to guarantee a row is floored at 0% (>100 so it works from any start; inert at 0). */
    private static final int LOH_FLOOR_TAPS = 105;
    /** Delay between deterministic +/- taps. Fast, but slow enough that taps reliably register. */
    private static final int LOH_DET_TAP_DELAY = 90;
    /** Correction passes after the open-loop floor+fill (fixes any dropped taps via static-frame OCR). */
    private static final int LOH_CORRECT_ITERS = 4;
    /** Settle before a correction read so the slider isn't mid-animation (static OCR is reliable). */
    private static final int LOH_SETTLE_BEFORE_READ = 550;
    /** How many times readPercent re-captures before returning null. */
    private static final int LOH_PCT_READ_ATTEMPTS = 4;
    /** Delay between readPercent re-capture attempts. */
    private static final int LOH_PCT_READ_RETRY_DELAY = 200;

    // -- Screen-verification anchors (each a white-text region + expected substring) so the routine
    //    can POLL for the expected screen after a tap instead of blind fixed delays. The desync that
    //    broke unattended runs was slow-load timing; polling absorbs it without risky double-taps.
    private static final int SCREEN_POLL_INTERVAL_MS = 400;   // OCR re-check cadence while waiting
    private static final int SCREEN_POLL_TIMEOUT_MS = 5000;   // give a slow screen up to this long
    private static final OcrSettingsData WHITE_TITLE_SETTINGS =
            OcrSettingsData.assembler()
                    .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ ")
                    .textLayout(TextLayout.SINGLE_LINE)
                    .stripBackground(true)
                    .setTextColor(new java.awt.Color(255, 255, 255))
                    .build();
    // ===================================================================
    // Pre-fight scouting -- "View Details" (matt/2026-08-20)
    // ===================================================================
    // matt asked for a "tale of the tape": after losing, read the battle report to see what the
    // enemy actually ran, then counter it on the retry. Driving the game by hand to find that screen
    // turned up something strictly better -- the magnifier on the stage screen's enemy portrait opens
    // a "View Details" panel that shows the enemy's troop counts AND a full per-troop-type stat
    // comparison BEFORE the fight. So the same decision can be made for ZERO attempts instead of
    // paying one attempt to learn it.
    //
    // All coordinates and the OCR shape below were measured off a real capture of that screen
    // (Cave of Monsters stage 3-9), not estimated.
    /** Magnifier badge on the stage screen's enemy portrait (bottom-left, next to "Stage N-N"). */
    private static final PointData STAGE_ENEMY_MAGNIFIER = new PointData(122, 1082);
    /** "View Details" title, top-left of the scouting panel. */
    private static final PointData SCOUT_ANCHOR_TL = new PointData(88, 20);
    private static final PointData SCOUT_ANCHOR_BR = new PointData(330, 62);
    private static final String    SCOUT_ANCHOR_TEXT = "details";
    /** Back arrow out of the scouting panel. */
    private static final PointData SCOUT_BACK_BTN = new PointData(40, 40);
    /** The panel is taller than the screen -- Marksman Health sits below the fold on first open. */
    private static final PointData SCOUT_SCROLL_FROM = new PointData(360, 1000);
    private static final PointData SCOUT_SCROLL_TO = new PointData(360, 500);
    /** A troop type is only comparable when all four of these were read. */
    private static final java.util.List<String> SCOUT_REQUIRED_STATS =
            java.util.List.of("Attack", "Defense", "Lethality", "Health");
    /** Opponent stats this close across all three types count as "the same enemy to everyone". */
    private static final double OPPONENT_UNIFORM_TOLERANCE = 0.5;

    // -- Equalize (matt/2026-08-20) --------------------------------------------------------------
    // The single-squad deployment screen carries its own "Equalize" button, which sets 33/33/33 in
    // ONE tap. Confirmed on lab_d0_troop_1787238417242.png alongside Withdraw All / Balance / Deploy,
    // with the live ratio printed along the bottom as "Troop Ratio: 33% 33% 33%".
    //
    // That replaces the Balance slider flow entirely for these zones: flooring three rows at 1% per
    // tap and refilling them takes about five minutes per zone and is the most fragile code path in
    // this routine (every Labyrinth bug fixed today lived in it). Used only when the scout says the
    // opponent presents identically to all three troop types -- see ScoutResult#opponentUniform for
    // why that is exactly when an even split is right, rather than merely convenient.
    private static final PointData EQUALIZE_BTN = new PointData(199, 1190);
    /** "Troop Ratio: 33% 33% 33%" strip along the bottom of the deployment screen. */
    private static final PointData TROOP_RATIO_LINE_TL = new PointData(60, 1068);
    private static final PointData TROOP_RATIO_LINE_BR = new PointData(700, 1112);
    private static final OcrSettingsData TROOP_RATIO_LINE_SETTINGS =
            OcrSettingsData.assembler()
                    .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789:% ")
                    .textLayout(TextLayout.SINGLE_LINE)
                    .build();
    // matt/2026-08-20: this demanded a literal '%' and so rejected a perfectly good read -- the strip
    // came back 'Troop Ratio: W 33% GP 33x 33%', where the middle percent sign OCR'd as an 'x'. The
    // three values were plainly 33/33/33 and Equalize had worked. Requiring the % adds nothing: the
    // icons between the numbers ('W', 'GP') contain no digits, so pulling every 1-3 digit number in
    // 0..100 out of the line after the "Troop Ratio:" label is both simpler and more tolerant.
    // Anchored on the percent sign, allowing the common misreads of it. Validated against every real
    // strip captured so far:
    //   "Troop Ratio: W 33% GP 33x 33%"  -> 33,33,33  even   (the middle '%' read as 'x')
    //   "Troop Ratio: G 33% 8 33% Q 33%" -> 33,33,33  even   (an ICON read as '8' -- anchoring on the
    //                                                         percent is what keeps it out)
    //   "Troop Ratio: W 60% GP 20x 20%"  -> 60,20,20  not even
    //   "Troop Ratio: garbage"           -> none      rejected
    // Matching bare digits instead would swallow that stray icon '8' and read 33/8/33.
    private static final java.util.regex.Pattern RATIO_PCT =
            java.util.regex.Pattern.compile("([0-9]{1,3})\\s*[%xX]");
    /** The whole panel body: enemy troop counts near the top, then the 12 stat rows. Read as one
     *  block and parsed BY LABEL rather than by row coordinates, so it survives the panel sitting at
     *  a different scroll offset (verified: the same parse works scrolled and unscrolled). */
    private static final PointData SCOUT_BODY_TL = new PointData(28, 170);
    private static final PointData SCOUT_BODY_BR = new PointData(700, 1275);
    /** Plain multi-line read -- this panel is dark text on flat light rows and needs no isolation.
     *  Verified with the bundled tesseract: all 12 rows and the troop counts came back exact. */
    private static final OcrSettingsData SCOUT_SETTINGS =
            OcrSettingsData.assembler()
                    .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.,%+' ")
                    .textLayout(TextLayout.TEXT_BLOCK)
                    .build();
    /** e.g. "+27.7% Infantry Attack +19.8%" -> mine, type, stat, theirs. */
    private static final java.util.regex.Pattern SCOUT_ROW = java.util.regex.Pattern.compile(
            "([0-9]+(?:\\.[0-9]+)?)\\s*%?\\s*(Infantry|Lancer|Marksman)\\s+(Attack|Defense|Lethality|Health)\\s*\\+?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*%?",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** "The Labyrinth" title on the zone map -- how we confirm we're back on the map between zones. */
    private static final PointData MAP_ANCHOR_TL = new PointData(85, 18);
    private static final PointData MAP_ANCHOR_BR = new PointData(365, 64);
    private static final String    MAP_ANCHOR_TEXT = "labyrinth";

    // Stage screen: the "Challenge" button (white text, blue button).
    private static final PointData STAGE_ANCHOR_TL = new PointData(255, 1195);
    private static final PointData STAGE_ANCHOR_BR = new PointData(465, 1245);
    private static final String    STAGE_ANCHOR_TEXT = "challenge";
    // Squad Config: the "Squad Config" title (top-left).
    private static final PointData SQUAD_ANCHOR_TL = new PointData(88, 22);
    private static final PointData SQUAD_ANCHOR_BR = new PointData(330, 62);
    private static final String    SQUAD_ANCHOR_TEXT = "squad";
    // Troop-detail: the "Land of Heroes" header (top-left, large white text). Distinct from Squad
    // Config ("Squad Config") — the only other screen reachable at that point — so "heroes" is a
    // robust confirm. (The small "Troop Ratio:" label proved flaky as an anchor.)
    private static final PointData TROOP_ANCHOR_TL = new PointData(88, 22);
    private static final PointData TROOP_ANCHOR_BR = new PointData(360, 62);
    private static final String    TROOP_ANCHOR_TEXT = "heroes";
    // Balance popup: the "Balance" title in the popup header.
    private static final PointData BALANCE_ANCHOR_TL = new PointData(268, 300);
    private static final PointData BALANCE_ANCHOR_BR = new PointData(456, 342);
    private static final String    BALANCE_ANCHOR_TEXT = "balance";
    // "Save the formation first?" dialog: the "Save and Exit" button text.
    private static final PointData SAVE_ANCHOR_TL = new PointData(385, 762);
    private static final PointData SAVE_ANCHOR_BR = new PointData(642, 816);
    private static final String    SAVE_ANCHOR_TEXT = "save";

    // The % digits are a STROKED font: a black fill with a bold WHITE OUTLINE on a pale-blue box.
    // Isolating on the black fill leaves a faint, broken ghost (the outline eats the core) that OCR
    // can't read. Isolating on the WHITE OUTLINE instead — setTextColor(white) — renders the digits
    // as crisp solid black on white. Verified offline against the real popup: 80/10/10 read cleanly.
    private static final OcrSettingsData LOH_PCT_SETTINGS =
            OcrSettingsData.assembler()
                    .charWhitelist("0123456789")
                    .textLayout(TextLayout.SINGLE_LINE)
                    .stripBackground(true)
                    .setTextColor(new java.awt.Color(255, 255, 255))
                    .build();

    // =========================== CONSTRUCTOR ===========================

    public DailyLabyrinthRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    // =========================== TASK OVERRIDES ===========================

    @Override
    public boolean provideDailyMissionProgress() {
        return true;
    }

    @Override
    public LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    protected void execute() {

        try {
            // TEST GATE (matt/2026-08-10): when LABYRINTH_FORMATION_TEST_BOOL is on, run ONLY the
            // free Land-of-Heroes formation-setup flow and stop — skip the normal daily-clear logic
            // so matt can trigger just this without burning a daily battle attempt.
            Boolean formationTestOn =
                    profile.getConfig(ConfigurationKeyEnum.LABYRINTH_FORMATION_TEST_BOOL, Boolean.class);
            if (Boolean.TRUE.equals(formationTestOn)) {
                logInfo("LABYRINTH_FORMATION_TEST_BOOL is ON — running formation setup only (no battle).");
                if (!navigateToLabyrinthMenu()) {
                    rescheduleOneHourLater("Failed to navigate to the Labyrinth menu (formation test)");
                    return;
                }
                // matt/2026-08-13: "we're up to like three now" -- runs Land of Heroes, THEN Cave of
                // Monsters, THEN Charm Mine, each independently gated by its own open/locked check.
                for (ZoneFormation zone : ZONE_FORMATIONS) {
                    setupZoneFormation(zone);
                    // Every zone's setup STARTS by OCR'ing its label off the map, so every zone must
                    // end back on the map. See returnToLabyrinthMap().
                    returnToLabyrinthMap(zone.zoneName() + " formation");
                }
                reschedule(nextLabyrinthStartTime());
                return;
            }

            // Step 1: Navigate to labyrinth menu
            if (!navigateToLabyrinthMenu()) {
                rescheduleOneHourLater("Failed to navigate to the Labyrinth menu");
                return;
            }

            // Step 2: Execute challenges based on current day
            executeLabyrinthChallenges();

            reschedule(nextLabyrinthStartTime());

        } catch (Exception e) {
            logError("An error occurred during the Labyrinth task: " + e.getMessage());
            rescheduleOneHourLater("Unexpected error during execution: " + e.getMessage());
        }
    }

    // =========================== NAVIGATION METHODS ===========================

    /**
     * Opens the side menu, switches to city tab, scrolls down and searches for
     * labyrinth
     * 
     * @return true if navigation was successful, false otherwise
     */
    private boolean navigateToLabyrinthMenu() {
        logInfo("Navigating to the Labyrinth menu...");

        if (navigationHelper.navigateToLabyrinth()) {
            logInfo("Successfully navigated to the Labyrinth menu.");
            return true;
        }
        logWarning("Labyrinth menu item not found.");
        return false;
    }

    /**
     * matt/2026-08-13, caught live: right after a real Charm-Mine-flow battle, one attempt found the
     * screen already drifted into an unrelated City popup (March Queue) by the time we went looking
     * for the Labyrinth menu item -- {@link #navigateToLabyrinthMenu} assumes it's starting from a
     * clean home/city screen and has no way to close a stray popup on its own, so the menu-item search
     * (which only exists on the bare city screen) came back "not found" even though nothing was
     * actually wrong. This wrapper presses back a few times first to settle onto a clean screen, then
     * calls the normal navigation, and retries once more (with extra back-presses) if that still
     * doesn't find the menu -- covers a stray popup/dialog without needing to guess exactly which one.
     */
    private boolean settleAndNavigateToLabyrinthMenu(int dungeonNumber) {
        settleToCleanScreen(2);
        if (navigateToLabyrinthMenu()) {
            return true;
        }

        logWarning("First re-navigation attempt before dungeon " + dungeonNumber
                + " failed to find the Labyrinth menu item; settling further and retrying once.");
        settleToCleanScreen(3);
        return navigateToLabyrinthMenu();
    }

    /** Presses back N times with a short settle delay between each, to close any lingering
     *  popup/dialog left over from the previous screen before attempting fresh navigation. */
    private void settleToCleanScreen(int backPresses) {
        for (int i = 0; i < backPresses; i++) {
            pressBack();
            sleepTask(TAB_SWITCH_DELAY);
        }
        sleepTask(MENU_NAVIGATION_DELAY);
    }

    // =========================== CHALLENGE EXECUTION ===========================

    /**
     * Executes labyrinth challenges based on the current day of the week
     */
    private void executeLabyrinthChallenges() {
        DayOfWeek currentDay = LocalDateTime.now(ZoneOffset.UTC).getDayOfWeek();
        List<Integer> availableDungeons = getAvailableDungeons(currentDay);

        logInfo("Executing challenges for " + currentDay + ". Available dungeons: " + availableDungeons);

        boolean anyCompleted = false;
        for (Integer dungeonNumber : availableDungeons) {
            // matt/2026-08-13, caught live: after a completed battle, attemptNormalChallenge's single
            // pressBack() only returns to the zone's OWN stage-select screen, not the outer "The
            // Labyrinth" map -- so the next dungeon's banner search (which only exists on the outer
            // map) silently failed with a false "not available today". Re-navigating explicitly
            // before every dungeon (not just relying on back-taps) is more robust than guessing a
            // back-press count -- reuses the same menu path already proven reliable at task start.
            if (dungeonNumber != availableDungeons.get(0)) {
                if (!settleAndNavigateToLabyrinthMenu(dungeonNumber)) {
                    logWarning("Could not re-navigate to the Labyrinth map before dungeon " + dungeonNumber
                            + "; skipping it this pass.");
                    continue;
                }
            }
            if (executeDungeonChallenge(dungeonNumber)) {
                logInfo("Successfully completed challenge for dungeon " + dungeonNumber + ".");
                anyCompleted = true;

            }
        }

        if (!anyCompleted) {
            logWarning("No dungeons were successfully completed today.");
        }
    }

    /**
     * Executes a specific dungeon challenge
     * 
     * @param dungeonNumber the dungeon number to challenge
     * @return true if challenge was completed successfully
     */
    private boolean executeDungeonChallenge(int dungeonNumber) {
        logInfo("Attempting to execute challenge for dungeon " + dungeonNumber + ".");

        // matt/2026-08-13, caught live: on a dungeon that comes right after a just-completed battle
        // (i.e. after settleAndNavigateToLabyrinthMenu's re-navigation, not the task's very first
        // dungeon), the very first banner search sometimes missed even though navigateToLabyrinthMenu
        // itself reported success -- the outer map likely hadn't finished settling/rendering (or a
        // trailing reward animation from the just-completed battle was still resolving) in the single
        // instant the search ran. The first dungeon of the day never showed this because nothing had
        // just played out on top of the map. Retrying a few times with a short pause is a cheap,
        // low-risk way to ride out that race without guessing exactly what's still animating.
        ImageSearchResultData labyrinthResult = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            labyrinthResult = templateSearchHelper.locatePattern(
                    getDungeonTemplate(dungeonNumber),
                    SearchConfigConstants.DEFAULT_SINGLE);
            if (labyrinthResult.isFound()) {
                break;
            }
            if (attempt < 3) {
                logInfo("Dungeon " + dungeonNumber + " banner not found on attempt " + attempt
                        + "; giving the map a moment to settle and retrying.");
                sleepTask(TAB_SWITCH_DELAY);
            }
        }
        if (!labyrinthResult.isFound()) {
            // matt/2026-08-13: caught live twice now that the retry above doesn't actually fix this --
            // by the time a screenshot gets pulled externally, the app has already moved on to whatever
            // screen the NEXT queued task opened, so there was never a real look at what the banner
            // search actually saw. Capture the frame right here, in the same instant as the failed
            // search, so the next occurrence has real evidence instead of a guess.
            saveLabyrinthFrame("banner_missing", dungeonNumber);
            logWarning("Dungeon " + dungeonNumber + " is not available today.");
            return false;
        }

        tapInside(labyrinthResult);
        // matt, 2026-08-19, caught live: TAB_SWITCH_DELAY (500ms) is a quick-tab-switch delay, not
        // a real zone-load delay -- entering a dungeon zone plays a slide/fade transition that isn't
        // finished settling in 500ms. attemptRaidChallenge() then ran its template search against
        // that still-animating frame and false-matched LABYRINTH_RAID_CHALLENGE (manually replayed
        // against a real captured frame: raidChallenge.png scores only ~57% against the real, settled
        // stage-select screen, well under the 90% threshold -- so the live 90%+ match that sent this
        // down the wrong branch had to be a transient animation frame, not the real screen). That
        // false branch then tapped nothing meaningful, "skipped" a battle that never started, and
        // pressBack()'d twice out of the zone -- exactly matt's report: "went in, claimed it, then
        // just got out" with zero attempts actually spent (confirmed live: Remaining attempts stayed
        // at 5/5). For dungeon 3 (Charm Mine) the same insufficient settle meant NONE of the three
        // challenge-type checks matched at all, so the routine silently gave up on that dungeon. Using
        // LABYRINTH_LOAD_DELAY (2000ms, already used elsewhere in this file for real zone-load waits)
        // instead of TAB_SWITCH_DELAY here lets the transition actually finish before any challenge-
        // type check runs.
        sleepTask(LABYRINTH_LOAD_DELAY);

        // Try quick challenge first
        if (attemptQuickChallenge(dungeonNumber)) {
            return true;
        }

        // Try raid challenge
        if (attemptRaidChallenge(dungeonNumber)) {
            return true;
        }

        // Try normal challenge
        return attemptNormalChallenge(dungeonNumber);
    }

    /**
     * Attempts to execute a quick challenge
     */
    private boolean attemptQuickChallenge(int dungeonNumber) {
        tapNear(new PointData(700, 1200));
        sleepTask(100);
        ImageSearchResultData quickChallengeResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_QUICK_CHALLENGE,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (quickChallengeResult.isFound()) {
            logInfo("'Quick Challenge' is available for dungeon " + dungeonNumber + ".");
            tapInside(quickChallengeResult);
            sleepTask(MENU_NAVIGATION_DELAY);

            // Skip battle animation
            tapNear(SKIP_BUTTON);
            sleepTask(300);
            tapInside(SKIP_BUTTON, SKIP_BUTTON, 10, 50);
            pressBack();
            return true;
        }
        return false;
    }

    /**
     * Attempts to execute a raid challenge
     */
    /**
     * matt, 2026-08-19, caught live: "Raid" is NOT a battle to skip -- it's an instant rewards
     * claim. Tapping the "Raid" button opens a "Raid Rewards" popup (real Charms/chests, a real
     * Claim button) with no battle animation at all. The old code assumed the same shape as Quick
     * Challenge (an animated battle needing SKIP_BUTTON taps at (71,827)) and never once tapped the
     * real Claim button -- SKIP_BUTTON's coordinate lands on empty space on this screen, so every
     * "successful" raid was actually two blind pressBack()s abandoning the reward popup unclaimed.
     * Verified live: a real captured frame showed the popup with 470 Charms + 5/4 chests sitting
     * there un-clicked; the real Claim button's pixel region (color-scanned from that same frame)
     * is x=[207,512] y=[825,901], center (359,863).
     */
    private static final PointData RAID_REWARDS_CLAIM_BTN = new PointData(359, 863);

    private boolean attemptRaidChallenge(int dungeonNumber) {
        ImageSearchResultData raidResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_RAID_CHALLENGE,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (raidResult.isFound()) {
            logInfo("'Raid Challenge' is available for dungeon " + dungeonNumber
                    + " (match score " + String.format("%.1f", raidResult.getMatchScore()) + ").");
            saveLabyrinthFrame("raid_detected", dungeonNumber);
            tapInside(raidResult);
            sleepTask(600);
            saveLabyrinthFrame("raid_after_tap", dungeonNumber);

            // Tap the real Claim button on the Raid Rewards popup (see class javadoc above).
            tapNear(RAID_REWARDS_CLAIM_BTN);
            sleepTask(500);
            saveLabyrinthFrame("raid_claimed", dungeonNumber);

            pressBack();
            return true;
        }
        return false;
    }

    /**
     * Attempts to execute a normal challenge
     */
    private boolean attemptNormalChallenge(int dungeonNumber) {
        ImageSearchResultData normalChallengeResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_NORMAL_CHALLENGE,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (!normalChallengeResult.isFound()) {
            logWarning("No 'Normal Challenge' button found for dungeon " + dungeonNumber + ".");
            return false;
        }

        tapInside(normalChallengeResult);
        sleepTask(300);

        // OBSERVE: the pre-deploy screen shows the enemy formation for this stage.
        saveLabyrinthFrame("enemy", dungeonNumber);

        // Try quick deploy first
        ImageSearchResultData quickDeployResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_QUICK_DEPLOY,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (quickDeployResult.isFound()) {
            logInfo("'Quick Deploy' button found. Deploying for dungeon " + dungeonNumber + ".");
            tapInside(quickDeployResult);
            sleepTask(100);
        }

        // matt/2026-08-13: for Cave of Monsters / Charm Mine, drive the configured ratio NOW --
        // right before Deploy, since it doesn't persist between visits like Land of Heroes does.
        ZoneFormation singleSquadZone = DUNGEON_SINGLE_SQUAD_ZONES.get(dungeonNumber);
        if (singleSquadZone != null) {
            setRatioBeforeDeploy(singleSquadZone, dungeonNumber);
        }

        // Deploy troops
        ImageSearchResultData deployResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_DEPLOY,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (deployResult.isFound()) {
            logInfo("'Deploy' button found. Deploying troops for dungeon " + dungeonNumber + ".");
            tapInside(deployResult);
            sleepTask(BATTLE_COMPLETION_DELAY);

            // OBSERVE: the result screen shows win/loss + rewards. This is the data we need to build
            // labyrinth victory/defeat detection (no template exists yet).
            saveLabyrinthFrame("result", dungeonNumber);

            // Skip battle results
            tapInside(RESULT_SKIP_BUTTON, RESULT_SKIP_BUTTON, 10, 50);
            pressBack();
            return true;
        }

        logWarning("Could not find 'Deploy' button for dungeon " + dungeonNumber + ".");
        return false;
    }

    // =================== ZONE FORMATION SETUP ===================

    /**
     * matt/2026-08-13: describes one Labyrinth zone's formation-setup inputs — the map-screen banner
     * tap point + label OCR box (for the open/locked check), and the config keys that drive its
     * troop ratios. {@link #setupZoneFormation} is generic over this.
     *
     * <p>
     * <b>matt/2026-08-13, caught live via ADB (Cave of Monsters):</b> the original design assumed
     * every zone shares Land of Heroes' two-squad structure (Challenge -> Squad Config -> Quick
     * Deploy -> per-squad Edit Formation -> Balance). Watching Cave of Monsters live end-to-end
     * proved that wrong: tapping Challenge on Cave of Monsters lands DIRECTLY on a single combined
     * troop-detail screen (Infantry/Lancer/Marksman, ONE Balance button) -- there is no Squad Config
     * screen, no Quick Deploy, no second squad at all. {@code singleSquad} distinguishes the two
     * shapes; only {@code squad1Keys} is used when true (squad2Keys stays populated but unused, so
     * the existing UI fields keep working without another rework).
     */
    private record ZoneFormation(String zoneName, PointData banner, PointData labelTl, PointData labelBr,
                                  boolean singleSquad,
                                  ConfigurationKeyEnum[] squad1Keys, ConfigurationKeyEnum[] squad2Keys) {}

    private static final ZoneFormation[] ZONE_FORMATIONS = {
        new ZoneFormation("Land of Heroes", LOH_ZONE_BANNER, LOH_ZONE_LABEL_TL, LOH_ZONE_LABEL_BR, false,
                new ConfigurationKeyEnum[] { ConfigurationKeyEnum.LABYRINTH_SQUAD1_INFANTRY_INT,
                        ConfigurationKeyEnum.LABYRINTH_SQUAD1_LANCER_INT, ConfigurationKeyEnum.LABYRINTH_SQUAD1_MARKSMAN_INT },
                new ConfigurationKeyEnum[] { ConfigurationKeyEnum.LABYRINTH_SQUAD2_INFANTRY_INT,
                        ConfigurationKeyEnum.LABYRINTH_SQUAD2_LANCER_INT, ConfigurationKeyEnum.LABYRINTH_SQUAD2_MARKSMAN_INT }),
        new ZoneFormation("Cave of Monsters", CAVE_ZONE_BANNER, CAVE_ZONE_LABEL_TL, CAVE_ZONE_LABEL_BR, true,
                new ConfigurationKeyEnum[] { ConfigurationKeyEnum.LABYRINTH_CAVE_SQUAD1_INFANTRY_INT,
                        ConfigurationKeyEnum.LABYRINTH_CAVE_SQUAD1_LANCER_INT, ConfigurationKeyEnum.LABYRINTH_CAVE_SQUAD1_MARKSMAN_INT },
                new ConfigurationKeyEnum[] { ConfigurationKeyEnum.LABYRINTH_CAVE_SQUAD2_INFANTRY_INT,
                        ConfigurationKeyEnum.LABYRINTH_CAVE_SQUAD2_LANCER_INT, ConfigurationKeyEnum.LABYRINTH_CAVE_SQUAD2_MARKSMAN_INT }),
        new ZoneFormation("Charm Mine", CHARM_ZONE_BANNER, CHARM_ZONE_LABEL_TL, CHARM_ZONE_LABEL_BR, true,
                new ConfigurationKeyEnum[] { ConfigurationKeyEnum.LABYRINTH_CHARM_SQUAD1_INFANTRY_INT,
                        ConfigurationKeyEnum.LABYRINTH_CHARM_SQUAD1_LANCER_INT, ConfigurationKeyEnum.LABYRINTH_CHARM_SQUAD1_MARKSMAN_INT },
                new ConfigurationKeyEnum[] { ConfigurationKeyEnum.LABYRINTH_CHARM_SQUAD2_INFANTRY_INT,
                        ConfigurationKeyEnum.LABYRINTH_CHARM_SQUAD2_LANCER_INT, ConfigurationKeyEnum.LABYRINTH_CHARM_SQUAD2_MARKSMAN_INT }),
        // matt/2026-08-16: Gaia Heart -- two-squad like Land of Heroes, but dispatched to its own
        // setupGaiaZone() (see the "Gaia Heart formation flow" constants above) because its commit
        // mechanism genuinely differs (direct-commit button, no back-arrow/dialog). The `singleSquad`
        // flag here is unused for Gaia -- routing happens by name in setupZoneFormation() below.
        new ZoneFormation("Gaia Heart", GAIA_ZONE_BANNER, GAIA_ZONE_LABEL_TL, GAIA_ZONE_LABEL_BR, false,
                new ConfigurationKeyEnum[] { ConfigurationKeyEnum.LABYRINTH_GAIA_SQUAD1_INFANTRY_INT,
                        ConfigurationKeyEnum.LABYRINTH_GAIA_SQUAD1_LANCER_INT, ConfigurationKeyEnum.LABYRINTH_GAIA_SQUAD1_MARKSMAN_INT },
                new ConfigurationKeyEnum[] { ConfigurationKeyEnum.LABYRINTH_GAIA_SQUAD2_INFANTRY_INT,
                        ConfigurationKeyEnum.LABYRINTH_GAIA_SQUAD2_LANCER_INT, ConfigurationKeyEnum.LABYRINTH_GAIA_SQUAD2_MARKSMAN_INT }),
    };

    /**
     * matt/2026-08-13: Cave of Monsters (dungeon 2) / Charm Mine (dungeon 3) proven live to have NO
     * standalone saved formation -- re-entering always resets to 33/33/33 no matter what the
     * Formation Test flow does. The only way their configured ratio ever actually applies is if it's
     * set fresh right before the REAL Deploy, every single attempt (see {@link #setRatioBeforeDeploy}).
     * Land of Heroes (dungeon 1) is excluded here -- it has its own genuine save mechanism via the
     * Formation Test flow.
     */
    private static final java.util.Map<Integer, ZoneFormation> DUNGEON_SINGLE_SQUAD_ZONES = java.util.Map.of(
            2, ZONE_FORMATIONS[1],  // Cave of Monsters
            3, ZONE_FORMATIONS[2]   // Charm Mine
    );

    /**
     * matt/2026-08-10 (Land of Heroes), extended 2026-08-13 to Cave of Monsters + Charm Mine —
     * TEST harness (free, no battle). From the Labyrinth menu this opens the given zone's stage
     * screen and sets up the deploy formation to its configured troop ratio, then SAVES it and
     * STOPS — it never taps Deploy/battle (battling burns a daily attempt while formation-setup is
     * free).
     *
     * <p>Branches on {@link ZoneFormation#singleSquad()}: Land of Heroes runs the original
     * Challenge -> Squad Config -> Quick Deploy -> per-squad Edit Formation -> Balance flow.
     * Cave of Monsters / Charm Mine (proven live) skip straight from Challenge to a single combined
     * troop-detail screen with ONE Balance button -- no Squad Config, no Quick Deploy, one ratio.
     */
    /** One troop type's four stats, mine vs the opponent's, as shown on the View Details panel. */
    private record ScoutLine(double mine, double theirs) {
        double edge() { return mine - theirs; }
    }

    /**
     * What the pre-fight scout saw.
     *
     * @param parsed           at least one complete troop type was read
     * @param opponentUniform  the opponent presents IDENTICALLY to all three of my troop types --
     *                         every stat the same across Infantry, Lancer and Marksman. This is the
     *                         precise condition under which an even split is correct: the
     *                         counter-triangle (Infantry > Lancer > Marksman > Infantry, ~+10%) only
     *                         pays when the enemy is skewed, so against a uniform enemy whatever you
     *                         favour meets its counter in equal measure and every ratio is equivalent.
     *                         Measured live on Cave of Monsters 3-9: opponent 19.8/19.8/26.4/26.4
     *                         against all three types, and 50,000/50,000/50,000 troops.
     */
    private record ScoutResult(boolean parsed, boolean opponentUniform) {
        static ScoutResult none() { return new ScoutResult(false, false); }
    }

    /**
     * Reads the pre-fight "View Details" panel from the stage screen: the enemy's troop counts and
     * the per-troop-type stat comparison. Pure OBSERVATION for now -- it logs what it finds and
     * changes no deploy behaviour.
     *
     * <p>Deliberately observation-only at this stage. The one stage scouted by hand so far (Cave of
     * Monsters 3-9) came back perfectly SYMMETRIC -- enemy 50,000/50,000/50,000, and identical stats
     * against all three of my troop types (+19.8 Atk/Def, +26.4 Leth/Health) -- so there was nothing
     * to counter there and no way to tell a good weighting rule from a bad one. Building a
     * counter-picker on a single symmetric sample would be guessing. Collect real readings across
     * zones and stages first, then wire the decision once there is an asymmetric case to test it on.
     *
     * @return true if the panel was read, false if it never opened (caller carries on regardless)
     */
    private ScoutResult scoutStageDetails(String tag) {
        if (!navStep(STAGE_ENEMY_MAGNIFIER, SCOUT_ANCHOR_TL, SCOUT_ANCHOR_BR, SCOUT_ANCHOR_TEXT,
                tag + " stage->viewDetails")) {
            logInfo(tag + " scout: View Details didn't open; skipping the scout (costs nothing, "
                    + "the fight is unaffected).");
            return ScoutResult.none();
        }
        saveLabyrinthFrame("scout", 0);

        // matt/2026-08-20: TWO passes, and they matter. The panel doesn't fit on one screen -- the
        // first live run read Infantry and Lancer complete (4 stats each) but only 3 rows of
        // Marksman, because Marksman Health sits below the fold. Summing edges over different row
        // counts then made Marksman look BEST (+5.5) purely because its worst row was missing
        // (Health 15.8 vs 26.4 = -10.6). Had the decision been live it would have loaded the ratio
        // onto the weakest troop type. So: read, scroll, read again, merge by (type, stat).
        java.util.Map<String, java.util.Map<String, ScoutLine>> byType = new java.util.LinkedHashMap<>();
        StringBuilder seen = new StringBuilder();
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1) {
                emuManager.swipeScreen(String.valueOf(EMULATOR_NUMBER), SCOUT_SCROLL_FROM, SCOUT_SCROLL_TO, 400);
                sleepTask(LABYRINTH_LOAD_DELAY);
            }
            String body = readStringValue(SCOUT_BODY_TL, SCOUT_BODY_BR, SCOUT_SETTINGS);
            if (body == null || body.isBlank()) continue;
            seen.append(body).append('\n');
            java.util.regex.Matcher m = SCOUT_ROW.matcher(body);
            while (m.find()) {
                String type = capitalize(m.group(2));
                String stat = capitalize(m.group(3));
                try {
                    byType.computeIfAbsent(type, k -> new java.util.LinkedHashMap<>())
                            .put(stat, new ScoutLine(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(4))));
                } catch (NumberFormatException ignored) { /* skip a mangled row */ }
            }
        }
        String body = seen.toString();
        if (body.isBlank()) {
            logWarning(tag + " scout: View Details opened but its body read blank on both passes.");
            pressBack();
            sleepTask(LABYRINTH_LOAD_DELAY);
            return ScoutResult.none();
        }

        if (byType.isEmpty()) {
            logWarning(tag + " scout: no stat rows parsed out of View Details. Raw text was: '"
                    + body.replace("\n", " | ").trim() + "'");
        } else {
            for (java.util.Map.Entry<String, java.util.Map<String, ScoutLine>> e : byType.entrySet()) {
                StringBuilder sb = new StringBuilder(tag + " scout [" + e.getKey() + "]:");
                double edge = 0;
                for (java.util.Map.Entry<String, ScoutLine> s : e.getValue().entrySet()) {
                    sb.append(' ').append(s.getKey()).append('=').append(s.getValue().mine())
                      .append("/").append(s.getValue().theirs());
                    edge += s.getValue().edge();
                }
                // Only a type with all four stats gets a comparable number. A partial sum is not a
                // smaller edge, it is a DIFFERENT measurement, and mixing the two is what made
                // Marksman read as the best type on the first live run. Say "incomplete" instead.
                if (e.getValue().keySet().containsAll(SCOUT_REQUIRED_STATS)) {
                    sb.append("  net edge ").append(String.format("%+.1f", edge));
                } else {
                    sb.append("  net edge N/A -- only ").append(e.getValue().size())
                      .append("/4 stats read (have ").append(e.getValue().keySet())
                      .append("); NOT comparable against a complete type, so not ranked.");
                }
                logInfo(sb.toString());
            }
            logInfo(tag + " scout: parsed " + byType.size() + " troop type(s). OBSERVATION ONLY -- "
                    + "the deploy ratio is unchanged. Collecting readings until an asymmetric stage "
                    + "appears to calibrate a counter-pick against.");
        }

        pressBack();
        sleepTask(LABYRINTH_LOAD_DELAY);
        boolean opponentUniform = false;
        if (byType.size() == 3) {
            java.util.List<java.util.Map<String, ScoutLine>> all = new java.util.ArrayList<>(byType.values());
            opponentUniform = all.stream().allMatch(m2 -> m2.keySet().containsAll(SCOUT_REQUIRED_STATS));
            if (opponentUniform) {
                for (String stat : SCOUT_REQUIRED_STATS) {
                    double ref = all.get(0).get(stat).theirs();
                    for (java.util.Map<String, ScoutLine> m2 : all) {
                        if (Math.abs(m2.get(stat).theirs() - ref) > OPPONENT_UNIFORM_TOLERANCE) {
                            opponentUniform = false;
                        }
                    }
                }
            }
            logInfo(tag + " scout: opponent presents "
                    + (opponentUniform ? "IDENTICALLY to all three troop types -- the counter-triangle "
                            + "has nothing to bite on here, so an even split is correct"
                          : "DIFFERENTLY across troop types -- there is a real matchup to exploit")
                    + ".");
        }
        return new ScoutResult(!byType.isEmpty(), opponentUniform);
    }

    /**
     * Taps Equalize on the single-squad deployment screen and CONFIRMS the result from the screen's
     * own "Troop Ratio:" strip. Returns false if the strip doesn't come back as three roughly equal
     * shares, so the caller can fall through to the slider flow rather than deploy on an assumption.
     */
    private boolean tapEqualize(String tag) {
        tapNear(EQUALIZE_BTN);
        sleepTask(MENU_NAVIGATION_DELAY);

        String line = readStringValue(TROOP_RATIO_LINE_TL, TROOP_RATIO_LINE_BR, TROOP_RATIO_LINE_SETTINGS);
        java.util.List<Integer> pcts = new java.util.ArrayList<>();
        if (line != null) {
            // Drop the "Troop Ratio:" label first so its own characters can't contribute digits.
            int colon = line.indexOf(':');
            String values = colon >= 0 ? line.substring(colon + 1) : line;
            java.util.regex.Matcher m = RATIO_PCT.matcher(values);
            while (m.find() && pcts.size() < 3) {
                try {
                    int v = Integer.parseInt(m.group(1));
                    if (v >= 0 && v <= 100) pcts.add(v);
                } catch (NumberFormatException ignored) { }
            }
        }
        if (pcts.size() != 3) {
            logWarning(tag + ": tapped Equalize but the Troop Ratio strip didn't read as three values "
                    + "(read: '" + (line == null ? "<null>" : line.trim()) + "'). Falling back to the "
                    + "Balance slider flow rather than deploying on an assumption.");
            return false;
        }
        int min = java.util.Collections.min(pcts), max = java.util.Collections.max(pcts);
        if (max - min > 2) {
            logWarning(tag + ": tapped Equalize but the ratio came back " + pcts
                    + " -- not an even split. Falling back to the Balance slider flow.");
            return false;
        }
        logInfo(tag + ": Equalize confirmed -- Troop Ratio now " + pcts + ". Skipped the Balance "
                + "slider flow entirely (that path is ~5 minutes of 1%-per-tap work; the even split "
                + "is correct here because the opponent presents identically to all three types).");
        return true;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Presses back until the Labyrinth map is showing again.
     *
     * <p>matt/2026-08-20: the zone loop calls setupZoneFormation() once per zone and each call
     * STARTS by OCR'ing that zone's label off the map, so every call must END on the map. Nothing
     * used to do that deliberately -- the old flow got back purely as a side effect of the
     * save-dialog navStep pressing back while failing to find a dialog that never existed. Removing
     * that dead wait (and adding the Equalize early-return) removed the accident with it, and the
     * very next run read Charm Mine's label as 'SO000' -- the "50,000" troop count on Cave of
     * Monsters' deployment screen, because it was still sitting there. The zone gate caught it and
     * skipped rather than tapping blind, but Charm and Gaia were then skipped for the wrong reason.
     * Navigate back on purpose instead of relying on a failure's side effects.
     */
    private void returnToLabyrinthMap(String tag) {
        for (int i = 0; i < 4; i++) {
            String title = readStringValue(MAP_ANCHOR_TL, MAP_ANCHOR_BR, ZONE_LABEL_SETTINGS);
            if (title != null && title.toLowerCase(java.util.Locale.ROOT).contains(MAP_ANCHOR_TEXT)) {
                return;
            }
            pressBack();
            sleepTask(LABYRINTH_LOAD_DELAY);
        }
        String title = readStringValue(MAP_ANCHOR_TL, MAP_ANCHOR_BR, ZONE_LABEL_SETTINGS);
        if (title == null || !title.toLowerCase(java.util.Locale.ROOT).contains(MAP_ANCHOR_TEXT)) {
            logWarning(tag + ": couldn't get back to the Labyrinth map after 4 back presses (title "
                    + "reads '" + title + "'). Remaining zones this pass will likely be skipped by "
                    + "their own label check, which is the safe outcome.");
        }
    }

    private void setupZoneFormation(ZoneFormation zone) {
        String tag = zone.zoneName() + " formation";
        logInfo(tag + ": starting formation setup (setup only, no battle).");

        saveLabyrinthFrame("map", 0); // one-shot: capture the Labyrinth map to calibrate zone-label OCR

        // Enter the zone by reading its map label (matt's rule): a LOCKED zone's line reads
        // "Opens in …"; an OPEN zone shows just name + countdown. Only tap the banner if it's open.
        String zoneLabel = readStringValue(zone.labelTl(), zone.labelBr(), ZONE_LABEL_SETTINGS);
        logInfo(tag + ": label OCR = '" + zoneLabel + "'.");

        // matt/2026-08-20: this gate was "if the label says 'Opens in' it's locked, OTHERWISE it's
        // open" -- a negative check, and the same wrong shape as the Monument Events-tab check fixed
        // in 80772e5. Anything unreadable sails straight through it. Caught live today: the Labyrinth
        // map only shows four zones at this scroll position (Cave of Monsters, Charm Mine, Research
        // Center, Gear Forge) and Land of Heroes is NOT on screen at all, so its label box lands on
        // bare fog and OCR'd 'Oo'. 'Oo' doesn't contain "open", so the zone was declared open, its
        // banner coordinate was tapped anyway, and the run wandered off into the Shelter/Furniture
        // screen -- see lab_d9_navfail_1787165251660.png from the 08-19 run, which is a furniture
        // catalogue, not the Labyrinth.
        //
        // Flip it to a positive check: the label must actually contain the zone's own name. Cave of
        // Monsters read exactly 'Cave of Monsters' on the same pass, so a real zone reads cleanly.
        // Deliberately fail-closed -- an unreadable label now SKIPS the zone, because skipping costs
        // nothing while proceeding blind taps unknown coordinates on an unknown screen.
        String normalizedLabel = zoneLabel == null
                ? "" : zoneLabel.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
        String normalizedName = zone.zoneName().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
        if (normalizedLabel.contains("opensin")) {
            logWarning(tag + ": reads LOCKED ('Opens in') — not open yet, skipping.");
            return;
        }
        if (!normalizedLabel.contains(normalizedName)) {
            logWarning(tag + ": label OCR ('" + zoneLabel + "') doesn't contain the zone's own name, so "
                    + "this zone isn't at that spot on the map right now (the map shows a different set "
                    + "of zones depending on scroll position). Skipping rather than tapping "
                    + zone.banner() + " blind on whatever IS there.");
            saveLabyrinthFrame("zone_label_mismatch", 9);
            return;
        }
        logInfo(tag + ": label confirms the zone is present and open — tapping its banner to enter.");
        // Step 1: banner -> stage screen (poll for the "Challenge" button).
        if (!navStep(zone.banner(), STAGE_ANCHOR_TL, STAGE_ANCHOR_BR, STAGE_ANCHOR_TEXT, tag + " banner->stage")) {
            logWarning(tag + ": never reached the stage screen; aborting.");
            return;
        }
        saveLabyrinthFrame("stage", 0);

        // Free pre-fight scout -- opens the enemy's View Details panel, logs the matchup, backs out.
        // Costs no attempt and cannot affect the fight; see scoutStageDetails() for why it is
        // observation-only for now.
        ScoutResult scout = scoutStageDetails(tag);

        if (zone.singleSquad()) {
            setupSingleSquadZone(zone, tag, scout);
            return;
        }

        if ("Gaia Heart".equals(zone.zoneName())) {
            setupGaiaZone(zone, tag);
            return;
        }

        // Step 2: Challenge -> Squad Config (poll for the "Squad Config" title).
        if (!navStep(LOH_CHALLENGE_BTN, SQUAD_ANCHOR_TL, SQUAD_ANCHOR_BR, SQUAD_ANCHOR_TEXT, tag + " Challenge->SquadConfig")) {
            logWarning(tag + ": never reached Squad Config; aborting.");
            return;
        }
        saveLabyrinthFrame("squad", 0);

        // Step 3: Quick Deploy fills heroes + troops for BOTH squads IN PLACE (stays on Squad Config,
        // so there is no screen change to verify — a short settle is enough).
        logInfo(tag + ": tapping Quick Deploy (fills squads in place).");
        tapNear(LOH_QUICK_DEPLOY_BTN);
        sleepTask(LABYRINTH_LOAD_DELAY);
        saveLabyrinthFrame("squad_filled", 0);

        // Step 4: configure each squad's ratio in turn. After a squad's "Save and Exit" the game drops
        // back to the STAGE screen, so for squads after the first we re-tap Challenge to reach Squad
        // Config again. Quick Deploy above already filled every squad, so we don't repeat it.
        int[][] squadRatios = readSquadRatiosFromConfig(zone);
        for (int i = 0; i < squadRatios.length; i++) {
            if (i > 0) {
                logInfo(tag + ": re-entering Squad Config for squad " + (i + 1) + ".");
                if (!navStep(LOH_CHALLENGE_BTN, SQUAD_ANCHOR_TL, SQUAD_ANCHOR_BR, SQUAD_ANCHOR_TEXT,
                        tag + " Challenge->SquadConfig(sq" + (i + 1) + ")")) {
                    logWarning(tag + ": could not re-enter Squad Config for squad " + (i + 1)
                            + "; aborting remaining squads.");
                    return;
                }
            }
            if (!configureSquadRatio(tag, i + 1, LOH_SQUAD_EDIT_BTNS[i], squadRatios[i])) {
                logWarning(tag + ": squad " + (i + 1) + " setup failed; aborting remaining squads.");
                return;
            }
        }

        logInfo(tag + ": all squads configured. STOPPING before Deploy (no battle attempt spent).");
    }

    /**
     * matt/2026-08-13: Cave of Monsters / Charm Mine's actual flow, proven live via ADB. Challenge
     * lands DIRECTLY on the combined troop-detail screen (Infantry/Lancer/Marksman, ONE Balance
     * button) -- no Squad Config, no Quick Deploy, no second squad. Only squad1Keys is used.
     */
    private void setupSingleSquadZone(ZoneFormation zone, String tag, ScoutResult scout) {
        // Challenge -> troop-detail screen directly. The screen's own title is the zone's name
        // (e.g. "Cave of Monsters"), so that's the anchor text -- generic across any single-squad zone.
        if (!navStep(LOH_CHALLENGE_BTN, TROOP_ANCHOR_TL, TROOP_ANCHOR_BR, zone.zoneName().toLowerCase(),
                tag + " Challenge->troop")) {
            logWarning(tag + ": never reached the troop-detail screen; aborting.");
            return;
        }
        saveLabyrinthFrame("troop", 0);

        if (scout.opponentUniform() && tapEqualize(tag)) {
            logInfo(tag + ": configured via Equalize. STOPPING before Deploy (no battle attempt spent).");
            return;
        }

        int[] ratio = readSingleRatioFromConfig(zone);
        if (!driveBalanceAndSave(tag, "single", zone.zoneName().toLowerCase(), ratio, false)) {
            logWarning(tag + ": ratio setup failed.");
            return;
        }
        logInfo(tag + ": configured. STOPPING before Deploy (no battle attempt spent).");
    }

    /**
     * matt/2026-08-16: Gaia Heart's real flow, proven live via ADB. Two squads like Land of Heroes
     * (Challenge -> Squad Config -> Quick Deploy -> per-squad Edit Formation -> Balance), but the
     * troop-detail screen's OWN "Edit Formation" button commits the ratio directly and returns to
     * Squad Config -- confirmed live that this alone (no back-arrow, no Save-and-Exit dialog) is
     * enough to persist the ratio across a full exit-to-map-and-back-in. Only squads 1-2 are driven;
     * Squad 3 (locked until Stage 15-10) has no live-verified coordinates yet -- see
     * GAIA_SQUAD_EDIT_BTNS's note.
     */
    private void setupGaiaZone(ZoneFormation zone, String tag) {
        // Challenge -> Squad Config (same anchor as Land of Heroes).
        if (!navStep(LOH_CHALLENGE_BTN, SQUAD_ANCHOR_TL, SQUAD_ANCHOR_BR, SQUAD_ANCHOR_TEXT,
                tag + " Challenge->SquadConfig")) {
            logWarning(tag + ": never reached Squad Config; aborting.");
            return;
        }
        saveLabyrinthFrame("gaia_squad", 0);

        // Quick Deploy fills both squads with REAL troops/heroes in place (not normalized, unlike
        // every other zone) -- idempotent, safe even if squads are already populated from a prior run.
        logInfo(tag + ": tapping Quick Deploy (fills squads with real troops/heroes in place).");
        tapNear(GAIA_QUICK_DEPLOY_BTN);
        sleepTask(LABYRINTH_LOAD_DELAY);
        saveLabyrinthFrame("gaia_squad_filled", 0);

        int[][] squadRatios = readSquadRatiosFromConfig(zone);
        for (int i = 0; i < GAIA_SQUAD_EDIT_BTNS.length; i++) {
            if (i > 0) {
                logInfo(tag + ": re-entering Squad Config for squad " + (i + 1) + ".");
                if (!navStep(LOH_CHALLENGE_BTN, SQUAD_ANCHOR_TL, SQUAD_ANCHOR_BR, SQUAD_ANCHOR_TEXT,
                        tag + " Challenge->SquadConfig(sq" + (i + 1) + ")")) {
                    logWarning(tag + ": could not re-enter Squad Config for squad " + (i + 1)
                            + "; aborting remaining squads.");
                    return;
                }
            }
            if (!driveGaiaBalanceAndSave(tag, "sq" + (i + 1), GAIA_SQUAD_EDIT_BTNS[i], squadRatios[i])) {
                logWarning(tag + ": squad " + (i + 1) + " setup failed; aborting remaining squads.");
                return;
            }
        }

        logInfo(tag + ": squads 1-2 configured. Squad 3 not driven (locked until Stage 15-10, no "
                + "live-verified coordinates yet). STOPPING before Deploy (no battle attempt spent).");
    }

    /**
     * Gaia Heart's squad-ratio commit: Edit Formation -> troop-detail -> Balance -> drive the three
     * sliders (Gaia's own row Y positions) -> Confirm -> the screen's OWN "Edit Formation" button,
     * which commits directly (verified live -- no back-arrow, no dialog, unlike Land of Heroes).
     */
    private boolean driveGaiaBalanceAndSave(String tag, String label, PointData editFormationBtn, int[] ratio) {
        logInfo(tag + ": configuring " + label + " -> " + ratio[0] + "/" + ratio[1] + "/" + ratio[2]
                + " (Inf/Lan/Mrk).");

        if (!navStep(editFormationBtn, TROOP_ANCHOR_TL, TROOP_ANCHOR_BR, "gaia heart",
                tag + " EditFormation->troop(" + label + ")")) {
            logWarning(tag + ": " + label + " -- never reached troop-detail.");
            return false;
        }
        saveLabyrinthFrame("gaia_troop", 0);

        if (!navStep(GAIA_BALANCE_BTN, BALANCE_ANCHOR_TL, BALANCE_ANCHOR_BR, BALANCE_ANCHOR_TEXT,
                tag + " Balance->popup(" + label + ")")) {
            logWarning(tag + ": " + label + " -- never reached the Balance popup.");
            return false;
        }
        saveLabyrinthFrame("gaia_balance_popup", 0);

        floorRowToZero("Infantry", GAIA_INFANTRY_ROW_Y);
        floorRowToZero("Lancer",   GAIA_LANCER_ROW_Y);
        floorRowToZero("Marksman", GAIA_MARKSMAN_ROW_Y);
        fillRowToTarget("Infantry", GAIA_INFANTRY_ROW_Y, GAIA_INF_PCT_TL, GAIA_INF_PCT_BR, ratio[0]);
        fillRowToTarget("Lancer",   GAIA_LANCER_ROW_Y,   GAIA_LAN_PCT_TL, GAIA_LAN_PCT_BR, ratio[1]);
        fillRowToTarget("Marksman", GAIA_MARKSMAN_ROW_Y, GAIA_MRK_PCT_TL, GAIA_MRK_PCT_BR, ratio[2]);

        Integer vi = readPercent(GAIA_INF_PCT_TL, GAIA_INF_PCT_BR);
        Integer vl = readPercent(GAIA_LAN_PCT_TL, GAIA_LAN_PCT_BR);
        Integer vm = readPercent(GAIA_MRK_PCT_TL, GAIA_MRK_PCT_BR);
        logInfo(tag + ": " + label + " post-set readback = "
                + vi + "/" + vl + "/" + vm + " (target " + ratio[0] + "/" + ratio[1] + "/" + ratio[2] + ").");
        saveLabyrinthFrame("gaia_balance_set", 0);

        // Confirm the popup -> back on troop-detail with the new ratio showing.
        if (!navStep(LOH_CONFIRM_BTN, TROOP_ANCHOR_TL, TROOP_ANCHOR_BR, "gaia heart",
                tag + " Confirm->troop(" + label + ")")) {
            logWarning(tag + ": " + label + " -- Confirm didn't return to troop-detail "
                    + "(continuing to the commit step anyway).");
        }

        // Gaia's own "Edit Formation" button commits directly and returns to Squad Config -- no
        // dialog to wait for, just confirm we're back (poll for "Squad Config").
        tapNear(GAIA_EDIT_FORMATION_COMMIT_BTN);
        if (!waitForScreen(SQUAD_ANCHOR_TL, SQUAD_ANCHOR_BR, SQUAD_ANCHOR_TEXT)) {
            logWarning(tag + ": " + label + " -- commit tap didn't visibly return to Squad Config; "
                    + "ratio may not have persisted.");
            return false;
        }
        logInfo(tag + ": " + label + " ratio committed.");
        return true;
    }

    /** Reads the per-squad {Inf,Lan,Mrk} ratios from the zone's config keys, falling back to
     *  {@link #LOH_DEFAULT_SQUAD_RATIOS} for any value that's missing/out of range. */
    private int[][] readSquadRatiosFromConfig(ZoneFormation zone) {
        int[][] d = LOH_DEFAULT_SQUAD_RATIOS;
        return new int[][] {
            { cfgInt(zone.squad1Keys()[0], d[0][0]), cfgInt(zone.squad1Keys()[1], d[0][1]), cfgInt(zone.squad1Keys()[2], d[0][2]) },
            { cfgInt(zone.squad2Keys()[0], d[1][0]), cfgInt(zone.squad2Keys()[1], d[1][1]), cfgInt(zone.squad2Keys()[2], d[1][2]) },
        };
    }

    /** Reads a single-squad zone's {Inf,Lan,Mrk} ratio from its squad1Keys (squad2Keys unused). */
    private int[] readSingleRatioFromConfig(ZoneFormation zone) {
        int[] d = LOH_DEFAULT_SQUAD_RATIOS[0];
        return new int[] {
            cfgInt(zone.squad1Keys()[0], d[0]), cfgInt(zone.squad1Keys()[1], d[1]), cfgInt(zone.squad1Keys()[2], d[2])
        };
    }

    private int cfgInt(ConfigurationKeyEnum key, int fallback) {
        try {
            Integer v = profile.getConfig(key, Integer.class);
            return (v != null && v >= 0 && v <= 100) ? v : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Configures a single squad's Infantry/Lancer/Marksman ratio, then persists it:
     * Edit Formation → Balance → drive the three sliders → Confirm → back → Save and Exit.
     *
     * <p>The sliders are driven in ASCENDING-target order so the rows that need to go DOWN move
     * before the rows that need to go UP — otherwise a raise can stall against the 100% cap (the
     * game refuses to push a row up while the total is already 100%).</p>
     *
     * <p>"Use as default" is deliberately left unticked so each squad keeps its own ratio, and the
     * Save-and-Exit dialog is what actually persists it (Confirm on the popup alone does not —
     * verified live 2026-08-10).</p>
     */
    private boolean configureSquadRatio(String tag, int squadNumber, PointData editFormationBtn, int[] ratio) {
        logInfo(tag + ": configuring Squad " + squadNumber + " -> "
                + ratio[0] + "/" + ratio[1] + "/" + ratio[2] + " (Inf/Lan/Mrk).");

        // Edit Formation -> troop-detail (poll for the "Troop Ratio" label).
        if (!navStep(editFormationBtn, TROOP_ANCHOR_TL, TROOP_ANCHOR_BR, TROOP_ANCHOR_TEXT,
                tag + " EditFormation->troop(sq" + squadNumber + ")")) {
            logWarning(tag + ": squad " + squadNumber + " — never reached troop-detail.");
            return false;
        }
        saveLabyrinthFrame("troop", squadNumber);

        return driveBalanceAndSave(tag, "sq" + squadNumber, TROOP_ANCHOR_TEXT, ratio);
    }

    /**
     * matt/2026-08-13: extracted from {@code configureSquadRatio} so both the two-squad (Land of
     * Heroes) and single-squad (Cave of Monsters / Charm Mine) flows share one implementation.
     * Assumes we're ALREADY on the troop-detail screen (Balance button visible) -- drives
     * Balance → the three sliders → Confirm → back → Save and Exit.
     *
     * <p>The sliders are driven in ASCENDING-target order so the rows that need to go DOWN move
     * before the rows that need to go UP — otherwise a raise can stall against the 100% cap (the
     * game refuses to push a row up while the total is already 100%).</p>
     *
     * <p>"Use as default" is deliberately left unticked so each squad/zone keeps its own ratio, and
     * the Save-and-Exit dialog is what actually persists it (Confirm on the popup alone does not —
     * verified live 2026-08-10).</p>
     *
     * @param label a short tag for logging (e.g. "sq1", "sq2", "single")
     * @param troopAnchorText the expected text on the troop-detail screen's title (e.g. "heroes" for
     *                        Land of Heroes, or the zone's own lowercased name for single-squad zones)
     *                        -- used to confirm Confirm returned us there, not a fixed "heroes" string
     */
    /**
     * matt/2026-08-13: the REAL fix for Cave of Monsters / Charm Mine -- since neither zone persists
     * a formation between visits, the only place setting a ratio actually matters is right here,
     * immediately before the real Deploy tap in {@code attemptNormalChallenge}. Best-effort: any
     * failure just logs a warning and lets Deploy proceed with whatever ratio is already showing
     * (never blocks a real battle attempt over a formation-setup hiccup).
     */
    private void setRatioBeforeDeploy(ZoneFormation zone, int dungeonNumber) {
        String tag = zone.zoneName() + " pre-deploy ratio";
        int[] ratio = readSingleRatioFromConfig(zone);
        logInfo(tag + ": setting " + ratio[0] + "/" + ratio[1] + "/" + ratio[2] + " (Inf/Lan/Mrk) "
                + "before dungeon " + dungeonNumber + " deploy.");

        if (!navStep(LOH_BALANCE_BTN, BALANCE_ANCHOR_TL, BALANCE_ANCHOR_BR, BALANCE_ANCHOR_TEXT,
                tag + " Balance->popup")) {
            logWarning(tag + ": never reached the Balance popup; deploying with whatever ratio is already set.");
            return;
        }

        floorRowToZero("Infantry", LOH_INFANTRY_ROW_Y);
        floorRowToZero("Lancer",   LOH_LANCER_ROW_Y);
        floorRowToZero("Marksman", LOH_MARKSMAN_ROW_Y);
        fillRowToTarget("Infantry", LOH_INFANTRY_ROW_Y, LOH_INF_PCT_TL, LOH_INF_PCT_BR, ratio[0]);
        fillRowToTarget("Lancer",   LOH_LANCER_ROW_Y,   LOH_LAN_PCT_TL, LOH_LAN_PCT_BR, ratio[1]);
        fillRowToTarget("Marksman", LOH_MARKSMAN_ROW_Y, LOH_MRK_PCT_TL, LOH_MRK_PCT_BR, ratio[2]);

        Integer vi = readPercent(LOH_INF_PCT_TL, LOH_INF_PCT_BR);
        Integer vl = readPercent(LOH_LAN_PCT_TL, LOH_LAN_PCT_BR);
        Integer vm = readPercent(LOH_MRK_PCT_TL, LOH_MRK_PCT_BR);
        logInfo(tag + ": post-set readback = " + vi + "/" + vl + "/" + vm
                + " (target " + ratio[0] + "/" + ratio[1] + "/" + ratio[2] + ").");

        // Confirm -> back to the troop-detail/pre-deploy screen. No "Save and Exit" step here --
        // we're deploying immediately after, not exiting, so there's nothing further to persist.
        if (!navStep(LOH_CONFIRM_BTN, TROOP_ANCHOR_TL, TROOP_ANCHOR_BR, zone.zoneName().toLowerCase(),
                tag + " Confirm->troop")) {
            logWarning(tag + ": Confirm didn't visibly return to the pre-deploy screen "
                    + "(continuing to Deploy anyway).");
        }
    }

    private boolean driveBalanceAndSave(String tag, String label, String troopAnchorText, int[] ratio) {
        return driveBalanceAndSave(tag, label, troopAnchorText, ratio, true);
    }

    /**
     * @param expectsSaveDialog false for single-squad zones (Cave of Monsters, Charm Mine). Confirmed
     *        live 2026-08-20 from lab_d0_troop_1787238417242.png: their screen is a DEPLOYMENT screen
     *        -- "Troop Ratio: 33%/33%/33%" along the bottom with Withdraw All / Equalize / Balance /
     *        Deploy -- and carries no Save control at all, because these zones don't persist a
     *        formation between visits (the deploy path's own comment says the same). Waiting for a
     *        "Save and Exit" dialog there waited for something that cannot appear, then reported
     *        "ratio setup failed" on a pass where the ratio had been set correctly and read back
     *        correctly (Cave 50/10/40, Charm 60/20/20 on that very run). Only Land of Heroes, which
     *        does persist a formation, shows that dialog.
     */
    private boolean driveBalanceAndSave(String tag, String label, String troopAnchorText, int[] ratio,
                                        boolean expectsSaveDialog) {
        // Balance -> ratio popup (poll for the "Balance" popup title). This is the critical gate:
        // the OCR slider-driver only works once we are genuinely on the popup.
        if (!navStep(LOH_BALANCE_BTN, BALANCE_ANCHOR_TL, BALANCE_ANCHOR_BR, BALANCE_ANCHOR_TEXT,
                tag + " Balance->popup(" + label + ")")) {
            logWarning(tag + ": " + label + " — never reached the Balance popup.");
            return false;
        }
        saveLabyrinthFrame("balance_popup", 0);

        // matt's approach: ZERO all three rows first, THEN fill each to target top-to-bottom. Zeroing
        // everything up front means the running total is 0 when we start adding, so a fill can never be
        // blocked by the 100% cap. 1 tap == 1% (verified live). No mid-drive OCR — the small stroked
        // digits only read reliably on a settled/static frame, so OCR is used ONLY for the correction
        // pass in fillRowToTarget (which re-adds any taps the game dropped).
        floorRowToZero("Infantry", LOH_INFANTRY_ROW_Y);
        floorRowToZero("Lancer",   LOH_LANCER_ROW_Y);
        floorRowToZero("Marksman", LOH_MARKSMAN_ROW_Y);
        fillRowToTarget("Infantry", LOH_INFANTRY_ROW_Y, LOH_INF_PCT_TL, LOH_INF_PCT_BR, ratio[0]);
        fillRowToTarget("Lancer",   LOH_LANCER_ROW_Y,   LOH_LAN_PCT_TL, LOH_LAN_PCT_BR, ratio[1]);
        fillRowToTarget("Marksman", LOH_MARKSMAN_ROW_Y, LOH_MRK_PCT_TL, LOH_MRK_PCT_BR, ratio[2]);

        // Best-effort verify readback (logged) + a frame for eyeballing the final ratio.
        Integer vi = readPercent(LOH_INF_PCT_TL, LOH_INF_PCT_BR);
        Integer vl = readPercent(LOH_LAN_PCT_TL, LOH_LAN_PCT_BR);
        Integer vm = readPercent(LOH_MRK_PCT_TL, LOH_MRK_PCT_BR);
        logInfo(tag + ": " + label + " post-set readback = "
                + vi + "/" + vl + "/" + vm + " (target " + ratio[0] + "/" + ratio[1] + "/" + ratio[2] + ").");
        saveLabyrinthFrame("balance_set", 0);

        // Confirm the popup (per-squad; no "use as default"). Popup closes -> back on troop-detail.
        if (!navStep(LOH_CONFIRM_BTN, TROOP_ANCHOR_TL, TROOP_ANCHOR_BR, troopAnchorText,
                tag + " Confirm->troop(" + label + ")")) {
            logWarning(tag + ": " + label + " — Confirm didn't return to troop-detail "
                    + "(continuing to the save step anyway).");
        }

        if (!expectsSaveDialog) {
            logInfo(tag + ": " + label + " ratio set (this zone has no Save step -- it doesn't persist "
                    + "a formation between visits, so the ratio applies to this deploy only).");
            return true;
        }

        // Exit the troop-detail -> "save the formation first?" dialog (poll for the "Save and Exit"
        // button) -> tap it to persist. Confirm on the popup alone does NOT save.
        if (!navStep(LOH_FORMATION_BACK_ARROW, SAVE_ANCHOR_TL, SAVE_ANCHOR_BR, SAVE_ANCHOR_TEXT,
                tag + " back->saveDialog(" + label + ")")) {
            logWarning(tag + ": " + label + " — save dialog never appeared; "
                    + "ratio may not have persisted.");
            return false;
        }
        tapNear(LOH_SAVE_AND_EXIT_BTN);
        sleepTask(MENU_NAVIGATION_DELAY);
        logInfo(tag + ": " + label + " ratio saved.");
        return true;
    }

    /**
     * Taps {@code target}, then POLLS (via OCR of {@code vtl..vbr}) for the expected screen to appear,
     * up to {@link #SCREEN_POLL_TIMEOUT_MS}. If it doesn't appear, taps ONCE more and polls again.
     * Returns true once the screen is confirmed.
     *
     * <p>Polling instead of a fixed sleep is what fixes the unattended desync: a slow-loading screen
     * is simply waited for, and we only re-tap once (covering a genuinely missed tap) rather than
     * blindly firing the next tap into whatever happens to be on screen.</p>
     */
    private boolean navStep(PointData target, PointData vtl, PointData vbr, String expectLower, String desc) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            tapNear(target);
            if (waitForScreen(vtl, vbr, expectLower)) {
                if (attempt > 1) logInfo("LoH nav [" + desc + "]: reached on retry.");
                return true;
            }
            logWarning("LoH nav [" + desc + "]: '" + expectLower + "' not present after tap " + attempt
                    + "; " + (attempt < 2 ? "retrying." : "giving up."));
        }
        saveLabyrinthFrame("navfail", 9); // debug: capture where we actually landed on give-up
        return false;
    }

    /** Polls the OCR region {@code vtl..vbr} until its text contains {@code expectLower} or timeout. */
    private boolean waitForScreen(PointData vtl, PointData vbr, String expectLower) {
        int waited = 0;
        while (waited < SCREEN_POLL_TIMEOUT_MS) {
            String s = readStringValue(vtl, vbr, WHITE_TITLE_SETTINGS);
            if (s != null && s.toLowerCase().contains(expectLower)) return true;
            sleepTask(SCREEN_POLL_INTERVAL_MS);
            waited += SCREEN_POLL_INTERVAL_MS;
        }
        return false;
    }

    /**
     * Taps a row's minus button enough times to guarantee it sits at 0% (extra taps at 0 are inert).
     *
     * <p>matt, 2026-08-19, caught live watching this run: {@code sleepTask(LOH_DET_TAP_DELAY)} put
     * the exact same 90ms between every one of the 105 taps -- mechanically perfect timing on the
     * same button, over and over, which is about as obvious an automation fingerprint as tap
     * behavior gets ("you are a f***ing bot, and that's very easy [to spot]," his words). The tap
     * coordinate itself already has a small randomized jitter (see {@code tapNear}'s default
     * {@link TapJitterPolicy#DEFAULT_POINT_JITTER_RADIUS}), but the delay between taps had none at
     * all. {@link TapJitterPolicy#sampleDelay} randomizes it (always >= the requested delay, so the
     * loop can't out-race the game's own tap-registration rate) the same way real human tapping
     * naturally varies.</p>
     */
    private void floorRowToZero(String label, int rowY) {
        logInfo("LoH slider [" + label + "]: flooring to 0%.");
        PointData minus = new PointData(LOH_MINUS_X, rowY);
        for (int i = 0; i < LOH_FLOOR_TAPS; i++) {
            tapNear(minus);
            sleepTask(TapJitterPolicy.sampleDelay(LOH_DET_TAP_DELAY));
        }
    }

    /**
     * Fills a row FROM 0% up to {@code targetPct}: taps plus {@code targetPct} times (1 tap == 1%),
     * then runs a few correction passes — read the settled value, and tap the exact remaining delta —
     * to re-add any taps the game dropped. The row must already be floored to 0 before calling.
     */
    private void fillRowToTarget(String label, int rowY, PointData pctTl, PointData pctBr, int targetPct) {
        PointData plus = new PointData(LOH_PLUS_X, rowY);
        PointData minus = new PointData(LOH_MINUS_X, rowY);
        logInfo("LoH slider [" + label + "]: filling 0 -> " + targetPct + "%.");
        for (int i = 0; i < targetPct; i++) {
            tapNear(plus);
            sleepTask(TapJitterPolicy.sampleDelay(LOH_DET_TAP_DELAY));
        }
        // Correction passes: fix dropped taps using the reliable static-frame OCR.
        for (int iter = 0; iter < LOH_CORRECT_ITERS; iter++) {
            sleepTask(LOH_SETTLE_BEFORE_READ);
            Integer cur = readPercent(pctTl, pctBr);
            if (cur == null) {
                logWarning("LoH slider [" + label + "]: correction read failed (iter " + (iter + 1)
                        + "); leaving as-is.");
                continue;
            }
            if (cur == targetPct) {
                logInfo("LoH slider [" + label + "]: at target " + targetPct + "%.");
                return;
            }
            int delta = targetPct - cur;
            logInfo("LoH slider [" + label + "]: read " + cur + "%, nudging " + (delta > 0 ? "+" : "")
                    + delta + " to hit " + targetPct + "%.");
            PointData btn = (delta > 0) ? plus : minus;
            for (int k = 0; k < Math.abs(delta); k++) {
                tapNear(btn);
                sleepTask(TapJitterPolicy.sampleDelay(LOH_DET_TAP_DELAY));
            }
        }
        logWarning("LoH slider [" + label + "]: could not confirm " + targetPct + "% after "
                + LOH_CORRECT_ITERS + " correction passes.");
    }

    /**
     * OCRs a % readout box and parses it to an int in 0..100, or null if unreadable. The digits are
     * borderline-OCR (small, stroked font, and the slider briefly animates after a nudge), so this
     * RE-READS a few times before giving up — a fresh frame each attempt smooths over transient misses.
     */
    private Integer readPercent(PointData tl, PointData br) {
        String lastRaw = null;
        for (int attempt = 1; attempt <= LOH_PCT_READ_ATTEMPTS; attempt++) {
            String raw = readStringValue(tl, br, LOH_PCT_SETTINGS);
            if (raw != null && !raw.isBlank()) {
                lastRaw = raw;
                String digits = raw.replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) {
                    try {
                        int v = Integer.parseInt(digits);
                        if (v >= 0 && v <= 100) return v;   // reject OCR noise outside the valid range
                    } catch (NumberFormatException ignored) { /* retry */ }
                }
            }
            if (attempt < LOH_PCT_READ_ATTEMPTS) sleepTask(LOH_PCT_READ_RETRY_DELAY);
        }
        // matt/2026-08-20: report WHAT was actually seen. The raw text only went to the shared
        // "String OCR result" DEBUG line, which is buried among hundreds of others and gives no clue
        // which region produced it -- so "read failed" looked like a blank read for months when it was
        // really an out-of-range parse ("690" for a box showing 50, "407" for one showing 40, both
        // rejected by the 0..100 guard). Naming the region and the text makes the next miscalibration
        // a one-line diagnosis.
        logWarning("percent read failed at " + tl + "->" + br + " after " + LOH_PCT_READ_ATTEMPTS
                + " attempts; last raw text was '" + (lastRaw == null ? "<null>" : lastRaw.trim())
                + "' (rejected: not a number in 0..100).");
        return null;
    }

    // =========================== UTILITY METHODS ===========================

    /**
     * Returns the list of available dungeons based on the day of the week
     * 
     * @param dayOfWeek the current day of the week
     * @return list of available dungeon numbers
     */
    private List<Integer> getAvailableDungeons(DayOfWeek dayOfWeek) {
        List<Integer> dungeons = new ArrayList<>();

        switch (dayOfWeek) {
            case MONDAY, TUESDAY -> dungeons.add(1);
            case WEDNESDAY, THURSDAY -> {
                dungeons.add(2);
                dungeons.add(3);
            }
            case FRIDAY, SATURDAY -> {
                dungeons.add(4);
                dungeons.add(5);
            }
            case SUNDAY -> dungeons.add(6);
        }

        return dungeons;
    }

    /**
     * Returns the appropriate template for each dungeon number
     * 
     * @param dungeonNumber the dungeon number (1-6)
     * @return the corresponding template enum
     */
    private TemplatesEnum getDungeonTemplate(int dungeonNumber) {
        return switch (dungeonNumber) {
            case 1 -> TemplatesEnum.LABYRINTH_DUNGEON_1;
            case 2 -> TemplatesEnum.LABYRINTH_DUNGEON_2;
            case 3 -> TemplatesEnum.LABYRINTH_DUNGEON_3;
            case 4 -> TemplatesEnum.LABYRINTH_DUNGEON_4;
            case 5 -> TemplatesEnum.LABYRINTH_DUNGEON_5;
            case 6 -> TemplatesEnum.LABYRINTH_DUNGEON_6;
            default -> {
                logWarning("Invalid dungeon number: " + dungeonNumber + ". Using dungeon 1 as a fallback.");
                yield TemplatesEnum.LABYRINTH_DUNGEON_1;
            }
        };
    }

    /**
     * matt/2026-08-10 (Labyrinth observation phase): dumps the current emulator frame to
     * {@code labyrinth-debug/} so we can build enemy-type + win/loss detection from real battle
     * screens. Pure observation — never changes deploy behaviour. The game has NO labyrinth
     * victory/defeat templates yet, so this is how we collect the training data for them.
     */
    private void saveLabyrinthFrame(String label, int dungeonNumber) {
        try {
            RawImageData frame = emuManager.captureScreen(String.valueOf(EMULATOR_NUMBER));
            BufferedImage img = dev.frostguard.vision.convert.ImageConverter.toBufferedImage(frame);
            File dir = new File(System.getProperty("user.dir"), "labyrinth-debug");
            dir.mkdirs();
            File out = new File(dir, "lab_d" + dungeonNumber + "_" + label + "_" + System.currentTimeMillis() + ".png");
            ImageIO.write(img, "png", out);
            logInfo("Labyrinth observation: saved " + out.getName());
        } catch (Exception e) {
            logWarning("Labyrinth observation: failed to save frame (" + label + "): " + e.getMessage());
        }
    }

    /**
     * Reschedules the task for one hour later with a reason
     *
     * @param reason the reason for rescheduling
     */
    private void rescheduleOneHourLater(String reason) {
        LocalDateTime nextExecution = LocalDateTime.now().plusHours(1);
        logWarning(reason + ". Rescheduling task for one hour later.");
        this.reschedule(nextExecution);
    }

    /**
     * matt/2026-08-13: "kick Labyrinth off at noon every day" -- reads the picked local start time
     * (LABYRINTH_DAILY_START_TIME_STRING, HH:mm, defaults to noon) instead of always following the
     * game's own 00:00 UTC reset boundary.
     */
    private LocalDateTime nextLabyrinthStartTime() {
        String startTime = profile.getConfig(ConfigurationKeyEnum.LABYRINTH_DAILY_START_TIME_STRING, String.class);
        return GameTimeUtils.nextLocalTime(startTime);
    }

}
