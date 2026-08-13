package dev.frostguard.tasks.exploration;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.api.domain.TesseractSettingsData.PageAnalysis;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.ocr.TesseractOcrProvider;

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
    private static final PointData SCROLL_START_POINT = new PointData(400, 800);
    private static final PointData SCROLL_END_POINT = new PointData(400, 100);
    private static final PointData SKIP_BUTTON = new PointData(71, 827);
    private static final PointData RESULT_SKIP_BUTTON = new PointData(640, 175);

    // Timing constants
    private static final int MENU_NAVIGATION_DELAY = 1000;
    private static final int TAB_SWITCH_DELAY = 500;
    private static final int SCROLL_DELAY = 1300;
    private static final int LABYRINTH_LOAD_DELAY = 2000;
    private static final int BATTLE_COMPLETION_DELAY = 3000;

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
    /** White label text over the map/banner. */
    private static final TesseractSettingsData ZONE_LABEL_SETTINGS =
            TesseractSettingsData.assembler()
                    .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 :")
                    .pageAnalysis(PageAnalysis.SINGLE_LINE)
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
    /** "Balance" button on the troop-detail screen that opens the troop-ratio popup. */
    private static final PointData LOH_BALANCE_BTN = new PointData(330, 1195);
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
    private static final PointData LOH_INF_PCT_TL = new PointData(558, 508);
    private static final PointData LOH_INF_PCT_BR = new PointData(632, 552);
    /** LIVE-TUNE: Lancer % box. */
    private static final PointData LOH_LAN_PCT_TL = new PointData(558, 653);
    private static final PointData LOH_LAN_PCT_BR = new PointData(632, 697);
    /** LIVE-TUNE: Marksman % box. */
    private static final PointData LOH_MRK_PCT_TL = new PointData(558, 798);
    private static final PointData LOH_MRK_PCT_BR = new PointData(632, 842);

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
    private static final TesseractSettingsData WHITE_TITLE_SETTINGS =
            TesseractSettingsData.assembler()
                    .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ ")
                    .pageAnalysis(PageAnalysis.SINGLE_LINE)
                    .stripBackground(true)
                    .setTextColor(new java.awt.Color(255, 255, 255))
                    .build();
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
    private static final TesseractSettingsData LOH_PCT_SETTINGS =
            TesseractSettingsData.assembler()
                    .charWhitelist("0123456789")
                    .pageAnalysis(PageAnalysis.SINGLE_LINE)
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
                logInfo("LABYRINTH_FORMATION_TEST_BOOL is ON — running Land-of-Heroes formation setup only.");
                if (!navigateToLabyrinthMenu()) {
                    rescheduleOneHourLater("Failed to navigate to the Labyrinth menu (formation test)");
                    return;
                }
                setupLandOfHeroesFormation();
                reschedule(GameTimeUtils.dailyResetTime());
                return;
            }

            // Step 1: Navigate to labyrinth menu
            if (!navigateToLabyrinthMenu()) {
                rescheduleOneHourLater("Failed to navigate to the Labyrinth menu");
                return;
            }

            // Step 2: Execute challenges based on current day
            executeLabyrinthChallenges();

            reschedule(GameTimeUtils.dailyResetTime());

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

        // Open side menu
        marchHelper.openLeftMenuCitySection(true);

        // Scroll down to find labyrinth
        swipe(SCROLL_START_POINT, SCROLL_END_POINT);
        sleepTask(SCROLL_DELAY);

        // Search for labyrinth in menu
        ImageSearchResultData labyrinthResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LEFT_MENU_LABYRINTH_BUTTON,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (labyrinthResult.isFound()) {
            tapPoint(labyrinthResult.getPoint());
            sleepTask(LABYRINTH_LOAD_DELAY);
            logInfo("Successfully navigated to the Labyrinth menu.");
            return true;
        } else {
            logWarning("Labyrinth menu item not found.");
            return false;
        }
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

        ImageSearchResultData labyrinthResult = templateSearchHelper.locatePattern(
                getDungeonTemplate(dungeonNumber),
                SearchConfigConstants.DEFAULT_SINGLE);
        if (!labyrinthResult.isFound()) {
            logWarning("Dungeon " + dungeonNumber + " is not available today.");
            return false;
        }

        tapPoint(labyrinthResult.getPoint());
        sleepTask(TAB_SWITCH_DELAY);

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
        tapPoint(new PointData(700, 1200));
        sleepTask(100);
        ImageSearchResultData quickChallengeResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_QUICK_CHALLENGE,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (quickChallengeResult.isFound()) {
            logInfo("'Quick Challenge' is available for dungeon " + dungeonNumber + ".");
            tapPoint(quickChallengeResult.getPoint());
            sleepTask(MENU_NAVIGATION_DELAY);

            // Skip battle animation
            tapPoint(SKIP_BUTTON);
            sleepTask(300);
            tapRandomPoint(SKIP_BUTTON, SKIP_BUTTON, 10, 50);
            pressBack();
            return true;
        }
        return false;
    }

    /**
     * Attempts to execute a raid challenge
     */
    private boolean attemptRaidChallenge(int dungeonNumber) {
        ImageSearchResultData raidResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_RAID_CHALLENGE,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (raidResult.isFound()) {
            logInfo("'Raid Challenge' is available for dungeon " + dungeonNumber + ".");
            tapPoint(raidResult.getPoint());
            sleepTask(400);
            tapRandomPoint(SKIP_BUTTON, SKIP_BUTTON, 10, 50);
            pressBack();
            sleepTask(400);
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

        tapPoint(normalChallengeResult.getPoint());
        sleepTask(300);

        // OBSERVE: the pre-deploy screen shows the enemy formation for this stage.
        saveLabyrinthFrame("enemy", dungeonNumber);

        // Try quick deploy first
        ImageSearchResultData quickDeployResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_QUICK_DEPLOY,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (quickDeployResult.isFound()) {
            logInfo("'Quick Deploy' button found. Deploying for dungeon " + dungeonNumber + ".");
            tapPoint(quickDeployResult.getPoint());
            sleepTask(100);
        }

        // Deploy troops
        ImageSearchResultData deployResult = templateSearchHelper.locatePattern(
                TemplatesEnum.LABYRINTH_DEPLOY,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (deployResult.isFound()) {
            logInfo("'Deploy' button found. Deploying troops for dungeon " + dungeonNumber + ".");
            tapPoint(deployResult.getPoint());
            sleepTask(BATTLE_COMPLETION_DELAY);

            // OBSERVE: the result screen shows win/loss + rewards. This is the data we need to build
            // labyrinth victory/defeat detection (no template exists yet).
            saveLabyrinthFrame("result", dungeonNumber);

            // Skip battle results
            tapRandomPoint(RESULT_SKIP_BUTTON, RESULT_SKIP_BUTTON, 10, 50);
            pressBack();
            return true;
        }

        logWarning("Could not find 'Deploy' button for dungeon " + dungeonNumber + ".");
        return false;
    }

    // =================== LAND-OF-HEROES FORMATION SETUP ===================

    /**
     * matt/2026-08-10 — TEST harness (free, no battle). From the Labyrinth menu this opens the
     * Land-of-Heroes stage screen and sets up the deploy formation to a configurable troop ratio,
     * then SAVES it and STOPS — it never taps Deploy/battle (battling burns a daily attempt while
     * formation-setup is free).
     *
     * <p>Flow: select Land of Heroes → Challenge → Quick Deploy → Balance → OCR-drive each troop
     * row's % to {@link #LOH_TARGET_RATIO} via the +/- nudges → tick "use as default" → Confirm →
     * Edit Formation (saves) → STOP.</p>
     *
     * <p>All coordinates are LIVE-TUNE best-estimates (see constants block); the orchestrator will
     * calibrate them via ADB.</p>
     */
    private void setupLandOfHeroesFormation() {
        logInfo("LoH formation: starting Land-of-Heroes formation setup (setup only, no battle).");

        saveLabyrinthFrame("map", 0); // one-shot: capture the Labyrinth map to calibrate zone-label OCR

        // Enter Land of Heroes by reading its map label (matt's rule): a LOCKED zone's line reads
        // "Opens in …"; an OPEN zone shows just name + countdown. Only tap the banner if it's open.
        String zoneLabel = readStringValue(LOH_ZONE_LABEL_TL, LOH_ZONE_LABEL_BR, ZONE_LABEL_SETTINGS);
        logInfo("LoH formation: Land of Heroes label OCR = '" + zoneLabel + "'.");
        if (zoneLabel != null && zoneLabel.toLowerCase().contains("open")) {
            logWarning("LoH formation: Land of Heroes reads LOCKED ('Opens in') — not open yet, aborting.");
            return;
        }
        logInfo("LoH formation: Land of Heroes looks open — tapping its banner to enter.");
        // Step 1: banner -> stage screen (poll for the "Challenge" button).
        if (!navStep(LOH_ZONE_BANNER, STAGE_ANCHOR_TL, STAGE_ANCHOR_BR, STAGE_ANCHOR_TEXT, "banner->stage")) {
            logWarning("LoH formation: never reached the Land of Heroes stage screen; aborting.");
            return;
        }
        saveLabyrinthFrame("stage", 0);

        // Step 2: Challenge -> Squad Config (poll for the "Squad Config" title).
        if (!navStep(LOH_CHALLENGE_BTN, SQUAD_ANCHOR_TL, SQUAD_ANCHOR_BR, SQUAD_ANCHOR_TEXT, "Challenge->SquadConfig")) {
            logWarning("LoH formation: never reached Squad Config; aborting.");
            return;
        }
        saveLabyrinthFrame("squad", 0);

        // Step 3: Quick Deploy fills heroes + troops for BOTH squads IN PLACE (stays on Squad Config,
        // so there is no screen change to verify — a short settle is enough).
        logInfo("LoH formation: tapping Quick Deploy (fills squads in place).");
        tapPoint(LOH_QUICK_DEPLOY_BTN);
        sleepTask(LABYRINTH_LOAD_DELAY);
        saveLabyrinthFrame("squad_filled", 0);

        // Step 4: configure each squad's ratio in turn. After a squad's "Save and Exit" the game drops
        // back to the STAGE screen, so for squads after the first we re-tap Challenge to reach Squad
        // Config again. Quick Deploy above already filled every squad, so we don't repeat it.
        int[][] squadRatios = readSquadRatiosFromConfig();
        for (int i = 0; i < squadRatios.length; i++) {
            if (i > 0) {
                logInfo("LoH formation: re-entering Squad Config for squad " + (i + 1) + ".");
                if (!navStep(LOH_CHALLENGE_BTN, SQUAD_ANCHOR_TL, SQUAD_ANCHOR_BR, SQUAD_ANCHOR_TEXT,
                        "Challenge->SquadConfig(sq" + (i + 1) + ")")) {
                    logWarning("LoH formation: could not re-enter Squad Config for squad " + (i + 1)
                            + "; aborting remaining squads.");
                    return;
                }
            }
            if (!configureSquadRatio(i + 1, LOH_SQUAD_EDIT_BTNS[i], squadRatios[i])) {
                logWarning("LoH formation: squad " + (i + 1) + " setup failed; aborting remaining squads.");
                return;
            }
        }

        logInfo("LoH formation: all squads configured. STOPPING before Deploy (no battle attempt spent).");
    }

    /** Reads the per-squad {Inf,Lan,Mrk} ratios from config (set on the Labyrinth tab in the UI),
     *  falling back to {@link #LOH_DEFAULT_SQUAD_RATIOS} for any value that's missing/out of range. */
    private int[][] readSquadRatiosFromConfig() {
        int[][] d = LOH_DEFAULT_SQUAD_RATIOS;
        return new int[][] {
            { cfgInt(ConfigurationKeyEnum.LABYRINTH_SQUAD1_INFANTRY_INT, d[0][0]),
              cfgInt(ConfigurationKeyEnum.LABYRINTH_SQUAD1_LANCER_INT,   d[0][1]),
              cfgInt(ConfigurationKeyEnum.LABYRINTH_SQUAD1_MARKSMAN_INT, d[0][2]) },
            { cfgInt(ConfigurationKeyEnum.LABYRINTH_SQUAD2_INFANTRY_INT, d[1][0]),
              cfgInt(ConfigurationKeyEnum.LABYRINTH_SQUAD2_LANCER_INT,   d[1][1]),
              cfgInt(ConfigurationKeyEnum.LABYRINTH_SQUAD2_MARKSMAN_INT, d[1][2]) },
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
    private boolean configureSquadRatio(int squadNumber, PointData editFormationBtn, int[] ratio) {
        logInfo("LoH formation: configuring Squad " + squadNumber + " -> "
                + ratio[0] + "/" + ratio[1] + "/" + ratio[2] + " (Inf/Lan/Mrk).");

        // Edit Formation -> troop-detail (poll for the "Troop Ratio" label).
        if (!navStep(editFormationBtn, TROOP_ANCHOR_TL, TROOP_ANCHOR_BR, TROOP_ANCHOR_TEXT,
                "EditFormation->troop(sq" + squadNumber + ")")) {
            logWarning("LoH formation: squad " + squadNumber + " — never reached troop-detail.");
            return false;
        }
        saveLabyrinthFrame("troop", squadNumber);

        // Balance -> ratio popup (poll for the "Balance" popup title). This is the critical gate:
        // the OCR slider-driver only works once we are genuinely on the popup.
        if (!navStep(LOH_BALANCE_BTN, BALANCE_ANCHOR_TL, BALANCE_ANCHOR_BR, BALANCE_ANCHOR_TEXT,
                "Balance->popup(sq" + squadNumber + ")")) {
            logWarning("LoH formation: squad " + squadNumber + " — never reached the Balance popup.");
            return false;
        }
        saveLabyrinthFrame("balance_popup", squadNumber);

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
        logInfo("LoH formation: Squad " + squadNumber + " post-set readback = "
                + vi + "/" + vl + "/" + vm + " (target " + ratio[0] + "/" + ratio[1] + "/" + ratio[2] + ").");
        saveLabyrinthFrame("balance_set", squadNumber);

        // Confirm the popup (per-squad; no "use as default"). Popup closes -> back on troop-detail.
        if (!navStep(LOH_CONFIRM_BTN, TROOP_ANCHOR_TL, TROOP_ANCHOR_BR, TROOP_ANCHOR_TEXT,
                "Confirm->troop(sq" + squadNumber + ")")) {
            logWarning("LoH formation: squad " + squadNumber + " — Confirm didn't return to troop-detail "
                    + "(continuing to the save step anyway).");
        }

        // Exit the troop-detail -> "save the formation first?" dialog (poll for the "Save and Exit"
        // button) -> tap it to persist. Confirm on the popup alone does NOT save.
        if (!navStep(LOH_FORMATION_BACK_ARROW, SAVE_ANCHOR_TL, SAVE_ANCHOR_BR, SAVE_ANCHOR_TEXT,
                "back->saveDialog(sq" + squadNumber + ")")) {
            logWarning("LoH formation: squad " + squadNumber + " — save dialog never appeared; "
                    + "ratio may not have persisted.");
            return false;
        }
        tapPoint(LOH_SAVE_AND_EXIT_BTN);
        sleepTask(MENU_NAVIGATION_DELAY);
        logInfo("LoH formation: Squad " + squadNumber + " ratio saved.");
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
            tapPoint(target);
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

    /** Taps a row's minus button enough times to guarantee it sits at 0% (extra taps at 0 are inert). */
    private void floorRowToZero(String label, int rowY) {
        logInfo("LoH slider [" + label + "]: flooring to 0%.");
        PointData minus = new PointData(LOH_MINUS_X, rowY);
        for (int i = 0; i < LOH_FLOOR_TAPS; i++) {
            tapPoint(minus);
            sleepTask(LOH_DET_TAP_DELAY);
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
            tapPoint(plus);
            sleepTask(LOH_DET_TAP_DELAY);
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
                tapPoint(btn);
                sleepTask(LOH_DET_TAP_DELAY);
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
        for (int attempt = 1; attempt <= LOH_PCT_READ_ATTEMPTS; attempt++) {
            String raw = readStringValue(tl, br, LOH_PCT_SETTINGS);
            if (raw != null && !raw.isBlank()) {
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
            BufferedImage img = TesseractOcrProvider.toBufferedImage(frame);
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

}
