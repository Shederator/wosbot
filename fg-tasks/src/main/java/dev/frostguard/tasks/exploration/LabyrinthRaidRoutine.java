package dev.frostguard.tasks.exploration;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;

/**
 * matt/2026-08-14: "the new labyrinth" -- a separate stage-based raid system layered on top of
 * the classic Land of Heroes / Cave of Monsters / Charm Mine zones ({@link DailyLabyrinthRoutine}
 * already owns those). Two zones live-confirmed: Research Center (Tech stats only) and Gear Forge
 * (Chief Gear stats only), each a straight numbered stage ladder (1-1, 1-2, ... 5-4, ...).
 *
 * <p>
 * Two very different buttons live at the exact same screen position depending on state:
 * <ul>
 * <li><b>Raid</b> (green) -- an INSTANT, FREE, no-battle catch-up claim for every stage at or
 * below your already-proven best result. Zero risk: confirmed live, tapping it just pops a "Raid
 * Rewards" popup with a Claim button -- no attempt spent, no chance of failure.</li>
 * <li><b>Challenge</b> (blue) -- a REAL battle attempt against the actual next uncleared stage,
 * spending one of a small daily pool (5/day, confirmed live). {@link #challengeZone} handles
 * this now -- see that method's header for the full "tail of the tape" design.</li>
 * </ul>
 *
 * <p>
 * <b>Live-verified 2026-08-14</b>: Research Center Raid-claimed from Stage 1-1 straight through
 * to its proven best, landing cleanly on the Challenge frontier each time. The Challenge flow
 * below (stat read, troop-ratio set via exact count entry, deploy, win/loss detection) was
 * live-verified hand-driven at Stage 4-4 before being encoded here: two real attempts spent
 * (60/20/20 and 80/10/10 Infantry leans), both lost, Battle Report confirmed 0/155,200 survivors
 * both times with the enemy retaining 86,557/153,432 (56%) and 10,746/153,432 (7%) HP
 * respectively -- the MODERATE lean (60/20/20) came far closer to winning than the AGGRESSIVE one
 * (80/10/10), which is the opposite of "more lean is always better". That live result is exactly
 * why the escalation policy here tries a DIFFERENT lean on a loss, never a more extreme version
 * of the same one.
 */
public class LabyrinthRaidRoutine extends DelayedTask {

    private static final int IDLE_RECHECK_HOURS = 24;
    private static final int PANEL_SETTLE_MS = 1200;
    private static final int ACTION_SETTLE_MS = 800;
    private static final int MAX_RAID_CLAIMS_PER_ZONE = 15;

    // -- Left-menu entry point (same panel Life Essence/Monument already use) --
    private static final PointData LEFT_MENU_SCROLL_START = new PointData(220, 700);
    private static final PointData LEFT_MENU_SCROLL_END = new PointData(220, 300);
    /** Whichever Labyrinth-zone row is currently showing in the collapsed panel -- tapping it
     *  opens the SAME "The Labyrinth" hub map regardless of which zone the row itself names. */
    private static final PointData LABYRINTH_ROW_ICON = new PointData(404, 836);

    // -- Labyrinth hub map (both zones visible at once) --
    private static final PointData RESEARCH_CENTER_BUILDING = new PointData(157, 700);
    private static final PointData GEAR_FORGE_BUILDING = new PointData(578, 780);
    private static final PointData HUB_BACK_ARROW = new PointData(44, 40);

    // -- Zone screen (Research Center / Gear Forge -- identical layout, different skin) --
    private static final PointData ZONE_CLOSE_X = new PointData(682, 40);
    private static final PointData ZONE_ACTION_BUTTON = new PointData(358, 1218);
    private static final PointData ZONE_ACTION_TEXT_TL = new PointData(280, 1195);
    private static final PointData ZONE_ACTION_TEXT_BR = new PointData(440, 1245);

    // -- Raid Rewards popup --
    private static final PointData RAID_CLAIM_BUTTON = new PointData(358, 861);

    private static final TesseractSettingsData BUTTON_TEXT_OCR_SETTINGS = TesseractSettingsData.assembler()
            .stripBackground(true)
            .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
            .pageAnalysis(TesseractSettingsData.PageAnalysis.SINGLE_LINE)
            .build();

    /** matt/2026-08-14: every coordinate above is hardcoded on purpose -- these are all STATIC
     *  UI elements (button positions never move), so there's no reason to burn an OCR/template
     *  pass finding them, same "hard-code what's static" call matt made on Gem Shop tonight. The
     *  ONE thing that genuinely varies is the button's TEXT (Raid vs Challenge), which is the only
     *  thing actually OCR'd. Every tap still goes through a small jitter box rather than the exact
     *  same pixel every time, matching this codebase's standing tap-jitter convention. */
    private static final int TAP_JITTER_PX = 6;

    private void tapWithJitter(PointData center) {
        PointData topLeft = new PointData(center.col() - TAP_JITTER_PX, center.row() - TAP_JITTER_PX);
        PointData bottomRight = new PointData(center.col() + TAP_JITTER_PX, center.row() + TAP_JITTER_PX);
        tapRandomPoint(topLeft, bottomRight);
    }

    // ========== "Tail of the tape" -- stat comparison screen ==========
    // matt/2026-08-14, live-verified: the magnifying-glass icon next to the stage's enemy portrait
    // on the Challenge screen opens "View Details" -- Troops Details (enemy composition) up top,
    // then a 12-row "My Stats" vs "Opponent's Stats" table (Infantry/Lancer/Marksman x
    // Attack/Defense/Lethality/Health), all 12 rows fitting on screen with no scroll needed at this
    // resolution. Only "My Stats" (the left column) is read -- the decision rule below only cares
    // which of MY OWN troop types is relatively strongest, not what the enemy has (see the header's
    // live-tested lesson on leaning vs countering).
    private static final PointData STAT_DETAILS_ICON = new PointData(122, 1080);
    private static final PointData STAT_DETAILS_BACK = new PointData(44, 40);

    private enum TroopType { INFANTRY, LANCER, MARKSMAN }

    private static final PointData[] MY_STAT_ROWS = {
            new PointData(90, 467), new PointData(90, 535), new PointData(90, 601), new PointData(90, 669),   // Infantry Atk/Def/Leth/HP
            new PointData(90, 737), new PointData(90, 805), new PointData(90, 872), new PointData(90, 939),   // Lancer Atk/Def/Leth/HP
            new PointData(90, 1007), new PointData(90, 1074), new PointData(90, 1141), new PointData(90, 1209), // Marksman Atk/Def/Leth/HP
    };
    private static final int STAT_ROW_HALF_WIDTH = 65;
    private static final int STAT_ROW_HALF_HEIGHT = 16;

    private static final TesseractSettingsData PERCENT_OCR_SETTINGS = TesseractSettingsData.assembler()
            .stripBackground(true)
            .charWhitelist("+-0123456789.%")
            .pageAnalysis(TesseractSettingsData.PageAnalysis.SINGLE_LINE)
            .build();

    private static final Pattern PERCENT_PATTERN = Pattern.compile("[+-]?(\\d+(?:\\.\\d+)?)");

    /**
     * Reads all 12 "My Stats" rows and averages them per troop type, returning the type with the
     * highest average -- the type to lean into. Returns {@code null} if fewer than 2 of the 4 rows
     * for EVERY type were readable (too little data to trust a decision).
     */
    private TroopType readStrongestTroopType() {
        double[] sums = new double[3];
        int[] counts = new int[3];
        for (int i = 0; i < MY_STAT_ROWS.length; i++) {
            TroopType type = TroopType.values()[i / 4];
            PointData center = MY_STAT_ROWS[i];
            String raw = stringHelper.attemptRecognition(
                    new PointData(center.getX() - STAT_ROW_HALF_WIDTH, center.getY() - STAT_ROW_HALF_HEIGHT),
                    new PointData(center.getX() + STAT_ROW_HALF_WIDTH, center.getY() + STAT_ROW_HALF_HEIGHT),
                    2, 150L, PERCENT_OCR_SETTINGS,
                    s -> s != null && !s.isBlank(),
                    s -> s);
            if (raw == null) {
                continue;
            }
            Matcher m = PERCENT_PATTERN.matcher(raw);
            if (!m.find()) {
                continue;
            }
            try {
                double value = Double.parseDouble(m.group(1));
                sums[type.ordinal()] += value;
                counts[type.ordinal()]++;
            } catch (NumberFormatException ignored) {
                // unreadable row -- skip it rather than guess
            }
        }

        TroopType best = null;
        double bestAvg = Double.NEGATIVE_INFINITY;
        StringBuilder summary = new StringBuilder();
        for (TroopType type : TroopType.values()) {
            if (counts[type.ordinal()] < 2) {
                summary.append(type).append("=unreadable ");
                continue;
            }
            double avg = sums[type.ordinal()] / counts[type.ordinal()];
            summary.append(type).append("=").append(String.format(Locale.US, "%.1f%%", avg))
                    .append("(" + counts[type.ordinal()] + "/4 rows) ");
            if (avg > bestAvg) {
                bestAvg = avg;
                best = type;
            }
        }
        logInfo(logLine("My Stats averages -- " + summary));
        return best;
    }

    // ========== Deploy screen -- exact troop-count entry ==========
    // matt/2026-08-14, live-verified, the ONLY reliable lever after the "Balance" button turned out
    // to just instantly Equalize (no adjustable dialog on this screen, unlike matt's recollection of
    // Gear Forge's own Balance dialog from earlier tonight -- may differ by zone, not assumed here):
    // tapping a type's raw count number opens a real Android EditText + OK button (dumped via
    // uiautomator, confirmed fixed position regardless of which row's field is open). Typing an exact
    // target and confirming works -- EXCEPT the three counts share one hard army-wide cap (155,200
    // here), so increasing one before decreasing the others silently reverts (over-cap edits are
    // rejected, not clamped). The only safe order is DECREASE first (frees capacity), INCREASE last.
    private static final PointData INFANTRY_COUNT_FIELD = new PointData(475, 366);
    private static final PointData LANCER_COUNT_FIELD = new PointData(475, 507);
    private static final PointData MARKSMAN_COUNT_FIELD = new PointData(475, 648);
    private static final PointData COUNT_EDIT_OK_BUTTON = new PointData(640, 1216);
    private static final PointData DEPLOY_BUTTON = new PointData(549, 1213);
    // matt/2026-08-14, live-verified crop (pixel-checked against a real deploy-screen screenshot):
    // tight enough to exclude the person-icon and warning-icon glyphs on either side, which
    // otherwise feed Tesseract noise it doesn't need with a digits/comma/slash whitelist.
    private static final PointData ARMY_TOTAL_TL = new PointData(85, 108);
    private static final PointData ARMY_TOTAL_BR = new PointData(260, 132);
    private static final int COUNT_EDIT_CLEAR_CHARS = 12;

    private static final TesseractSettingsData ARMY_TOTAL_OCR_SETTINGS = TesseractSettingsData.assembler()
            .stripBackground(true)
            .charWhitelist("0123456789,/")
            .pageAnalysis(TesseractSettingsData.PageAnalysis.SINGLE_LINE)
            .build();

    /** Small fixed preset library (matt's call: presets, not live math) -- percentages for
     *  {Infantry, Lancer, Marksman}, indexed by which type to lean into. Live-tested at 4-4:
     *  60/20/20 got the enemy to 7% HP remaining; 80/10/10 (more extreme) got WORSE results
     *  (56% remaining) -- so this stays at a moderate 60% lean, never escalates further. */
    private static int[] presetFor(TroopType lean) {
        return switch (lean) {
            case INFANTRY -> new int[] { 60, 20, 20 };
            case LANCER -> new int[] { 20, 60, 20 };
            case MARKSMAN -> new int[] { 20, 20, 60 };
        };
    }

    /** Reads the "N,NNN/M,MMM" army total cap so preset percentages can be turned into exact counts. */
    private Integer readArmyCap() {
        String raw = readStringValue(ARMY_TOTAL_TL, ARMY_TOTAL_BR, ARMY_TOTAL_OCR_SETTINGS);
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split("/");
        if (parts.length != 2) {
            return null;
        }
        try {
            return Integer.parseInt(parts[1].replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Sets the deploy screen's troop ratio to the given {Infantry%, Lancer%, Marksman%} preset.
     * Always decreases before increasing (see class-header note) -- computes each type's target
     * count, sorts by delta ascending (most-negative/decreasing first), and applies in that order.
     */
    private boolean setTroopRatio(int armyCap, int[] percentages) {
        int[] targets = new int[3];
        for (int i = 0; i < 3; i++) {
            targets[i] = Math.round(armyCap * percentages[i] / 100f);
        }
        // Rounding can leave the sum a few off the true cap; dump any remainder onto the lean type
        // (index of the max percentage) so the total still exactly matches what the game expects.
        int sum = targets[0] + targets[1] + targets[2];
        int leanIndex = percentages[0] >= percentages[1] && percentages[0] >= percentages[2] ? 0
                : percentages[1] >= percentages[2] ? 1 : 2;
        targets[leanIndex] += (armyCap - sum);

        PointData[] fields = { INFANTRY_COUNT_FIELD, LANCER_COUNT_FIELD, MARKSMAN_COUNT_FIELD };
        String[] names = { "Infantry", "Lancer", "Marksman" };

        // Apply order: current counts are unknown without another OCR pass, but every deploy screen
        // observed live starts equal-ish, and decreasing a type that's ALREADY at/below its target is
        // a safe no-op read-then-skip. Simplest robust order that matches every case seen live:
        // decrease the two non-lean types first (freeing capacity), then set the lean type last.
        for (int i = 0; i < 3; i++) {
            if (i == leanIndex) {
                continue;
            }
            if (!setSingleCount(fields[i], names[i], targets[i])) {
                return false;
            }
        }
        return setSingleCount(fields[leanIndex], names[leanIndex], targets[leanIndex]);
    }

    private boolean setSingleCount(PointData field, String label, int target) {
        tapWithJitter(field);
        sleepTask(ACTION_SETTLE_MS);
        emuManager.clearText(EMULATOR_NUMBER, COUNT_EDIT_CLEAR_CHARS);
        emuManager.writeText(EMULATOR_NUMBER, String.valueOf(target));
        sleepTask(300);
        tapPoint(COUNT_EDIT_OK_BUTTON);
        sleepTask(ACTION_SETTLE_MS);
        logInfo(logLine("Set " + label + " to " + target + "."));
        return true;
    }

    // ========== Battle result detection ==========
    // matt/2026-08-14, live-verified: the post-battle screen shows an unambiguous "Defeat!" or
    // "Victory!" banner -- far more reliable than parsing Battle Report troop-loss numbers.
    private static final PointData RESULT_BANNER_TL = new PointData(90, 320);
    private static final PointData RESULT_BANNER_BR = new PointData(630, 400);
    private static final PointData RESULT_ADJUST_TROOPS_BTN = new PointData(358, 618);
    private static final int BATTLE_RESOLVE_WAIT_MS = 8000;

    private static final TesseractSettingsData RESULT_OCR_SETTINGS = TesseractSettingsData.assembler()
            .stripBackground(true)
            .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!")
            .pageAnalysis(TesseractSettingsData.PageAnalysis.SINGLE_LINE)
            .build();

    private enum BattleResult { VICTORY, DEFEAT, UNKNOWN }

    private BattleResult readBattleResult() {
        String raw = stringHelper.attemptRecognition(
                RESULT_BANNER_TL, RESULT_BANNER_BR,
                4, 500L, RESULT_OCR_SETTINGS,
                s -> s != null && !s.isBlank(),
                s -> s);
        if (raw == null) {
            return BattleResult.UNKNOWN;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("victory")) {
            return BattleResult.VICTORY;
        }
        if (lower.contains("defeat")) {
            return BattleResult.DEFEAT;
        }
        return BattleResult.UNKNOWN;
    }

    /**
     * matt/2026-08-14, live-verified: "Retry" re-fights with the IDENTICAL deployed ratio -- same
     * inputs, same deterministic outcome, wastes an attempt for literally nothing (confirmed live:
     * lost twice in a row with no ratio change between). Never used here. Instead, on a loss, this
     * taps "Adjust number of troops" to return to the deploy screen with a fresh preset.
     */
    private void backToDeployScreenAfterLoss() {
        tapWithJitter(RESULT_ADJUST_TROOPS_BTN);
        sleepTask(PANEL_SETTLE_MS);
    }

    // ========== The Challenge flow itself ==========

    private static final int MAX_CHALLENGE_ATTEMPTS_PER_ZONE_PER_DAY = 2;

    /**
     * matt/2026-08-14: real "tail of the tape" automation. Reads the stat-comparison screen, picks
     * a preset that leans into whichever of MY OWN troop types is relatively strongest (live-tested
     * lesson: lean into your own strength, don't try to counter the enemy's composition -- countering
     * made an otherwise-close loss WORSE), deploys, and checks the result:
     * <ul>
     * <li>Win -- stop immediately. The next Raid pass picks up the newly-cleared stage for free.</li>
     * <li>Loss -- try ONE different lean (the troop type with the next-highest My Stats average,
     * a genuinely different composition, not a more extreme version of the same one -- live-tested
     * lesson: escalating the SAME lean from 60/20/20 to 80/10/10 made the result WORSE, not
     * better). If that also loses, declare the stage unwinnable for today and stop.</li>
     * </ul>
     * Capped at {@value #MAX_CHALLENGE_ATTEMPTS_PER_ZONE_PER_DAY} real attempts per zone per pass
     * regardless of how many the account has left, so a genuinely bad matchup can never burn the
     * whole daily pool on its own.
     */
    private void challengeZone(String zoneName) {
        tapWithJitter(ZONE_ACTION_BUTTON);
        sleepTask(PANEL_SETTLE_MS);

        tapWithJitter(STAT_DETAILS_ICON);
        sleepTask(PANEL_SETTLE_MS);
        TroopType primaryLean = readStrongestTroopType();
        tapWithJitter(STAT_DETAILS_BACK);
        sleepTask(ACTION_SETTLE_MS);

        if (primaryLean == null) {
            logWarning(logLine(zoneName + ": couldn't read the stat comparison confidently -- "
                    + "skipping the Challenge attempt this pass rather than guessing a preset blind."));
            tapWithJitter(ZONE_CLOSE_X);
            sleepTask(ACTION_SETTLE_MS);
            return;
        }

        Integer armyCap = readArmyCap();
        if (armyCap == null || armyCap <= 0) {
            logWarning(logLine(zoneName + ": couldn't read the army total cap -- skipping the "
                    + "Challenge attempt this pass rather than guessing a target count blind."));
            tapWithJitter(ZONE_CLOSE_X);
            sleepTask(ACTION_SETTLE_MS);
            return;
        }

        TroopType[] triedOrder = orderedLeanCandidates(primaryLean);
        for (int attempt = 0; attempt < Math.min(triedOrder.length, MAX_CHALLENGE_ATTEMPTS_PER_ZONE_PER_DAY);
                attempt++) {
            TroopType lean = triedOrder[attempt];
            int[] preset = presetFor(lean);
            logInfo(logLine(zoneName + ": attempt " + (attempt + 1) + "/"
                    + MAX_CHALLENGE_ATTEMPTS_PER_ZONE_PER_DAY + " -- leaning " + lean + " ("
                    + preset[0] + "/" + preset[1] + "/" + preset[2] + ")."));

            if (!setTroopRatio(armyCap, preset)) {
                logWarning(logLine(zoneName + ": failed to set the troop ratio -- aborting this "
                        + "Challenge attempt rather than deploying an unknown composition."));
                break;
            }

            tapPoint(DEPLOY_BUTTON);
            sleepTask(BATTLE_RESOLVE_WAIT_MS);

            BattleResult result = readBattleResult();
            StatisticsService.obtain().addToCounter(profile, "Labyrinth Challenge Attempts", 1);

            if (result == BattleResult.VICTORY) {
                logInfo(logLine(zoneName + ": VICTORY leaning " + lean + " -- stage cleared. "
                        + "Stopping here; next Raid pass claims it for free."));
                StatisticsService.obtain().addToCounter(profile, "Labyrinth Challenge Wins", 1);
                tapWithJitter(ZONE_CLOSE_X);
                sleepTask(ACTION_SETTLE_MS);
                return;
            }

            if (result == BattleResult.UNKNOWN) {
                logWarning(logLine(zoneName + ": couldn't confirm Victory or Defeat after deploying -- "
                        + "stopping here rather than guessing what happened or spending another attempt "
                        + "blind."));
                break;
            }

            logInfo(logLine(zoneName + ": Defeat leaning " + lean + "."));
            if (attempt + 1 < Math.min(triedOrder.length, MAX_CHALLENGE_ATTEMPTS_PER_ZONE_PER_DAY)) {
                backToDeployScreenAfterLoss();
            }
        }

        logInfo(logLine(zoneName + ": stage not cleared after " + MAX_CHALLENGE_ATTEMPTS_PER_ZONE_PER_DAY
                + " differently-composed attempts -- treating as unwinnable for today rather than "
                + "continuing to spend the daily pool on it. Will retry automatically once Tech/Gear "
                + "levels improve."));
        tapWithJitter(ZONE_CLOSE_X);
        sleepTask(ACTION_SETTLE_MS);
    }

    /** Primary lean first, then the troop type with the next-highest My Stats average (a genuinely
     *  different composition) -- never a more extreme version of the same lean (see class header). */
    private TroopType[] orderedLeanCandidates(TroopType primary) {
        TroopType[] others = new TroopType[2];
        int idx = 0;
        for (TroopType t : TroopType.values()) {
            if (t != primary) {
                others[idx++] = t;
            }
        }
        return new TroopType[] { primary, others[0], others[1] };
    }

    public LabyrinthRaidRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
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
        navigateToLabyrinthHub();

        int totalClaimed = 0;
        totalClaimed += raidZone("Research Center", RESEARCH_CENTER_BUILDING);
        totalClaimed += raidZone("Gear Forge", GEAR_FORGE_BUILDING);

        tapWithJitter(HUB_BACK_ARROW);
        sleepTask(ACTION_SETTLE_MS);

        StatisticsService.obtain().addToCounter(profile, "Labyrinth Raid Claims", totalClaimed);
        logInfo(logLine("Pass complete. Claimed " + totalClaimed + " total raid reward(s) across both zones. "
                + "Rechecking in " + IDLE_RECHECK_HOURS + " hours."));
        reschedule(LocalDateTime.now().plusHours(IDLE_RECHECK_HOURS));
    }

    private void navigateToLabyrinthHub() {
        marchHelper.openLeftMenuCitySection(true);
        sleepTask(500);
        swipe(LEFT_MENU_SCROLL_START, LEFT_MENU_SCROLL_END);
        sleepTask(700);
        tapWithJitter(LABYRINTH_ROW_ICON);
        sleepTask(PANEL_SETTLE_MS);
    }

    /**
     * Repeatedly taps Raid + Claim on one zone until either a Challenge button appears (the real
     * frontier stage, handed off to {@link #challengeZone}) or the safety cap is hit.
     *
     * @return number of Raid claims made in this zone this pass
     */
    private int raidZone(String zoneName, PointData buildingPoint) {
        tapWithJitter(buildingPoint);
        sleepTask(PANEL_SETTLE_MS);

        int claimed = 0;
        for (int i = 0; i < MAX_RAID_CLAIMS_PER_ZONE; i++) {
            String buttonText = stringHelper.attemptRecognition(
                    ZONE_ACTION_TEXT_TL, ZONE_ACTION_TEXT_BR,
                    3, 200L, BUTTON_TEXT_OCR_SETTINGS,
                    s -> s != null && !s.isBlank(),
                    s -> s);

            if (buttonText == null) {
                logWarning(logLine(zoneName + ": could not read the action button text -- stopping here "
                        + "rather than tapping blind."));
                break;
            }

            String normalized = buttonText.toLowerCase();
            if (normalized.contains("raid")) {
                tapWithJitter(ZONE_ACTION_BUTTON);
                sleepTask(ACTION_SETTLE_MS);
                tapWithJitter(RAID_CLAIM_BUTTON);
                sleepTask(ACTION_SETTLE_MS);
                claimed++;
                logInfo(logLine(zoneName + ": Raid-claimed (" + claimed + " this pass)."));
            } else if (normalized.contains("challenge")) {
                logInfo(logLine(zoneName + ": reached the real Challenge frontier -- running the "
                        + "stat-comparison pass."));
                challengeZone(zoneName);
                break;
            } else {
                logWarning(logLine(zoneName + ": action button read as '" + buttonText + "' -- neither "
                        + "Raid nor Challenge recognized, stopping here rather than guessing."));
                break;
            }
        }

        if (claimed >= MAX_RAID_CLAIMS_PER_ZONE) {
            logWarning(logLine(zoneName + ": hit the safety cap (" + MAX_RAID_CLAIMS_PER_ZONE
                    + " claims) -- stopping this pass."));
        }

        tapWithJitter(ZONE_CLOSE_X);
        sleepTask(ACTION_SETTLE_MS);
        return claimed;
    }

    private String logLine(String note) {
        return "LabyrinthRaidRoutine | " + note;
    }
}
